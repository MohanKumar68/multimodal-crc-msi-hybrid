# WSI Pipeline Design Document

## Overview

This document describes the design of the Whole-Slide Image (WSI) ingestion pipeline for the colorectal cancer MSI/MSS multimodal fusion project. The pipeline provides a memory-efficient foundation for processing large pathology whole-slide images, extracting tissue patches, and generating machine-readable manifests for downstream analysis.

## 1. Motivation and Objectives

### 1.1 Problem Statement
The existing project focuses on multimodal fusion for colorectal cancer detection using precomputed embeddings from JPG patches. However, the eventual goal is to enable direct processing of raw pathology whole-slide images (WSIs) without requiring precomputed numerical embeddings.

### 1.2 Design Goals
- **Memory Efficiency**: Never load entire gigabyte-scale WSIs into RAM
- **Modular Architecture**: Small, testable, independently understandable modules
- **Privacy-Conscious**: Default to local processing without external uploads
- **Memory-Conscious**: Use region-based reading and incremental processing
- **Reproducible**: Generate machine-readable manifests with full processing records
- **Extensible**: Easy to integrate with existing Phikon-v2 pipeline

## 2. Architecture Overview

### 2.1 Module Structure

```
src/wsi_pipeline/
├── __init__.py
├── config.py                    # Configuration management
├── slide_reader.py             # OpenSlide-based WSI reading
├── tissue_detection.py         # Tissue region detection
├── patch_extractor.py          # Patch extraction and management
├── manifest.py                 # Manifest and record management
├── pipeline.py                 # Main pipeline orchestration
└── cli.py                      # Command-line interface

tests/wsi_pipeline/
├── test_metadata_schema.py
├── test_slide_reader.py
├── test_tissue_detection.py
├── test_patch_extractor.py
├── test_manifest.py
└── test_pipeline.py
```

### 2.2 Processing Pipeline

1. **Input**: Local WSI file path
2. **Slide Reading**: Extract metadata, thumbnails, regions using OpenSlide
3. **Tissue Detection**: Identify tissue regions using HSV thresholding
4. **Patch Extraction**: Extract patches from tissue regions with configurable limits
5. **Manifest Generation**: Create machine-readable processing record
6. **Output**: Extracted patches + processing metadata

## 3. Technical Design

### 3.1 Memory Management Strategy

#### 3.1.1 Region-Based Reading
- WSIs are never loaded into memory entirely
- Uses OpenSlide's region-based reading for patch extraction
- Thumbnails are generated for visualization and tissue detection
- Processing is incremental with explicit resource cleanup

#### 3.1.2 Bounded Patch Extraction
- Configurable maximum patch count (default: 1000)
- Minimum tissue coverage threshold (default: 0.5)
- Stride-based extraction to avoid overlapping patches
- Early termination when limits are reached

#### 3.1.3 Resource Monitoring
- Peak RAM monitoring (when available)
- Processing time tracking
- Disk usage monitoring
- Configurable memory warnings and errors

### 3.2 Data Models

#### 3.2.1 Technical Metadata
Separates automatically extracted slide properties:
- File format, size, dimensions
- Pyramid information and downsample factors
- MPP (microns per pixel) when available
- Vendor and color space information
- Associated images and byte order

#### 3.2.2 Case Metadata
User-supplied clinical information:
- Case ID, specimen type, tissue site
- Stain type, collection and processing dates
- Clinical context and metadata provenance
- Custom fields for extensibility

#### 3.2.3 Patch Records
Individual patch extraction records:
- Patch ID and coordinates
- Source slide and pyramid level
- Tissue coverage percentage
- Extraction status and error information
- File checksums for integrity verification

### 3.3 Tissue Detection Algorithm

#### 3.3.1 HSV Thresholding
- Converts RGB images to HSV color space
- Configurable thresholds for Hue, Saturation, Value
- Morphological operations for cleanup
- Handles empty or nearly blank slides

#### 3.3.2 Fallback Strategies
- Otsu's method for grayscale images
- Connected component analysis for region filtering
- Minimum patch size constraints
- Coverage thresholds for candidate regions

### 3.4 Manifest System

