"""Unit tests for metadata schema validation."""

import pytest
from pathlib import Path
import tempfile
import json

from src.wsi_pipeline.config import (
    MetadataSchema,
    SlideFormat,
    validate_slide_path,
    generate_slide_id,
    load_config_from_file,
    save_config_to_file
)


def test_metadata_schema_validation():
    """Test MetadataSchema validation."""
    # Valid schema
    valid_schema = MetadataSchema(
        file_format="svs",
        file_size_bytes=1000000,
        dimensions={"width": 10000, "height": 10000},
        level_count=5,
        case_id="TEST001"
    )
    assert valid_schema.file_format == "svs"
    assert valid_schema.case_id == "TEST001"

    # Invalid case_id should be stripped
    schema = MetadataSchema(
        file_format="unknown",
        case_id="  TEST002  "
    )
    assert schema.case_id == "TEST002"

    # Invalid file format should be set to unknown
    schema = MetadataSchema(file_format="invalid")
    assert schema.file_format == "unknown"

    # Empty case_id should be None
    schema = MetadataSchema(case_id="")
    assert schema.case_id is None

    # Case_id with only whitespace should be None
    schema = MetadataSchema(case_id="   ")
    assert schema.case_id is None


def test_slide_format_enum():
    """Test SlideFormat enum."""
    assert SlideFormat.SVS.value == "svs"
    assert SlideFormat.TIF.value == "tif"
    assert SlideFormat.TIFF.value == "tiff"
    assert SlideFormat.NDPI.value == "ndpi"


def test_validate_slide_path(tmp_path):
    """Test slide path validation."""
    # Valid path
    valid_path = tmp_path / "slide.svs"
    valid_path.write_bytes(b"dummy")
    result = validate_slide_path(valid_path)
    assert result == valid_path

    # Non-existent path
    with pytest.raises(ValueError, match="does not exist"):
        validate_slide_path(tmp_path / "nonexistent.svs")

    # Directory instead of file
    with pytest.raises(ValueError, match="not a file"):
        validate_slide_path(tmp_path)

    # Invalid extension
    invalid_path = tmp_path / "slide.txt"
    invalid_path.write_bytes(b"dummy")
    with pytest.raises(ValueError, match="Unsupported WSI format"):
        validate_slide_path(invalid_path)


def test_generate_slide_id():
    """Test slide ID generation."""
    path1 = Path("/test/path/slide1.svs")
    path2 = Path("/test/path/slide1.svs")  # Same path should generate same hash

    id1 = generate_slide_id(path1)
    id2 = generate_slide_id(path2)

    assert id1 == id2
    assert id1.startswith("slide1_")
    assert len(id1) > len("slide1_")  # Should include hash and timestamp


def test_load_config_from_file(tmp_path):
    """Test configuration loading from file."""
    config_data = {
        "patch_extraction": {
            "output_directory": "test_output",
            "max_patches": 500
        }
    }

    config_file = tmp_path / "config.json"
    with open(config_file, 'w') as f:
        json.dump(config_data, f)

    loaded_config = load_config_from_file(config_file)
    assert loaded_config == config_data

    # Non-existent file should raise error
    with pytest.raises(FileNotFoundError):
        load_config_from_file(tmp_path / "nonexistent.json")


def test_save_config_to_file(tmp_path):
    """Test configuration saving to file."""
    config = {
        "patch_extraction": {
            "output_directory": "saved_output",
            "max_patches": 750
        }
    }

    config_file = tmp_path / "saved_config.json"
    save_config_to_file(config, config_file)

    # Verify file was created
    assert config_file.exists()

    # Verify content
    with open(config_file, 'r') as f:
        saved_config = json.load(f)

    assert saved_config == config

    # Verify directory was created
    config_file.parent.mkdir(exist_ok=True)  # Should not raise error


def test_get_default_config():
    """Test default configuration retrieval."""
    from src.wsi_pipeline.config import get_default_config

    config = get_default_config()

    assert "patch_size" in config
    assert config["patch_size"]["width"] == 224
    assert config["patch_size"]["height"] == 224

    assert "patch_stride" in config
    assert "max_patches" in config
    assert config["max_patches"] == 1000

    assert "tissue_detection" in config
    assert "hsv_threshold" in config["tissue_detection"]


def test_technical_metadata_is_complete():
    """Test TechnicalMetadata completeness check."""
    from src.wsi_pipeline.config import TechnicalMetadata

    # Incomplete metadata
    incomplete = TechnicalMetadata()
    incomplete.file_format = "unknown"
    assert not incomplete.is_complete()

    # Complete metadata
    complete = TechnicalMetadata()
    complete.file_format = "svs"
    complete.dimensions = {"width": 1000, "height": 1000}
    complete.level_count = 5
    assert complete.is_complete()

    # Partial completion
    partial = TechnicalMetadata()
    partial.file_format = "svs"
    partial.dimensions = {"width": 1000, "height": 1000}
    # level_count not set
    assert not partial.is_complete()  # Should be incomplete since level_count defaults to 0


def test_metadata_schema_optional_fields():
    """Test that optional fields can be None."""
    schema = MetadataSchema(
        file_format="svs",
        dimensions={"width": 100, "height": 100},
        level_count=3,
        # Other optional fields not specified
    )

    assert schema.case_id is None
    assert schema.specimen_type is None
    assert schema.tissue_site is None
    assert schema.stain is None
    assert schema.mpp_x is None
    assert schema.objective_power is None


if __name__ == "__main__":
    pytest.main([__file__, "-v"])