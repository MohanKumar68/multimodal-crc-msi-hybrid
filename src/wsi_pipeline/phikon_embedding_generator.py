"""Phikon-v2 embedding generator for WSI patches.

This module provides functionality to generate 1024-dimensional embeddings
for WSI patches using the Phikon-v2 model.
"""

import os
import math
import time
import torch
import numpy as np
from PIL import Image
from typing import Dict, List, Optional, Tuple, Any, Union
from transformers import AutoImageProcessor, AutoModel
import logging

from .config import TechnicalMetadata

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class PhikonEmbeddingGeneratorError(Exception):
    """Exception raised for Phikon embedding generation errors."""
    pass


class PhikonEmbeddingGenerator:
    """Generate Phikon-v2 embeddings for WSI patches with singleton pattern and lazy initialization."""
    _instance = None
    _initialized = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        if not PhikonEmbeddingGenerator._initialized:
            self.config = config or {}
            self.device = torch.device('cpu')
            if torch.cuda.is_available():
                self.device = torch.device('cuda')
            elif hasattr(torch.backends, 'mps') and torch.backends.mps.is_available():
                self.device = torch.device('mps')
            self.processor = None
            self.model = None
            try:
                self._load_model()
                PhikonEmbeddingGenerator._initialized = True
            except PhikonEmbeddingGeneratorError:
                raise
            except Exception as e:
                raise PhikonEmbeddingGeneratorError(f"Model initialization failed: {e}")

    def _load_model(self):
        try:
            self.processor = AutoImageProcessor.from_pretrained("owkin/phikon-v2")
            self.model = AutoModel.from_pretrained("owkin/phikon-v2", output_attentions=False).to(self.device)
            self.model.eval()
            logger.info(f"Phikon-v2 initialized on {self.device}")
        except Exception as e:
            logger.error(f"Phikon-v2 load failed: {e}")
            raise PhikonEmbeddingGeneratorError(f"Model initialization failed: {e}")

    def generate_embedding(
        self,
        patch_image: Union[Image.Image, List[Image.Image]],
        batch_size: int = 1
    ) -> Tuple[Union[torch.Tensor, List[torch.Tensor]], Union[Dict[str, Any], List[Dict[str, Any]]]]:
        """
        Generate 1024-dimensional embedding(s) for a WSI patch or a list of patches.

        Args:
            patch_image: PIL Image of a patch, or a list of PIL Images
            batch_size: Batch size when processing multiple patches

        Returns:
            If single Image: Tuple of (embedding_tensor [1024], metadata_dict)
            If list of Images: Tuple of (list of embedding_tensors [[1024], ...], list of metadata_dicts)
        """
        if isinstance(patch_image, (list, tuple)):
            if not patch_image:
                return [], []
            embeddings = []
            metadata_list = []
            for i in range(0, len(patch_image), batch_size):
                batch = patch_image[i:i + batch_size]
                batch_embeddings, batch_metadata = self._process_batch(batch)
                embeddings.extend(batch_embeddings)
                metadata_list.extend(batch_metadata)
            return embeddings, metadata_list
        else:
            if not isinstance(patch_image, Image.Image):
                raise PhikonEmbeddingGeneratorError(f"Expected PIL Image, got {type(patch_image)}")
            if patch_image.width <= 0 or patch_image.height <= 0:
                raise PhikonEmbeddingGeneratorError("Image has invalid dimensions")
            try:
                inputs = self.processor(images=patch_image, return_tensors="pt").to(self.device)
                with torch.inference_mode():
                    outputs = self.model(**inputs)
                embedding = outputs.last_hidden_state[0, 0, :].cpu()  # [1024]
                if embedding.shape != torch.Size([1024]):
                    raise PhikonEmbeddingGeneratorError(f"Unexpected embedding shape: {embedding.shape}")
                if not torch.isfinite(embedding).all():
                    raise PhikonEmbeddingGeneratorError("Non-finite values in embedding")
                metadata = {
                    "patch_id": "placeholder",
                    "slide_id": "placeholder",
                    "coordinates": (0, 0, 224, 224),
                    "model_version": "phikon-v2",
                    "device": str(self.device)
                }
                return embedding, metadata
            except PhikonEmbeddingGeneratorError:
                raise
            except Exception as e:
                logger.error(f"Failed to generate embedding: {e}")
                raise PhikonEmbeddingGeneratorError(f"Failed to generate embedding: {e}")

    def _process_batch(self, batch: List[Image.Image]) -> Tuple[List[torch.Tensor], List[Dict[str, Any]]]:
        emb_list, metadata_list, _ = self._process_batch_with_timing(batch)
        return emb_list, metadata_list

    def _process_batch_with_timing(
        self,
        batch: List[Image.Image]
    ) -> Tuple[List[torch.Tensor], List[Dict[str, Any]], int]:
        """Processes a batch of patch images, measuring solely the model forward-pass time in nanoseconds.

        Returns:
            Tuple of (embeddings_list, metadata_list, forward_pass_ns)
        """
        if not batch:
            return [], [], 0
        for img in batch:
            if not isinstance(img, Image.Image) or img.width <= 0 or img.height <= 0:
                raise PhikonEmbeddingGeneratorError("Batch contains invalid image")
        try:
            inputs = self.processor(images=batch, return_tensors="pt").to(self.device)

            # Hardware synchronization prior to monotonic timer
            if self.device.type == "cuda" and torch.cuda.is_available():
                torch.cuda.synchronize()

            t_start = time.perf_counter_ns()
            with torch.inference_mode():
                outputs = self.model(**inputs)

            if self.device.type == "cuda" and torch.cuda.is_available():
                torch.cuda.synchronize()

            t_end = time.perf_counter_ns()
            forward_pass_ns = max(0, t_end - t_start)

            embeddings = outputs.last_hidden_state[:, 0, :].detach().cpu().clone()  # [B, 1024]
            del outputs
            del inputs
            if self.device.type == "cuda" and torch.cuda.is_available():
                torch.cuda.empty_cache()
            if embeddings.dim() != 2 or embeddings.size(1) != 1024:
                raise PhikonEmbeddingGeneratorError(f"Unexpected batch embedding shape: {embeddings.shape}")
            if not torch.isfinite(embeddings).all():
                raise PhikonEmbeddingGeneratorError("Non-finite values in embeddings")
            emb_list = [emb for emb in embeddings]
            del embeddings
            metadata_list = [
                {
                    "patch_id": f"patch_{idx}",
                    "model_version": "phikon-v2",
                    "device": str(self.device)
                }
                for idx in range(len(emb_list))
            ]
            return emb_list, metadata_list, forward_pass_ns
        except PhikonEmbeddingGeneratorError:
            raise
        except Exception as e:
            logger.error(f"Phikon embedding batch failed: {e}")
            raise PhikonEmbeddingGeneratorError(f"Batch processing failed: {e}")

    def generate_embeddings(self, patch_paths: List[str], output_dir: str) -> List[Dict[str, Any]]:
        """
        Generate embeddings for multiple patches from file paths.
        """
        embeddings_metadata = []
        os.makedirs(output_dir, exist_ok=True)
        for patch_path in patch_paths:
            try:
                patch_image = Image.open(patch_path).convert('RGB')
                embedding, metadata = self.generate_embedding(patch_image)
                output_path = os.path.join(output_dir, f"embedding_{os.path.basename(patch_path)}.pt")
                torch.save(embedding, output_path)
                metadata.update({
                    "patch_path": patch_path,
                    "embedding_path": output_path
                })
                embeddings_metadata.append(metadata)
                logger.info(f"Successfully generated embedding for {patch_path}")
            except Exception as e:
                logger.error(f"Failed to generate embedding for {patch_path}: {e}")
                raise PhikonEmbeddingGeneratorError(f"Failed to generate embedding for {patch_path}: {e}")
        return embeddings_metadata
