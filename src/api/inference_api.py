"""Minimal Flask inference API for Phikon‑v2 embeddings + cross‑attention fusion.

Provides:
- GET /health – simple health check.
- POST /predict – accepts a JSON payload with either synthetic placeholders or real image patches (base64‑encoded PNG/JPEG) and a clinical embedding vector. Returns the MSI probability from the fusion model.

The API loads the Phikon‑v2 model and the fusion checkpoint once at start‑up, keeps them in evaluation mode, and uses ``torch.inference_mode()`` for inference.
"""

import matplotlib
matplotlib.use("Agg")

import base64
import io
import hashlib
import json
import logging
import math
import gc
import os
import sys
import tempfile
import time
import uuid
import re
import threading
from pathlib import Path
from typing import List, Optional, Tuple, Dict, Any, Union

# Robust sys.path resolution ensuring imports work whether run directly or as a package
_current_file = Path(__file__).resolve()
_api_dir = _current_file.parent
_src_dir = _api_dir.parent
_repo_root = _src_dir.parent
for _p in (str(_repo_root), str(_src_dir)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import requests
from flask_cors import CORS
from flask import Flask, request, jsonify
from PIL import Image
import torch

# Configure logging with console and live diagnostic file handler
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)
if not logger.handlers:
    _ch = logging.StreamHandler()
    _ch.setFormatter(logging.Formatter("[%(asctime)s] %(levelname)s:%(name)s: %(message)s"))
    logger.addHandler(_ch)

try:
    _log_dir = Path(__file__).resolve().parent.parent.parent / "results"
    _log_dir.mkdir(parents=True, exist_ok=True)
    _diag_file = _log_dir / "live_timing_diagnostic.log"
    _fh = logging.FileHandler(_diag_file, mode="a", encoding="utf-8")
    _fh.setFormatter(logging.Formatter("[%(asctime)s] %(levelname)s:%(name)s: %(message)s"))
    logger.addHandler(_fh)
except Exception:
    pass

# Thread-safe in-memory session timing store for multi-request analyses (/embed_tiles -> /fuse)
_session_lock = threading.Lock()
_analysis_session_timings: Dict[str, Dict[str, Any]] = {}

# Project imports (relative to repository root or src)
try:
    from wsi_pipeline.phikon_embedding_generator import PhikonEmbeddingGenerator
except ImportError:
    from src.wsi_pipeline.phikon_embedding_generator import PhikonEmbeddingGenerator

try:
    from .wsipipeline_integration import run_wsi_inference, WSIInferenceError
except (ImportError, ValueError):
    try:
        from api.wsipipeline_integration import run_wsi_inference, WSIInferenceError
    except ImportError:
        from src.api.wsipipeline_integration import run_wsi_inference, WSIInferenceError

try:
    from architectures.cross_attention_fusion import SummaryToPatchCrossAttention
except ImportError:
    from src.architectures.cross_attention_fusion import SummaryToPatchCrossAttention

# Microscopic ViT XAI Heatmap Generator (Senior Rollout Engine)
try:
    from generate_pixel_xai import generate_smooth_vit_heatmap
except ImportError:
    try:
        from src.generate_pixel_xai import generate_smooth_vit_heatmap
    except ImportError:
        generate_smooth_vit_heatmap = None

# ClinicalBERT encoder (runtime)
# ClinicalBERT encoder (runtime)
# Use a singleton pattern to avoid reloading on every request
try:
    from transformers import AutoTokenizer, AutoModel
except ImportError:
    AutoTokenizer = None
    AutoModel = None

# Singleton ClinicalBERT encoder
class ClinicalBERTEncoder:
    """Reusable ClinicalBERT text encoder with lazy initialization.

    Loads tokenizer and model once, validates inputs, and returns [1,768] embeddings.
    """
    _instance = None
    _initialized = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, model_name="emilyalsentzer/Bio_ClinicalBERT", device="cpu"):
        if not ClinicalBERTEncoder._initialized:
            ClinicalBERTEncoder._initialized = True
            self.model_name = model_name
            self.device = torch.device(device)
            self.tokenizer = None
            self.model = None
            self._load()

    def _load(self):
        """Load model and tokenizer lazily."""
        try:
            try:
                self.tokenizer = AutoTokenizer.from_pretrained(self.model_name, local_files_only=True)
                self.model = AutoModel.from_pretrained(self.model_name, local_files_only=True).to(self.device)
            except Exception:
                self.tokenizer = AutoTokenizer.from_pretrained(self.model_name)
                self.model = AutoModel.from_pretrained(self.model_name).to(self.device)
            self.model.eval()
            logger.info(f"ClinicalBERT encoder loaded on {self.device}")
        except Exception as e:
            logger.error(f"Failed to load ClinicalBERT: {e}")
            self.model = None
            self.tokenizer = None
            raise RuntimeError(f"ClinicalBERT initialization failed: {e}")

    def encode(self, text: str) -> torch.Tensor:
        """Encode text to [1,768] embedding.

        Args:
            text: Non-empty clinical report string

        Returns:
            [1,768] float32 tensor

        Raises:
            ValueError: If text is empty or encoding fails
        """
        embedding, _ = self.encode_with_timing(text)
        return embedding

    def encode_with_timing(self, text: str) -> Tuple[torch.Tensor, int]:
        """Encode text to [1,768] embedding, measuring solely the forward-pass execution in nanoseconds.

        Tokenization and input preparation occur strictly outside the timed boundary.

        Returns:
            Tuple of (embedding [1, 768], forward_pass_ns)
        """
        if not text or not isinstance(text, str) or not text.strip():
            raise ValueError("Text must be non-empty string")
        if self.model is None or self.tokenizer is None:
            raise RuntimeError("ClinicalBERT not initialized")

        try:
            inputs = self.tokenizer(
                text,
                return_tensors="pt",
                truncation=True,
                max_length=512
            ).to(self.device)

            if self.device.type == "cuda" and torch.cuda.is_available():
                torch.cuda.synchronize()

            t_start = time.perf_counter_ns()
            with torch.inference_mode():
                outputs = self.model(**inputs)

            if self.device.type == "cuda" and torch.cuda.is_available():
                torch.cuda.synchronize()

            t_end = time.perf_counter_ns()
            forward_ns = max(0, t_end - t_start)

            embedding = outputs.last_hidden_state[:, 0, :].cpu()  # CLS pooling
            if embedding.shape != (1, 768):
                raise ValueError(f"Unexpected embedding shape: {embedding.shape}")
            if not torch.isfinite(embedding).all():
                raise RuntimeError("Embedding contains non-finite values")
            return embedding, forward_ns
        except Exception as e:
            logger.error(f"ClinicalBERT encoding failed: {e}")
            raise RuntimeError(f"Encoding failed: {e}")

# Initialize encoder once
try:
    clinical_encoder = ClinicalBERTEncoder(device="cpu")
except Exception as e:
    logger.error(f"Failed to initialize ClinicalBERT encoder: {e}")
    clinical_encoder = None

# WSI pipeline integration imports
try:
    from src.api.wsipipeline_integration import (
        run_wsi_inference,
        WSIInferenceError,
        load_clinical_embedding,
    )
except ImportError:
    run_wsi_inference = None
    WSIInferenceError = None
    load_clinical_embedding = None

# ---------------------------------------------------------------------------
# Load models (once) --------------------------------------------------------
# ---------------------------------------------------------------------------

# Phikon‑v2 generator – loads processor + model from the cached directory.
phikon_generator = PhikonEmbeddingGenerator()

# Fusion model – load the checkpoint that has been verified as the correct one.
fusion_model = SummaryToPatchCrossAttention()
try:
    fusion_state = torch.load("models/fusion_model_v1_1.pth", map_location="cpu")
    fusion_model.load_state_dict(fusion_state)
except Exception as e:
    # If the checkpoint cannot be loaded we raise at start‑up; the API will be unavailable.
    raise RuntimeError(f"Failed to load fusion checkpoint: {e}")

fusion_model.eval()

# ---------------------------------------------------------------------------
# Flask app -----------------------------------------------------------------
# ---------------------------------------------------------------------------

app = Flask(__name__)
CORS(app, resources={r"/*": {"origins": "*"}})
# Limit request body to 100 MiB to prevent oversized payloads while safely allowing 500+ tiles
app.config["MAX_CONTENT_LENGTH"] = 100 * 1024 * 1024

PHIKON_DEFAULT_BATCH_SIZE = 25

@app.route("/health", methods=["GET"])
@app.route("/health/", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "timing_instrumentation": True,
        "supported_endpoints": ["/predict", "/embed_tiles", "/fuse", "/generate_summary", "/generate_heatmap"]
    })

@app.route("/wsipredict", methods=["POST"])
def wsipredict():
    """Run full WSI inference using the WSI pipeline.

    Requires:
    - "slide_path": Path to the WSI file
    - "patient_id": TCGA patient ID for loading pre-computed clinical embedding
    - Optional: "slide_id" for custom slide identifier

    Returns the structured result from run_wsi_inference.
    """
    if run_wsi_inference is None:
        return jsonify({"error": "WSI integration not available"}), 503

    data = request.get_json(force=True)

    # Required parameters
    slide_path = data.get("slide_path")
    patient_id = data.get("patient_id")

    if not slide_path:
        return jsonify({"error": "slide_path is required"}), 400
    if not patient_id:
        return jsonify({"error": "patient_id is required"}), 400

    # Optional parameters
    slide_id = data.get("slide_id")
    config = data.get("config")

    try:
        # Load clinical embedding from pre-computed tensors
        clinical_embedding = load_clinical_embedding(patient_id)

        # Run WSI inference with clinical encoder injection
        result = run_wsi_inference(
            slide_path=slide_path,
            clinical_input=clinical_embedding,  # Pass tensor directly
            slide_id=slide_id,
            config=config,
            clinical_encoder=clinical_encoder  # Inject singleton
        )
    except (WSIInferenceError, ValueError, FileNotFoundError) as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        return jsonify({"error": f"WSI inference failed: {str(e)}"}), 500

    return jsonify(result)

