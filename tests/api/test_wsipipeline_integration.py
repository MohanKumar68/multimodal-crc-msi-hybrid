"""Mocked tests for WSI Pipeline Integration Layer.

These tests verify the orchestration logic using mocked components.
They are TECHNICAL SMOKE TESTS only and do not validate clinical accuracy.
No real WSI slides are processed.
"""

import sys
from pathlib import Path
import pytest
import torch
from unittest.mock import Mock, MagicMock, patch, PropertyMock

# Ensure project root is on path
sys.path.insert(0, str(Path(__file__).parents[2] / "src"))

from src.api.wsipipeline_integration import (
    run_wsi_inference,
    load_clinical_embedding,
    WSIInferenceError,
    _generate_patch_embeddings,
    _validate_clinical_embedding,
)


class MockPatchRecord:
    """Mock PatchRecord matching the actual manifest schema."""
    def __init__(self, patch_id, output_path, extraction_status="completed", slide_id="test_slide"):
        self.patch_id = patch_id
        self.slide_id = slide_id
        self.coordinates = {"x": 0, "y": 0, "width": 224, "height": 224}
        self.pyramid_level = 0
        self.tissue_coverage = 0.8
        self.extraction_status = extraction_status
        self.output_path = output_path
        self.error_message = None
        self.checksum = "abc123"
        self.extraction_timestamp = "2024-01-01 00:00:00"


class MockWSIManifest:
    """Mock WSIManifest with successful patches."""
    def __init__(self, slide_id="test_slide", patches=None):
        self.slide_id = slide_id
        self.source_file = "/fake/path/slide.svs"
        self.technical_metadata = None
        self.case_metadata = None
        self.extraction_config = Mock()
        self.patch_records = patches or []
        self.processing_status = "completed"
        self.errors = []
        self.processing_statistics = Mock(
            total_patches_requested=len(patches or []),
            successful_patches=len(patches or []),
            failed_patches=0,
            skipped_patches=0,
            processing_start_time="2024-01-01 00:00:00",
            processing_end_time="2024-01-01 00:00:10",
            total_processing_time_seconds=10.0,
        )
        self.created_at = "2024-01-01 00:00:00"
        self.completed_at = "2024-01-01 00:00:10"
        self.run_id = "test_run"

    def get_successful_patches(self):
        return [p for p in self.patch_records if p.extraction_status == "completed"]

    def get_failed_patches(self):
        return [p for p in self.patch_records if p.extraction_status == "failed"]


class MockProcessingSummary:
    """Mock processing summary returned by pipeline."""
    def __init__(self, manifest_path="/fake/manifest.json"):
        self.manifest_path = manifest_path

    def get(self, key, default=None):
        return getattr(self, key, default)


# Fixtures
@pytest.fixture
def mock_patches(tmp_path):
    """Create mock PNG patches on disk."""
    patches = []
    for i in range(3):
        patch_path = tmp_path / f"test_patch_{i}.png"
        # Create a minimal valid PNG
        from PIL import Image
        img = Image.new("RGB", (224, 224), color=(128, 128, 128))
        img.save(patch_path)
        patches.append(str(patch_path))
    return patches


@pytest.fixture
def mock_manifest(mock_patches):
    """Create a mock manifest with successful patches."""
    patch_records = [
        MockPatchRecord(f"patch_{i}", path)
        for i, path in enumerate(mock_patches)
    ]
    return MockWSIManifest(patches=patch_records)


@pytest.fixture
def valid_clinical_embedding():
    """Valid clinical embedding tensor [1, 768]."""
    return torch.randn(1, 768)


# Tests for _validate_clinical_embedding
def test_validate_clinical_embedding_1d(valid_clinical_embedding):
    """Test validation of 1D clinical embedding."""
    emb_1d = valid_clinical_embedding.squeeze(0)
    result = _validate_clinical_embedding(emb_1d)
    assert result.shape == (1, 768)
    assert torch.isfinite(result).all()


def test_validate_clinical_embedding_2d(valid_clinical_embedding):
    """Test validation of 2D clinical embedding."""
    result = _validate_clinical_embedding(valid_clinical_embedding)
    assert result.shape == (1, 768)
    assert torch.isfinite(result).all()


def test_validate_clinical_embedding_wrong_1d_length():
    """Test rejection of wrong 1D length."""
    emb = torch.randn(512)
    with pytest.raises(WSIInferenceError, match="768 elements"):
        _validate_clinical_embedding(emb)


def test_validate_clinical_embedding_wrong_2d_shape():
    """Test rejection of wrong 2D shape."""
    emb = torch.randn(2, 768)
    with pytest.raises(WSIInferenceError, match="shape \[1, 768\]"):
        _validate_clinical_embedding(emb)


