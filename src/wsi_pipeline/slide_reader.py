"""WSI slide reader using OpenSlide.

This module provides a memory-efficient reader for whole-slide images (WSI)
using the OpenSlide library. It supports reading metadata, thumbnails, and
regions without loading the entire slide into memory.
"""

import os
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any
import logging

from PIL import Image
import openslide

from .config import TechnicalMetadata, validate_slide_path, generate_slide_id

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class SlideReaderError(Exception):
    """Exception raised for slide reading errors."""
    pass


class SlideReader:
    """Memory-efficient whole-slide image reader."""

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        """
        Initialize the slide reader.

        Args:
            config: Optional configuration dictionary
        """
        self.config = config or {}
        self.slide: Optional[openslide.OpenSlide] = None
        self.slide_path: Optional[Path] = None
        self.metadata: TechnicalMetadata = TechnicalMetadata()
        self._is_open: bool = False

    def open_slide(self, slide_path: str) -> "SlideReader":
        """
        Open a whole-slide image file.

        Args:
            slide_path: Path to the WSI file

        Returns:
            Self for method chaining

        Raises:
            SlideReaderError: If the slide cannot be opened
        """
        try:
            # Validate and convert path
            self.slide_path = validate_slide_path(slide_path)

            # Check file size if configured
            if self.config.get("file_validation", {}).get("check_file_size", True):
                max_size_mb = self.config["file_validation"]["max_file_size_mb"]
                file_size_mb = self.slide_path.stat().st_size / (1024 * 1024)
                if file_size_mb > max_size_mb:
                    raise SlideReaderError(
                        f"File size ({file_size_mb:.2f} MB) exceeds maximum "
                        f"({max_size_mb} MB)"
                    )

            # Open the slide
            logger.info(f"Opening slide: {self.slide_path}")
            self.slide = openslide.OpenSlide(str(self.slide_path))
            self._is_open = True

            # Update metadata
            self._update_metadata()

            logger.info(f"Successfully opened slide: {self.slide_path}")
            return self

        except openslide.OpenSlideError as e:
            raise SlideReaderError(f"Failed to open slide {slide_path}: {e}")
        except Exception as e:
            raise SlideReaderError(f"Error opening slide {slide_path}: {e}")

    def _update_metadata(self) -> None:
        """Extract and update slide metadata."""
        if not self.slide:
            return

        try:
            # Basic file information
            self.metadata.file_format = self._detect_format()
            self.metadata.file_size_bytes = self.slide_path.stat().st_size
            self.metadata.processing_timestamp = time.strftime("%Y-%m-%d %H:%M:%S")

            # Level information
            self.metadata.level_count = len(self.slide.level_dimensions)
            self.metadata.dimensions = self.slide.level_dimensions[0]

            # Pyramid dimensions and downsample factors
            self.metadata.pyramid_dimensions = list(self.slide.level_dimensions)
            self.metadata.downsample_factors = [
                self.slide.level_downsamples[i]
                for i in range(self.metadata.level_count)
            ]

            # Read slide properties
            properties = self.slide.properties

            # Check for MPP (microns per pixel)
            if openslide.PROPERTY_NAME_MPP_X in properties:
                self.metadata.mpp_x = float(properties[openslide.PROPERTY_NAME_MPP_X])
                self.metadata.is_mpp_available = True

            if openslide.PROPERTY_NAME_MPP_Y in properties:
                self.metadata.mpp_y = float(properties[openslide.PROPERTY_NAME_MPP_Y])
                self.metadata.is_mpp_available = True

            # Read objective power
            if openslide.PROPERTY_NAME_OBJECTIVE_POWER in properties:
                self.metadata.objective_power = float(
                    properties[openslide.PROPERTY_NAME_OBJECTIVE_POWER]
                )

            # Read vendor information
            if openslide.PROPERTY_NAME_VENDOR in properties:
                self.metadata.vendor = properties[openslide.PROPERTY_NAME_VENDOR]

            # Read color space
            if openslide.PROPERTY_NAME_COLOR_TYPE in properties:
                self.metadata.color_space = properties[openslide.PROPERTY_NAME_COLOR_TYPE]

            # Read byte order
            if openslide.PROPERTY_NAME_BYTE_ORDER in properties:
                self.metadata.byte_order = properties[openslide.PROPERTY_NAME_BYTE_ORDER]

            # Check for associated images
            self.metadata.associated_images = []
            for key in properties:
                if key.startswith("openslide.associated-image."):
                    self.metadata.associated_images.append(key.split(".", 1)[1])

            logger.debug(f"Extracted metadata: {self.metadata.to_dict()}")

        except Exception as e:
            logger.warning(f"Error extracting metadata: {e}")
            # Continue with partial metadata

    def _detect_format(self) -> str:
        """Detect slide format from file extension and OpenSlide capabilities."""
        if not self.slide_path:
            return "unknown"

        ext = self.slide_path.suffix.lower()

        # Map extensions to OpenSlide format names
        format_map = {
            ".svs": "svs",
            ".tif": "tif",
            ".tiff": "tiff",
            ".ndpi": "ndpi",

        }

        return format_map.get(ext, "unknown")

    def get_thumbnail(
        self,
        size: Optional[Tuple[int, int]] = None,
        level: int = 0
    ) -> Image.Image:
        """
        Get a thumbnail of the slide.

        Args:
            size: Thumbnail size as (width, height)
            level: Pyramid level to read from

        Returns:
            PIL Image

        Raises:
            SlideReaderError: If slide is not open or thumbnail cannot be generated
        """
        if not self.slide:
            raise SlideReaderError("Slide not open")

        try:
            if size is None:
                size = self.config.get("thumbnail_size", {"width": 800, "height": 600})

            # Generate thumbnail
            thumbnail = self.slide.get_thumbnail(size)
            return thumbnail

        except Exception as e:
            raise SlideReaderError(f"Failed to generate thumbnail: {e}")

    def read_region(
        self,
        location: Tuple[int, int],
        level: int,
        size: Tuple[int, int]
    ) -> Image.Image:
        """
        Read a region from the slide.

        Args:
            location: Top-left corner coordinates (x, y) at specified level
            level: Pyramid level
            size: Region size (width, height)

        Returns:
            PIL Image

        Raises:
            SlideReaderError: If region cannot be read
        """
        if not self.slide:
            raise SlideReaderError("Slide not open")

        try:
            # Read region
            region = self.slide.read_region(location, level, size)
            return region

        except Exception as e:
            raise SlideReaderError(f"Failed to read region at {location}: {e}")

    def get_level_dimensions(self, level: int = 0) -> Tuple[int, int]:
        """
        Get dimensions at a specific pyramid level.

        Args:
            level: Pyramid level

        Returns:
            Tuple of (width, height)

        Raises:
            SlideReaderError: If slide is not open or level is invalid
        """
        if not self.slide or not self._is_open:
            raise SlideReaderError("Slide not open")

        if level < 0:
            raise SlideReaderError(f"Invalid level {level}. Level cannot be negative")

        # Get the actual number of levels from the slide
        try:
            actual_level_count = len(self.slide.level_dimensions)
            if level >= actual_level_count:
                raise SlideReaderError(
                    f"Invalid level {level}. Valid range: 0-{actual_level_count - 1}"
                )
            return self.slide.level_dimensions[level]
        except AttributeError:
            raise SlideReaderError("Slide does not have level_dimensions attribute")

    def get_level_downsample(self, level: int = 0) -> float:
        """
        Get downsample factor for a specific pyramid level.

        Args:
            level: Pyramid level

        Returns:
            Downsample factor

        Raises:
            SlideReaderError: If slide is not open or level is invalid
        """
        if not self.slide or not self._is_open:
            raise SlideReaderError("Slide not open")

        if level < 0:
            raise SlideReaderError(f"Invalid level {level}. Level cannot be negative")

        # Get the actual number of levels from the slide
        try:
            actual_level_count = len(self.slide.level_dimensions)
            if level >= actual_level_count:
                raise SlideReaderError(
                    f"Invalid level {level}. Valid range: 0-{actual_level_count - 1}"
                )
            return self.slide.level_downsamples[level]
        except AttributeError:
            raise SlideReaderError("Slide does not have level_dimensions attribute")

    def close(self) -> None:
        """Close the slide and release resources."""
        if self.slide:
            self.slide.close()
            self.slide = None
            self._is_open = False
            logger.info("Slide closed")

    def __enter__(self) -> "SlideReader":
        """Context manager entry."""
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        """Context manager exit."""
        self.close()

    def __del__(self) -> None:
        """Destructor."""
        try:
            self.close()
        except AttributeError:
            # Ignore only AttributeError during cleanup, especially with mock objects
            pass

    def __repr__(self) -> str:
        """String representation."""
        status = "open" if self._is_open else "closed"
        path_str = str(self.slide_path) if self.slide_path else "none"
        return f"SlideReader(status={status}, path={path_str})"