def _decode_patch(b64_str: str) -> Image.Image:
    try:
        raw = base64.b64decode(b64_str)
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:
        raise ValueError(f"Invalid base64 image data: {exc}")


def extract_phikon_embeddings_batched(
    patches: List[Image.Image],
    batch_size: int = PHIKON_DEFAULT_BATCH_SIZE,
    return_timing: bool = False
) -> Union[torch.Tensor, Tuple[torch.Tensor, int]]:
    """Extracts Phikon-v2 embeddings for candidate patches in bounded batches.

    Clears CUDA/Python memory cache between batches to prevent OOM when handling
    large patch counts (>100 tiles).

    Returns:
        Tensor of shape [N, 1024] (or (Tensor, total_forward_pass_ns) if return_timing=True)
    """
    if not patches:
        empty = torch.empty((0, 1024), dtype=torch.float32)
        return (empty, 0) if return_timing else empty

    vision_embeddings = []
    num_patches = len(patches)
    total_forward_ns = 0

    sub_batch_size = max(1, min(batch_size, 10))
    for start_idx in range(0, num_patches, sub_batch_size):
        end_idx = min(start_idx + sub_batch_size, num_patches)
        batch = patches[start_idx:end_idx]

        # Use PhikonEmbeddingGenerator's batch processor with timing
        batch_embs, _, batch_forward_ns = phikon_generator._process_batch_with_timing(batch)
        vision_embeddings.extend(batch_embs)
        total_forward_ns += batch_forward_ns

        # Clear memory cache between batches
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        gc.collect()

    stacked = torch.stack(vision_embeddings)
    return (stacked, total_forward_ns) if return_timing else stacked


def _validate_and_extract_attention(
    attn_weights: torch.Tensor,
    expected_patch_count: int
) -> Optional[List[float]]:
    """Validates and extracts normalized attention weights from cross-attention layer.

    SummaryToPatchCrossAttention MultiheadAttention output shape is [1, 1, N].
    Returns list of N floats summing to ~1.0, or None if validation fails.
    """
    if attn_weights is None:
        return None
    try:
        attn_tensor = attn_weights[0, 0].detach().cpu()
        if attn_tensor.ndim == 0:
            attn_vals = [float(attn_tensor.item())]
        else:
            attn_vals = [float(x) for x in attn_tensor.tolist()]

        if len(attn_vals) != expected_patch_count:
            logger.warning(
                f"Attention weight count mismatch: got {len(attn_vals)}, expected {expected_patch_count}"
            )
            return None
        if not all(math.isfinite(v) and 0.0 <= v <= 1.0 for v in attn_vals):
            logger.warning(
                f"Attention weights contain non-finite or out-of-range values: {attn_vals}"
            )
            return None
        if abs(sum(attn_vals) - 1.0) > 1e-3:
            logger.warning(
                f"Attention weights sum ({sum(attn_vals):.6f}) deviates from 1.0 normalization"
            )
            return None

        return attn_vals
    except Exception as attn_err:
        logger.warning(f"Failed to extract cross-attention weights: {attn_err}")
        return None


SAMPLE_TCGA_REPORT_TEXT = (
    "Final Pathologic Diagnosis: Colon, ascending, hemicolectomy: Invasive adenocarcinoma, moderately differentiated. "
    "Tumor invades muscularis propria into subserosal adipose tissue (pT3). Margin of resection negative for carcinoma. "
    "Regional lymph nodes 0/14 positive for metastasis (pN0). Clinical stage: Stage IIA."
)


@app.route("/embed_tiles", methods=["POST"])
@app.route("/embed_tiles/", methods=["POST"])
def embed_tiles():
    """Stage 1: Incremental batch extraction of Phikon-v2 ViT embeddings.

    Accepts:
        - "patches": list of base64-encoded JPEG/PNG strings
        - "batch_size": optional int (default 25, clamped 1..50)
        - "request_id": optional string for request tracing

    Returns:
        - "request_id": str
        - "embeddings": list of 1024-dimensional float vectors
        - "count": int
    """
    t0 = time.time()
    data = request.get_json(force=True)
    request_id = str(data.get("request_id") or uuid.uuid4())

    raw_patches = data.get("patches")
    if not isinstance(raw_patches, list) or len(raw_patches) == 0:
        return jsonify({"error": "'patches' must be a non-empty list of base64 strings", "request_id": request_id}), 400

    batch_size = int(data.get("batch_size", PHIKON_DEFAULT_BATCH_SIZE))
    batch_size = max(1, min(batch_size, 50))

    patches: List[Image.Image] = []
    try:
        for b64 in raw_patches:
            patches.append(_decode_patch(b64))
    except Exception as exc:
        return jsonify({"error": str(exc), "request_id": request_id}), 400

    try:
        with torch.inference_mode():
            vision_tensor, vision_ns = extract_phikon_embeddings_batched(
                patches, batch_size=batch_size, return_timing=True
            )
            embeddings_list = vision_tensor.tolist()
            del vision_tensor
            del patches
            del raw_patches
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            gc.collect()
    except Exception as exc:
        return jsonify({"error": f"Phikon feature extraction failed: {exc}", "request_id": request_id}), 500

    vision_ms = round(vision_ns / 1_000_000.0, 2)
    elapsed_ms = round((time.time() - t0) * 1000, 2)

    # Accumulate session vision timing in thread-safe registry
    session_id = str(data.get("session_id") or re.sub(r"-chunk-\d+$", "", request_id))
    with _session_lock:
        now = time.time()
        # Clean up stale sessions older than 30 minutes
        stale_keys = [k for k, v in _analysis_session_timings.items() if now - v.get("created_at", now) > 1800]
        for k in stale_keys:
            _analysis_session_timings.pop(k, None)

        sess = _analysis_session_timings.setdefault(session_id, {
            "vision_ns": 0,
            "patch_count": 0,
            "chunks_count": 0,
            "created_at": now
        })
        sess["vision_ns"] += vision_ns
        sess["patch_count"] += len(embeddings_list)
        sess["chunks_count"] += 1
        chunk_match = re.search(r"-chunk-(\d+)$", request_id)
        chunk_number = int(chunk_match.group(1)) if chunk_match else int(sess["chunks_count"])
        session_accumulated_vision_ms = round(sess["vision_ns"] / 1_000_000.0, 2)

    logger.info(
        "[STEP2_DIAGNOSTIC /embed_tiles] %s",
        json.dumps({
            "request_id": request_id,
            "session_id": session_id,
            "chunk_number": chunk_number,
            "actual_tile_count": len(embeddings_list),
            "measured_vision_inference_ms": vision_ms,
            "session_accumulated_vision_ms": session_accumulated_vision_ms
        })
    )
    logger.info(
        "[DIAGNOSTIC_BOUNDARY] %s",
        json.dumps({
            "event": "STAGE1_EMBED_TILES",
            "request_id": request_id,
            "session_id": session_id,
            "patch_count": len(embeddings_list),
            "batch_size": batch_size,
            "vision_inference_ms": vision_ms,
            "session_accumulated_vision_ms": session_accumulated_vision_ms,
            "latency_ms": elapsed_ms,
        })
    )

    return jsonify({
        "request_id": request_id,
        "session_id": session_id,
        "embeddings": embeddings_list,
        "count": len(embeddings_list),
        "total_model_inference_ms": vision_ms,
        "inference_latency_ms": vision_ms,
        "vision_inference_ms": vision_ms,
        "session_accumulated_vision_ms": session_accumulated_vision_ms,
        "inference_timing_scope": "forward_pass_vision_chunk"
    })