def test_validate_clinical_embedding_non_finite():
    """Test rejection of non-finite values."""
    emb = torch.tensor([float('inf')] + [0.0] * 767)
    with pytest.raises(WSIInferenceError, match="non-finite"):
        _validate_clinical_embedding(emb)


def test_validate_clinical_embedding_wrong_type():
    """Test rejection of non-tensor input."""
    with pytest.raises(WSIInferenceError, match="torch.Tensor"):
        _validate_clinical_embedding("not a tensor")


# Tests for _generate_patch_embeddings
def test_generate_patch_embeddings_success(mock_manifest, mock_patches):
    """Test successful patch embedding generation."""
    # Mock Phikon generator
    mock_phikon = Mock()
    mock_phikon.generate_embedding.return_value = (
        torch.randn(1, 1024),  # embedding
        {"model_version": "phikon-v2"}  # metadata
    )

    vision_tensor, patch_ids = _generate_patch_embeddings(mock_manifest, mock_phikon)

    assert vision_tensor.shape == (3, 1024)
    assert len(patch_ids) == 3
    assert all(pid.startswith("patch_") for pid in patch_ids)
    assert mock_phikon.generate_embedding.call_count == 3


def test_generate_patch_embeddings_no_successful_patches():
    """Test failure when no successful patches."""
    manifest = MockWSIManifest(patches=[])
    mock_phikon = Mock()

    with pytest.raises(WSIInferenceError, match="No successful patches"):
        _generate_patch_embeddings(manifest, mock_phikon)


def test_generate_patch_embeddings_missing_output_path():
    """Test failure when patch has no output_path."""
    patches = [MockPatchRecord("patch_0", output_path=None)]
    manifest = MockWSIManifest(patches=patches)
    mock_phikon = Mock()

    with pytest.raises(WSIInferenceError, match="no output_path"):
        _generate_patch_embeddings(manifest, mock_phikon)


def test_generate_patch_embeddings_file_not_found(tmp_path):
    """Test failure when patch file doesn't exist."""
    patches = [MockPatchRecord("patch_0", output_path="/nonexistent/patch.png")]
    manifest = MockWSIManifest(patches=patches)
    mock_phikon = Mock()

    with pytest.raises(WSIInferenceError, match="not found"):
        _generate_patch_embeddings(manifest, mock_phikon)


def test_generate_patch_embeddings_invalid_embedding_shape(mock_manifest, mock_patches):
    """Test failure when Phikon returns wrong embedding shape."""
    mock_phikon = Mock()
    mock_phikon.generate_embedding.return_value = (
        torch.randn(1, 512),  # Wrong shape
        {"model_version": "phikon-v2"}
    )

    with pytest.raises(WSIInferenceError, match="embedding shape"):
        _generate_patch_embeddings(mock_manifest, mock_phikon)


def test_generate_patch_embeddings_non_finite_embedding(mock_manifest, mock_patches):
    """Test failure when Phikon returns non-finite embedding."""
    mock_phikon = Mock()
    bad_emb = torch.randn(1, 1024)
    bad_emb[0, 0] = float('nan')
    mock_phikon.generate_embedding.return_value = (bad_emb, {"model_version": "phikon-v2"})

    with pytest.raises(WSIInferenceError, match="Non-finite values"):
        _generate_patch_embeddings(mock_manifest, mock_phikon)


# Tests for run_wsi_inference (full orchestration)
@patch("src.api.wsipipeline_integration.WSIPipeline")
@patch("src.api.wsipipeline_integration.PhikonEmbeddingGenerator")
@patch("src.api.wsipipeline_integration.SummaryToPatchCrossAttention")
def test_run_wsi_inference_success(
    mock_fusion_class,
    mock_phikon_class,
    mock_pipeline_class,
    mock_manifest,
    valid_clinical_embedding,
):
    """Test successful full orchestration (mocked)."""
    # Setup mock pipeline
    mock_pipeline = Mock()
    mock_pipeline_class.return_value = mock_pipeline
    mock_pipeline.process_slide.return_value = (mock_manifest, MockProcessingSummary())

    # Setup mock Phikon generator
    mock_phikon = Mock()
    mock_phikon_class.return_value = mock_phikon
    mock_phikon.generate_embedding.return_value = (
        torch.randn(1, 1024),
        {"model_version": "phikon-v2"}
    )

    # Setup mock fusion model
    mock_fusion = Mock()
    mock_fusion_class.return_value = mock_fusion
    mock_fusion.eval.return_value = None
    mock_fusion.return_value = (torch.tensor([[0.75]]), None)  # probability, attn

    # Run inference
    result = run_wsi_inference(
        slide_path="/fake/slide.svs",
        clinical_input=valid_clinical_embedding,
        slide_id="test_slide",
    )

    assert result["validation_status"] == "success"
    assert "probability" in result
    assert isinstance(result["probability"], float)
    assert result["slide_id"] == "test_slide"
    assert result["patch_count"] == 3
    assert len(result["patch_ids"]) == 3
    assert result["vision_tensor_shape"] == [3, 1024]
    assert result["clinical_tensor_shape"] == [1, 768]