#### 3.4.1 Schema Design
- JSON-based manifest format
- Separate sections for metadata, patches, statistics
- Timestamps and processing status tracking
- Error logging and recovery information

#### 3.4.2 Resumability
- Stable run IDs for tracking
- Checkpoint manifests for interrupted processing
- Status tracking for successful/failed patches
- Safe overwrite behavior with explicit confirmation

## 4. Integration with Existing System

### 4.1 Phikon-v2 Integration Boundary

#### 4.1.1 Proposed Adapter Pattern
1. **Patch Collection**: Read extracted patches from filesystem
2. **Batch Processing**: Process patches in configurable batches
3. **Embedding Generation**: Apply existing Phikon-v2 processor
4. **Incremental Storage**: Save embeddings with patch correspondence
5. **Resumption Support**: Continue from existing manifests

#### 4.1.2 Integration Points
- Patch size compatibility (224×224 matches existing system)
- Coordinate mapping between WSI levels and patches
- Tissue coverage filtering compatible with existing preprocessing
- Manifest format compatible with existing dataset structure

### 4.2 Backward Compatibility
- Preserves existing model weights and architecture
- Maintains current evaluation scripts
- Keeps existing dataset structure intact
- Adds new functionality without breaking changes

## 5. Privacy and Security Design

### 5.1 Local Processing Default
- No external file uploads (including transfer.sh)
- Local filesystem processing only
- Configurable output directories
- No patient-identifiable data in filenames

### 5.2 Data Sanitization
- Non-identifying slide IDs
- Secure checksum generation
- No sensitive data in git commits
- Configurable .gitignore rules

### 5.3 Access Control
- File path validation
- Format-specific access control
- Read-only processing where possible
- Audit trail via manifests

## 6. Error Handling and Recovery

### 6.1 Error Classification
- **Input Validation**: Invalid file paths, unsupported formats
- **Processing Errors**: Tissue detection failures, extraction errors
- **Resource Errors**: Memory limits, disk space issues
- **Format Errors**: Corrupted files, metadata parsing failures

### 6.2 Recovery Strategies
- **Resumption**: Continue from last successful checkpoint
- **Fallback**: Use alternative algorithms when primary fails
- **Graceful Degradation**: Process with available information
- **Clear Reporting**: Detailed error messages with context

## 7. Testing Strategy

### 7.1 Unit Testing
- Metadata schema validation
- Invalid file path handling
- Unsupported format detection
- Thumbnail generation
- Empty tissue mask handling
- Patch dimension validation
- Coordinate tracking
- Maximum patch count enforcement
- Tissue coverage filtering
- Output directory handling
- Manifest correctness verification
- Partial failure handling
- Resumption functionality
- Safe defaults for sensitive data

### 7.2 Integration Testing
- Real WSI processing (when available)
- Full pipeline end-to-end testing
- Memory usage validation
- Performance benchmarking
- Error recovery scenarios

### 7.3 Continuous Integration
- Automated test runs
- Performance regression detection
- Resource usage monitoring
- Code coverage validation

## 8. Deployment and Operations

### 8.1 Windows PowerShell Commands

#### 8.1.1 Installation
```powershell
# Install required dependencies
pip install openslide-python openslide-bin numpy scikit-image Pillow

# Install optional dependencies
pip install opencv-python matplotlib psutil
```

#### 8.1.2 OpenSlide Check
```powershell
# Check if OpenSlide is available
python -c "import openslide; print('OpenSlide available')"

# Test with a sample WSI
wsi_pipeline process C:\path\to\slide.svs
```

#### 8.1.3 Running Tests
```powershell
# Run WSI pipeline tests
python -m pytest tests/wsi_pipeline/ -v

# Run specific test module
python -m pytest tests/wsi_pipeline/test_slide_reader.py -v
```

#### 8.1.4 Processing a WSI
```powershell
# Process a single WSI
wsi_pipeline process D:\path\to\patient.svs --slide-id TCGA-NH-A5IV-01Z-00-DX1.937D9B41-01

# With custom configuration
wsi_pipeline process D:\path\to\patient.svs --slide-id my_slide --run-id run_001 --output-dir D:\output\patches

# Resume from previous run
wsi_pipeline resume D:\path\to\manifest_final.json
```

