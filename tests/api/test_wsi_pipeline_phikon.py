import sys
from pathlib import Path

# Add src directory to Python path for imports
src_dir = Path(__file__).parents[2] / "src"
if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))

import pytest
import torch
from PIL import Image
from unittest.mock import Mock, patch
from src.api.wsipipeline_integration import run_wsi_inference, WSIInferenceError
from src.wsi_pipeline.phikon_embedding_generator import PhikonEmbeddingGenerator, PhikonEmbeddingGeneratorError

def test_phikon_singleton_reuse():
    # Test lazy initialization and singleton reuse
    gen1 = PhikonEmbeddingGenerator()
    gen2 = PhikonEmbeddingGenerator()
    assert gen1 is gen2, "Singleton not reused"

def test_phikon_embedding_shape(tmp_path):
    # Create mock patch
    patch_path = tmp_path / "mock.png"
    Image.new('RGB', (224, 224), color='white').save(patch_path)
    gen = PhikonEmbeddingGenerator()
    embedding, _ = gen.generate_embedding(Image.open(patch_path))
    assert embedding.shape == (1024,), "Embedding dim mismatch"
    assert torch.isfinite(embedding).all(), "Non-finite values"

def test_phikon_batch_processing():
    gen = PhikonEmbeddingGenerator()
    patches = [Image.new('RGB', (224, 224), color='white') for _ in range(3)]
    embeddings, metadata = gen.generate_embedding(patches, batch_size=2)
    assert len(embeddings) == 3, "Incorrect batch count"
    assert all(e.shape == (1024,) for e in embeddings), "Shape mismatch"

def test_phikon_invalid_image():
    gen = PhikonEmbeddingGenerator()
    with pytest.raises(PhikonEmbeddingGeneratorError, match="invalid"):
        gen.generate_embedding(Image.new('RGB', (0, 0)))

def test_phikon_model_init_failure():
    orig_instance = PhikonEmbeddingGenerator._instance
    orig_init = PhikonEmbeddingGenerator._initialized
    try:
        PhikonEmbeddingGenerator._instance = None
        PhikonEmbeddingGenerator._initialized = False
        with patch.object(PhikonEmbeddingGenerator, '_load_model', side_effect=Exception("mock")):
            with pytest.raises(PhikonEmbeddingGeneratorError, match="initialization"):
                PhikonEmbeddingGenerator()
    finally:
        PhikonEmbeddingGenerator._instance = orig_instance
        PhikonEmbeddingGenerator._initialized = orig_init

def test_wsi_runtime_phikon_integration(tmp_path):
    # Mock slide with real patch
    patch_path = tmp_path / "mock.png"
    Image.new('RGB', (224, 224), color='white').save(patch_path)
    (tmp_path / "mock.svs").touch()
    mock_manifest = Mock()
    mock_manifest.get_successful_patches.return_value = [Mock(output_path=str(patch_path))]
    # Mock pipeline components
    with patch('src.api.wsipipeline_integration.WSIPipeline') as mock_pipeline, \
         patch('src.api.wsipipeline_integration.PhikonEmbeddingGenerator') as mock_gen_class, \
         patch('src.api.wsipipeline_integration.SummaryToPatchCrossAttention') as mock_fusion:
        mock_pipeline_instance = Mock()
        mock_pipeline_instance.process_slide.return_value = (mock_manifest, Mock())
        mock_pipeline.return_value = mock_pipeline_instance
        mock_gen_instance = Mock()
        mock_gen_instance.generate_embedding.return_value = (torch.randn(1024), {})
        mock_gen_class.return_value = mock_gen_instance
        mock_fusion_instance = Mock()
        mock_fusion_instance.return_value = (torch.tensor([[0.5]]), torch.tensor([[[1.0]]]))
        mock_fusion.return_value = mock_fusion_instance
        # Valid clinical tensor
        clinical_tensor = torch.randn(1, 768)
        # Run inference
        result = run_wsi_inference(
            slide_path=str(tmp_path / "mock.svs"),
            clinical_input=clinical_tensor,
        )
        # Verify calls
        mock_pipeline_instance.process_slide.assert_called_once()
        mock_gen_instance.generate_embedding.assert_called_once()
        mock_fusion_instance.assert_called_once()
        assert "probability" in result

def test_wsi_phikon_empty_clinical():
    with pytest.raises(WSIInferenceError, match="Empty clinical"):
        run_wsi_inference(
            slide_path="/mock.svs",
            clinical_input="")

def test_wsi_missing_clinical_encoder():
    with pytest.raises(WSIInferenceError, match="encoder not provided"):
        run_wsi_inference(
            slide_path="/mock.svs",
            clinical_input="report text",  # Requires encoder
        )
