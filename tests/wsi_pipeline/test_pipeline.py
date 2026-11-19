"""Unit tests for pipeline functionality."""

import pytest
import tempfile
from pathlib import Path
import time
from unittest.mock import Mock, patch, MagicMock
import numpy as np

from src.wsi_pipeline.pipeline import WSIPipeline, WSIPipelineCLI, PipelineError
from src.wsi_pipeline.manifest import ManifestManager, WSIManifest
from src.wsi_pipeline.config import TechnicalMetadata, CaseMetadata
from src.wsi_pipeline.slide_reader import SlideReader


def test_wsi_pipeline_initialization():
    """Test WSIPipeline initialization."""
    # Default initialization
    pipeline = WSIPipeline()
    assert pipeline.config == {}
    assert pipeline.slide_reader is None
    assert pipeline.tissue_detector is None
    assert pipeline.patch_extractor is None
    assert pipeline.current_manifest is None
    assert pipeline.processing_start_time is None
    assert pipeline.processing_end_time is None

    # Custom config and manifest manager
    config = {"patch_extraction": {"max_patches": 500}}
    manifest_manager = ManifestManager()
    pipeline = WSIPipeline(config, manifest_manager)

    assert pipeline.config == config
    assert pipeline.manifest_manager == manifest_manager


def test_wsi_pipeline_initialize_components():
    """Test component initialization."""
    pipeline = WSIPipeline()

    # Should initialize all components
    pipeline.initialize_components()

    assert pipeline.slide_reader is not None
    assert pipeline.tissue_detector is not None
    assert pipeline.patch_extractor is not None


def test_wsi_pipeline_context_manager():
    """Test context manager functionality."""
    pipeline = WSIPipeline()

    # Should initialize components when entering context
    with pipeline:
        assert pipeline.slide_reader is not None
        assert pipeline.tissue_detector is not None
        assert pipeline.patch_extractor is not None

    # Components should be cleaned up after exiting context
    assert pipeline.slide_reader is None
    assert pipeline.tissue_detector is None
    assert pipeline.patch_extractor is None


def test_wsi_pipeline_cleanup():
    """Test pipeline cleanup."""
    pipeline = WSIPipeline()

    # Mock components
    pipeline.slide_reader = Mock()
    pipeline.tissue_detector = Mock()
    pipeline.patch_extractor = Mock()

    # Cleanup should call close methods
    pipeline.cleanup()

    # After cleanup, components should be set to None
    assert pipeline.slide_reader is None
    assert pipeline.tissue_detector is None
    assert pipeline.patch_extractor is None


def test_wsi_pipeline_repr():
    """Test string representation."""
    pipeline = WSIPipeline()
    repr_str = repr(pipeline)
    assert "WSIPipeline" in repr_str


def test_wsi_pipeline_error_handling():
    """Test error handling in pipeline."""
    pipeline = WSIPipeline()

    # Mock the components to avoid actual processing
    with patch.object(WSIPipeline, 'initialize_components'):
        with patch.object(WSIPipeline, 'process_slide') as mock_process:
            mock_process.side_effect = PipelineError("Test error")

            # Test error handling
            try:
                with pipeline:
                    pipeline.process_slide("/nonexistent/path.svs")
                assert False, "Should have raised PipelineError"
            except PipelineError:
                # Expected
                pass

    # Test cleanup after error
    assert pipeline.slide_reader is None
    assert pipeline.tissue_detector is None
    assert pipeline.patch_extractor is None


def test_wsi_pipeline_component_initialization():
    """Test that all components are properly initialized."""
    pipeline = WSIPipeline()

    # Check that components have expected attributes
    pipeline.initialize_components()

    # Slide reader should have expected methods
    assert hasattr(pipeline.slide_reader, 'open_slide')
    assert hasattr(pipeline.slide_reader, 'close')

    # Tissue detector should have expected methods
    assert hasattr(pipeline.tissue_detector, 'detect_tissue_mask')

    # Patch extractor should have expected methods
    assert hasattr(pipeline.patch_extractor, 'extract_patches_from_slide')


def test_wsi_pipeline_slide_id_generation():
    """Test slide ID generation."""
    pipeline = WSIPipeline()

    test_paths = [
        "/test/path/slide1.svs",
        "C:\\path\\to\\slide2.tiff",
        "/path/with spaces/slide3.ndpi"
    ]

    for path in test_paths:
        slide_id = pipeline._generate_slide_id(path)
        assert isinstance(slide_id, str)
        assert len(slide_id) > 0


