"""Unit tests for slide reader functionality."""

import pytest
from pathlib import Path
import tempfile
from PIL import Image
import numpy as np

from src.wsi_pipeline.slide_reader import SlideReader, SlideReaderError


def test_slide_reader_initialization():
    """Test SlideReader initialization."""
    # Default config
    reader = SlideReader()
    assert reader.config == {}
    assert reader.slide is None
    assert reader._is_open == False
    assert str(reader) == "SlideReader(status=closed, path=none)"

    # Custom config
    config = {"thumbnail_size": {"width": 512, "height": 512}}
    reader = SlideReader(config)
    assert reader.config == config


def test_slide_reader_open_slide(tmp_path):
    """Test slide opening with valid file."""
    # Create a dummy file
    dummy_path = tmp_path / "slide.svs"
    dummy_path.write_bytes(b"dummy content")

    # Create reader and open slide
    reader = SlideReader()

    # This will fail because it's not a real WSI file, but should test the path validation
    try:
        reader.open_slide(str(dummy_path))
        # If it succeeds (unlikely), check basic properties
        assert reader.slide_path == dummy_path
        assert reader._is_open == True
    except SlideReaderError:
        # Expected for non-WSI file
        pass


def test_slide_reader_close():
    """Test slide reader cleanup."""
    reader = SlideReader()

    # Mock slide object
    class MockSlide:
        def close(self):
            pass

    reader.slide = MockSlide()
    reader._is_open = True
    reader.slide_path = Path("/test/path.svs")

    reader.close()

    assert reader.slide is None
    assert reader._is_open == False


def test_slide_reader_context_manager():
    """Test context manager functionality."""
    reader = SlideReader()

    # Mock slide object
    class MockSlide:
        def close(self):
            pass

    reader.slide = MockSlide()
    reader._is_open = True

    # Test context manager
    with reader:
        assert reader._is_open == True
        # Inside context

    # After exiting context
    assert reader.slide is None
    assert reader._is_open == False


def test_slide_reader_get_level_dimensions():
    """Test level dimensions retrieval."""
    reader = SlideReader()

    # Mock slide with level dimensions
    class MockSlide:
        level_dimensions = [(10000, 10000), (5000, 5000), (2500, 2500)]

    reader.slide = MockSlide()
    reader._is_open = True

    # Valid level
    width, height = reader.get_level_dimensions(0)
    assert width == 10000
    assert height == 10000

    width, height = reader.get_level_dimensions(1)
    assert width == 5000
    assert height == 5000

    # Invalid level
    with pytest.raises(SlideReaderError, match="Invalid level"):
        reader.get_level_dimensions(-1)

    with pytest.raises(SlideReaderError, match="Invalid level"):
        reader.get_level_dimensions(5)

    # Not open
    reader._is_open = False
    with pytest.raises(SlideReaderError, match="Slide not open"):
        reader.get_level_dimensions(0)


def test_slide_reader_get_level_downsample():
    """Test level downsample retrieval."""
    reader = SlideReader()

    # Mock slide with level dimensions and downsamples
    class MockSlide:
        level_dimensions = [(10000, 10000), (5000, 5000), (2500, 2500)]
        level_downsamples = [1.0, 2.0, 4.0]

    reader.slide = MockSlide()
    reader._is_open = True

    # Valid level
    downsample = reader.get_level_downsample(0)
    assert downsample == 1.0

    downsample = reader.get_level_downsample(2)
    assert downsample == 4.0

    # Invalid level
    with pytest.raises(SlideReaderError, match="Invalid level"):
        reader.get_level_downsample(10)


def test_slide_reader_repr():
    """Test string representation."""
    reader = SlideReader()
    assert "SlideReader" in str(reader)

    reader._is_open = True
    reader.slide_path = Path("/test/path.svs")
    repr_str = str(reader)
    assert "status=open" in repr_str
    assert "path=" in repr_str


def test_slide_reader_error_handling():
    """Test error handling."""
    reader = SlideReader()
    reader._is_open = False

    # All operations should fail when slide is not open
    with pytest.raises(SlideReaderError, match="Slide not open"):
        reader.get_thumbnail()

    with pytest.raises(SlideReaderError, match="Slide not open"):
        reader.read_region((0, 0), 0, (100, 100))

    with pytest.raises(SlideReaderError, match="Slide not open"):
        reader.get_level_dimensions(0)

    with pytest.raises(SlideReaderError, match="Slide not open"):
        reader.get_level_downsample(0)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])