### 8.2 Docker Deployment
```dockerfile
FROM python:3.10-slim

# Install system dependencies
RUN apt-get update && apt-get install -y \
    wget \
    libopencv-dev \
    libjpeg-dev \
    zlib1g-dev

# Install OpenSlide
RUN wget https://github.com/OpenSlide/OpenSlide/releases/download/v3.6.1/OpenSlide-3.6.1.tar.gz && \
    tar -xzf OpenSlide-3.6.1.tar.gz && \
    cd OpenSlide-3.6.1 && \
    ./configure && \
    make && \
    make install && \
    ldconfig

# Install Python dependencies
COPY requirements.txt .
RUN pip install -r requirements.txt

# Copy pipeline code
COPY src/wsi_pipeline/ /app/src/wsi_pipeline/

WORKDIR /app

CMD ["python", "-m", "src.wsi_pipeline.cli"]
```

## 9. Performance Considerations

### 9.1 Memory Usage
- Peak RAM for thumbnails: ~50-100 MB
- Peak RAM for tissue detection: ~100-200 MB
- Peak RAM for patch extraction: ~200-400 MB
- Total RAM usage remains bounded regardless of slide size

### 9.2 Processing Time
- Slide opening: 1-5 seconds
- Metadata extraction: 0.5-2 seconds
- Thumbnail generation: 1-3 seconds
- Tissue detection: 2-10 seconds (depends on image size)
- Patch extraction: Variable (based on patch count)
- Manifest generation: <1 second

### 9.3 Scalability
- Processing scales with number of patches, not slide size
- Memory usage is independent of WSI resolution
- Can process multiple slides in parallel
- Filesystem storage scales with extracted patches

## 10. Known Limitations

### 10.1 Format Support
- Limited to OpenSlide-supported formats (SVS, TIFF, NDPI, etc.)
- May not support all proprietary vendor formats
- JPEG/PNG files may be accepted but not treated as true WSIs

### 10.2 Tissue Detection
- HSV thresholding may not detect all tissue types
- Requires validation on diverse slide types
- Performance may vary with stain quality
- May need tuning for specific scanners

### 10.3 Processing Constraints
- Maximum patch count limits may exclude rare cases
- Tissue coverage thresholds may be too conservative
- Processing time scales with number of patches
- Requires local filesystem access

## 11. Future Enhancements

### 11.1 Advanced Tissue Detection
- Deep learning-based segmentation models
- Multi-modal tissue detection (hematoxylin, eosin, etc.)
- Tumor vs. stroma differentiation
- Automated threshold optimization

### 11.2 Enhanced Integration
- Direct Phikon-v2 embedding generation
- Clinical report integration
- Automated slide quality assessment
- Multi-patient batch processing

### 11.3 Cloud and Edge Deployment
- Stream processing capabilities
- Distributed processing for large datasets
- Edge device optimization
- Privacy-preserving processing

### 11.4 Enhanced Features
- Automated slide quality control
- Histopathology-specific preprocessing
- Patch quality assessment
- Automated annotation generation

## 12. Validation and Verification

### 12.1 Testing Requirements
- Unit tests for all modules
- Integration tests with real WSIs
- Performance benchmarking
- Memory usage validation
- Error recovery testing
- Resumption functionality testing

### 12.2 Acceptance Criteria
- Never load entire WSI into RAM
- Process at least 1000 patches within reasonable time
- Generate complete manifest for each processing run
- Handle errors gracefully with clear reporting
- Support resuming interrupted processing
- Maintain compatibility with existing system

## 13. Conclusion

The WSI pipeline provides a robust, memory-efficient foundation for processing whole-slide images in the colorectal cancer detection project. It maintains strict separation between technical and clinical metadata, supports resumable processing, and integrates cleanly with the existing Phikon-v2 pipeline. The design prioritizes privacy, safety, and extensibility while providing clear error reporting and recovery mechanisms.

This implementation establishes the input-processing foundation required for the next phase of development, which will integrate the extracted patches with the existing multimodal fusion system.