# Singleton instance for backward compatibility
_current_reader: Optional[SlideReader] = None


def get_current_reader() -> Optional[SlideReader]:
    """Get the current slide reader instance (for backward compatibility)."""
    return _current_reader


def set_current_reader(reader: SlideReader) -> None:
    """Set the current slide reader instance (for backward compatibility)."""
    global _current_reader
    _current_reader = reader


if __name__ == "__main__":
    # Example usage
    import argparse

    parser = argparse.ArgumentParser(description="Test SlideReader")
    parser.add_argument("slide_path", help="Path to WSI file")
    parser.add_argument("--level", type=int, default=0, help="Pyramid level")
    parser.add_argument("--width", type=int, default=512, help="Region width")
    parser.add_argument("--height", type=int, default=512, help="Region height")
    parser.add_argument("--x", type=int, default=0, help="Region X coordinate")
    parser.add_argument("--y", type=int, default=0, help="Region Y coordinate")

    args = parser.parse_args()

    try:
        with SlideReader() as reader:
            reader.open_slide(args.slide_path)
            print(f"Opened slide: {reader.slide_path}")
            print(f"Metadata: {reader.metadata.to_dict()}")

            # Test thumbnail
            thumb = reader.get_thumbnail()
            print(f"Thumbnail size: {thumb.size}")

            # Test region reading
            width, height = reader.get_level_dimensions(args.level)
            print(f"Level {args.level} dimensions: {width}x{height}")

            # Read a region if possible
            if width >= args.x + args.width and height >= args.y + args.height:
                region = reader.read_region((args.x, args.y), args.level, (args.width, args.height))
                print(f"Region size: {region.size}")
            else:
                print(f"Region ({args.x}, {args.y}, {args.width}, {args.height}) too large for level {args.level}")

    except SlideReaderError as e:
        print(f"Error: {e}")
    except Exception as e:
        print(f"Unexpected error: {e}")