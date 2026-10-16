"""WSI Pipeline Module

This module provides a memory-efficient whole-slide image (WSI) ingestion and
patch extraction pipeline for the colorectal cancer MSI/MSS multimodal fusion project.

The pipeline is designed to process large pathology whole-slide images without
loading entire slides into RAM, using region-based reading and incremental extraction.
"""

from .config import (
    SlideFormat,
    ProcessingStatus,
    TechnicalMetadata,
    CaseMetadata,
    MetadataSchema,
    get_default_config,
    validate_slide_path,
    generate_slide_id,
    load_config_from_file,
    save_config_to_file
)

from .slide_reader import SlideReader, SlideReaderError

from .tissue_detection import TissueDetector, TissueDetectorError

from .patch_extractor import PatchExtractor, PatchRecord

from .manifest import (
    WSIManifest,
    PatchRecord as ManifestPatchRecord,
    ProcessingStatistics,
    ExtractionConfig,
    ManifestManager
)

from .pipeline import WSIPipeline, PipelineError, WSIPipelineCLI

from .cli import WSIPipelineCLIWrapper

__all__ = [
    # Configuration
    "SlideFormat",
    "ProcessingStatus",
    "TechnicalMetadata",
    "CaseMetadata",
    "MetadataSchema",
    "get_default_config",
    "validate_slide_path",
    "generate_slide_id",
    "load_config_from_file",
    "save_config_to_file",
    # Core components
    "SlideReader",
    "SlideReaderError",
    "TissueDetector",
    "TissueDetectorError",
    "PatchExtractor",
    "PatchRecord",
    "WSIManifest",
    "ProcessingStatistics",
    "ExtractionConfig",
    "ManifestManager",
    # Pipeline
    "WSIPipeline",
    "PipelineError",
    "WSIPipelineCLI",
    "WSIPipelineCLIWrapper",
]