@app.route("/fuse", methods=["POST"])
@app.route("/fuse/", methods=["POST"])
def fuse():
    """Stage 2: Sequence cross-attention fusion of vision embeddings and clinical report.

    Accepts:
        - "vision_embeddings": list of N vectors of 1024 floats
        - "clinical_report": clinical report string (or "clinical_embedding": list of 768 floats)
        - "request_id": optional string for request tracing

    Returns:
        - "request_id": str
        - "probability": float (MSI classification probability)
        - "raw_logit": float (unbounded logit before sigmoid)
        - "checkpoint_sigmoid_present": bool
        - "threshold": float (0.83)
        - "predicted_class": str ("MSI-H" or "MSS")
        - "report_sha256": str
        - "is_sample_report": bool
        - "tile_count": int
        - "vision_shape": [N, 1024]
        - "clinical_shape": [1, 768]
        - "attention_weights": list of N floats (relative tile weights summing to ~1.0)
    """
    t0 = time.time()
    data = request.get_json(force=True)
    request_id = str(data.get("request_id") or uuid.uuid4())

    raw_embs = data.get("vision_embeddings")
    if not isinstance(raw_embs, list) or len(raw_embs) == 0:
        return jsonify({"error": "'vision_embeddings' must be a non-empty list of vectors", "request_id": request_id}), 400

    try:
        vision_tensor = torch.tensor(raw_embs, dtype=torch.float32)
        if vision_tensor.ndim != 2 or vision_tensor.shape[1] != 1024:
            return jsonify({
                "error": f"'vision_embeddings' must have shape [N, 1024], got {list(vision_tensor.shape)}",
                "request_id": request_id
            }), 400
        if not torch.isfinite(vision_tensor).all():
            return jsonify({"error": "vision_embeddings contains non-finite values", "request_id": request_id}), 400
    except Exception as exc:
        return jsonify({"error": f"Invalid vision_embeddings: {exc}", "request_id": request_id}), 400

    # Clinical input handling
    report_text = data.get("clinical_report")
    clinical_emb = data.get("clinical_embedding")

    # Resolve session vision timing from server registry or client payload
    session_id = str(data.get("session_id") or data.get("request_id") or request_id)
    server_vision_ms = 0.0
    registry_lookup = "NOT_FOUND"
    with _session_lock:
        sess = _analysis_session_timings.pop(session_id, None)
        if sess and sess.get("vision_ns", 0) > 0:
            server_vision_ms = round(sess["vision_ns"] / 1_000_000.0, 2)
            registry_lookup = "FOUND"

    client_vision_ms = 0.0
    for key in ("accumulated_vision_inference_ms", "vision_inference_ms"):
        if key in data and data.get(key) is not None:
            try:
                val = float(data.get(key) or 0.0)
                if math.isfinite(val) and val > 0.0:
                    client_vision_ms = val
                    break
            except (ValueError, TypeError):
                pass

    accumulated_vision_ms = server_vision_ms if server_vision_ms > 0.0 else client_vision_ms

    if (report_text is not None) == (clinical_emb is not None):
        return jsonify({"error": "Exactly one of 'clinical_report' or 'clinical_embedding' is required", "request_id": request_id}), 400

    report_sha256 = (
        hashlib.sha256(report_text.strip().encode("utf-8")).hexdigest()
        if isinstance(report_text, str) and report_text.strip()
        else (
            hashlib.sha256(str(clinical_emb[:10]).encode("utf-8")).hexdigest()
            if clinical_emb is not None
            else "none"
        )
    )
    is_sample_report = (
        isinstance(report_text, str)
        and report_text.strip() == SAMPLE_TCGA_REPORT_TEXT.strip()
    )

    clinical_text_ns = 0
    if clinical_emb is not None:
        if not isinstance(clinical_emb, list) or len(clinical_emb) != 768:
            return jsonify({"error": "'clinical_embedding' must be a list of 768 floats", "request_id": request_id}), 400
        try:
            clinical_tensor = torch.tensor(clinical_emb, dtype=torch.float32).unsqueeze(0)
            if not torch.isfinite(clinical_tensor).all():
                return jsonify({"error": "Clinical embedding contains non-finite values", "request_id": request_id}), 400
        except Exception as exc:
            return jsonify({"error": f"Invalid clinical embedding: {exc}", "request_id": request_id}), 400
    elif report_text is not None:
        if not isinstance(report_text, str) or not report_text.strip():
            return jsonify({"error": "Clinical report text must be a non-empty string", "request_id": request_id}), 400
        if clinical_encoder is None or clinical_encoder.model is None:
            return jsonify({"error": "ClinicalBERT runtime encoding unavailable", "request_id": request_id}), 501
        try:
            clinical_tensor, clinical_text_ns = clinical_encoder.encode_with_timing(report_text)
        except ValueError as ve:
            return jsonify({"error": str(ve), "request_id": request_id}), 400
        except Exception as e:
            return jsonify({"error": f"ClinicalBERT encoding failed: {str(e)}", "request_id": request_id}), 500
    else:
        return jsonify({"error": "No clinical input provided", "request_id": request_id}), 400

    # Run Sequence Cross-Attention Fusion
    try:
        fusion_device = next(fusion_model.parameters()).device
        if fusion_device.type == "cuda" and torch.cuda.is_available():
            torch.cuda.synchronize()

        t_fusion_start = time.perf_counter_ns()
        with torch.inference_mode():
            prob, attn_weights = fusion_model(vision_tensor, clinical_tensor)
            prob_val = float(prob.item())

        if fusion_device.type == "cuda" and torch.cuda.is_available():
            torch.cuda.synchronize()

        t_fusion_end = time.perf_counter_ns()
        fusion_ns = max(0, t_fusion_end - t_fusion_start)

        attention_list = _validate_and_extract_attention(attn_weights, expected_patch_count=vision_tensor.shape[0])
    except Exception as exc:
        return jsonify({"error": f"Fusion inference failed: {exc}", "request_id": request_id}), 500

    if not torch.isfinite(torch.tensor(prob_val)):
        return jsonify({"error": "Non-finite probability output", "request_id": request_id}), 500

    raw_logit = float(torch.logit(torch.tensor(prob_val)).item())
    checkpoint_sigmoid_present = (
        hasattr(fusion_model, "classifier")
        and len(fusion_model.classifier) > 0
        and isinstance(fusion_model.classifier[-1], torch.nn.Sigmoid)
    )
    threshold = 0.83
    predicted_class = "MSI-H" if prob_val >= threshold else "MSS"
    elapsed_ms = round((time.time() - t0) * 1000, 2)

    clinical_text_ms = round(clinical_text_ns / 1_000_000.0, 2)
    fusion_classification_ms = round(fusion_ns / 1_000_000.0, 2)
    total_model_inference_ms = round(accumulated_vision_ms + clinical_text_ms + fusion_classification_ms, 2)

    diag = {
        "event": "INFERENCE_BOUNDARY_DIAGNOSTIC",
        "endpoint": "/fuse",
        "request_id": request_id,
        "tile_count": int(vision_tensor.shape[0]),
        "decoded_tiles_count": int(vision_tensor.shape[0]),
        "image_dimensions": [224, 224, 3],
        "report_sha256": report_sha256,
        "is_sample_report": is_sample_report,
        "vision_shape": list(vision_tensor.shape),
        "clinical_shape": list(clinical_tensor.shape),
        "raw_logit_before_sigmoid": raw_logit,
        "checkpoint_sigmoid_present": checkpoint_sigmoid_present,
        "threshold": threshold,
        "probability": prob_val,
        "predicted_class": predicted_class,
        "total_model_inference_ms": total_model_inference_ms,
        "vision_inference_ms": round(accumulated_vision_ms, 2) if accumulated_vision_ms > 0 else None,
        "clinical_text_inference_ms": clinical_text_ms,
        "fusion_classification_ms": fusion_classification_ms,
        "inference_timing_scope": "forward_pass_accumulated",
        "latency_ms": elapsed_ms,
    }
    logger.info(
        "[STEP2_DIAGNOSTIC /fuse] %s",
        json.dumps({
            "received_session_id": session_id,
            "received_accumulated_vision_inference_ms": client_vision_ms,
            "backend_registry_lookup_result": registry_lookup,
            "retrieved_accumulated_vision_duration": server_vision_ms,
            "clinical_text_inference_ms": clinical_text_ms,
            "fusion_classification_ms": fusion_classification_ms,
            "total_model_inference_ms": total_model_inference_ms,
            "final_response_fields": {
                "request_id": request_id,
                "tile_count": int(vision_tensor.shape[0]),
                "predicted_class": predicted_class,
                "probability": prob_val,
                "total_model_inference_ms": total_model_inference_ms,
                "inference_latency_ms": total_model_inference_ms,
                "vision_inference_ms": round(accumulated_vision_ms, 2) if accumulated_vision_ms > 0 else None,
                "clinical_text_inference_ms": clinical_text_ms,
                "fusion_classification_ms": fusion_classification_ms
            }
        })
    )
    logger.info("[DIAGNOSTIC_BOUNDARY] %s", json.dumps(diag))

    return jsonify({
        "request_id": request_id,
        "probability": prob_val,
        "raw_logit": raw_logit,
        "checkpoint_sigmoid_present": checkpoint_sigmoid_present,
        "threshold": threshold,
        "predicted_class": predicted_class,
        "report_sha256": report_sha256,
        "is_sample_report": is_sample_report,
        "tile_count": int(vision_tensor.shape[0]),
        "vision_shape": list(vision_tensor.shape),
        "clinical_shape": list(clinical_tensor.shape),
        "attention_weights": attention_list,
        "session_id": session_id,
        "total_model_inference_ms": total_model_inference_ms,
        "inference_latency_ms": total_model_inference_ms,
        "vision_inference_ms": round(accumulated_vision_ms, 2) if accumulated_vision_ms > 0 else None,
        "clinical_text_inference_ms": clinical_text_ms,
        "fusion_classification_ms": fusion_classification_ms,
        "inference_timing_scope": "forward_pass_accumulated"
    })


