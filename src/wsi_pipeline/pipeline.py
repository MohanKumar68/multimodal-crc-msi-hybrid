"""Main WSI pipeline for whole-slide image processing and patch extraction.

This module provides the main pipeline class that orchestrates the entire WSI
processing workflow, from slide reading to patch extraction and manifest generation.
"""

import time
import json
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple
import logging

from .config import TechnicalMetadata, CaseMetadata
from .slide_reader import SlideReader
from .tissue_detection import TissueDetector
from .patch_extractor import PatchExtractor, PatchRecord
from .manifest import WSIManifest, ManifestManager, ExtractionConfig
from .phikon_embedding_generator import PhikonEmbeddingGenerator

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class PipelineError(Exception):
    """Exception raised for pipeline errors."""
    pass


class WSIPipeline:
    """Main pipeline for WSI processing and patch extraction."""

    def __init__(self, config: Optional[Dict[str, Any]] = None, manifest_manager: Optional[ManifestManager] = None):
        """
        Initialize the WSI pipeline.

        Args:
            config: Optional configuration dictionary
            manifest_manager: Optional manifest manager instance
        """
        self.config = config or {}
        self.manifest_manager = manifest_manager or ManifestManager()

        # Pipeline components
        self.slide_reader: Optional[SlideReader] = None
        self.tissue_detector: Optional[TissueDetector] = None
        self.patch_extractor: Optional[PatchExtractor] = None

        # Processing state
        self.current_slide_id: Optional[str] = None
        self.current_manifest: Optional[WSIManifest] = None
        self.processing_start_time: Optional[float] = None
        self.processing_end_time: Optional[float] = None

    def initialize_components(self) -> None:
        """Initialize pipeline components."""
        try:
            # Initialize slide reader
            self.slide_reader = SlideReader(self.config)
            logger.info("Slide reader initialized")

            # Initialize tissue detector
            self.tissue_detector = TissueDetector(self.config)
            logger.info("Tissue detector initialized")

            # Initialize patch extractor
            self.patch_extractor = PatchExtractor(self.config)
            logger.info("Patch extractor initialized")

        except Exception as e:
            raise PipelineError(f"Failed to initialize pipeline components: {e}")

    def process_slide(
        self,
        slide_path: str,
        slide_id: Optional[str] = None,
        technical_metadata: Optional[TechnicalMetadata] = None,
        case_metadata: Optional[CaseMetadata] = None,
        resume_manifest_path: Optional[str] = None,
        run_id: Optional[str] = None
    ) -> Tuple[WSIManifest, Dict[str, Any]]:
        """
        Process a single whole-slide image.

        Args:
            slide_path: Path to WSI file
            slide_id: Optional slide ID
            technical_metadata: Optional pre-extracted technical metadata
            case_metadata: Optional case metadata
            resume_manifest_path: Optional path to manifest for resuming extraction
            run_id: Optional run ID for tracking

        Returns:
            Tuple of (manifest, processing summary)

        Raises:
            PipelineError: If processing fails
        """
        try:
            self.processing_start_time = time.time()
            logger.info(f"Starting WSI pipeline processing: {slide_path}")

            # Check if current manifest is set
            if self.current_manifest is None:
                # Generate slide_id if not provided
                current_slide_id = slide_id or self._generate_slide_id(slide_path)
                self.current_slide_id = current_slide_id
                # Create a new manifest if not set
                self.current_manifest = WSIManifest(
                    slide_id=current_slide_id,
                    source_file=slide_path,
                    extraction_config=ExtractionConfig(**self.config.get("patch_extraction", {})),
                    run_id=run_id or f"{current_slide_id}_{int(time.time())}"
                )
                logger.info(f"Created new manifest for {current_slide_id}")

            # Initialize components if not already done
            if not self.slide_reader or not self.tissue_detector or not self.patch_extractor:
                self.initialize_components()

            # Initialize Phikon embedding generator
            self.phikon_generator = PhikonEmbeddingGenerator()

            # Generate slide ID if not provided
            current_slide_id = slide_id or self._generate_slide_id(slide_path)
            self.current_slide_id = current_slide_id

            # Create or load manifest
            if resume_manifest_path and Path(resume_manifest_path).exists():
                self.current_manifest = self.manifest_manager.load_manifest(resume_manifest_path)
                logger.info(f"Loaded existing manifest for resuming: {resume_manifest_path}")
            else:
                self.current_manifest = WSIManifest(
                    slide_id=current_slide_id,
                    source_file=slide_path,
                    extraction_config=ExtractionConfig(**self.config.get("patch_extraction", {})),
                    run_id=run_id or f"{current_slide_id}_{int(time.time())}"
                )
                logger.info(f"Created new manifest for {current_slide_id}")

            # Update manifest status
            self.current_manifest.update_processing_status("running")
            self.current_manifest.technical_metadata = technical_metadata.to_dict() if technical_metadata else None
            self.current_manifest.case_metadata = case_metadata.to_dict() if case_metadata else None

            # Save initial manifest
            initial_manifest_path = self.manifest_manager.save_manifest(
                self.current_manifest,
                f"{current_slide_id}_initial.json"
            )
            logger.info(f"Saved initial manifest: {initial_manifest_path}")

            # Extract patches
            patch_records, extraction_summary = self.patch_extractor.extract_patches_from_slide(
                slide_path=slide_path,
                slide_id=current_slide_id,
                technical_metadata=technical_metadata,
                case_metadata=case_metadata,
                resume_manifest_path=resume_manifest_path
            )

            # Update current manifest with extracted patches
            for patch_record in patch_records:
                self.current_manifest.add_patch(patch_record)

            # Determine final status
            successful_patches = len(self.current_manifest.get_successful_patches())
            failed_patches = len(self.current_manifest.get_failed_patches())

            if failed_patches == 0:
                final_status = "completed"
            elif successful_patches > 0:
                final_status = "partial"
            else:
                final_status = "failed"

            # Update manifest status
            self.current_manifest.update_processing_status(final_status)

            # Save final manifest
            final_manifest_path = self.manifest_manager.save_manifest(
                self.current_manifest,
                f"{current_slide_id}_final.json"
            )
            logger.info(f"Saved final manifest: {final_manifest_path}")

            self.processing_end_time = time.time()

            # Generate processing summary
            processing_summary = self._generate_processing_summary(
                slide_path,
                current_slide_id,
                extraction_summary,
                final_manifest_path
            )

            logger.info(
                f"Completed WSI pipeline processing. "
                f"Status: {final_status}, "
                f"Successful patches: {successful_patches}, "
                f"Failed patches: {failed_patches}, "
                f"Total time: {self.processing_end_time - self.processing_start_time:.2f}s"
            )

            return self.current_manifest, processing_summary

        except Exception as e:
            logger.error(f"Pipeline processing failed: {e}")

            # Update manifest with error if available
            if self.current_manifest:
                self.current_manifest.add_error(str(e))
                self.current_manifest.update_processing_status("failed")
                try:
                    error_manifest_path = self.manifest_manager.save_manifest(
                        self.current_manifest,
                        f"{self.current_slide_id or 'unknown'}_error.json"
                    )
                    logger.info(f"Saved error manifest: {error_manifest_path}")
                except Exception as save_error:
                    logger.error(f"Failed to save error manifest: {save_error}")

            raise PipelineError(f"WSI pipeline processing failed: {e}")

    def _generate_slide_id(self, slide_path: str) -> str:
        """Generate slide ID from path."""
        from .config import generate_slide_id
        return generate_slide_id(Path(slide_path))

    def _generate_processing_summary(
        self,
        slide_path: str,
        slide_id: str,
        extraction_summary: Dict[str, Any],
        manifest_path: str
    ) -> Dict[str, Any]:
        """
        Generate processing summary.

        Args:
            slide_path: Slide file path
            slide_id: Slide ID
            extraction_summary: Extraction summary from patch extractor
            manifest_path: Path to saved manifest

        Returns:
            Processing summary dictionary

        Raises:
            PipelineError: If no current manifest is set
        """
        if not self.current_manifest:
            raise PipelineError("No current manifest set for processing summary")

        total_time = (self.processing_end_time - self.processing_start_time) if self.processing_start_time and self.processing_end_time else 0.0

        summary = {
            "slide_id": slide_id,
            "slide_path": slide_path,
            "processing_start_time": (
                time.strftime("%Y-%m-%d %H:%M:%S")
                if self.processing_start_time else None
            ),
            "processing_end_time": (
                time.strftime("%Y-%m-%d %H:%M:%S")
                if self.processing_end_time else None
            ),
            "total_processing_time_seconds": total_time,
            "processing_status": (
                self.current_manifest.processing_status
                if self.current_manifest else "unknown"
            ),
            "manifest_path": manifest_path,
            "extracted_patches": extraction_summary.get("extracted_patches", 0),
            "successful_patches": len(self.current_manifest.get_successful_patches()) if self.current_manifest else 0,
            "failed_patches": len(self.current_manifest.get_failed_patches()) if self.current_manifest else 0,
            "skipped_patches": sum(
                1 for p in self.current_manifest.patch_records
                if p.extraction_status == "skipped"
            ) if self.current_manifest else 0,
            "pipeline_version": "1.0.0"
        }

        return summary

    def cleanup(self) -> None:
        """Clean up pipeline resources."""
        try:
            if self.slide_reader:
                try:
                    self.slide_reader.close()
                except Exception as e:
                    logger.error(f"Error closing slide reader: {e}")
                finally:
                    self.slide_reader = None

            if self.tissue_detector:
                try:
                    self.tissue_detector.close()
                except Exception as e:
                    logger.error(f"Error closing tissue detector: {e}")
                finally:
                    self.tissue_detector = None

            if self.patch_extractor:
                try:
                    self.patch_extractor.cleanup()
                except Exception as e:
                    logger.error(f"Error cleaning up patch extractor: {e}")
                finally:
                    self.patch_extractor = None

            logger.info("Pipeline resources cleaned up")

        except Exception as e:
            logger.warning(f"Unexpected error during cleanup: {e}")

    def __enter__(self) -> "WSIPipeline":
        """Context manager entry."""
        self.initialize_components()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        """Context manager exit."""
        self.cleanup()

    def __repr__(self) -> str:
        """String representation."""
        return (
            f"WSIPipeline(components_initialized={'initialized' in self.__dict__}, "
            f"manifest_manager={self.manifest_manager})"
        )


