"""Command-line interface for WSI pipeline operations.

This module provides command-line interfaces for various WSI pipeline operations,
including slide processing, configuration management, and status reporting.
"""

import argparse
import json
import sys
import os
from pathlib import Path
from typing import Dict, List, Optional, Any
import logging

from .config import get_default_config, load_config_from_file, save_config_to_file, validate_slide_path
from .pipeline import WSIPipeline, WSIPipelineCLI
from .manifest import ManifestManager

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class WSIPipelineError(Exception):
    """Exception raised for CLI errors."""
    pass


class WSIPipelineCLIWrapper:
    """Wrapper class for WSI pipeline CLI operations."""

    def __init__(self):
        """Initialize CLI wrapper."""
        self.pipeline: Optional[WSIPipeline] = None
        self.manifest_manager = ManifestManager()

    def process_slide(
        self,
        slide_path: str,
        slide_id: Optional[str] = None,
        resume_manifest: Optional[str] = None,
        run_id: Optional[str] = None,
        output_dir: Optional[str] = None,
        config_file: Optional[str] = None
    ) -> None:
        """
        Process a slide through the WSI pipeline.

        Args:
            slide_path: Path to WSI file
            slide_id: Optional slide ID
            resume_manifest: Optional path to manifest for resuming
            run_id: Optional run ID for tracking
            output_dir: Optional output directory
            config_file: Optional configuration file
        """
        try:
            # Load configuration
            config = self._load_configuration(config_file)

            # Override output directory if specified
            if output_dir:
                config["patch_extraction"]["output_directory"] = output_dir

            # Initialize pipeline
            self.pipeline = WSIPipeline(config)

            print(f"Processing slide: {slide_path}")
            if slide_id:
                print(f"Slide ID: {slide_id}")
            if resume_manifest:
                print(f"Resume from manifest: {resume_manifest}")
            if output_dir:
                print(f"Output directory: {output_dir}")

            # Process slide
            manifest, summary = self.pipeline.process_slide(
                slide_path=slide_path,
                slide_id=slide_id,
                resume_manifest_path=resume_manifest,
                run_id=run_id
            )

            # Print summary
            self._print_processing_summary(summary)

            # Save configuration if changes were made
            if config_file:
                save_config_to_file(config, config_file)

        except Exception as e:
            raise WSIPipelineError(f"Slide processing failed: {e}")

    def resume_processing(
        self,
        manifest_path: str,
        output_dir: Optional[str] = None,
        config_file: Optional[str] = None
    ) -> None:
        """
        Resume processing from a manifest.

        Args:
            manifest_path: Path to manifest for resuming
            output_dir: Optional output directory
            config_file: Optional configuration file
        """
        try:
            # Load configuration
            config = self._load_configuration(config_file)

            # Override output directory if specified
            if output_dir:
                config["patch_extraction"]["output_directory"] = output_dir

            # Initialize pipeline
            self.pipeline = WSIPipeline(config, self.manifest_manager)

            print(f"Resuming from manifest: {manifest_path}")

            # Process from manifest
            manifest, summary = self.pipeline.process_slide(
                slide_path="",  # Not needed for resume
                resume_manifest_path=manifest_path
            )

            # Print summary
            self._print_processing_summary(summary)

        except Exception as e:
            raise WSIPipelineError(f"Resume processing failed: {e}")

    def show_status(self) -> None:
        """Show current pipeline status."""
        if self.pipeline:
            print("Pipeline Status:")
            print(f"  Slide reader: {'Yes' if self.pipeline.slide_reader else 'No'}")
            print(f"  Tissue detector: {'Yes' if self.pipeline.tissue_detector else 'No'}")
            print(f"  Patch extractor: {'Yes' if self.pipeline.patch_extractor else 'No'}")
            print(f"  Current slide: {self.pipeline.current_slide_id or 'None'}")
            print(f"  Current manifest: {'Yes' if self.pipeline.current_manifest else 'No'}")
            print(f"  Processing start time: {self.pipeline.processing_start_time or 'Not started'}")
            print(f"  Processing end time: {self.pipeline.processing_end_time or 'Not completed'}")
        else:
            print("Pipeline not initialized")

    def show_manifests(self, slide_id: Optional[str] = None) -> None:
        """
        Show available manifests.

        Args:
            slide_id: Optional slide ID to filter by
        """
        manifests = self.manifest_manager.list_manifests(slide_id)

        if not manifests:
            print("No manifests found")
            return

        print("Available manifests:")
        for manifest_path in manifests:
            try:
                manifest = self.manifest_manager.load_manifest(manifest_path)
                print(f"  {manifest_path}")
                print(f"    Slide ID: {manifest.slide_id}")
                print(f"    Status: {manifest.processing_status}")
                print(f"    Patches: {len(manifest.patch_records)}")
                print(f"    Created: {manifest.created_at}")
                if manifest.completed_at:
                    print(f"    Completed: {manifest.completed_at}")
            except Exception as e:
                print(f"  {manifest_path} (error loading: {e})")

    def configure_pipeline(self, config_file: str, overwrite: bool = False) -> None:
        """
        Configure pipeline with configuration file.

        Args:
            config_file: Path to configuration file
            overwrite: Whether to overwrite existing configuration
        """
        try:
            if not Path(config_file).exists():
                raise WSIPipelineError(f"Configuration file not found: {config_file}")

            config = load_config_from_file(config_file)

            # Create new config file with defaults if it doesn't exist
            if not Path(config_file).exists():
                default_config = get_default_config()
                save_config_to_file(default_config, config_file)
                print(f"Created new configuration file: {config_file}")
            else:
                if overwrite:
                    save_config_to_file(config, config_file)
                    print(f"Updated configuration file: {config_file}")
                else:
                    print(f"Configuration file already exists: {config_file}")
                    print(f"Content: {json.dumps(config, indent=2)}")

        except Exception as e:
            raise WSIPipelineError(f"Configuration failed: {e}")

    def list_config_options(self) -> None:
        """List all available configuration options."""
        default_config = get_default_config()

        print("WSI Pipeline Configuration Options:")
        for section, options in default_config.items():
            print(f"\n{section.replace('_', ' ').title()}:")
            if isinstance(options, dict):
                for key, value in options.items():
                    print(f"  {key}: {value}")

    def _load_configuration(self, config_file: Optional[str]) -> Dict[str, Any]:
        """
        Load configuration from file or use defaults.

        Args:
            config_file: Optional configuration file path

        Returns:
            Configuration dictionary
        """
        if config_file and Path(config_file).exists():
            config = load_config_from_file(config_file)
            print(f"Loaded configuration from: {config_file}")
        else:
            config = get_default_config()
            print("Using default configuration")

        return config

    def _print_processing_summary(self, summary: Dict[str, Any]) -> None:
        """
        Print processing summary.

        Args:
            summary: Processing summary dictionary
        """
        print("\nProcessing Summary:")
        print(f"  Slide ID: {summary['slide_id']}")
        print(f"  Status: {summary['processing_status']}")
        print(f"  Successful patches: {summary['successful_patches']}")
        print(f"  Failed patches: {summary['failed_patches']}")
        print(f"  Total time: {summary['total_processing_time_seconds']:.2f}s")
        print(f"  Manifest: {summary['manifest_path']}")

    def cleanup(self) -> None:
        """Clean up pipeline resources."""
        if self.pipeline:
            self.pipeline.cleanup()
            self.pipeline = None

    def __enter__(self) -> "WSIPipelineCLIWrapper":
        """Context manager entry."""
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        """Context manager exit."""
        self.cleanup()


