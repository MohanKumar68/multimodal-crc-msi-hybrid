"""Patch extractor for WSI processing.

This module provides memory-efficient patch extraction from whole-slide images
based on tissue detection masks. It supports configurable extraction parameters,
incremental processing, and resumable operations.
"""

import os
import time
import hashlib
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any
import logging
import json
import numpy as np

from PIL import Image

from .config import TechnicalMetadata, CaseMetadata
from .slide_reader import SlideReader
from .tissue_detection import TissueDetector

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class PatchExtractorError(Exception):
    """Exception raised for patch extraction errors."""
    pass


class PatchRecord:
    """Record of an extracted patch."""

    def __init__(
        self,
        patch_id: str,
        slide_id: str,
        coordinates: Tuple[int, int, int, int],  # x, y, width, height
        pyramid_level: int,
        tissue_coverage: float,
        output_path: Optional[str] = None,
        extraction_status: str = "pending",
        error_message: Optional[str] = None,
        checksum: Optional[str] = None
    ):
        """
        Initialize patch record.

        Args:
            patch_id: Unique identifier for the patch
            slide_id: Identifier for the source slide
            coordinates: Patch coordinates (x, y, width, height) at extraction level
            pyramid_level: Pyramid level from which patch was extracted
            tissue_coverage: Percentage of tissue in the patch
            output_path: Path where patch was saved
            extraction_status: Status of extraction ("pending", "completed", "failed")
            error_message: Error message if extraction failed
            checksum: SHA256 checksum of extracted patch
        """
        self.patch_id = patch_id
        self.slide_id = slide_id
        self.coordinates = coordinates
        self.pyramid_level = pyramid_level
        self.tissue_coverage = tissue_coverage
        self.output_path = output_path
        self.extraction_status = extraction_status
        self.error_message = error_message
        self.checksum = checksum
        self.extraction_timestamp = time.strftime("%Y-%m-%d %H:%M:%S")

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            "patch_id": self.patch_id,
            "slide_id": self.slide_id,
            "coordinates": {
                "x": self.coordinates[0],
                "y": self.coordinates[1],
                "width": self.coordinates[2],
                "height": self.coordinates[3]
            },
            "pyramid_level": self.pyramid_level,
            "tissue_coverage": self.tissue_coverage,
            "output_path": self.output_path,
            "extraction_status": self.extraction_status,
            "error_message": self.error_message,
            "checksum": self.checksum,
            "extraction_timestamp": self.extraction_timestamp
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "PatchRecord":
        """Create PatchRecord from dictionary."""
        coords = data["coordinates"]
        return cls(
            patch_id=data["patch_id"],
            slide_id=data["slide_id"],
            coordinates=(coords["x"], coords["y"], coords["width"], coords["height"]),
            pyramid_level=data["pyramid_level"],
            tissue_coverage=data["tissue_coverage"],
            output_path=data.get("output_path"),
            extraction_status=data.get("extraction_status", "pending"),
            error_message=data.get("error_message"),
            checksum=data.get("checksum")
        )

    def __repr__(self) -> str:
        """String representation."""
        return (
            f"PatchRecord(id={self.patch_id}, slide={self.slide_id}, "
            f"coords={self.coordinates}, level={self.pyramid_level}, "
            f"status={self.extraction_status})"
        )


