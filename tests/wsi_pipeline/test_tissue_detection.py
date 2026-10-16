"""Unit tests for tissue detection functionality."""

import pytest
import numpy as np
from PIL import Image
import tempfile
from pathlib import Path

from src.wsi_pipeline.tissue_detection import TissueDetector, TissueDetectorError


def test_tissue_detector_initialization():
    """Test TissueDetector initialization."""
    # Default config
    detector = TissueDetector()
    assert detector.threshold_method == "hsv_threshold"
    assert detector.h_min == 0
    assert detector.h_max == 30
    assert detector.s_min == 30
    assert detector.s_max == 255
    assert detector.v_min == 30
    assert detector.v_max == 255

    # Custom config
    config = {
        "tissue_detection": {
            "method": "otsu",
            "hsv_threshold": {"h_min": 10, "h_max": 40, "s_min": 20, "s_max": 200, "v_min": 20, "v_max": 200}
        }
    }
    detector = TissueDetector(config)
    assert detector.threshold_method == "otsu"
    assert detector.h_min == 10


def test_rgb_to_hsv_conversion():
    """Test RGB to HSV conversion."""
    detector = TissueDetector()

    # Create a test image (red pixel)
    rgb_array = np.array([[[255, 0, 0]]], dtype=np.uint8)

    hsv = detector._rgb_to_hsv(rgb_array)
    assert hsv.shape == (1, 1, 3)
    assert hsv[0, 0, 0] > 0  # Hue should be non-zero for red


def test_hsv_thresholding():
    """Test HSV thresholding."""
    detector = TissueDetector()

    # Create HSV image with tissue-like values
    hsv_array = np.array([[[20, 100, 150]]], dtype=np.uint8)  # Within HSV ranges

    mask = detector._apply_hsv_thresholding(hsv_array, apply_morphology=False)
    assert mask.shape == (1, 1)
    assert mask[0, 0] == 255  # Should be detected as tissue

    # Create HSV image with background values
    hsv_array = np.array([[[200, 20, 30]]], dtype=np.uint8)  # Outside HSV ranges

    mask = detector._apply_hsv_thresholding(hsv_array, apply_morphology=False)
    assert mask[0, 0] == 0  # Should not be detected as tissue


def test_otsu_thresholding():
    """Test Otsu thresholding."""
    detector = TissueDetector()

    # Create grayscale image with two distinct regions
    gray_array = np.zeros((10, 10), dtype=np.uint8)
    gray_array[5:, :] = 200  # Dark region

    mask = detector._apply_otsu_thresholding(gray_array, apply_morphology=False)
    assert mask.shape == (10, 10)
    # Otsu should find a reasonable threshold


def test_morphological_operations():
    """Test morphological operations."""
    detector = TissueDetector()

    # Create a small white region
    mask = np.zeros((10, 10), dtype=np.uint8)
    mask[3:7, 3:7] = 255  # 4x4 white square

    # Apply morphological operations
    result = detector._apply_morphological_operations(mask)

    assert result.shape == mask.shape
    # Erosion and dilation should preserve the white region


def test_tissue_coverage_estimation():
    """Test tissue coverage estimation."""
    detector = TissueDetector()

    # Create test mask
    mask = np.zeros((100, 100), dtype=np.uint8)
    mask[20:80, 20:80] = 255  # 60x60 white region (36% coverage)

    coverage = detector.estimate_tissue_coverage(mask, total_area=10000)
    assert abs(coverage - 36.0) < 0.1  # Should be approximately 36%

    # Test with mask-derived total area
    coverage = detector.estimate_tissue_coverage(mask)
    assert abs(coverage - 36.0) < 0.1


def test_candidate_region_filtering():
    """Test candidate region filtering."""
    detector = TissueDetector()

    # Create test mask with multiple regions
    mask = np.zeros((50, 50), dtype=np.uint8)
    mask[5:15, 5:15] = 255  # Small region (should be filtered out)
    mask[20:40, 20:40] = 255  # Large region (should be kept)

    regions = detector.filter_candidate_regions(
        mask,
        min_patch_size=50,  # Small region should be filtered
        min_coverage_threshold=0.5
    )

    # Should only keep the large region
    assert len(regions) == 1
    x, y, w, h = regions[0]
    assert w >= 50 and h >= 50


def test_visualization_creation():
    """Test visualization creation."""
    detector = TissueDetector()

    # Create test mask and image
    mask = np.zeros((10, 10), dtype=np.uint8)
    mask[2:8, 2:8] = 255

    image = Image.new("RGB", (10, 10), (128, 128, 128))

    visualization = detector.create_visualization(mask, image)

    assert visualization.size == (10, 10)
    assert visualization.mode == "RGB"

    # Test with None image
    visualization = detector.create_visualization(mask, None)
    assert visualization.size == (10, 10)


def test_tissue_detector_repr():
    """Test string representation."""
    detector = TissueDetector()
    repr_str = repr(detector)
    assert "TissueDetector" in repr_str
    assert "threshold_method" in repr_str


def test_tissue_detector_error_handling():
    """Test error handling."""
    detector = TissueDetector()

    # Test with invalid image (None)
    with pytest.raises(TissueDetectorError):
        detector.detect_tissue_mask(None)

    # Test with empty image
    empty_image = Image.new("RGB", (0, 0), (0, 0, 0))
    with pytest.raises(TissueDetectorError):
        detector.detect_tissue_mask(empty_image)


def test_empty_tissue_detection():
    """Test handling of empty or nearly blank slides."""
    detector = TissueDetector()

    # Create blank image
    blank_image = Image.new("RGB", (100, 100), (0, 0, 0))
    mask = detector.detect_tissue_mask(blank_image)

    # Should handle gracefully (may produce empty or nearly empty mask)
    assert mask.shape == (100, 100)


def test_custom_hsv_thresholds():
    """Test custom HSV threshold configuration."""
    config = {
        "tissue_detection": {
            "hsv_threshold": {
                "h_min": 100,
                "h_max": 140,
                "s_min": 100,
                "s_max": 255,
                "v_min": 100,
                "v_max": 255
            }
        }
    }

    detector = TissueDetector(config)
    assert detector.h_min == 100
    assert detector.h_max == 140
    assert detector.s_min == 100
    assert detector.s_max == 255
    assert detector.v_min == 100
    assert detector.v_max == 255


if __name__ == "__main__":
    pytest.main([__file__, "-v"])