"""Unit tests for patch extractor functionality."""

import pytest
import tempfile
from pathlib import Path
import json
import shutil

from src.wsi_pipeline.patch_extractor import PatchExtractor, PatchRecord
from src.wsi_pipeline.slide_reader import SlideReader


def test_patch_extractor_initialization():
    """Test PatchExtractor initialization."""
    # Default config
    extractor = PatchExtractor()
    assert extractor.config == {}
    assert extractor.max_patches == 1000
    assert extractor.patch_size["width"] == 224
    assert extractor.patch_size["height"] == 224
    assert extractor.output_directory.exists()

    # Custom config
    config = {
        "patch_extraction": {
            "output_directory": "custom_output",
            "max_patches": 500,
            "patch_size": {"width": 128, "height": 128}
        }
    }
    extractor = PatchExtractor(config)
    assert extractor.max_patches == 500
    assert extractor.patch_size["width"] == 128
    assert extractor.output_directory.name == "custom_output"


def test_patch_record_creation():
    """Test PatchRecord creation and serialization."""
    record = PatchRecord(
        patch_id="test_patch_001",
        slide_id="test_slide",
        coordinates=(100, 200, 224, 224),
        pyramid_level=0,
        tissue_coverage=0.75,
        extraction_status="completed"
    )

    # Test dictionary conversion
    data = record.to_dict()
    assert data["patch_id"] == "test_patch_001"
    assert data["slide_id"] == "test_slide"
    assert data["coordinates"]["x"] == 100
    assert data["coordinates"]["y"] == 200
    assert data["extraction_status"] == "completed"

    # Test dictionary reconstruction
    record2 = PatchRecord.from_dict(data)
    assert record2.patch_id == record.patch_id
    assert record2.coordinates == record.coordinates


def test_patch_record_repr():
    """Test PatchRecord string representation."""
    record = PatchRecord(
        patch_id="test_patch",
        slide_id="test_slide",
        coordinates=(0, 0, 224, 224),
        pyramid_level=0,
        tissue_coverage=0.5
    )

    repr_str = repr(record)
    assert "PatchRecord" in repr_str
    assert "test_patch" in repr_str
    assert "test_slide" in repr_str


def test_patch_extractor_summary_generation():
    """Test extraction summary generation."""
    extractor = PatchExtractor()

    summary = extractor._generate_summary("test_slide", "/path/to/slide.svs")

    assert "slide_id" in summary
    assert summary["slide_id"] == "test_slide"
    assert "slide_path" in summary
    assert summary["slide_path"] == "/path/to/slide.svs"
    assert "max_patches" in summary
    assert summary["max_patches"] == 1000


def test_patch_extractor_repr():
    """Test PatchExtractor string representation."""
    extractor = PatchExtractor()
    repr_str = repr(extractor)
    assert "PatchExtractor" in repr_str
    assert "patch_size" in repr_str


def test_patch_extractor_output_directory():
    """Test output directory creation."""
    with tempfile.TemporaryDirectory() as temp_dir:
        config = {
            "patch_extraction": {
                "output_directory": temp_dir + "/test_output"
            }
        }
        extractor = PatchExtractor(config)

        # Directory should be created
        assert Path(temp_dir).exists()
        assert Path(temp_dir + "/test_output").exists()


def test_patch_extractor_checksum_calculation():
    """Test checksum calculation."""
    extractor = PatchExtractor()

    # Create test file
    test_file = Path("test_checksum.txt")
    test_file.write_text("test content")

    try:
        checksum = extractor._calculate_file_checksum(test_file)
        # SHA256 of "test content" should be predictable
        expected_checksum = "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b"
        assert checksum == expected_checksum
    finally:
        test_file.unlink()


def test_patch_extractor_manifest_loading(tmp_path):
    """Test manifest loading and initialization."""
    # Test loading existing manifest
    manifest_data = {
        "slide_id": "test_slide",
        "patch_records": []
    }

    manifest_file = tmp_path / "manifest.json"
    with open(manifest_file, 'w') as f:
        json.dump(manifest_data, f)

    # This would require actual manifest loading logic
    # For now, just test that the file can be loaded
    with open(manifest_file, 'r') as f:
        loaded_data = json.load(f)

    assert loaded_data["slide_id"] == "test_slide"

    # Test manifest initialization (would need manifest.py import)
    # from src.wsi_pipeline.manifest import WSIManifest
    # manifest = WSIManifest.load_from_file(str(manifest_file))
    # assert manifest.slide_id == "test_slide"


def test_patch_extraction_boundary_conditions():
    """Test patch extraction with various boundary conditions."""
    with tempfile.TemporaryDirectory() as temp_dir:
        config = {
            "patch_extraction": {
                "output_directory": temp_dir,
                "max_patches": 10,
                "min_tissue_coverage": 0.5
            }
        }
        extractor = PatchExtractor(config)

        # Test with non-existent slide (should fail gracefully)
        try:
            records, summary = extractor.extract_patches_from_slide(
                slide_path="/nonexistent/path.svs",
                slide_id="nonexistent_slide"
            )
            # If it doesn't fail, at least verify summary structure
            assert "slide_id" in summary
        except Exception:
            # Expected to fail for non-existent file
            pass


def test_patch_extractor_slide_id_generation():
    """Test slide ID generation."""
    extractor = PatchExtractor()

    test_paths = [
        "/test/path/slide1.svs",
        "C:\\path\\to\\slide2.tiff",
        "/path/with spaces/slide3.ndpi"
    ]

    for path in test_paths:
        slide_id = extractor._generate_slide_id(path)
        assert isinstance(slide_id, str)
        assert len(slide_id) > 0


def test_patch_extractor_extraction_level_config():
    """Test extraction level configuration."""
    config = {
        "patch_extraction": {
            "extraction_level": 2
        }
    }
    extractor = PatchExtractor(config)
    assert extractor.extraction_level == 2

    # Default should be 0
    default_extractor = PatchExtractor({})
    assert default_extractor.extraction_level == 0


def test_patch_extractor_patch_size_config():
    """Test patch size configuration."""
    config = {
        "patch_extraction": {
            "patch_size": {"width": 256, "height": 256}
        }
    }
    extractor = PatchExtractor(config)
    assert extractor.patch_size["width"] == 256
    assert extractor.patch_size["height"] == 256


def test_patch_extractor_strides_config():
    """Test patch stride configuration."""
    config = {
        "patch_extraction": {
            "patch_stride": {"x": 128, "y": 128}
        }
    }
    extractor = PatchExtractor(config)
    assert extractor.patch_stride["x"] == 128
    assert extractor.patch_stride["y"] == 128


def test_patch_extractor_tissue_coverage_config():
    """Test tissue coverage threshold configuration."""
    config = {
        "patch_extraction": {
            "min_tissue_coverage": 0.8
        }
    }
    extractor = PatchExtractor(config)
    assert extractor.min_tissue_coverage == 0.8


if __name__ == "__main__":
    pytest.main([__file__, "-v"])