@patch("src.api.wsipipeline_integration.WSIPipeline")
@patch("src.api.wsipipeline_integration.PhikonEmbeddingGenerator")
@patch("src.api.wsipipeline_integration.SummaryToPatchCrossAttention")
def test_run_wsi_inference_pipeline_error(
    mock_fusion_class,
    mock_phikon_class,
    mock_pipeline_class,
    valid_clinical_embedding,
):
    """Test handling of pipeline failure."""
    mock_pipeline = Mock()
    mock_pipeline_class.return_value = mock_pipeline
    mock_pipeline.process_slide.side_effect = Exception("Pipeline failed")

    with pytest.raises(WSIInferenceError, match="Unexpected pipeline error"):
        run_wsi_inference(
            slide_path="/fake/slide.svs",
            clinical_embedding=valid_clinical_embedding,
        )


@patch("src.api.wsipipeline_integration.WSIPipeline")
@patch("src.api.wsipipeline_integration.PhikonEmbeddingGenerator")
@patch("src.api.wsipipeline_integration.SummaryToPatchCrossAttention")
def test_run_wsi_inference_no_current_manifest(
    mock_fusion_class,
    mock_phikon_class,
    mock_pipeline_class,
    valid_clinical_embedding,
):
    """Test handling of known 'no current manifest' interface issue."""
    mock_pipeline = Mock()
    mock_pipeline_class.return_value = mock_pipeline
    mock_pipeline.process_slide.side_effect = Exception("No current manifest set for processing")

    with pytest.raises(WSIInferenceError, match="current_manifest"):
        run_wsi_inference(
            slide_path="/fake/slide.svs",
            clinical_embedding=valid_clinical_embedding,
        )


@patch("src.api.wsipipeline_integration.WSIPipeline")
@patch("src.api.wsipipeline_integration.PhikonEmbeddingGenerator")
@patch("src.api.wsipipeline_integration.SummaryToPatchCrossAttention")
def test_run_wsi_inference_missing_patches(
    mock_fusion_class,
    mock_phikon_class,
    mock_pipeline_class,
    valid_clinical_embedding,
):
    """Test handling when pipeline returns no successful patches."""
    empty_manifest = MockWSIManifest(patches=[])
    mock_pipeline = Mock()
    mock_pipeline_class.return_value = mock_pipeline
    mock_pipeline.process_slide.return_value = (empty_manifest, MockProcessingSummary())

    with pytest.raises(WSIInferenceError, match="No successful patches"):
        run_wsi_inference(
            slide_path="/fake/slide.svs",
            clinical_embedding=valid_clinical_embedding,
        )


@patch("src.api.wsipipeline_integration.WSIPipeline")
@patch("src.api.wsipipeline_integration.PhikonEmbeddingGenerator")
@patch("src.api.wsipipeline_integration.SummaryToPatchCrossAttention")
def test_run_wsi_inference_non_finite_output(
    mock_fusion_class,
    mock_phikon_class,
    mock_pipeline_class,
    mock_manifest,
    valid_clinical_embedding,
):
    """Test handling of non-finite model output."""
    mock_pipeline = Mock()
    mock_pipeline_class.return_value = mock_pipeline
    mock_pipeline.process_slide.return_value = (mock_manifest, MockProcessingSummary())

    mock_phikon = Mock()
    mock_phikon_class.return_value = mock_phikon
    mock_phikon.generate_embedding.return_value = (
        torch.randn(1, 1024),
        {"model_version": "phikon-v2"}
    )

    mock_fusion = Mock()
    mock_fusion_class.return_value = mock_fusion
    mock_fusion.eval.return_value = None
    mock_fusion.return_value = (torch.tensor([[float('nan')]]), None)

    with pytest.raises(WSIInferenceError, match="non-finite probability"):
        run_wsi_inference(
            slide_path="/fake/slide.svs",
            clinical_embedding=valid_clinical_embedding,
        )


# Test load_clinical_embedding
def test_load_clinical_embedding_not_found(tmp_path):
    """Test loading clinical embedding for non-existent patient."""
    with pytest.raises(WSIInferenceError, match="not found"):
        load_clinical_embedding("NONEXISTENT", tensors_dir=str(tmp_path))


# End of tests