@app.route("/predict", methods=["POST"])
@app.route("/predict/", methods=["POST"])
def predict():
    t0 = time.time()
    data = request.get_json(force=True)
    request_id = str(data.get("request_id") or uuid.uuid4())

    # -------------------------------------------------------------------
    # Input validation
    # -------------------------------------------------------------------
    patches: List[Image.Image] = []
    try:
        if data.get("synthetic", False):
            num = int(data.get("num_patches", 5))
            for _ in range(num):
                patches.append(Image.new("RGB", (224, 224), color=(128, 128, 128)))
        else:
            raw_patches = data.get("patches")
            if not isinstance(raw_patches, list) or len(raw_patches) == 0:
                return jsonify({"error": "'patches' must be a non-empty list of base64 strings", "request_id": request_id}), 400
            for b64 in raw_patches:
                patches.append(_decode_patch(b64))
    except Exception as exc:
        return jsonify({"error": str(exc), "request_id": request_id}), 400

    # Clinical input handling
    report_text = data.get("clinical_report")
    clinical_emb = data.get("clinical_embedding")

    report_sha256 = (
        hashlib.sha256(report_text.strip().encode("utf-8")).hexdigest()
        if isinstance(report_text, str) and report_text.strip()
        else (
            hashlib.sha256(str(clinical_emb[:10]).encode("utf-8")).hexdigest()
            if clinical_emb is not None
            else "none"
        )
    )
    is_sample_report = (
        isinstance(report_text, str)
        and report_text.strip() == SAMPLE_TCGA_REPORT_TEXT.strip()
    )

    clinical_text_ns = 0
    if data.get("synthetic", False) and report_text is None and clinical_emb is None:
        clinical_tensor = torch.zeros((1, 768), dtype=torch.float32)
    else:
        if (report_text is not None) == (clinical_emb is not None):
            return jsonify({"error": "Exactly one of 'clinical_report' or 'clinical_embedding' is required", "request_id": request_id}), 400

        if clinical_emb is not None:
            if not isinstance(clinical_emb, list) or len(clinical_emb) != 768:
                return jsonify({"error": "'clinical_embedding' must be a list of 768 floats", "request_id": request_id}), 400
            try:
                clinical_tensor = torch.tensor(clinical_emb, dtype=torch.float32).unsqueeze(0)
                if not torch.isfinite(clinical_tensor).all():
                    return jsonify({"error": "Clinical embedding contains non-finite values", "request_id": request_id}), 400
            except Exception as exc:
                return jsonify({"error": f"Invalid clinical embedding: {exc}", "request_id": request_id}), 400
        elif report_text is not None:
            if not isinstance(report_text, str) or not report_text.strip():
                return jsonify({"error": "Clinical report text must be a non-empty string", "request_id": request_id}), 400
            if clinical_encoder is None or clinical_encoder.model is None:
                return jsonify({"error": "ClinicalBERT runtime encoding unavailable", "request_id": request_id}), 501
            try:
                clinical_tensor, clinical_text_ns = clinical_encoder.encode_with_timing(report_text)
            except ValueError as ve:
                return jsonify({"error": str(ve), "request_id": request_id}), 400
            except Exception as e:
                return jsonify({"error": f"ClinicalBERT encoding failed: {str(e)}", "request_id": request_id}), 500
        else:
            return jsonify({"error": "No clinical input provided", "request_id": request_id}), 400

    # -------------------------------------------------------------------
    # Two-Stage Run: Stage 1 Batched Feature Extraction + Stage 2 Sequence Fusion
    # -------------------------------------------------------------------
    try:
        with torch.inference_mode():
            # Stage 1: Bounded batch extraction (25 tiles at a time with memory clearing)
            vision_tensor, vision_ns = extract_phikon_embeddings_batched(
                patches, batch_size=PHIKON_DEFAULT_BATCH_SIZE, return_timing=True
            )

        fusion_device = next(fusion_model.parameters()).device
        if fusion_device.type == "cuda" and torch.cuda.is_available():
            torch.cuda.synchronize()

        t_fusion_start = time.perf_counter_ns()
        with torch.inference_mode():
            # Stage 2: Sequence fusion over all N patches simultaneously
            prob, attn_weights = fusion_model(vision_tensor, clinical_tensor)
            prob_val = float(prob.item())

        if fusion_device.type == "cuda" and torch.cuda.is_available():
            torch.cuda.synchronize()

        t_fusion_end = time.perf_counter_ns()
        fusion_ns = max(0, t_fusion_end - t_fusion_start)

        # Attention weight validation
        attention_list = _validate_and_extract_attention(attn_weights, expected_patch_count=len(patches))
    except Exception as exc:
        return jsonify({"error": f"Inference failed: {exc}", "request_id": request_id}), 500

    if not torch.isfinite(torch.tensor(prob_val)):
        return jsonify({"error": "Non-finite probability output", "request_id": request_id}), 500

    raw_logit = float(torch.logit(torch.tensor(prob_val)).item())
    checkpoint_sigmoid_present = (
        hasattr(fusion_model, "classifier")
        and len(fusion_model.classifier) > 0
        and isinstance(fusion_model.classifier[-1], torch.nn.Sigmoid)
    )
    threshold = 0.83
    predicted_class = "MSI-H" if prob_val >= threshold else "MSS"
    elapsed_ms = round((time.time() - t0) * 1000, 2)

    vision_ms = round(vision_ns / 1_000_000.0, 2)
    clinical_text_ms = round(clinical_text_ns / 1_000_000.0, 2)
    fusion_classification_ms = round(fusion_ns / 1_000_000.0, 2)
    total_model_inference_ms = round((vision_ns + clinical_text_ns + fusion_ns) / 1_000_000.0, 2)

    diag = {
        "event": "INFERENCE_BOUNDARY_DIAGNOSTIC",
        "endpoint": "/predict",
        "request_id": request_id,
        "tile_count": len(patches),
        "decoded_tiles_count": len(patches),
        "image_dimensions": [224, 224, 3],
        "report_sha256": report_sha256,
        "is_sample_report": is_sample_report,
        "vision_shape": list(vision_tensor.shape),
        "clinical_shape": list(clinical_tensor.shape),
        "raw_logit_before_sigmoid": raw_logit,
        "checkpoint_sigmoid_present": checkpoint_sigmoid_present,
        "threshold": threshold,
        "probability": prob_val,
        "predicted_class": predicted_class,
        "total_model_inference_ms": total_model_inference_ms,
        "vision_inference_ms": vision_ms,
        "clinical_text_inference_ms": clinical_text_ms,
        "fusion_classification_ms": fusion_classification_ms,
        "inference_timing_scope": "forward_pass_accumulated",
        "latency_ms": elapsed_ms,
    }
    logger.info(
        "[STEP2_DIAGNOSTIC /predict] %s",
        json.dumps({
            "request_id": request_id,
            "actual_tile_count": len(patches),
            "vision_inference_ms": vision_ms,
            "clinical_text_inference_ms": clinical_text_ms,
            "fusion_classification_ms": fusion_classification_ms,
            "total_model_inference_ms": total_model_inference_ms,
            "probability": prob_val,
            "predicted_class": predicted_class
        })
    )
    logger.info("[DIAGNOSTIC_BOUNDARY] %s", json.dumps(diag))

    return jsonify({
        "request_id": request_id,
        "probability": prob_val,
        "raw_logit": raw_logit,
        "checkpoint_sigmoid_present": checkpoint_sigmoid_present,
        "threshold": threshold,
        "predicted_class": predicted_class,
        "report_sha256": report_sha256,
        "is_sample_report": is_sample_report,
        "tile_count": len(patches),
        "vision_shape": list(vision_tensor.shape),
        "clinical_shape": list(clinical_tensor.shape),
        "attention_weights": attention_list,
        "total_model_inference_ms": total_model_inference_ms,
        "inference_latency_ms": total_model_inference_ms,
        "vision_inference_ms": vision_ms,
        "clinical_text_inference_ms": clinical_text_ms,
        "fusion_classification_ms": fusion_classification_ms,
        "inference_timing_scope": "forward_pass_accumulated"
    })


@app.route("/generate_heatmap", methods=["POST"])
@app.route("/generate_heatmap/", methods=["POST"])
def generate_heatmap():
    """Generates a genuine Phikon-v2 ViT attention rollout heatmap for a candidate patch.

    Accepts:
        - "patch": base64-encoded JPEG/PNG string
    Returns:
        - "status": "ok"
        - "heatmap_base64": base64-encoded PNG image
        - "method": "Phikon-v2 ViT Attention Rollout (Recursive 6-Layer Product)"
        - "description": str
        - "dimensions": [width, height]
        - "panels": list of panel descriptions
    """
    if generate_smooth_vit_heatmap is None:
        return jsonify({"error": "ViT pixel XAI engine not available"}), 503

    data = request.get_json(force=True)
    raw_patch = data.get("patch")
    if not raw_patch or not isinstance(raw_patch, str):
        return jsonify({"error": "'patch' must be a non-empty base64 string"}), 400

    try:
        pil_img = _decode_patch(raw_patch)
    except Exception as exc:
        return jsonify({"error": f"Invalid base64 image: {exc}"}), 400

    run_id = str(uuid.uuid4())[:8]
    temp_dir = Path(tempfile.gettempdir())
    in_file = temp_dir / f"phikon_in_{run_id}.jpg"
    out_file = temp_dir / f"phikon_out_{run_id}.png"

    try:
        pil_img.save(str(in_file), format="JPEG")
        generated_path = generate_smooth_vit_heatmap(str(in_file), str(out_file))
        if not generated_path or not Path(generated_path).exists():
            return jsonify({"error": "Heatmap generation produced no output file"}), 500

        with Image.open(generated_path) as out_img:
            dimensions = [out_img.width, out_img.height]

        with open(generated_path, "rb") as f:
            hm_bytes = f.read()

        hm_b64 = base64.b64encode(hm_bytes).decode("utf-8")
        return jsonify({
            "status": "ok",
            "heatmap_base64": hm_b64,
            "method": "Phikon-v2 ViT Attention Rollout (Recursive 6-Layer Product)",
            "description": "Tissue-aware microscopic cellular attention map extracted from the last 6 self-attention layers of owkin/phikon-v2 (ViT-L/16).",
            "dimensions": dimensions,
            "panels": [
                "Darkened H&E Patch (0.45x)",
                "ROI Contours - Top 20% Tissue",
                "Dark Jet Tissue Overlay (alpha=0.68)"
            ]
        })
    except Exception as exc:
        logger.error(f"Heatmap generation failed: {exc}", exc_info=True)
        return jsonify({"error": f"Heatmap generation failed: {exc}"}), 500
    finally:
        try:
            if in_file.exists():
                in_file.unlink()
            if out_file.exists():
                out_file.unlink()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# LLM Clinical Scribe Configuration & Service
# ---------------------------------------------------------------------------
def _load_env_file():
    """Lightweight loader for .env file at repo root without third-party dependencies."""
    try:
        env_path = Path(__file__).resolve().parent.parent.parent / ".env"
        if env_path.is_file():
            with open(env_path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        k = k.strip()
                        v = v.strip().strip("'\"")
                        if k and k not in os.environ:
                            os.environ[k] = v
    except Exception as exc:
        logger.warning(f"Failed to load .env file: {exc}")

_load_env_file()


def get_llm_config() -> Dict[str, Any]:
    """Resolves active LLM configuration dynamically from environment with robust defaults."""
    provider = os.getenv("LLM_PROVIDER", "ollama").lower()
    ollama_url = os.getenv("OLLAMA_BASE_URL", os.getenv("OLLAMA_HOST", "http://127.0.0.1:11434")).rstrip("/")
    ollama_model = os.getenv("OLLAMA_MODEL", "gemma3:1b")
    try:
        ollama_timeout = int(os.getenv("OLLAMA_TIMEOUT", "120"))
    except ValueError:
        ollama_timeout = 120
    openai_url = os.getenv("OPENAI_BASE_URL", "").rstrip("/")
    openai_model = os.getenv("OPENAI_MODEL", "")
    openai_key = os.getenv("OPENAI_API_KEY", "")
    return {
        "provider": provider,
        "ollama_base_url": ollama_url,
        "ollama_model": ollama_model,
        "ollama_timeout": ollama_timeout,
        "openai_base_url": openai_url,
        "openai_model": openai_model,
        "openai_api_key": openai_key,
    }


LLM_PROVIDER = os.getenv("LLM_PROVIDER", "ollama").lower()
OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", os.getenv("OLLAMA_HOST", "http://127.0.0.1:11434")).rstrip("/")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "gemma3:1b")
OLLAMA_TIMEOUT = int(os.getenv("OLLAMA_TIMEOUT", "120"))

OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL", "").rstrip("/")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")