class WSIPipelineCLI:
    """Command-line interface for WSI pipeline."""

    def __init__(self):
        """Initialize CLI."""
        self.pipeline: Optional[WSIPipeline] = None

    def run(self, args: List[str]) -> None:
        """
        Run CLI with command-line arguments.

        Args:
            args: Command-line arguments
        """
        try:
            if len(args) < 1:
                self._print_help()
                return

            command = args[0].lower()

            if command == "process":
                self._process_command(args[1:])
            elif command == "resume":
                self._resume_command(args[1:])
            elif command == "status":
                self._status_command(args[1:])
            elif command == "help":
                self._print_help()
            else:
                print(f"Unknown command: {command}")
                self._print_help()

        except Exception as e:
            print(f"Error: {e}")
            import traceback
            traceback.print_exc()

    def _process_command(self, args: List[str]) -> None:
        """Process a WSI file."""
        if len(args) < 1:
            raise ValueError("Missing slide path")

        slide_path = args[0]

        # Parse optional arguments
        slide_id = None
        resume_manifest_path = None
        run_id = None

        i = 1
        while i < len(args):
            arg = args[i]
            if arg == "--slide-id" and i + 1 < len(args):
                slide_id = args[i + 1]
                i += 2
            elif arg == "--resume" and i + 1 < len(args):
                resume_manifest_path = args[i + 1]
                i += 2
            elif arg == "--run-id" and i + 1 < len(args):
                run_id = args[i + 1]
                i += 2
            else:
                i += 1

        print(f"Processing slide: {slide_path}")
        if slide_id:
            print(f"Slide ID: {slide_id}")
        if resume_manifest_path:
            print(f"Resume from manifest: {resume_manifest_path}")

        # Run pipeline
        with WSIPipeline() as pipeline:
            manifest, summary = pipeline.process_slide(
                slide_path=slide_path,
                slide_id=slide_id,
                resume_manifest_path=resume_manifest_path,
                run_id=run_id
            )

            print(f"\nProcessing Summary:")
            print(f"  Slide ID: {summary['slide_id']}")
            print(f"  Status: {summary['processing_status']}")
            print(f"  Successful patches: {summary['successful_patches']}")
            print(f"  Failed patches: {summary['failed_patches']}")
            print(f"  Total time: {summary['total_processing_time_seconds']:.2f}s")
            print(f"  Manifest: {summary['manifest_path']}")

    def _resume_command(self, args: List[str]) -> None:
        """Resume processing from manifest."""
        if len(args) < 1:
            raise ValueError("Missing manifest path")

        manifest_path = args[0]
        print(f"Resuming from manifest: {manifest_path}")

        # Run pipeline with manifest
        with WSIPipeline() as pipeline:
            manifest, summary = pipeline.process_slide(
                slide_path="",  # Not needed for resume
                resume_manifest_path=manifest_path
            )

            print(f"\nResume Summary:")
            print(f"  Slide ID: {summary['slide_id']}")
            print(f"  Status: {summary['processing_status']}")
            print(f"  Successful patches: {summary['successful_patches']}")
            print(f"  Failed patches: {summary['failed_patches']}")

    def _status_command(self, args: List[str]) -> None:
        """Show pipeline status."""
        if self.pipeline:
            print(f"Pipeline status: Initialized")
            print(f"  Slide reader: {'Yes' if self.pipeline.slide_reader else 'No'}")
            print(f"  Tissue detector: {'Yes' if self.pipeline.tissue_detector else 'No'}")
            print(f"  Patch extractor: {'Yes' if self.pipeline.patch_extractor else 'No'}")
            print(f"  Current slide: {self.pipeline.current_slide_id or 'None'}")
            print(f"  Current manifest: {'Yes' if self.pipeline.current_manifest else 'No'}")
        else:
            print("Pipeline not initialized")

    def _print_help(self) -> None:
        """Print help information."""
        help_text = """
WSI Pipeline CLI
=================

Commands:
  process <slide_path> [options]  Process a WSI file
  resume <manifest_path>           Resume processing from manifest
  status                           Show current pipeline status
  help                              Show this help message

Options for process command:
  --slide-id <id>                 Specify slide ID
  --resume <manifest_path>         Resume from existing manifest
  --run-id <id>                    Specify run ID for tracking

Examples:
  wsi_pipeline process /path/to/slide.svs --slide-id my_slide
  wsi_pipeline resume manifest.json
  wsi_pipeline status
        """
        print(help_text)


if __name__ == "__main__":
    # Example usage
    import argparse

    parser = argparse.ArgumentParser(description="WSI Pipeline CLI")
    parser.add_argument("command", help="Command (process, resume, status, help)")
    parser.add_argument("args", nargs="*", help="Command arguments")

    args = parser.parse_args()

    # Create CLI instance
    cli = WSIPipelineCLI()

    # Run CLI
    cli.run([args.command] + args.args)