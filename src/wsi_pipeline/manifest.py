"""Manifest management for WSI patch extraction.

This module provides functionality to create, save, and manage manifests for
whole-slide image processing. A manifest records processing metadata,
patch extraction details, and ensures reproducibility of the extraction process.
"""

import json
import time
from pathlib import Path
from typing import Dict, List, Optional, Any, Union
from dataclasses import dataclass, field, asdict
from datetime import datetime

from .config import TechnicalMetadata, CaseMetadata
from .patch_extractor import PatchRecord

@dataclass
class PatchRecord:
    """Record of an extracted patch."""

    patch_id: str
    slide_id: str
    coordinates: Dict[str, int]  # x, y, width, height
    pyramid_level: int
    tissue_coverage: float
    extraction_status: str = "pending"
    output_path: Optional[str] = None
    error_message: Optional[str] = None
    checksum: Optional[str] = None
    extraction_timestamp: str = field(default_factory=lambda: time.strftime("%Y-%m-%d %H:%M:%S"))

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            "patch_id": self.patch_id,
            "slide_id": self.slide_id,
            "coordinates": self.coordinates,
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
        return cls(
            patch_id=data["patch_id"],
            slide_id=data["slide_id"],
            coordinates=data["coordinates"],
            pyramid_level=data["pyramid_level"],
            tissue_coverage=data["tissue_coverage"],
            output_path=data.get("output_path"),
            extraction_status=data.get("extraction_status", "pending"),
            error_message=data.get("error_message"),
            checksum=data.get("checksum"),
            extraction_timestamp=data.get("extraction_timestamp")
        )


@dataclass
class ProcessingStatistics:
    """Statistics about processing run."""

    total_patches_requested: int = 0
    successful_patches: int = 0
    failed_patches: int = 0
    skipped_patches: int = 0
    processing_start_time: Optional[str] = None
    processing_end_time: Optional[str] = None
    total_processing_time_seconds: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            "total_patches_requested": self.total_patches_requested,
            "successful_patches": self.successful_patches,
            "failed_patches": self.failed_patches,
            "skipped_patches": self.skipped_patches,
            "processing_start_time": self.processing_start_time,
            "processing_end_time": self.processing_end_time,
            "total_processing_time_seconds": self.total_processing_time_seconds
        }


@dataclass
class ExtractionConfig:
    """Configuration for patch extraction."""

    patch_size: Dict[str, int] = field(default_factory=lambda: {"width": 224, "height": 224})
    patch_stride: Dict[str, int] = field(default_factory=lambda: {"x": 224, "y": 224})
    max_patches: int = 1000
    min_tissue_coverage: float = 0.5
    extraction_level: int = 0
    output_directory: str = "wsi_extracted_patches"
    thumbnail_size: Dict[str, int] = field(default_factory=lambda: {"width": 800, "height": 600})

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return asdict(self)


@dataclass
class WSIManifest:
    """Manifest for whole-slide image processing."""

    slide_id: str
    source_file: Optional[str] = None
    technical_metadata: Optional[Dict[str, Any]] = None
    case_metadata: Optional[Dict[str, Any]] = None
    extraction_config: ExtractionConfig = field(default_factory=ExtractionConfig)
    patch_records: List[PatchRecord] = field(default_factory=list)
    processing_status: str = "pending"
    errors: List[str] = field(default_factory=list)
    processing_statistics: ProcessingStatistics = field(default_factory=ProcessingStatistics)
    created_at: str = field(default_factory=lambda: time.strftime("%Y-%m-%d %H:%M:%S"))
    completed_at: Optional[str] = None
    run_id: Optional[str] = None

    def add_patch(self, patch_record: PatchRecord) -> None:
        """Add a patch record to the manifest."""
        self.patch_records.append(patch_record)

        # Update statistics
        self.processing_statistics.total_patches_requested += 1

        if patch_record.extraction_status == "completed":
            self.processing_statistics.successful_patches += 1
        elif patch_record.extraction_status == "failed":
            self.processing_statistics.failed_patches += 1
        elif patch_record.extraction_status == "skipped":
            self.processing_statistics.skipped_patches += 1

    def update_processing_status(self, status: str) -> None:
        """Update processing status."""
        self.processing_status = status
        self.completed_at = time.strftime("%Y-%m-%d %H:%M:%S") if status in [
            "completed", "partial", "failed"
        ] else None

    def add_error(self, error: str) -> None:
        """Add an error message to the manifest."""
        self.errors.append(error)

    def is_complete(self) -> bool:
        """Check if processing is complete."""
        return self.processing_status in ["completed", "partial", "failed"]

    def get_successful_patches(self) -> List[PatchRecord]:
        """Get list of successfully extracted patches."""
        return [p for p in self.patch_records if p.extraction_status == "completed"]

    def get_failed_patches(self) -> List[PatchRecord]:
        """Get list of failed patches."""
        return [p for p in self.patch_records if p.extraction_status == "failed"]

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            "slide_id": self.slide_id,
            "source_file": self.source_file,
            "technical_metadata": self.technical_metadata,
            "case_metadata": self.case_metadata,
            "extraction_config": self.extraction_config.to_dict(),
            "patch_records": [p.to_dict() for p in self.patch_records],
            "processing_status": self.processing_status,
            "errors": self.errors,
            "processing_statistics": self.processing_statistics.to_dict(),
            "created_at": self.created_at,
            "completed_at": self.completed_at,
            "run_id": self.run_id
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "WSIManifest":
        """Create WSIManifest from dictionary."""
        data_copy = data.copy()

        # Convert nested objects
        if "extraction_config" in data_copy:
            data_copy["extraction_config"] = ExtractionConfig(**data_copy["extraction_config"])

        if "processing_statistics" in data_copy:
            data_copy["processing_statistics"] = ProcessingStatistics(**data_copy["processing_statistics"])

        if "patch_records" in data_copy:
            data_copy["patch_records"] = [
                PatchRecord.from_dict(p) for p in data_copy["patch_records"]
            ]

        return cls(**data_copy)

    @classmethod
    def load_from_file(cls, file_path: str) -> "WSIManifest":
        """Load manifest from JSON file."""
        file_path = Path(file_path)

        if not file_path.exists():
            raise FileNotFoundError(f"Manifest file not found: {file_path}")

        with open(file_path, 'r') as f:
            data = json.load(f)

        return cls.from_dict(data)

    def save_to_file(self, file_path: str) -> None:
        """Save manifest to JSON file."""
        file_path = Path(file_path)
        file_path.parent.mkdir(parents=True, exist_ok=True)

        with open(file_path, 'w') as f:
            json.dump(self.to_dict(), f, indent=2)

    def create_resume_manifest(
        self,
        slide_id: str,
        source_file: Optional[str] = None,
        run_id: Optional[str] = None
    ) -> "WSIManifest":
        """
        Create a new manifest for resuming extraction.

        Args:
            slide_id: Slide identifier
            source_file: Source file path
            run_id: Optional run ID for tracking

        Returns:
            New manifest instance
        """
        resume_manifest = WSIManifest(
            slide_id=slide_id,
            source_file=source_file,
            extraction_config=self.extraction_config,
            processing_status="resuming",
            run_id=run_id or f"{slide_id}_{int(time.time())}"
        )

        return resume_manifest

    def __repr__(self) -> str:
        """String representation."""
        return (
            f"WSIManifest(slide_id={self.slide_id}, "
            f"patches={len(self.patch_records)}, "
            f"status={self.processing_status})"
        )