def _is_ollama_model_available(target_model: str, available_names: List[str]) -> bool:
    """Accurately checks whether the configured model is installed in Ollama."""
    target = target_model.strip()
    if not target:
        return False
    for name in available_names:
        # Exact match (e.g. 'gemma3:1b' == 'gemma3:1b')
        if name == target:
            return True
        # If target has no tag (e.g. 'gemma3'), match 'gemma3:latest' or any 'gemma3:*'
        if ":" not in target and (name == f"{target}:latest" or name.startswith(f"{target}:")):
            return True
        # If target has tag, match only if tag matches
        # e.g. 'gemma3:1b' matches 'gemma3:1b:latest' or 'registry/gemma3:1b'
        if ":" in target:
            target_tag = target.split(":")[-1]
            target_base = target.split(":")[0].split("/")[-1]
            if name.endswith(f":{target_tag}") and target_base in name:
                return True
    return False


def check_llm_health() -> Dict[str, Any]:
    """Checks whether the configured LLM service is online, reachable, and has the model loaded."""
    cfg = get_llm_config()
    provider = cfg["provider"]
    ollama_url = cfg["ollama_base_url"]
    ollama_model = cfg["ollama_model"]

    if provider == "ollama":
        try:
            resp = requests.get(f"{ollama_url}/api/tags", timeout=3)
            if resp.status_code == 200:
                data = resp.json()
                raw_models = data.get("models", [])
                available_names = [m.get("name", "") for m in raw_models]
                model_found = _is_ollama_model_available(ollama_model, available_names)
                if model_found:
                    return {
                        "status": "ready",
                        "provider": "ollama",
                        "base_url": ollama_url,
                        "model": ollama_model,
                        "model_available": True,
                        "available_models": available_names,
                        "error": None,
                        "setup_instructions": []
                    }
                else:
                    return {
                        "status": "model_missing",
                        "provider": "ollama",
                        "base_url": ollama_url,
                        "model": ollama_model,
                        "model_available": False,
                        "available_models": available_names,
                        "error": f"Ollama is running at {ollama_url}, but model '{ollama_model}' is not pulled yet.",
                        "setup_instructions": [
                            f"Run 'ollama pull {ollama_model}' in terminal to download model weights."
                        ]
                    }
            else:
                return {
                    "status": "unhealthy",
                    "provider": "ollama",
                    "base_url": ollama_url,
                    "model": ollama_model,
                    "model_available": False,
                    "available_models": [],
                    "error": f"Ollama returned HTTP {resp.status_code}",
                    "setup_instructions": ["Check Ollama service status."]
                }
        except Exception as exc:
            return {
                "status": "unreachable",
                "provider": "ollama",
                "base_url": ollama_url,
                "model": ollama_model,
                "model_available": False,
                "available_models": [],
                "error": f"Cannot connect to Ollama at {ollama_url}: {str(exc)}",
                "setup_instructions": [
                    "1. Download and install Ollama from https://ollama.com/download",
                    "2. Launch Ollama in terminal or background: 'ollama serve'",
                    f"3. Download the research model: 'ollama pull {ollama_model}'",
                    f"4. Confirm availability at {ollama_url}/api/tags"
                ]
            }
    elif provider in ("openai_compatible", "openai"):
        openai_url = cfg["openai_base_url"]
        openai_model = cfg["openai_model"]
        if not openai_url or not openai_model:
            return {
                "status": "unconfigured",
                "provider": provider,
                "base_url": openai_url,
                "model": openai_model,
                "model_available": False,
                "available_models": [],
                "error": "OPENAI_BASE_URL and OPENAI_MODEL environment variables must be configured.",
                "setup_instructions": ["Set OPENAI_BASE_URL and OPENAI_MODEL environment variables."]
            }
        return {
            "status": "ready",
            "provider": provider,
            "base_url": openai_url,
            "model": openai_model,
            "model_available": True,
            "available_models": [openai_model],
            "error": None,
            "setup_instructions": []
        }
    else:
        return {
            "status": "disabled",
            "provider": provider,
            "base_url": "",
            "model": "none",
            "model_available": False,
            "available_models": [],
            "error": f"LLM provider '{provider}' disabled. Operating in deterministic template fallback mode.",
            "setup_instructions": ["Set LLM_PROVIDER=ollama to enable local LLM."]
        }


@app.route("/llm_status", methods=["GET"])
@app.route("/llm_status/", methods=["GET"])
def llm_status():
    """Diagnostic endpoint reporting availability, health, and setup instructions for the LLM scribe."""
    return jsonify(check_llm_health())


# ---------------------------------------------------------------------------
# Clinical Scribe Helper Functions & Safety Validation
# ---------------------------------------------------------------------------

def _format_probability(prob_val: float) -> str:
    """Formats estimated MSI probability safely handling [0, 1] float and [0, 100] percentage inputs.

    Avoids accidental double scaling and formats small values accurately (e.g. 0.0014 -> 0.14%).
    """
    pct = prob_val * 100.0 if 0.0 <= prob_val <= 1.0 else prob_val
    pct = round(pct, 4)
    if pct == 0.0:
        return "0.0%"
    elif 0.0 < pct < 0.1:
        return f"{pct:.2f}%"
    elif 0.1 <= pct < 1.0:
        formatted = f"{pct:.2f}%"
        return formatted if not formatted.endswith("0%") else f"{pct:.1f}%"
    else:
        return f"{pct:.1f}%"


def _extract_clinical_findings(clinical_notes: str) -> Dict[str, str]:
    """Extracts documented pathology fields from unstructured report text without fabricating missing data."""
    text = clinical_notes.strip()
    text_lower = text.lower()

    fields = {
        "primary_site": "Not stated in supplied report",
        "histology": "Not stated in supplied report",
        "differentiation": "Not stated in supplied report",
        "extension_stage": "Not stated in supplied report",
        "margins": "Not stated in supplied report",
        "lymph_nodes": "Not stated in supplied report",
        "peritoneal": "Not stated in supplied report",
        "other_findings": "None documented"
    }

    # 1. Primary site
    sites = [
        ("colon, ascending", "Ascending colon"), ("ascending colon", "Ascending colon"),
        ("colon, sigmoid", "Sigmoid colon"), ("sigmoid colon", "Sigmoid colon"),
        ("colon, transverse", "Transverse colon"), ("transverse colon", "Transverse colon"),
        ("colon, descending", "Descending colon"), ("descending colon", "Descending colon"),
        ("colon, cecum", "Cecum"), ("cecum", "Cecum"),
        ("hepatic flexure", "Hepatic flexure"), ("splenic flexure", "Splenic flexure"),
        ("rectosigmoid", "Rectosigmoid"), ("rectum", "Rectum"),
        ("colon", "Colon (subsite unspecified)")
    ]
    for s_low, s_name in sites:
        if s_low in text_lower:
            fields["primary_site"] = s_name
            break

    # 2. Histology
    if "mucinous adenocarcinoma" in text_lower or "mucinous carcinoma" in text_lower:
        fields["histology"] = "Mucinous adenocarcinoma"
    elif "signet ring" in text_lower:
        fields["histology"] = "Signet ring cell carcinoma"
    elif "adenocarcinoma" in text_lower:
        fields["histology"] = "Adenocarcinoma"
    elif "carcinoma" in text_lower:
        fields["histology"] = "Carcinoma"

    # 3. Differentiation
    if "poorly differentiated" in text_lower or "high grade" in text_lower:
        fields["differentiation"] = "Poorly differentiated (High grade)"
    elif "moderately differentiated" in text_lower or "intermediate grade" in text_lower:
        fields["differentiation"] = "Moderately differentiated"
    elif "well differentiated" in text_lower or "low grade" in text_lower:
        fields["differentiation"] = "Well differentiated (Low grade)"
    elif "undifferentiated" in text_lower:
        fields["differentiation"] = "Undifferentiated"

    # 4. Tumour extension / pathological stage
    stage_match = re.search(r"\b(p?t[0-4][a-c]?\s*(?:p?n[0-2][a-c]?)?(?:\s*p?m[0-1][a-c]?)?)\b", text, re.IGNORECASE)
    invasion_match = re.search(r"invasion\s+(?:of|through|into)\s+([^.]+)", text, re.IGNORECASE)
    if stage_match and invasion_match:
        fields["extension_stage"] = f"{stage_match.group(1).upper()} ({invasion_match.group(0).strip()})"
    elif stage_match:
        fields["extension_stage"] = stage_match.group(1).upper()
    elif invasion_match:
        fields["extension_stage"] = invasion_match.group(0).strip().capitalize()

    # 5. Resection margins
    margin_clause = None
    for part in re.split(r"[.\n]", text):
        if "margin" in part.lower():
            margin_clause = part.strip()
            break
    if margin_clause:
        m_low = margin_clause.lower()
        if any(term in m_low for term in ["negative", "tumor-free", "clear", "not involved", "free of tumor"]):
            fields["margins"] = margin_clause
        elif any(term in m_low for term in ["positive", "involved"]):
            fields["margins"] = margin_clause
        else:
            fields["margins"] = margin_clause

    # 6. Lymph node findings
    ln_match = re.search(r"\b(\d+)\s*(?:of|/)\s*(\d+)\b\s*(?:regional\s+)?lymph\s+nodes?", text, re.IGNORECASE)
    if not ln_match:
        ln_match = re.search(r"(?:regional\s+)?lymph\s+nodes?.*?\b(\d+)\s*(?:of|/)\s*(\d+)\b", text, re.IGNORECASE)
    if ln_match:
        num_pos = int(ln_match.group(1))
        total_nodes = int(ln_match.group(2))
        ratio = f"{num_pos}/{total_nodes}"
        if num_pos == 0:
            fields["lymph_nodes"] = f"{ratio} positive for metastasis ({total_nodes} nodes examined, 0 positive; no nodal metastasis)"
        else:
            fields["lymph_nodes"] = f"{ratio} involved by metastatic carcinoma ({num_pos} of {total_nodes} nodes positive)"
    elif "lymph node" in text_lower:
        ln_text = re.search(r"([^.]*lymph\s+nodes?[^.]*)", text, re.IGNORECASE)
        if ln_text:
            fields["lymph_nodes"] = ln_text.group(1).strip()

    # 7. Peritoneal involvement
    if "peritoneal" in text_lower or "peritoneum" in text_lower:
        p_match = re.search(r"([^.]*peritone[^.]*)", text, re.IGNORECASE)
        if p_match:
            fields["peritoneal"] = p_match.group(1).strip()

    # 8. Other findings (ulceration, specimen prep, LVI, PNI)
    others = []
    if "ulcerated" in text_lower:
        others.append("Ulcerated lesion")
    if "hemicolectomy" in text_lower:
        others.append("Hemicolectomy resection specimen")
    if "perineural invasion" in text_lower:
        pni = re.search(r"([^.]*perineural\s+invasion[^.]*)", text, re.IGNORECASE)
        if pni: others.append(pni.group(1).strip())
    if "lymphovascular invasion" in text_lower:
        lvi = re.search(r"([^.]*lymphovascular\s+invasion[^.]*)", text, re.IGNORECASE)
        if lvi: others.append(lvi.group(1).strip())
    if others:
        fields["other_findings"] = "; ".join(others)

    return fields