class PatchExtractor:
    """Memory-efficient patch extractor for WSI."""

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        """
        Initialize the patch extractor.

        Args:
            config: Optional configuration dictionary
        """
        self.config = config or {}
        self.patch_config = self.config.get("patch_extraction", {})
        self.output_directory = Path(self.patch_config.get("output_directory", "wsi_extracted_patches"))
        self.patch_size = self.patch_config.get("patch_size", {"width": 224, "height": 224})
        self.patch_stride = self.patch_config.get("patch_stride", {"x": 224, "y": 224})
        self.max_patches = self.patch_config.get("max_patches", 1000)
        self.min_tissue_coverage = self.patch_config.get("min_tissue_coverage", 0.5)
        self.extraction_level = self.patch_config.get("extraction_level", 0)

        # Create output directory
        self.output_directory.mkdir(parents=True, exist_ok=True)

        # Track extracted patches
        self.extracted_patches: List[PatchRecord] = []
        self.processing_start_time: Optional[float] = None
        self.processing_end_time: Optional[float] = None

    def extract_patches_from_slide(
        self,
        slide_path: str,
        slide_id: Optional[str] = None,
        technical_metadata: Optional[TechnicalMetadata] = None,
        case_metadata: Optional[CaseMetadata] = None,
        resume_manifest_path: Optional[str] = None
    ) -> Tuple[List[PatchRecord], Dict[str, Any]]:
        """
        Extract patches from a whole-slide image.

        Args:
            slide_path: Path to WSI file
            slide_id: Optional slide ID
            technical_metadata: Optional pre-extracted technical metadata
            case_metadata: Optional case metadata
            resume_manifest_path: Optional path to manifest for resuming extraction

        Returns:
            Tuple of (list of patch records, processing summary)

        Raises:
            PatchExtractorError: If patch extraction fails
        """
        try:
            self.processing_start_time = time.time()
            logger.info(f"Starting patch extraction from: {slide_path}")

            # Load or initialize manifest for resuming
            manifest = self._load_or_initialize_manifest(
                slide_id or Path(slide_path).stem,
                resume_manifest_path
            )

            # Initialize slide reader
            with SlideReader(self.config) as reader:
                reader.open_slide(slide_path)

                # Update technical metadata if provided
                if technical_metadata:
                    reader.metadata = technical_metadata

                # Generate slide ID if not provided
                current_slide_id = slide_id or self._generate_slide_id(slide_path)

                # Initialize tissue detector
                detector = TissueDetector(self.config)

                # Get slide dimensions
                level_dimensions = reader.get_level_dimensions(self.extraction_level)
                level_downsample = reader.get_level_downsample(self.extraction_level)

                logger.info(
                    f"Slide dimensions at level {self.extraction_level}: {level_dimensions}, "
                    f"downsample: {level_downsample}"
                )

                # Extract patches
                patch_records = self._extract_patches_from_level(
                    reader, current_slide_id, level_dimensions, level_downsample, detector, manifest
                )

                self.processing_end_time = time.time()

                # Save manifest
                manifest_path = self.output_directory / f"{current_slide_id}_manifest.json"
                manifest.save(manifest_path)

                logger.info(
                    f"Completed patch extraction. "
                    f"Successfully extracted {len(patch_records)} patches. "
                    f"Total time: {self.processing_end_time - self.processing_start_time:.2f}s"
                )

                return patch_records, self._generate_summary(current_slide_id, slide_path)

        except Exception as e:
            raise PatchExtractorError(f"Patch extraction failed: {e}")

    def _load_or_initialize_manifest(
        self,
        slide_id: str,
        resume_manifest_path: Optional[str] = None
    ) -> "WSIManifest":
        """Load existing manifest or initialize new one."""
        if resume_manifest_path and Path(resume_manifest_path).exists():
            # Load from resume manifest
            from .manifest import WSIManifest
            with open(resume_manifest_path, 'r') as f:
                data = json.load(f)
            return WSIManifest.from_dict(data)
        else:
            # Create new manifest
            from .manifest import WSIManifest, ExtractionConfig
            config = ExtractionConfig(
                patch_size=self.patch_size,
                patch_stride=self.patch_stride,
                max_patches=self.max_patches,
                min_tissue_coverage=self.min_tissue_coverage,
                extraction_level=self.extraction_level,
                output_directory=str(self.output_directory)
            )
            return WSIManifest(slide_id=slide_id, extraction_config=config)

    def _generate_slide_id(self, slide_path: str) -> str:
        """Generate slide ID from path."""
        slide_name = Path(slide_path).stem.replace(" ", "_").replace("-", "_")
        file_hash = hashlib.md5(slide_path.encode()).hexdigest()[:12]
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        return f"{slide_name}_{file_hash}_{timestamp}"

    def _extract_patches_from_level(
        self,
        reader: SlideReader,
        slide_id: str,
        level_dimensions: Tuple[int, int],
        level_downsample: float,
        detector: TissueDetector,
        manifest: "WSIManifest"
    ) -> List[PatchRecord]:
        """
        Extract patches from a specific pyramid level.

        Args:
            reader: Slide reader instance
            slide_id: Slide identifier
            level_dimensions: Dimensions at current extraction level
            level_downsample: Downsample factor
            detector: Tissue detector instance
            manifest: Manifest instance

        Returns:
            List of patch records
        """
        width, height = level_dimensions
        patch_width, patch_height = self.patch_size["width"], self.patch_size["height"]
        stride_x, stride_y = self.patch_stride["x"], self.patch_stride["y"]

        # Calculate extraction area based on tissue detection
        tissue_mask = self._create_tissue_mask_at_level(reader, detector, self.extraction_level)

        # Get candidate regions from tissue mask
        candidate_regions = detector.filter_candidate_regions(
            tissue_mask,
            min_patch_size=min(patch_width, patch_height) * 2,
            min_coverage_threshold=self.min_tissue_coverage
        )

        # Filter candidates based on slide boundaries
        filtered_regions = []
        for x, y, w, h in candidate_regions:
            # Check if region fits within slide boundaries at extraction level
            x_level = int(x * level_downsample)
            y_level = int(y * level_downsample)
            w_level = int(w * level_downsample)
            h_level = int(h * level_downsample)

            if (x_level + w_level <= width and
                y_level + h_level <= height and
                w_level >= patch_width and
                h_level >= patch_height):

                # Crop to patch size if region is larger
                if w_level > patch_width or h_level > patch_height:
                    # Center the crop
                    x_center = x_level + w_level // 2
                    y_center = y_level + h_level // 2
                    x_level = max(0, x_center - patch_width // 2)
                    y_level = max(0, y_center - patch_height // 2)

                filtered_regions.append((x_level, y_level, patch_width, patch_height))

        # Limit to max patches
        if len(filtered_regions) > self.max_patches:
            logger.warning(
                f"Found {len(filtered_regions)} candidate regions, "
                f"limiting to {self.max_patches}"
            )
            filtered_regions = filtered_regions[:self.max_patches]

        logger.info(f"Will extract {len(filtered_regions)} patches")

        # Extract patches
        patch_records = []
        for i, (x, y, w, h) in enumerate(filtered_regions):
            patch_id = f"{slide_id}_patch_{i:06d}"

            try:
                # Extract patch
                patch = reader.read_region((x, y), self.extraction_level, (w, h))

                # Save patch
                patch_path = self.output_directory / f"{patch_id}.png"
                patch.save(patch_path)

                # Calculate tissue coverage
                tissue_pixels = np.sum(np.array(patch) > 127)
                patch_area = w * h
                tissue_coverage = tissue_pixels / patch_area if patch_area > 0 else 0.0

                # Generate checksum
                checksum = self._calculate_file_checksum(patch_path)

                # Create patch record
                patch_record = PatchRecord(
                    patch_id=patch_id,
                    slide_id=slide_id,
                    coordinates=(x, y, w, h),
                    pyramid_level=self.extraction_level,
                    tissue_coverage=tissue_coverage,
                    output_path=str(patch_path),
                    extraction_status="completed",
                    checksum=checksum
                )

                patch_records.append(patch_record)
                manifest.add_patch(patch_record)

                logger.debug(f"Extracted patch {i+1}/{len(filtered_regions)}: {patch_id}")

            except Exception as e:
                logger.error(f"Failed to extract patch {i+1}: {e}")

                # Create failed patch record
                patch_record = PatchRecord(
                    patch_id=patch_id,
                    slide_id=slide_id,
                    coordinates=(x, y, w, h),
                    pyramid_level=self.extraction_level,
                    tissue_coverage=0.0,
                    extraction_status="failed",
                    error_message=str(e)
                )

                patch_records.append(patch_record)
                manifest.add_patch(patch_record)

        return patch_records

    def _create_tissue_mask_at_level(
        self,
        reader: SlideReader,
        detector: TissueDetector,
        level: int
    ) -> np.ndarray:
        """
        Create tissue mask at extraction level.

        Args:
            reader: Slide reader instance
            detector: Tissue detector instance
            level: Pyramid level

        Returns:
            Binary tissue mask
        """
        try:
            # Get slide dimensions at target level
            width, height = reader.get_level_dimensions(level)

            # Calculate thumbnail size for tissue detection
            thumbnail_size = self.config.get("thumbnail_size", {"width": 800, "height": 600})

            # Generate thumbnail at the target level
            thumbnail = reader.get_thumbnail(
                size=(min(thumbnail_size["width"], width),
                      min(thumbnail_size["height"], height)),
                level=level
            )

            # Detect tissue in thumbnail
            tissue_mask = detector.detect_tissue_mask(thumbnail, apply_morphology=True)

            return tissue_mask

        except Exception as e:
            logger.warning(f"Failed to create tissue mask at level {level}: {e}")
            # Return empty mask
            return np.zeros((100, 100), dtype=np.uint8)

    def _calculate_file_checksum(self, file_path: Path) -> str:
        """Calculate SHA256 checksum of a file."""
        sha256_hash = hashlib.sha256()
        with open(file_path, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()

    def _generate_summary(
        self,
        slide_id: str,
        slide_path: str
    ) -> Dict[str, Any]:
        """
        Generate processing summary.

        Args:
            slide_id: Slide identifier
            slide_path: Slide file path

        Returns:
            Processing summary dictionary
        """
        total_time = (self.processing_end_time - self.processing_start_time) if self.processing_start_time and self.processing_end_time else 0.0

        summary = {
            "slide_id": slide_id,
            "slide_path": str(slide_path),
            "processing_start_time": (
                time.strftime("%Y-%m-%d %H:%M:%S")
                if self.processing_start_time else None
            ),
            "processing_end_time": (
                time.strftime("%Y-%m-%d %H:%M:%S")
                if self.processing_end_time else None
            ),
            "total_processing_time_seconds": total_time,
            "extraction_level": self.extraction_level,
            "patch_size": self.patch_size,
            "patch_stride": self.patch_stride,
            "max_patches": self.max_patches,
            "min_tissue_coverage": self.min_tissue_coverage,
            "output_directory": str(self.output_directory),
            "total_patches_requested": len(self.extracted_patches),
            "successful_patches": sum(
                1 for p in self.extracted_patches
                if p.extraction_status == "completed"
            ),
            "failed_patches": sum(
                1 for p in self.extracted_patches
                if p.extraction_status == "failed"
            ),
            "skipped_patches": sum(
                1 for p in self.extracted_patches
                if p.extraction_status == "skipped"
            )
        }

        return summary

    def save_manifest(self, manifest_path: str) -> None:
        """
        Save extraction manifest to file.

        Args:
            manifest_path: Path to save manifest
        """
        try:
            manifest_data = {
                "patch_records": [p.to_dict() for p in self.extracted_patches],
                "summary": self._generate_summary(
                    Path(manifest_path).stem,
                    manifest_path
                )
            }

            with open(manifest_path, 'w') as f:
                json.dump(manifest_data, f, indent=2)

            logger.info(f"Manifest saved to: {manifest_path}")

        except Exception as e:
            logger.error(f"Failed to save manifest: {e}")
            raise PatchExtractorError(f"Failed to save manifest: {e}")

    def get_extraction_summary(self) -> Dict[str, Any]:
        """Get current extraction summary."""
        return self._generate_summary(
            "current_session",
            "current_session"
        )

    def __repr__(self) -> str:
        """String representation."""
        return (
            f"PatchExtractor(patch_size={self.patch_size}, "
            f"max_patches={self.max_patches}, "
            f"output_dir={self.output_directory})"
        )


# Import at bottom to avoid circular imports
from .manifest import WSIManifest


if __name__ == "__main__":
    # Example usage
    import argparse

    parser = argparse.ArgumentParser(description="Test PatchExtractor")
    parser.add_argument("slide_path", help="Path to WSI file")
    parser.add_argument("--output-dir", help="Output directory for patches")
    parser.add_argument("--max-patches", type=int, default=10, help="Maximum patches to extract")
    parser.add_argument("--level", type=int, default=0, help="Pyramid level for extraction")

    args = parser.parse_args()

    try:
        # Configure extraction
        config = {
            "patch_extraction": {
                "output_directory": args.output_dir or "test_extracted_patches",
                "max_patches": args.max_patches,
                "extraction_level": args.level,
                "thumbnail_size": {"width": 512, "height": 512}
            }
        }

        # Extract patches
        extractor = PatchExtractor(config)
        records, summary = extractor.extract_patches_from_slide(
            slide_path=args.slide_path,
            slide_id="test_slide"
        )

        print(f"Extraction completed!")
        print(f"Summary: {json.dumps(summary, indent=2)}")
        print(f"Extracted {len(records)} patch records")

    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()