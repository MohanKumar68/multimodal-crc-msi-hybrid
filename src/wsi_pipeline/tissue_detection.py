"""Tissue detection for WSI patch extraction.

This module implements tissue detection algorithms to identify regions of interest
in whole-slide images. The primary method uses HSV-based thresholding to detect
tissue regions while excluding background areas.
"""

import numpy as np
from PIL import Image, ImageFilter
from typing import Tuple, Dict, Any, Optional
import logging
from skimage import morphology
from skimage.filters import threshold_otsu

from .config import TechnicalMetadata

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class TissueDetectorError(Exception):
    """Exception raised for tissue detection errors."""
    pass


class TissueDetector:
    """Detect tissue regions in whole-slide images."""

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        """
        Initialize the tissue detector.

        Args:
            config: Optional configuration dictionary
        """
        self.config = config or {}
        self.tissue_detection_config = self.config.get("tissue_detection", {})
        self.threshold_method = self.tissue_detection_config.get("method", "hsv_threshold")

        # HSV thresholding parameters
        hsv_config = self.tissue_detection_config.get("hsv_threshold", {})
        self.h_min = hsv_config.get("h_min", 0)
        self.h_max = hsv_config.get("h_max", 30)
        self.s_min = hsv_config.get("s_min", 30)
        self.s_max = hsv_config.get("s_max", 255)
        self.v_min = hsv_config.get("v_min", 30)
        self.v_max = hsv_config.get("v_max", 255)

        # Morphological operation kernels
        kernel_size = self.tissue_detection_config.get("erosion_kernel", 3)
        self.erosion_kernel = np.ones((kernel_size, kernel_size), np.uint8)
        self.dilation_kernel = np.ones((kernel_size, kernel_size), np.uint8)

    def detect_tissue_mask(
        self,
        image: Image.Image,
        apply_morphology: bool = True
    ) -> np.ndarray:
        """
        Detect tissue regions in an image using configurable thresholding.

        Args:
            image: Input image (PIL Image)
            apply_morphology: Whether to apply morphological operations

        Returns:
            Binary tissue mask as numpy array (uint8, 0=background, 255=tissue)

        Raises:
            TissueDetectorError: If tissue detection fails
        """
        try:
            # Convert to numpy array for processing
            img_array = np.array(image)

            if len(img_array.shape) == 3:
                # RGB image - convert to HSV
                hsv = self._rgb_to_hsv(img_array)
                return self._apply_hsv_thresholding(hsv, apply_morphology)
            else:
                # Grayscale image - use Otsu's method
                return self._apply_otsu_thresholding(img_array, apply_morphology)

        except Exception as e:
            raise TissueDetectorError(f"Tissue detection failed: {e}")

    def _rgb_to_hsv(self, rgb_array: np.ndarray) -> np.ndarray:
        """Convert RGB array to HSV."""
        # Normalize to [0, 1]
        rgb_normalized = rgb_array.astype(np.float32) / 255.0

        # Convert to HSV
        hsv = np.zeros_like(rgb_normalized)
        hsv[..., 0] = np.mod(np.arctan2(
            np.sqrt(3) * (rgb_normalized[..., 1] - rgb_normalized[..., 2]),
            2 * rgb_normalized[..., 0] - rgb_normalized[..., 1] - rgb_normalized[..., 2]
        ) / (2 * np.pi) * 255, 256)

        max_val = np.max(rgb_normalized, axis=2)
        min_val = np.min(rgb_normalized, axis=2)
        delta = max_val - min_val

        hsv[..., 1] = np.where(max_val > 0, delta / max_val, 0) * 255
        hsv[..., 2] = max_val * 255

        return hsv.astype(np.uint8)

    def _apply_hsv_thresholding(
        self,
        hsv_array: np.ndarray,
        apply_morphology: bool
    ) -> np.ndarray:
        """Apply HSV thresholding to create tissue mask."""
        h = hsv_array[..., 0]  # Hue
        s = hsv_array[..., 1]  # Saturation
        v = hsv_array[..., 2]  # Value

        # Apply HSV thresholds
        h_mask = (h >= self.h_min) & (h <= self.h_max)
        s_mask = (s >= self.s_min) & (s <= self.s_max)
        v_mask = (v >= self.v_min) & (v <= self.v_max)

        # Combine masks
        combined_mask = h_mask & s_mask & v_mask

        # Convert to uint8 (0=background, 255=tissue)
        tissue_mask = np.zeros_like(h, dtype=np.uint8)
        tissue_mask[combined_mask] = 255

        if apply_morphology:
            tissue_mask = self._apply_morphological_operations(tissue_mask)

        return tissue_mask

    def _apply_otsu_thresholding(
        self,
        gray_array: np.ndarray,
        apply_morphology: bool
    ) -> np.ndarray:
        """Apply Otsu's thresholding to grayscale image."""
        # Apply Otsu's method
        threshold = threshold_otsu(gray_array)
        binary_mask = gray_array > threshold

        # Convert to uint8 (0=background, 255=tissue)
        tissue_mask = np.zeros_like(gray_array, dtype=np.uint8)
        tissue_mask[binary_mask] = 255

        if apply_morphology:
            tissue_mask = self._apply_morphological_operations(tissue_mask)

        return tissue_mask

    def _apply_morphological_operations(self, binary_mask: np.ndarray) -> np.ndarray:
        """Apply morphological operations to clean up the binary mask."""
        try:
            # Convert to binary format for scikit-image
            binary = binary_mask > 127

            # Apply erosion then dilation (opening)
            if self.erosion_kernel.size > 0:
                binary = morphology.opening(binary, self.erosion_kernel)

            # Apply dilation then erosion (closing)
            if self.dilation_kernel.size > 0:
                binary = morphology.closing(binary, self.dilation_kernel)

            # Convert back to uint8
            result = np.zeros_like(binary_mask, dtype=np.uint8)
            result[binary] = 255

            return result

        except Exception as e:
            logger.warning(f"Morphological operations failed: {e}")
            return binary_mask

    def estimate_tissue_coverage(
        self,
        tissue_mask: np.ndarray,
        total_area: Optional[int] = None
    ) -> float:
        """
        Estimate tissue coverage percentage.

        Args:
            tissue_mask: Binary tissue mask
            total_area: Total area (width * height). If None, calculated from mask

        Returns:
            Tissue coverage as percentage (0-100)
        """
        if total_area is None:
            total_area = tissue_mask.size

        tissue_pixels = np.sum(tissue_mask > 127)
        coverage = (tissue_pixels / total_area) * 100

        return coverage

    def filter_candidate_regions(
        self,
        tissue_mask: np.ndarray,
        min_patch_size: int = 100,
        min_coverage_threshold: float = 0.5
    ) -> list:
        """
        Filter candidate tissue regions based on size and coverage.

        Args:
            tissue_mask: Binary tissue mask
            min_patch_size: Minimum patch size in pixels
            min_coverage_threshold: Minimum tissue coverage as fraction (0-1)

        Returns:
            List of candidate regions (x, y, width, height)
        """
        try:
            # Convert to binary for connected component analysis
            binary_mask = tissue_mask > 127

            # Find connected components
            labeled_array, num_features = morphology.label(binary_mask)

            candidate_regions = []

            for label in range(1, num_features + 1):
                # Get bounding box of component
                coords = np.where(label == labeled_array)
                if len(coords[0]) == 0:
                    continue

                x_min, x_max = np.min(coords[1]), np.max(coords[1])
                y_min, y_max = np.min(coords[0]), np.max(coords[0])
                width = x_max - x_min + 1
                height = y_max - y_min + 1

                # Check size requirements
                if width >= min_patch_size and height >= min_patch_size:
                    # Calculate coverage of this region within its bounding box
                    region_area = width * height
                    component_pixels = np.sum(label == labeled_array[y_min:y_max+1, x_min:x_max+1])
                    region_coverage = component_pixels / region_area

                    if region_coverage >= min_coverage_threshold:
                        candidate_regions.append((x_min, y_min, width, height))

            return candidate_regions

        except Exception as e:
            logger.warning(f"Region filtering failed: {e}")
            return []

    def create_visualization(
        self,
        tissue_mask: np.ndarray,
        original_image: Optional[Image.Image] = None
    ) -> Image.Image:
        """
        Create a visualization of the tissue mask.

        Args:
            tissue_mask: Binary tissue mask
            original_image: Optional original image for overlay

        Returns:
            Visualization image
        """
        try:
            if original_image is None:
                # Create a blank image
                height, width = tissue_mask.shape
                visualization = Image.new("RGB", (width, height), (0, 0, 0))
            else:
                visualization = original_image.copy()

            # Create overlay
            overlay = np.zeros((tissue_mask.shape[0], tissue_mask.shape[1], 3), dtype=np.uint8)
            overlay[tissue_mask > 127] = (0, 255, 0)  # Green for tissue

            if original_image is not None:
                # Blend with original
                np.copyto(visualization, overlay, where=tissue_mask > 127)
            else:
                visualization = Image.fromarray(overlay)

            return visualization

        except Exception as e:
            logger.warning(f"Visualization creation failed: {e}")
            return Image.new("RGB", (100, 100), (0, 0, 0))

    def __repr__(self) -> str:
        """String representation."""
        return (
            f"TissueDetector(threshold_method={self.threshold_method}, "
            f"h_range=({self.h_min}-{self.h_max}), s_range=({self.s_min}-{self.s_max}), "
            f"v_range=({self.v_min}-{self.v_max}))"
        )


if __name__ == "__main__":
    # Example usage
    import argparse

    parser = argparse.ArgumentParser(description="Test TissueDetector")
    parser.add_argument("image_path", help="Path to test image")
    parser.add_argument("--output", help="Output path for tissue mask")

    args = parser.parse_args()

    try:
        # Initialize detector
        detector = TissueDetector()

        # Load test image
        test_image = Image.open(args.image_path)
        print(f"Loaded test image: {test_image.size}, mode: {test_image.mode}")

        # Detect tissue
        tissue_mask = detector.detect_tissue_mask(test_image)
        coverage = detector.estimate_tissue_coverage(tissue_mask)
        print(f"Tissue coverage: {coverage:.2f}%")

        # Get candidate regions
        regions = detector.filter_candidate_regions(tissue_mask, min_patch_size=100)
        print(f"Found {len(regions)} candidate regions")

        # Create visualization
        visualization = detector.create_visualization(tissue_mask, test_image)

        # Save results
        if args.output:
            visualization.save(args.output)
            print(f"Visualization saved to: {args.output}")

        print("Test completed successfully")

    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()