def _build_structured_summary_markdown(
    case_id: str,
    specimen_source: str,
    generation_status: str,
    clinical_notes: str,
    confidence: float,
    is_msi: bool,
    top_scores: Optional[List[float]] = None
) -> str:
    """Generates the concise, structured 5-section table-based report according to research app specifications."""
    fields = _extract_clinical_findings(clinical_notes)
    formatted_prob = _format_probability(confidence)
    predicted_class = "MSI-High (Microsatellite Instability)" if is_msi else "MSS (Microsatellite Stable)"

    md = [
        "### A. Case Overview",
        f"- **Case Identifier:** {case_id}",
        f"- **Specimen Source & Staining:** {specimen_source}",
        f"- **Summary Generation Status:** {generation_status}",
        "",
        "### B. Documented Clinical Findings",
        "| Documented Parameter | Source Finding |",
        "|---|---|",
        f"| Primary Site | {fields['primary_site']} |",
        f"| Histology | {fields['histology']} |",
        f"| Tumour Differentiation | {fields['differentiation']} |",
        f"| Tumour Extension / Pathological Stage | {fields['extension_stage']} |",
        f"| Resection Margins | {fields['margins']} |",
        f"| Lymph-Node Findings | {fields['lymph_nodes']} |",
        f"| Peritoneal Involvement | {fields['peritoneal']} |",
        f"| Other Documented Findings | {fields['other_findings']} |",
        "",
        "### C. Computational Multimodal Prediction",
        "| Metric | Research Finding |",
        "|---|---|",
        f"| Predicted MSI/MSS Class | {predicted_class} |",
        f"| Estimated MSI Probability | {formatted_prob} |",
        "| Research Classification Threshold | tau = 0.83 (configured decision threshold) |",
        "| Interpretation Status | Computational research output; not a confirmed diagnosis |",
        "",
        "Note: The estimated MSI probability reflects computational multimodal query-key affinity and is not a measure of confidence in the predicted class. The threshold tau = 0.83 is a configured research parameter, not a clinically validated cut-off.",
        "",
        "### D. Top Cross-Attention Scores",
        "| Tile Rank | Attention Weight |",
        "|---|---:|"
    ]

    if top_scores and len(top_scores) > 0:
        top_n = sorted(top_scores, reverse=True)[:5]
        for i, s in enumerate(top_n):
            md.append(f"| Tile #{i+1} | {s:.4f} |")
    else:
        md.append("| Tile-level attention | Not available |")

    md.extend([
        "",
        "Note: Attention weights describe computational emphasis across input tiles and do not independently identify histological features or anatomical locations.",
        "",
        "### E. Limitations & Verification Disclaimer",
        "This is an investigational research result, not a confirmed clinical diagnosis. MSI/MSS status requires appropriate independent verification by qualified professionals."
    ])

    return "\n".join(md)


# Prohibited terms for summary validation to prevent medical advice and spatial hallucinations
PROHIBITED_TREATMENT_TERMS = [
    "pembrolizumab", "nivolumab", "ipilimumab", "dostarlimab",
    "checkpoint inhibitor", "checkpoint inhibition", "anti-pd", "anti-ctla",
    "prescribe", "chemotherapy regimen", "adjuvant chemo", "adjuvant therapy",
    "recommend treatment", "recommended treatment", "treatment regimen",
    "first-line", "second-line"
]

PROHIBITED_SPATIAL_AND_GENOMIC_TERMS = [
    "seven parietal layers", "7 parietal layers",
    "attention was directed to parietal", "attention directed to parietal",
    "attention directed toward parietal", "attention focused on parietal",
    "attention on parietal layers",
    "tile labeled", "tile named",
    "specifically, the tumor core", "specifically, the ulcerated",
    "represents the tumor", "representing the tumor", "representing the adenocarcinoma",
    "genomic characteristics", "tumor's genomic", "tumour's genomic",
    "stable genetic profile", "stable architecture", "stable tissue architecture",
    "reduction in the likelihood of the specimen being a highly aggressive"
]

PROHIBITED_CONFIDENCE_TERMS = [
    "confidence in the predicted", "confidence in the mss", "confidence in this prediction",
    "degree of confidence", "measures confidence", "confidence score", "model's confidence"
]


def _validate_summary_output(
    summary: str,
    clinical_notes: str = "",
    is_msi: bool = False,
    confidence: float = 0.5,
    top_scores: Optional[List[float]] = None
) -> Tuple[bool, Optional[str]]:
    """Validates generated summary for length, evidence grounding, absence of treatment advice,

    absence of spatial/genomic hallucinations, and factual agreement with supplied clinical report.
    """
    if not summary or len(summary.strip()) < 100:
        return False, "Summary too short or empty"

    summary_lower = summary.lower()
    clinical_lower = clinical_notes.lower() if clinical_notes else ""

    # 1. Prohibited treatment / immunotherapy advice
    for term in PROHIBITED_TREATMENT_TERMS:
        if term in summary_lower:
            return False, f"Prohibited treatment/immunotherapy recommendation detected: '{term}'"

    # 2. Prohibited spatial / anatomical / genomic attributions
    for term in PROHIBITED_SPATIAL_AND_GENOMIC_TERMS:
        if term in summary_lower:
            # Allow legitimate negative disclaimer statements like "do not represent ... genomic characteristics"
            if "genomic" in term and ("do not represent" in summary_lower or "does not represent" in summary_lower or "not represent" in summary_lower):
                continue
            return False, f"Unsupported spatial/anatomical/genomic attribution detected: '{term}'"

    # 3. Prohibited confidence framing of probability or threshold
    for term in PROHIBITED_CONFIDENCE_TERMS:
        if term in summary_lower:
            # Allow legitimate cautionary statements stating it is NOT a measure of confidence
            if ("not a measure of" in summary_lower or "does not measure" in summary_lower or "not confidence" in summary_lower or "is not confidence" in summary_lower) and f"not a measure of {term}" in summary_lower:
                continue
            if "not a measure of confidence in the predicted class" in summary_lower and term == "confidence in the predicted":
                continue
            return False, f"Unsupported confidence framing detected: '{term}'"

    # 4. Check margin claims against clinical notes
    if "tumor-free" in clinical_lower or "margins are negative" in clinical_lower or "margins negative" in clinical_lower:
        if re.search(r"margins?\s+.*(?:contain|harbor|exhibit|show|positive|with)\s+.*(?:metast|carcinoma|tumor|cancer)", summary_lower) or "margins also contain" in summary_lower or "margins contain" in summary_lower:
            return False, "Hallucinated margin metastases when input report states margins are tumor-free"

    # 5. Check lymph node claims against clinical notes
    if "0/14" in clinical_lower or "0/" in clinical_lower or "negative for metastasis" in clinical_lower:
        if re.search(r"(?:potential\s+lymph\s+node\s+involvement|multiple\s+(?:regional\s+)?lymph\s+node\s+metastases|evidence\s+of\s+nodal\s+metastasis)", summary_lower):
            return False, "Hallucinated lymph node involvement when input report indicates zero positive nodes"

    # 6. Class consistency check
    if not is_msi and ("msi-high" in summary_lower or "high microsatellite instability" in summary_lower):
        return False, "Contradictory class label: MSI-High asserted for an MSS prediction"
    if is_msi and ("microsatellite stable" in summary_lower or " mss " in f" {summary_lower} "):
        return False, "Contradictory class label: MSS asserted for an MSI prediction"

    # 7. Structured sections check
    required_sections = ["clinical findings", "computational", "attention", "disclaimer"]
    for sec in required_sections:
        if sec not in summary_lower:
            return False, f"Missing required structured section: '{sec}'"

    # 8. Table syntax check (must contain Markdown table delimiters)
    if "|" not in summary:
        return False, "Summary missing required structured table formatting"

    # 9. Cross-attention rows validation against verified top_scores
    if top_scores is not None:
        max_expected_rows = min(len(top_scores), 5)
        # Find all tile rows in Section D
        tile_rows = re.findall(r"\|\s*Tile\s*#?(\d+)\s*\|\s*([0-9.]+)\s*\|", summary, re.IGNORECASE)
        if max_expected_rows == 0:
            if tile_rows:
                return False, "Fabricated tile attention rows when no attention scores are available"
            if "not available" not in summary_lower:
                return False, "Missing 'Not available' statement for empty attention scores"
        else:
            if len(tile_rows) > max_expected_rows:
                return False, f"Fabricated extra tile attention rows: summary contains {len(tile_rows)} rows, but model produced only {max_expected_rows} tile(s)"
            # Verify numerical values roughly match verified top_scores
            sorted_expected = sorted(top_scores, reverse=True)[:max_expected_rows]
            for idx, (_, val_str) in enumerate(tile_rows):
                try:
                    val = float(val_str)
                    exp_val = sorted_expected[idx]
                    if abs(val - exp_val) > 0.05:
                        return False, f"Attention score mismatch at row {idx+1}: got {val}, expected {exp_val:.4f}"
                except ValueError:
                    return False, f"Malformed attention score in row {idx+1}: {val_str}"

    return True, None


