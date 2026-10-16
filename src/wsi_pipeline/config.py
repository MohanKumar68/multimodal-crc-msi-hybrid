"""Configuration management for WSI pipeline.

This module defines configuration classes and utilities for WSI processing,
including validation, defaults, and schema definitions for both automatic
and manual metadata.
"""

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Any, Union
from datetime import datetime
from enum import Enum
from pydantic import BaseModel, validator, Field
import hashlib


class SlideFormat(str, Enum):
    """Supported whole-slide image formats."""
    SVS = "svs"
    TIF = "tif"
    TIFF = "tiff"
    NDPI = "ndpi"
    SCN = "scn"
    UFM = "ufm"
    UNKNOWN = "unknown"


class ProcessingStatus(str, Enum):
    """Pipeline processing status."""
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    PARTIAL = "partial"
    FAILED = "failed"
    CANCELLED = "cancelled"
    PAUSED = "paused"


@dataclass
class TechnicalMetadata:
    """Automatically extracted technical metadata from WSI file."""

    file_format: str = "unknown"
    file_size_bytes: int = 0
    dimensions: Optional[Dict[str, int]] = None  # {'width': int, 'height': int}
    level_count: int = 0
    pyramid_dimensions: List[Dict[str, int]] = field(default_factory=list)
    downsample_factors: List[float] = field(default_factory=list)
    mpp_x: Optional[float] = None
    mpp_y: Optional[float] = None
    objective_power: Optional[float] = None
    vendor: Optional[str] = None
    associated_images: Optional[List[str]] = None
    color_space: Optional[str] = None
    byte_order: Optional[str] = None
    is_mpp_available: bool = False
    processing_timestamp: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TechnicalMetadata":
        """Create TechnicalMetadata from dictionary."""
        instance = cls()
        for key, value in data.items():
            if hasattr(instance, key):
                setattr(instance, key, value)
        return instance

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        result = {}
        for key, value in self.__dict__.items():
            if not key.startswith('_') and value is not None:
                result[key] = value
        return result

    def is_complete(self) -> bool:
        """Check if essential technical metadata is available."""
        return (
            self.file_format != "unknown" and
            self.dimensions is not None and
            self.level_count > 0
        )


@dataclass
class CaseMetadata:
    """User-supplied clinical metadata for WSI case."""

    case_id: Optional[str] = None
    specimen_type: Optional[str] = None
    tissue_site: Optional[str] = None
    stain: Optional[str] = None
    collection_date: Optional[str] = None
    processing_date: Optional[str] = None
    clinical_context: Optional[str] = None
    is_medical: bool = False
    metadata_provenance: Optional[str] = None
    custom_fields: Dict[str, Any] = field(default_factory=dict)


class MetadataSchema(BaseModel):
    """Schema definition for both technical and case metadata."""

    # Technical metadata fields
    file_format: str = Field(
        default="unknown",
        description="File format (svs, tif, tiff, ndpi, etc.)"
    )
    file_size_bytes: int = Field(
        default=0,
        description="File size in bytes"
    )
    dimensions: Optional[Dict[str, int]] = Field(
        default=None,
        description="Image dimensions at level 0"
    )
    level_count: int = Field(
        default=0,
        description="Number of pyramid levels"
    )
    pyramid_dimensions: List[Dict[str, int]] = Field(
        default_factory=list,
        description="Dimensions at each pyramid level"
    )
    downsample_factors: List[float] = Field(
        default_factory=list,
        description="Downsample factors for each level"
    )
    mpp_x: Optional[float] = Field(
        default=None,
        description="Microns per pixel X direction"
    )
    mpp_y: Optional[float] = Field(
        default=None,
        description="Microns per pixel Y direction"
    )
    objective_power: Optional[float] = Field(
        default=None,
        description="Objective power used for acquisition"
    )
    vendor: Optional[str] = Field(
        default=None,
        description="WSI vendor (e.g., Leica, Aperio)"
    )
    associated_images: Optional[List[str]] = Field(
        default=None,
        description="List of associated images in the WSI"
    )
    color_space: Optional[str] = Field(
        default=None,
        description="Color space of the WSI"
    )
    byte_order: Optional[str] = Field(
        default=None,
        description="Byte order (e.g., 'IA', 'BI')"
    )

    # Case metadata fields
    case_id: Optional[str] = Field(
        default=None,
        description="Internal case reference identifier",
        max_length=100
    )
    specimen_type: Optional[str] = Field(
        default=None,
        description="Type of specimen",
        max_length=50
    )
    tissue_site: Optional[str] = Field(
        default=None,
        description="Tissue site or region",
        max_length=100
    )
    stain: Optional[str] = Field(
        default=None,
        description="Stain used (e.g., H&E)",
        max_length=50
    )
    collection_date: Optional[str] = Field(
        default=None,
        description="Specimen collection date (ISO format)"
    )
    processing_date: Optional[str] = Field(
        default=None,
        description="Specimen processing date (ISO format)"
    )
    clinical_context: Optional[str] = Field(
        default=None,
        description="Clinical context or indication",
        max_length=200
    )
    is_medical: bool = Field(
        default=False,
        description="Whether this is clinical patient data"
    )
    metadata_provenance: Optional[str] = Field(
        default=None,
        description="Source of metadata (e.g., 'manual', 'automatic')"
    )

    @validator('file_format')
    def validate_file_format(cls, v):
        """Validate file format value."""
        valid_formats = [fmt.value for fmt in SlideFormat]
        v_lower = v.lower()
        if v_lower not in valid_formats:
            return SlideFormat.UNKNOWN.value
        return v_lower

    @validator('case_id')
    def validate_case_id(cls, v):
        """Validate case ID length and content."""
        if v is not None and len(v.strip()) == 0:
            return None
        return v.strip() if v else v