def create_parser() -> argparse.ArgumentParser:
    """Create argument parser for CLI."""
    parser = argparse.ArgumentParser(
        description="WSI Pipeline Command Line Interface",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python cli.py process /path/to/slide.svs
  python cli.py process /path/to/slide.svs --slide-id my_slide --run-id run_001
  python cli.py resume manifest.json
  python cli.py status
  python cli.py manifests --slide-id my_slide
  python cli.py configure config.json
  python cli.py help
        """
    )

    subparsers = parser.add_subparsers(dest="command", help="Command to execute")

    # Process command
    process_parser = subparsers.add_parser("process", help="Process a WSI file")
    process_parser.add_argument("slide_path", help="Path to WSI file")
    process_parser.add_argument("--slide-id", help="Slide ID")
    process_parser.add_argument("--resume", help="Path to manifest for resuming")
    process_parser.add_argument("--run-id", help="Run ID for tracking")
    process_parser.add_argument("--output-dir", help="Output directory")
    process_parser.add_argument("--config", help="Path to configuration file")

    # Resume command
    resume_parser = subparsers.add_parser("resume", help="Resume processing from manifest")
    resume_parser.add_argument("manifest_path", help="Path to manifest for resuming")
    resume_parser.add_argument("--output-dir", help="Output directory")
    resume_parser.add_argument("--config", help="Path to configuration file")

    # Status command
    status_parser = subparsers.add_parser("status", help="Show current pipeline status")

    # Manifests command
    manifests_parser = subparsers.add_parser("manifests", help="Show available manifests")
    manifests_parser.add_argument("--slide-id", help="Slide ID to filter by")

    # Configure command
    configure_parser = subparsers.add_parser("configure", help="Configure pipeline")
    configure_parser.add_argument("config_file", help="Path to configuration file")
    configure_parser.add_argument("--overwrite", action="store_true", help="Overwrite existing configuration")

    # List config command
    list_config_parser = subparsers.add_parser("list-config", help="List configuration options")

    # Help command
    help_parser = subparsers.add_parser("help", help="Show help message")

    return parser


def main() -> None:
    """Main entry point."""
    parser = create_parser()
    args = parser.parse_args()

    cli = WSIPipelineCLIWrapper()

    try:
        if args.command == "process":
            cli.process_slide(
                slide_path=args.slide_path,
                slide_id=args.slide_id,
                resume_manifest=args.resume,
                run_id=args.run_id,
                output_dir=args.output_dir,
                config_file=args.config
            )
        elif args.command == "resume":
            cli.resume_processing(
                manifest_path=args.manifest_path,
                output_dir=args.output_dir,
                config_file=args.config
            )
        elif args.command == "status":
            cli.show_status()
        elif args.command == "manifests":
            cli.show_manifests(args.slide_id)
        elif args.command == "configure":
            cli.configure_pipeline(args.config_file, args.overwrite)
        elif args.command == "list-config":
            cli.list_config_options()
        elif args.command == "help":
            parser.print_help()
        else:
            parser.print_help()

    except WSIPipelineError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        cli.cleanup()


if __name__ == "__main__":
    main()