def _build_clinical_scribe_prompt(
    confidence: float,
    is_msi: bool,
    clinical_notes: str,
    top_scores: Optional[List[float]] = None,
    case_id: str = "Research Case",
    specimen_source: str = "Surgical Resection (H&E)"
) -> str:
    """Builds a structured clinical safety prompt enforcing strict evidence grounding,

    no treatment recommendations, no spatial/anatomical/genomic hallucinations, and research disclaimer.
    """
    formatted_prob = _format_probability(confidence)
    predicted_class = "MSI-High (Microsatellite Instability)" if is_msi else "MSS (Microsatellite Stable)"

    top_scores_str = ""
    if top_scores and len(top_scores) > 0:
        top_n = sorted(top_scores, reverse=True)[:5]
        top_scores_str = ", ".join(f"Tile #{i+1}: {s:.4f}" for i, s in enumerate(top_n))
        section_d_rows = "\n".join(f"| Tile #{i+1} | {s:.4f} |" for i, s in enumerate(top_n))
        tile_constraint = (
            f"There are exactly {len(top_n)} valid tile(s) for this case. "
            f"Section D must list EXACTLY these {len(top_n)} row(s). "
            f"NEVER invent, pad, extrapolate, or fabricate additional tile rows. "
            f"If only 1 tile is provided, output EXACTLY 1 row in Section D; do NOT pad to 5 rows."
        )
    else:
        top_scores_str = "Not available"
        section_d_rows = "| Tile-level attention | Not available |"
        tile_constraint = (
            "Tile-level attention scores are not available for this case. "
            "Section D must state '| Tile-level attention | Not available |'. Do not invent numbers."
        )

    # Truncate clinical notes safely to 4000 characters to preserve full pathology resection text
    safe_clinical_notes = clinical_notes[:4000].strip()
    fields = _extract_clinical_findings(safe_clinical_notes)

    return (
        "You are an AI research assistant compiling a structured clinical research summary for a computational pathology study. "
        "Generate the summary using concise Markdown tables and short explanatory notes adhering strictly to the verified inputs below. "
        "Do not invent clinical findings, do not invent tile names or anatomical locations, and do not provide treatment advice.\n\n"
        "[VERIFIED CASE INPUTS]\n"
        f"- Case Identifier: {case_id}\n"
        f"- Specimen Source & Staining: {specimen_source}\n"
        f"- Primary Site: {fields['primary_site']}\n"
        f"- Histology: {fields['histology']}\n"
        f"- Tumour Differentiation: {fields['differentiation']}\n"
        f"- Tumour Extension / Pathological Stage: {fields['extension_stage']}\n"
        f"- Resection Margins: {fields['margins']}\n"
        f"- Lymph-Node Findings: {fields['lymph_nodes']}\n"
        f"- Peritoneal Involvement: {fields['peritoneal']}\n"
        f"- Other Documented Findings: {fields['other_findings']}\n"
        f"- Pathology Report Text: {safe_clinical_notes}\n"
        f"- Model Prediction: {predicted_class}\n"
        f"- Estimated MSI Probability: {formatted_prob}\n"
        f"- Research Decision Threshold: tau = 0.83 (configured research cut-off; not clinically validated)\n"
        f"- Model Architecture: SummaryToPatchCrossAttention (Bio_ClinicalBERT 768-D + Phikon-v2 ViT-L 1024-D, 8 attention heads)\n"
        f"- Numerical Tile Attention Scores: {top_scores_str}\n\n"
        "[STRICT CONSTRAINTS]\n"
        "1. FACTUAL GROUNDING: Extract only facts directly stated in the Pathology Report Text. "
        "If a parameter (such as stage or peritoneal involvement) is not stated, report 'Not stated in supplied report'. "
        "If margins are tumor-free or negative, state they are tumor-free; NEVER claim or imply that margins contain metastases. "
        "If regional lymph nodes are 0/X positive or negative, state there is no nodal metastasis; NEVER claim potential lymph node involvement or multiple metastases. "
        "Do not describe moderate differentiation as proof of an aggressive tumor.\n"
        f"2. NO TILE LABELS OR FABRICATED ROWS: {tile_constraint} "
        "Tiles are unlabeled computational image patches without anatomical or histological names. "
        "Never associate attention scores with tumor core, margins, anatomical layers, or genomic characteristics. "
        "Refer to them ONLY by rank ('Tile #1', 'Tile #2').\n"
        "3. NO MEDICAL TREATMENT ADVICE: Absolutely do NOT recommend any treatment, surgery, chemotherapy, or immunotherapy (such as pembrolizumab, nivolumab, or checkpoint inhibitors).\n"
        f"4. PROBABILITY SEMANTICS & NO CONFIDENCE LABELS: State the estimated MSI probability as {formatted_prob} relative to research decision threshold tau = 0.83. "
        "Do NOT label the threshold or probability as confidence, uncertainty, or diagnostic certainty. Do NOT describe MSS as a 'stable genetic profile', 'stable architecture', or confirmed genomic status.\n"
        "5. RESEARCH DISCLAIMER: Explicitly state that this is an investigational computational prediction, not a clinical diagnosis. Pathologist review and confirmatory IHC/PCR testing are mandatory.\n"
        "6. NO CONVERSATIONAL FILLER: Output ONLY the 5 Markdown sections. Do NOT start with greetings or preambles (e.g., 'Okay, here is...'). Do NOT end with postambles or offers to refine. Start directly with '### A. Case Overview' and end after Section E.\n\n"
        "[REQUIRED OUTPUT FORMAT — STRUCTURED SECTIONS & TABLES]\n"
        "### A. Case Overview\n"
        f"- **Case Identifier:** {case_id}\n"
        f"- **Specimen Source & Staining:** {specimen_source}\n"
        "- **Summary Generation Status:** LLM Scribe Synthesis\n\n"
        "### B. Documented Clinical Findings\n"
        "| Documented Parameter | Source Finding |\n"
        "|---|---|\n"
        f"| Primary Site | {fields['primary_site']} |\n"
        f"| Histology | {fields['histology']} |\n"
        f"| Tumour Differentiation | {fields['differentiation']} |\n"
        f"| Tumour Extension / Pathological Stage | {fields['extension_stage']} |\n"
        f"| Resection Margins | {fields['margins']} |\n"
        f"| Lymph-Node Findings | {fields['lymph_nodes']} |\n"
        f"| Peritoneal Involvement | {fields['peritoneal']} |\n"
        f"| Other Documented Findings | {fields['other_findings']} |\n\n"
        "### C. Computational Multimodal Prediction\n"
        "| Metric | Research Finding |\n"
        "|---|---|\n"
        f"| Predicted MSI/MSS Class | {predicted_class} |\n"
        f"| Estimated MSI Probability | {formatted_prob} |\n"
        "| Research Classification Threshold | tau = 0.83 (configured decision threshold) |\n"
        "| Interpretation Status | Computational research output; not a confirmed diagnosis |\n\n"
        "Note: The estimated MSI probability reflects computational multimodal query-key affinity and is not a measure of confidence in the predicted class. The threshold tau = 0.83 is a configured research parameter, not a clinically validated cut-off.\n\n"
        "### D. Top Cross-Attention Scores\n"
        "| Tile Rank | Attention Weight |\n"
        "|---|---:|\n"
        f"{section_d_rows}\n\n"
        "Note: Attention weights describe computational emphasis across input tiles and do not independently identify histological features or anatomical locations.\n\n"
        "### E. Limitations & Verification Disclaimer\n"
        "This is an investigational research result, not a confirmed clinical diagnosis. MSI/MSS status requires appropriate independent verification by qualified professionals."
    )


def _clean_llm_markdown(raw_text: str) -> str:
    """Strips conversational preambles before Section A and conversational postambles after Section E."""
    if not raw_text:
        return ""
    lines = raw_text.splitlines()
    start_idx = 0
    # Find start of Section A
    for idx, l in enumerate(lines):
        clean = l.strip().lstrip("#* -").lower()
        if clean.startswith("a. case overview") or clean.startswith("case overview") or "section a" in clean:
            start_idx = idx
            break

    # Find end of Section E (keep Section E and its disclaimer, drop trailing postamble)
    end_idx = len(lines)
    seen_section_e = False
    for idx in range(start_idx, len(lines)):
        clean = lines[idx].strip().lstrip("#* -").lower()
        if "section e" in clean or "limitations" in clean or clean.startswith("e. limitations"):
            seen_section_e = True
            continue
        if seen_section_e:
            trimmed = lines[idx].strip()
            if trimmed.startswith("---") or trimmed.lower().startswith("let me know") or trimmed.lower().startswith("feel free"):
                end_idx = idx
                break

    cleaned = "\n".join(lines[start_idx:end_idx]).strip()
    return cleaned if cleaned else raw_text.strip()