class ManifestManager:
    """Manager for WSI manifests."""

    def __init__(self, manifests_dir: str = "wsi_manifests"):
        """
        Initialize manifest manager.

        Args:
            manifests_dir: Directory for storing manifests
        """
        self.manifests_dir = Path(manifests_dir)
        self.manifests_dir.mkdir(parents=True, exist_ok=True)

    def save_manifest(self, manifest: WSIManifest, file_name: Optional[str] = None) -> str:
        """
        Save manifest to file.

        Args:
            manifest: Manifest to save
            file_name: Optional file name. If None, uses slide_id

        Returns:
            Path to saved manifest file
        """
        if file_name is None:
            file_name = f"{manifest.slide_id}_manifest.json"

        file_path = self.manifests_dir / file_name
        manifest.save_to_file(str(file_path))

        return str(file_path)

    def load_manifest(self, file_path: str) -> WSIManifest:
        """
        Load manifest from file.

        Args:
            file_path: Path to manifest file

        Returns:
            Loaded manifest
        """
        return WSIManifest.load_from_file(file_path)

    def list_manifests(self, slide_id: Optional[str] = None) -> List[str]:
        """
        List available manifests.

        Args:
            slide_id: Optional slide ID to filter by

        Returns:
            List of manifest file paths
        """
        manifests = []

        for file_path in self.manifests_dir.glob("*.json"):
            try:
                manifest = self.load_manifest(str(file_path))
                if slide_id is None or manifest.slide_id == slide_id:
                    manifests.append(str(file_path))
            except Exception:
                continue

        return sorted(manifests)

    def cleanup_old_manifests(self, keep_count: int = 10) -> None:
        """
        Clean up old manifests, keeping only the most recent ones.

        Args:
            keep_count: Number of recent manifests to keep per slide
        """
        # Group manifests by slide_id
        slide_manifests = {}

        for file_path in self.manifests_dir.glob("*.json"):
            try:
                manifest = self.load_manifest(str(file_path))
                slide_id = manifest.slide_id

                if slide_id not in slide_manifests:
                    slide_manifests[slide_id] = []

                slide_manifests[slide_id].append((file_path.stat().st_mtime, file_path))
            except Exception:
                continue

        # Keep only the most recent manifests
        for slide_id, manifests in slide_manifests.items():
            # Sort by modification time (newest first)
            manifests.sort(key=lambda x: x[0], reverse=True)

            # Remove older manifests
            for _, file_path in manifests[keep_count:]:
                try:
                    file_path.unlink()
                except Exception:
                    continue


if __name__ == "__main__":
    # Example usage
    print("WSI Manifest Management Module")

    # Create a manifest
    manifest = WSIManifest(
        slide_id="test_slide_001",
        source_file="/path/to/slide.svs"
    )

    # Add some patch records
    patch1 = PatchRecord(
        patch_id="test_slide_001_patch_001",
        slide_id="test_slide_001",
        coordinates={"x": 0, "y": 0, "width": 224, "height": 224},
        pyramid_level=0,
        tissue_coverage=0.75,
        extraction_status="completed"
    )

    patch2 = PatchRecord(
        patch_id="test_slide_001_patch_002",
        slide_id="test_slide_001",
        coordinates={"x": 224, "y": 0, "width": 224, "height": 224},
        pyramid_level=0,
        tissue_coverage=0.62,
        extraction_status="completed"
    )

    manifest.add_patch(patch1)
    manifest.add_patch(patch2)

    manifest.update_processing_status("completed")

    # Save manifest
    manifest.save_to_file("test_manifest.json")

    print(f"Created and saved manifest: {manifest}")
    print(f"Manifest file: test_manifest.json")
    print(f"Successful patches: {len(manifest.get_successful_patches())}")