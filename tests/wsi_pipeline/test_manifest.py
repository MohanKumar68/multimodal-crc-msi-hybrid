"""Unit tests for manifest management functionality."""

import pytest
import tempfile
import json
from pathlib import Path
import time

from src.wsi_pipeline.manifest import (
    WSIManifest,
    PatchRecord,
    ProcessingStatistics,
    ExtractionConfig,
    ManifestManager
)


def test_patch_record_creation_and_dict_conversion():
    """Test PatchRecord creation and dictionary conversion."""
    record = PatchRecord(
        patch_id="patch_001",
        slide_id="slide_001",
        coordinates={"x": 100, "y": 200, "width": 224, "height": 224},
        pyramid_level=0,
        tissue_coverage=0.75,
        output_path="/path/to/patch.png",
        extraction_status="completed",
        error_message=None,
        checksum="abc123"
    )

    # Test dictionary conversion
    data = record.to_dict()
    assert data["patch_id"] == "patch_001"
    assert data["slide_id"] == "slide_001"
    assert data["coordinates"]["x"] == 100
    assert data["coordinates"]["width"] == 224
    assert data["extraction_status"] == "completed"
    assert data["checksum"] == "abc123"

    # Test dictionary reconstruction
    record2 = PatchRecord.from_dict(data)
    assert record2.patch_id == record.patch_id
    assert record2.slide_id == record.slide_id
    assert record2.coordinates == record.coordinates
    assert record2.extraction_status == record.extraction_status


def test_processing_statistics():
    """Test ProcessingStatistics."""
    stats = ProcessingStatistics(
        total_patches_requested=100,
        successful_patches=85,
        failed_patches=10,
        skipped_patches=5
    )

    data = stats.to_dict()
    assert data["total_patches_requested"] == 100
    assert data["successful_patches"] == 85
    assert data["failed_patches"] == 10
    assert data["skipped_patches"] == 5


def test_extraction_config():
    """Test ExtractionConfig."""
    config = ExtractionConfig(
        patch_size={"width": 256, "height": 256},
        patch_stride={"x": 128, "y": 128},
        max_patches=500,
        min_tissue_coverage=0.8,
        extraction_level=1,
        output_directory="test_output"
    )

    data = config.to_dict()
    assert data["patch_size"]["width"] == 256
    assert data["patch_stride"]["x"] == 128
    assert data["max_patches"] == 500
    assert data["min_tissue_coverage"] == 0.8
    assert data["extraction_level"] == 1

    # Test default config
    default_config = ExtractionConfig()
    assert default_config.patch_size["width"] == 224
    assert default_config.patch_size["height"] == 224


def test_wsi_manifest_creation():
    """Test WSIManifest creation and basic functionality."""
    manifest = WSIManifest(
        slide_id="slide_001",
        source_file="/path/to/slide.svs",
        run_id="run_001"
    )

    assert manifest.slide_id == "slide_001"
    assert manifest.source_file == "/path/to/slide.svs"
    assert manifest.run_id == "run_001"
    assert manifest.processing_status == "pending"
    assert len(manifest.patch_records) == 0
    assert manifest.created_at is not None


def test_wsi_manifest_add_patch():
    """Test adding patches to manifest."""
    manifest = WSIManifest(slide_id="slide_001")

    # Add successful patch
    record1 = PatchRecord(
        patch_id="patch_001",
        slide_id="slide_001",
        coordinates={"x": 100, "y": 100, "width": 224, "height": 224},
        pyramid_level=0,
        tissue_coverage=0.8,
        extraction_status="completed"
    )
    manifest.add_patch(record1)

    assert len(manifest.patch_records) == 1
    assert manifest.processing_statistics.total_patches_requested == 1
    assert manifest.processing_statistics.successful_patches == 1

    # Add failed patch
    record2 = PatchRecord(
        patch_id="patch_002",
        slide_id="slide_001",
        coordinates={"x": 300, "y": 300, "width": 224, "height": 224},
        pyramid_level=0,
        tissue_coverage=0.0,
        extraction_status="failed",
        error_message="Permission denied"
    )
    manifest.add_patch(record2)

    assert len(manifest.patch_records) == 2
    assert manifest.processing_statistics.total_patches_requested == 2
    assert manifest.processing_statistics.successful_patches == 1
    assert manifest.processing_statistics.failed_patches == 1


def test_wsi_manifest_update_status():
    """Test updating manifest processing status."""
    manifest = WSIManifest(slide_id="slide_001")

    assert manifest.processing_status == "pending"
    assert manifest.completed_at is None

    # Update to running
    manifest.update_processing_status("running")
    assert manifest.processing_status == "running"
    assert manifest.completed_at is None

    # Update to completed
    manifest.update_processing_status("completed")
    assert manifest.processing_status == "completed"
    assert manifest.completed_at is not None