def _generate_deterministic_template(
    confidence: float,
    is_msi: bool,
    clinical_notes: str,
    top_scores: Optional[List[float]] = None,
    error_detail: Optional[str] = None,
    case_id: str = "Research Case",
    specimen_source: str = "Surgical Resection (H&E)"
) -> Dict[str, Any]:
    """Generates structured table-based deterministic fallback summary adhering to clinical grounding."""
    gen_status = "Deterministic Template Fallback (Validation Fallback)" if error_detail else "Deterministic Template Fallback (Ollama Offline)"
    summary = _build_structured_summary_markdown(
        case_id=case_id,
        specimen_source=specimen_source,
        generation_status=gen_status,
        clinical_notes=clinical_notes,
        confidence=confidence,
        is_msi=is_msi,
        top_scores=top_scores
    )
    model_label = "Deterministic Clinical Scribe (Validation Fallback)" if error_detail else "Deterministic Clinical Scribe (Ollama Offline)"
    return {
        "summary": summary,
        "source": "deterministic_template_fallback",
        "model_used": model_label,
        "is_llm": False,
        "error_detail": error_detail
    }


def _generate_clinical_summary_text(
    confidence: float,
    is_msi: bool,
    clinical_notes: str,
    top_scores: Optional[List[float]] = None,
    allow_fallback: bool = True,
    case_id: str = "Research Case",
    specimen_source: str = "Surgical Resection (H&E)"
) -> Dict[str, Any]:
    """Generates structured table-based clinical research summary (Ollama LLM or Deterministic Scribe)."""
    cfg = get_llm_config()
    provider = cfg["provider"]
    ollama_url = cfg["ollama_base_url"]
    ollama_model = cfg["ollama_model"]
    ollama_timeout = cfg["ollama_timeout"]
    openai_url = cfg["openai_base_url"]
    openai_model = cfg["openai_model"]
    openai_key = cfg["openai_api_key"]

    prompt = _build_clinical_scribe_prompt(
        confidence=confidence,
        is_msi=is_msi,
        clinical_notes=clinical_notes,
        top_scores=top_scores,
        case_id=case_id,
        specimen_source=specimen_source
    )
    last_err: Optional[str] = None

    # 1. Try Ollama LLM provider if configured
    if provider == "ollama":
        try:
            resp = requests.post(
                f"{ollama_url}/api/generate",
                json={"model": ollama_model, "prompt": prompt, "stream": False},
                timeout=ollama_timeout,
            )
            if resp.ok and "response" in resp.json():
                raw_text = resp.json()["response"].strip()
                if raw_text:
                    cleaned_text = _clean_llm_markdown(raw_text)
                    is_valid, val_err = _validate_summary_output(
                        summary=cleaned_text,
                        clinical_notes=clinical_notes,
                        is_msi=is_msi,
                        confidence=confidence,
                        top_scores=top_scores
                    )
                    if is_valid:
                        return {
                            "summary": cleaned_text,
                            "source": "ollama_llm",
                            "model_used": f"{ollama_model} (Ollama LLM)",
                            "is_llm": True,
                            "error_detail": None
                        }
                    else:
                        logger.warning(f"Ollama output rejected by safety validator: {val_err}")
                        last_err = f"Safety validator rejected LLM output: {val_err}"
                else:
                    last_err = "Ollama returned empty response text"
            else:
                last_err = f"Ollama HTTP {resp.status_code}: {resp.text}"
        except Exception as exc:
            logger.warning(f"Ollama generation failed or unreachable: {exc}")
            last_err = str(exc)
    elif provider in ("openai_compatible", "openai") and openai_url:
        try:
            headers = {"Content-Type": "application/json"}
            if openai_key:
                headers["Authorization"] = f"Bearer {openai_key}"
            resp = requests.post(
                f"{openai_url}/chat/completions",
                headers=headers,
                json={
                    "model": openai_model,
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": 0.2
                },
                timeout=ollama_timeout,
            )
            if resp.ok:
                choice_text = resp.json()["choices"][0]["message"]["content"].strip()
                if choice_text:
                    cleaned_text = _clean_llm_markdown(choice_text)
                    is_valid, val_err = _validate_summary_output(
                        summary=cleaned_text,
                        clinical_notes=clinical_notes,
                        is_msi=is_msi,
                        confidence=confidence,
                        top_scores=top_scores
                    )
                    if is_valid:
                        return {
                            "summary": cleaned_text,
                            "source": "other_llm_provider",
                            "model_used": f"{openai_model} (Remote LLM)",
                            "is_llm": True,
                            "error_detail": None
                        }
                    else:
                        logger.warning(f"Remote LLM output rejected by safety validator: {val_err}")
                        last_err = f"Safety validator rejected remote LLM output: {val_err}"
                else:
                    last_err = "Remote LLM returned empty choice content"
            else:
                last_err = f"Remote LLM HTTP {resp.status_code}: {resp.text}"
        except Exception as exc:
            logger.warning(f"OpenAI-compatible LLM failed: {exc}")
            last_err = str(exc)
    else:
        last_err = f"LLM provider '{provider}' not active"

    if not allow_fallback:
        raise RuntimeError(f"LLM generation failed and fallback disallowed: {last_err}")

    # 2. Deterministic template fallback strictly compliant with safety rules
    return _generate_deterministic_template(
        confidence=confidence,
        is_msi=is_msi,
        clinical_notes=clinical_notes,
        top_scores=top_scores,
        error_detail=last_err,
        case_id=case_id,
        specimen_source=specimen_source
    )


@app.route("/generate_summary", methods=["POST"])
@app.route("/generate_summary/", methods=["POST"])
def generate_summary():
    """Generates an AI diagnostic clinical summary for the analyzed research case.

    Accepts:
        - "confidence": float (0..100)
        - "is_msi": bool
        - "clinical_report": string
        - "top_scores": optional list of floats
        - "allow_fallback": optional bool (default True)
        - "case_id": optional string (default "Research Case")
        - "specimen_source": optional string (default "Surgical Resection (H&E)")

    Returns:
        - "status": "ok"
        - "summary": string
        - "source": "ollama_llm" | "other_llm_provider" | "deterministic_template_fallback"
        - "model_used": string
        - "is_llm": bool
        - "error_detail": string or null
    """
    data = request.get_json(force=True)
    confidence = float(data.get("confidence", 50.0))
    is_msi = bool(data.get("is_msi", False))
    clinical_report = data.get("clinical_report", "")
    top_scores = data.get("top_scores")
    allow_fallback = bool(data.get("allow_fallback", True))
    case_id = str(data.get("case_id") or "Research Case")
    specimen_source = str(data.get("specimen_source") or "Surgical Resection (H&E)")

    if not isinstance(clinical_report, str) or not clinical_report.strip():
        return jsonify({"error": "'clinical_report' must be a non-empty string"}), 400

    start_time = time.time()
    num_scores = len(top_scores) if isinstance(top_scores, list) else 0
    logger.info(
        f"[Summary Start] case_id='{case_id}', is_msi={is_msi}, confidence={confidence:.4f}, "
        f"top_scores_count={num_scores}, allow_fallback={allow_fallback}"
    )

    try:
        summary_result = _generate_clinical_summary_text(
            confidence=confidence,
            is_msi=is_msi,
            clinical_notes=clinical_report,
            top_scores=top_scores,
            allow_fallback=allow_fallback,
            case_id=case_id,
            specimen_source=specimen_source
        )
        duration = time.time() - start_time
        logger.info(
            f"[Summary Complete] case_id='{case_id}', duration={duration:.2f}s, "
            f"source={summary_result['source']}, model='{summary_result['model_used']}', is_llm={summary_result['is_llm']}"
        )
        return jsonify({
            "status": "ok",
            "summary": summary_result["summary"],
            "source": summary_result["source"],
            "model_used": summary_result["model_used"],
            "is_llm": summary_result["is_llm"],
            "error_detail": summary_result.get("error_detail")
        })
    except Exception as exc:
        duration = time.time() - start_time
        logger.error(f"[Summary Failed] case_id='{case_id}', duration={duration:.2f}s: {exc}", exc_info=True)
        return jsonify({
            "error": f"Clinical summary generation failed: {exc}",
            "source": "failed",
            "is_llm": False
        }), 500


@app.route("/diagnostic_trace", methods=["POST"])
@app.route("/diagnostic_trace/", methods=["POST"])
def diagnostic_trace():
    """Diagnostic telemetry endpoint to receive multi-stage timing trace from Android client."""
    try:
        trace_data = request.get_json(force=True)
        stage = trace_data.get("stage")
        name = trace_data.get("name", "unknown")
        details = trace_data.get("data", {})
        logger.info("[ANDROID_TELEMETRY STAGE %s - %s] %s", stage, name, json.dumps(details))
        return jsonify({"status": "ok", "stage": stage}), 200
    except Exception as exc:
        logger.warning("[ANDROID_TELEMETRY ERROR] Failed to record trace: %s", exc)
        return jsonify({"error": str(exc)}), 400


# ---------------------------------------------------------------------------
# When run directly, start the server on the conventional development port.
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    print("\n" + "=" * 60)
    print(" Colorectal Cancer Multimodal Inference API (Phikon-v2 + ClinicalBERT)")
    print(" [STATUS] Monotonic Forward-Pass Timing Instrumentation ACTIVE")
    print(" [ENDPOINTS] /predict, /embed_tiles, /fuse, /health, /generate_summary")
    print(" [HOST] http://0.0.0.0:5000")
    print("=" * 60 + "\n", flush=True)
    app.run(host="0.0.0.0", port=5000, debug=False)