def get_default_config() -> Dict[str, Any]:
    """Get default pipeline configuration."""
    return {
        "patch_size": {"width": 224, "height": 224},
        "patch_stride": {"x": 224, "y": 224},
        "max_patches": 1000,
        "min_tissue_coverage": 0.5,
        "extraction_level": 0,
        "output_directory": "wsi_extracted_patches",
        "thumbnail_size": {"width": 800, "height": 600},
        "tissue_detection": {
            "method": "hsv_threshold",
            "hsv_threshold": {"h_min": 0, "h_max": 30, "s_min": 30, "v_min": 30},
            "erosion_kernel": 3,
            "dilation_kernel": 3
        },
        "memory_monitoring": {
            "enabled": True,
            "warning_threshold_mb": 2048,
            "error_threshold_mb": 4096
        },
        "file_validation": {
            "check_file_size": True,
            "max_file_size_mb": 10000,
            "required_extensions": [".svs", ".tif", ".tiff", ".ndpi", ".scn", ".ufm"]
        }
    }


def validate_slide_path(slide_path: Union[str, Path]) -> Path:
    """Validate WSI file path exists and has acceptable extension."""
    slide_path = Path(slide_path)

    if not slide_path.exists():
        raise ValueError(f"WSI file does not exist: {slide_path}")

    if not slide_path.is_file():
        raise ValueError(f"Path is not a file: {slide_path}")

    valid_extensions = {ext.lower() for ext in get_default_config()["file_validation"]["required_extensions"]}
    if slide_path.suffix.lower() not in valid_extensions:
        raise ValueError(
            f"Unsupported WSI format: {slide_path.suffix}. "
            f"Supported formats: {', '.join(sorted(valid_extensions))}"
        )

    return slide_path


def generate_slide_id(slide_path: Path) -> str:
    """Generate a non-identifying slide ID from file path."""
    file_hash = hashlib.md5(str(slide_path.absolute()).encode()).hexdigest()[:12]
    file_name = slide_path.stem.replace(" ", "_").replace("-", "_")
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return f"{file_name}_{file_hash}_{timestamp}"


def load_config_from_file(config_path: Union[str, Path]) -> Dict[str, Any]:
    """Load configuration from JSON file."""
    config_path = Path(config_path)

    if not config_path.exists():
        raise FileNotFoundError(f"Configuration file not found: {config_path}")

    with open(config_path, 'r') as f:
        config = json.load(f)

    return config


def save_config_to_file(config: Dict[str, Any], config_path: Union[str, Path]) -> None:
    """Save configuration to JSON file."""
    config_path = Path(config_path)
    config_path.parent.mkdir(parents=True, exist_ok=True)

    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)


if __name__ == "__main__":
    # Example usage
    print("WSI Pipeline Configuration Module")
    print("Default config:", json.dumps(get_default_config(), indent=2))