def test_wsi_manifest_add_error():
    """Test adding error messages to manifest."""
    manifest = WSIManifest(slide_id="slide_001")

    assert len(manifest.errors) == 0

    manifest.add_error("File not found")
    assert len(manifest.errors) == 1
    assert "File not found" in manifest.errors

    manifest.add_error("Permission denied")
    assert len(manifest.errors) == 2
    assert "Permission denied" in manifest.errors


def test_wsi_manifest_is_complete():
    """Test manifest completeness checking."""
    manifest = WSIManifest(slide_id="slide_001")

    assert not manifest.is_complete()

    manifest.update_processing_status("completed")
    assert manifest.is_complete()

    # Partial should also be complete
    manifest.processing_status = "partial"
    assert manifest.is_complete()

    # Failed should also be complete
    manifest.processing_status = "failed"
    assert manifest.is_complete()


def test_wsi_manifest_get_successful_patches():
    """Test filtering successful patches."""
    manifest = WSIManifest(slide_id="slide_001")

    # Add various status patches
    records = [
        PatchRecord("patch1", "slide_001", {"x": 0, "y": 0, "width": 100, "height": 100}, 0, 0.8, "completed"),
        PatchRecord("patch2", "slide_001", {"x": 100, "y": 100, "width": 100, "height": 100}, 0, 0.0, "failed"),
        PatchRecord("patch3", "slide_001", {"x": 200, "y": 200, "width": 100, "height": 100}, 0, 0.6, "completed"),
        PatchRecord("patch4", "slide_001", {"x": 300, "y": 300, "width": 100, "height": 100}, 0, 0.0, "skipped"),
    ]

    for record in records:
        manifest.add_patch(record)

    successful = manifest.get_successful_patches()
    assert len(successful) == 2
    assert all(p.extraction_status == "completed" for p in successful)


def test_wsi_manifest_dict_conversion():
    """Test manifest dictionary conversion."""
    manifest = WSIManifest(slide_id="slide_001")

    # Add a patch
    record = PatchRecord(
        patch_id="test_patch",
        slide_id="slide_001",
        coordinates={"x": 100, "y": 100, "width": 224, "height": 224},
        pyramid_level=0,
        tissue_coverage=0.75
    )
    manifest.add_patch(record)

    # Convert to dictionary
    data = manifest.to_dict()

    assert data["slide_id"] == "slide_001"
    assert "patch_records" in data
    assert len(data["patch_records"]) == 1
    assert data["patch_records"][0]["patch_id"] == "test_patch"


def test_wsi_manifest_from_dict():
    """Test manifest creation from dictionary."""
    data = {
        "slide_id": "slide_001",
        "source_file": "/path/to/slide.svs",
        "extraction_config": {
            "patch_size": {"width": 224, "height": 224},
            "patch_stride": {"x": 224, "y": 224},
            "max_patches": 1000,
            "min_tissue_coverage": 0.5,
            "extraction_level": 0,
            "output_directory": "output"
        },
        "patch_records": [
            {
                "patch_id": "patch_001",
                "slide_id": "slide_001",
                "coordinates": {"x": 100, "y": 100, "width": 224, "height": 224},
                "pyramid_level": 0,
                "tissue_coverage": 0.8,
                "extraction_status": "completed"
            }
        ],
        "processing_status": "completed"
    }

    manifest = WSIManifest.from_dict(data)

    assert manifest.slide_id == "slide_001"
    assert len(manifest.patch_records) == 1
    assert manifest.patch_records[0].patch_id == "patch_001"
    assert manifest.processing_status == "completed"


def test_wsi_manifest_save_load(tmp_path):
    """Test manifest save and load functionality."""
    manifest = WSIManifest(
        slide_id="slide_001",
        source_file="/path/to/slide.svs",
        run_id="run_001"
    )

    # Add some patches
    for i in range(3):
        record = PatchRecord(
            patch_id=f"patch_{i:03d}",
            slide_id="slide_001",
            coordinates={"x": i*100, "y": i*100, "width": 224, "height": 224},
            pyramid_level=0,
            tissue_coverage=0.5 + (i * 0.1),
            extraction_status="completed"
        )
        manifest.add_patch(record)

    # Save to file
    manifest_file = tmp_path / "manifest.json"
    manifest.save_to_file(str(manifest_file))

    # Verify file was created
    assert manifest_file.exists()

    # Load from file
    loaded_manifest = WSIManifest.load_from_file(str(manifest_file))

    assert loaded_manifest.slide_id == manifest.slide_id
    assert loaded_manifest.source_file == manifest.source_file
    assert len(loaded_manifest.patch_records) == len(manifest.patch_records)
    assert loaded_manifest.run_id == manifest.run_id