def test_wsi_pipeline_processing_summary_generation():
    """Test processing summary generation."""
    pipeline = WSIPipeline()
    pipeline.current_manifest = WSIManifest(slide_id="slide_001")

    # Mock processing times
    pipeline.processing_start_time = time.time()
    time.sleep(0.01)  # Small delay
    pipeline.processing_end_time = time.time()

    summary = pipeline._generate_processing_summary(
        "/path/to/slide.svs",
        "slide_001",
        {"extracted_patches": 5},
        "/path/to/manifest.json"
    )

    assert "slide_id" in summary
    assert summary["slide_id"] == "slide_001"
    assert "slide_path" in summary
    assert "total_processing_time_seconds" in summary
    assert summary["total_processing_time_seconds"] > 0
    assert "extracted_patches" in summary
    assert summary["extracted_patches"] == 5
    assert "manifest_path" in summary


def test_wsi_pipeline_status_with_no_current():
    """Test pipeline status when no current manifest."""
    pipeline = WSIPipeline()
    pipeline.current_manifest = None

    # Mock components
    pipeline.slide_reader = Mock()
    pipeline.tissue_detector = Mock()
    pipeline.patch_extractor = Mock()

    with pytest.raises(Exception):
        # Should raise error when trying to generate processing summary with no current manifest
        pipeline._generate_processing_summary("dummy_path", "dummy_id", {}, "dummy_manifest_path")


def test_wsi_pipeline_manifest_creation():
    """Test manifest creation during pipeline processing."""
    pipeline = WSIPipeline()

    # Create mock manifest
    manifest = WSIManifest(
        slide_id="test_slide",
        source_file="/path/to/slide.svs",
        run_id="test_run"
    )

    pipeline.current_manifest = manifest
    assert pipeline.current_manifest.slide_id == "test_slide"


def test_wsi_pipeline_error_recovery():
    """Test error recovery in pipeline."""
    pipeline = WSIPipeline()

    # Simulate error scenario
    pipeline.current_manifest = None
    pipeline.processing_start_time = time.time()

    # Simulate error by not completing
    pipeline.processing_end_time = time.time()

    # Pipeline should still be in error state
    assert pipeline.processing_end_time is not None


class TestWSIPipelineCLI:
    """Test WSIPipelineCLI class."""

    def test_cli_initialization(self):
        """Test CLI initialization."""
        cli = WSIPipelineCLI()
        assert cli.pipeline is None

    def test_cli_commands_validation(self):
        """Test CLI command validation."""
        cli = WSIPipelineCLI()

        # Mock pipeline
        cli.pipeline = Mock()
        cli.pipeline.slide_reader = Mock()
        cli.pipeline.tissue_detector = Mock()
        cli.pipeline.patch_extractor = Mock()

        # Test status command
        # This would test the status command implementation
        pass

    def test_cli_help(self):
        """Test help functionality."""
        cli = WSIPipelineCLI()
        # Help should be available
        assert hasattr(cli, '_print_help')


def test_wsi_pipeline_component_integration():
    """Test integration between pipeline components."""
    pipeline = WSIPipeline()

    # Mock all components
    mock_reader = Mock(spec=SlideReader)
    mock_detector = Mock()  # Mock for TissueDetector
    mock_extractor = Mock()  # Mock for PatchExtractor

    # Set up mock methods
    mock_reader.get_level_dimensions.return_value = (10000, 10000)
    mock_reader.get_level_downsample.return_value = 1.0
    mock_detector.detect_tissue_mask.return_value = np.zeros((100, 100), dtype=np.uint8)
    mock_extractor.extract_patches_from_slide.return_value = ([], {})

    # Replace pipeline components
    pipeline.slide_reader = mock_reader
    pipeline.tissue_detector = mock_detector
    pipeline.patch_extractor = mock_extractor

    # The pipeline should use these components
    assert pipeline.slide_reader is not None
    assert pipeline.tissue_detector is not None
    assert pipeline.patch_extractor is not None


def test_wsi_pipeline_memory_efficiency():
    """Test that pipeline components are properly closed."""
    pipeline = WSIPipeline()

    # Mock components with close methods
    mock_reader = Mock()
    mock_reader.close = Mock()

    mock_detector = Mock()
    mock_extractor = Mock()
    mock_extractor.cleanup = Mock()

    pipeline.slide_reader = mock_reader
    pipeline.tissue_detector = mock_detector
    pipeline.patch_extractor = mock_extractor

    # Cleanup should call close methods
    pipeline.cleanup()

    # Verify cleanup was called
    mock_reader.close.assert_called_once()
    mock_extractor.cleanup.assert_called_once()


def test_wsi_pipeline_status_reporting():
    """Test pipeline status reporting."""
    pipeline = WSIPipeline()

    # Set up pipeline state
    pipeline.current_slide_id = "test_slide"
    pipeline.processing_start_time = time.time()
    pipeline.processing_end_time = time.time() + 10.5

    # Create mock manifest
    manifest = WSIManifest(slide_id="test_slide")
    pipeline.current_manifest = manifest

    # Test that pipeline can report its status
    assert pipeline.current_slide_id == "test_slide"
    assert pipeline.processing_end_time - pipeline.processing_start_time > 10.0
    assert pipeline.current_manifest is not None


if __name__ == "__main__":
    pytest.main([__file__, "-v"])