def test_wsi_manifest_create_resume_manifest():
    """Test creating a resume manifest."""
    base_manifest = WSIManifest(
        slide_id="slide_001",
        source_file="/path/to/slide.svs"
    )

    resume_manifest = base_manifest.create_resume_manifest(
        slide_id="slide_001",
        source_file="/path/to/slide.svs",
        run_id="resume_run_001"
    )

    assert resume_manifest.slide_id == "slide_001"
    assert resume_manifest.source_file == "/path/to/slide.svs"
    assert resume_manifest.run_id == "resume_run_001"
    assert resume_manifest.processing_status == "resuming"
    assert resume_manifest.extraction_config.patch_size == {"width": 224, "height": 224}


def test_manifest_manager_creation():
    """Test ManifestManager creation."""
    manager = ManifestManager()
    assert manager.manifests_dir.exists()

    # Custom directory
    custom_dir = Path("/tmp/custom_manifests")
    manager = ManifestManager(str(custom_dir))
    assert manager.manifests_dir == custom_dir


def test_manifest_manager_save_load_manifest():
    """Test ManifestManager save and load functionality."""
    with tempfile.TemporaryDirectory() as temp_dir:
        manager = ManifestManager(temp_dir)

        # Create a manifest
        manifest = WSIManifest(
            slide_id="slide_001",
            source_file="/path/to/slide.svs"
        )

        # Save manifest
        manifest_path = manager.save_manifest(manifest)
        assert Path(manifest_path).exists()

        # Load manifest
        loaded_manifest = manager.load_manifest(manifest_path)
        assert loaded_manifest.slide_id == "slide_001"


def test_manifest_manager_list_manifests():
    """Test listing available manifests."""
    with tempfile.TemporaryDirectory() as temp_dir:
        manager = ManifestManager(temp_dir)

        # Create test manifests
        manifest1 = WSIManifest(slide_id="slide_001")
        manager.save_manifest(manifest1, "manifest1.json")

        manifest2 = WSIManifest(slide_id="slide_002")
        manager.save_manifest(manifest2, "manifest2.json")

        # List all manifests
        all_manifests = manager.list_manifests()
        assert len(all_manifests) == 2

        # List filtered by slide_id
        slide_manifests = manager.list_manifests("slide_001")
        assert len(slide_manifests) == 1
        assert "slide_001" in slide_manifests[0]


def test_manifest_manager_cleanup_old_manifests():
    """Test cleanup of old manifests."""
    with tempfile.TemporaryDirectory() as temp_dir:
        manager = ManifestManager(temp_dir)

        # Create multiple manifests for the same slide
        for i in range(5):
            manifest = WSIManifest(slide_id="slide_001")
            manager.save_manifest(manifest, f"manifest_{i:03d}.json")

        # List manifests
        manifests = manager.list_manifests("slide_001")
        assert len(manifests) == 5

        # Cleanup, keeping only 2 most recent
        manager.cleanup_old_manifests(keep_count=2)

        # Should have only 2 manifests left
        remaining = manager.list_manifests("slide_001")
        assert len(remaining) == 2


def test_wsi_manifest_edge_cases():
    """Test edge cases and error conditions."""
    # Test with None values
    manifest = WSIManifest(
        slide_id="slide_001",
        source_file=None,
        technical_metadata=None,
        case_metadata=None
    )

    assert manifest.source_file is None
    assert manifest.technical_metadata is None
    assert manifest.case_metadata is None

    # Test with empty patch list
    assert manifest.get_successful_patches() == []
    assert manifest.get_failed_patches() == []

    # Test status transitions
    manifest.update_processing_status("running")
    assert manifest.processing_status == "running"

    manifest.update_processing_status("completed")
    assert manifest.is_complete()


def test_patch_record_edge_cases():
    """Test PatchRecord edge cases."""
    # Test with minimal required fields
    record = PatchRecord(
        patch_id="min_record",
        slide_id="slide_001",
        coordinates={"x": 0, "y": 0, "width": 10, "height": 10},
        pyramid_level=0,
        tissue_coverage=0.0
    )

    assert record.extraction_status == "pending"
    assert record.error_message is None
    assert record.checksum is None
    assert record.output_path is None

    # Test dictionary conversion with minimal fields
    data = record.to_dict()
    assert data["extraction_status"] == "pending"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])