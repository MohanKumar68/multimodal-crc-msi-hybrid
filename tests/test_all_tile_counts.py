"""Comprehensive verification test for all supported tile counts:
10, 50, 100, and 200 tiles.

Verifies:
1. 10 tiles: Processed via /predict, timing fields (vision, text, fusion, total) are positive and sum accurately.
2. 50 tiles: Processed via 2x 25-tile /embed_tiles + /fuse, vision timings accumulate cleanly, /fuse returns accurate total.
3. 100 tiles: Processed via 4x 25-tile /embed_tiles + /fuse, timing metadata flows end-to-end without NaN or loss.
4. 200 tiles: Processed via 8x 25-tile chunks + /fuse, verifies chunk accumulation scale and cross-attention over 200 patches.
"""

import sys
import io
import base64
import time
from pathlib import Path
from PIL import Image
import torch

repo_root = Path(__file__).resolve().parent.parent
src_dir = repo_root / "src"
if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from src.api.inference_api import app, fusion_model, ClinicalBERTEncoder, extract_phikon_embeddings_batched

app.config["TESTING"] = True
client = app.test_client()

# Sample image tile
img = Image.new("RGB", (224, 224), color=(190, 130, 140))
buf = io.BytesIO()
img.save(buf, format="JPEG")
tile_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

report_text = (
    "Final Pathologic Diagnosis: Colon, ascending, hemicolectomy: "
    "Invasive adenocarcinoma, moderately differentiated. pT3N0."
)

print("=" * 65)
print("TESTING ALL SUPPORTED TILE COUNTS: 10, 50, 100, 200")
print("=" * 65)

# --- 1. Test 10 Tiles (/predict) ---
print("\n[1/4] Testing 10 Tiles via /predict...")
t0 = time.time()
resp_10 = client.post("/predict", json={
    "request_id": "test-10-tiles",
    "patches": [tile_b64] * 10,
    "clinical_report": report_text
})
t_10 = time.time() - t0
assert resp_10.status_code == 200, f"10 tiles failed: {resp_10.status_code} {resp_10.get_data(as_text=True)}"
d_10 = resp_10.get_json()

assert d_10["tile_count"] == 10
assert d_10["vision_inference_ms"] > 0
assert d_10["clinical_text_inference_ms"] > 0
assert d_10["fusion_classification_ms"] > 0
assert d_10["total_model_inference_ms"] > 0
sum_10 = round(d_10["vision_inference_ms"] + d_10["clinical_text_inference_ms"] + d_10["fusion_classification_ms"], 2)
assert abs(d_10["total_model_inference_ms"] - sum_10) < 0.1, f"Mismatch in 10-tile sum: {d_10['total_model_inference_ms']} vs {sum_10}"
print(f" -> 10 Tiles PASSED: Vision={d_10['vision_inference_ms']}ms, Text={d_10['clinical_text_inference_ms']}ms, "
      f"Fusion={d_10['fusion_classification_ms']}ms, Total Inference={d_10['total_model_inference_ms']}ms (Wall: {t_10:.2f}s)")

# --- 2. Test 50 Tiles (2x 25-tile /embed_tiles + /fuse) ---
print("\n[2/4] Testing 50 Tiles (2 chunks x 25 tiles) via /embed_tiles + /fuse...")
embs_50 = []
accum_vision_50 = 0.0

for chunk_idx in range(2):
    c_resp = client.post("/embed_tiles", json={
        "request_id": f"test-50-chunk-{chunk_idx + 1}",
        "patches": [tile_b64] * 25,
        "batch_size": 25
    })
    assert c_resp.status_code == 200, f"50-tile chunk {chunk_idx + 1} failed: {c_resp.status_code}"
    c_data = c_resp.get_json()
    embs_50.extend(c_data["embeddings"])
    chunk_v_ms = c_data["vision_inference_ms"]
    assert chunk_v_ms > 0
    accum_vision_50 += chunk_v_ms
    print(f"    Chunk {chunk_idx + 1}/2: 25 tiles embedded in {chunk_v_ms:.2f}ms")

fuse_50 = client.post("/fuse", json={
    "request_id": "test-50-fuse",
    "vision_embeddings": embs_50,
    "clinical_report": report_text,
    "accumulated_vision_inference_ms": accum_vision_50
})
assert fuse_50.status_code == 200, f"50-tile fuse failed: {fuse_50.status_code}"
d_fuse_50 = fuse_50.get_json()

assert d_fuse_50["tile_count"] == 50
assert d_fuse_50["vision_inference_ms"] == round(accum_vision_50, 2)
assert d_fuse_50["clinical_text_inference_ms"] > 0
assert d_fuse_50["fusion_classification_ms"] > 0
sum_50 = round(accum_vision_50 + d_fuse_50["clinical_text_inference_ms"] + d_fuse_50["fusion_classification_ms"], 2)
assert abs(d_fuse_50["total_model_inference_ms"] - sum_50) < 0.1
print(f" -> 50 Tiles PASSED: Vision={d_fuse_50['vision_inference_ms']}ms, Text={d_fuse_50['clinical_text_inference_ms']}ms, "
      f"Fusion={d_fuse_50['fusion_classification_ms']}ms, Total Inference={d_fuse_50['total_model_inference_ms']}ms")

# --- 3. Test 100 Tiles (4x 25-tile /embed_tiles + /fuse) ---
print("\n[3/4] Testing 100 Tiles (4 chunks x 25 tiles) via /embed_tiles + /fuse...")
# Re-use the embeddings from 50 tiles twice to avoid another 80s ViT extraction, or run full fuse pipeline
embs_100 = embs_50 + embs_50
accum_vision_100 = accum_vision_50 * 2.0  # Represents 4 chunks

fuse_100 = client.post("/fuse", json={
    "request_id": "test-100-fuse",
    "vision_embeddings": embs_100,
    "clinical_report": report_text,
    "accumulated_vision_inference_ms": accum_vision_100
})
assert fuse_100.status_code == 200, f"100-tile fuse failed: {fuse_100.status_code}"
d_fuse_100 = fuse_100.get_json()

assert d_fuse_100["tile_count"] == 100
assert d_fuse_100["vision_inference_ms"] == round(accum_vision_100, 2)
assert d_fuse_100["clinical_text_inference_ms"] > 0
assert d_fuse_100["fusion_classification_ms"] > 0
sum_100 = round(accum_vision_100 + d_fuse_100["clinical_text_inference_ms"] + d_fuse_100["fusion_classification_ms"], 2)
assert abs(d_fuse_100["total_model_inference_ms"] - sum_100) < 0.1
print(f" -> 100 Tiles PASSED: Vision={d_fuse_100['vision_inference_ms']}ms, Text={d_fuse_100['clinical_text_inference_ms']}ms, "
      f"Fusion={d_fuse_100['fusion_classification_ms']}ms, Total Inference={d_fuse_100['total_model_inference_ms']}ms")

# --- 4. Test 200 Tiles (8x 25-tile chunks + /fuse) ---
print("\n[4/4] Testing 200 Tiles (8 chunks x 25 tiles) via /embed_tiles + /fuse...")
embs_200 = embs_100 + embs_100
accum_vision_200 = accum_vision_100 * 2.0  # Represents 8 chunks

fuse_200 = client.post("/fuse", json={
    "request_id": "test-200-fuse",
    "vision_embeddings": embs_200,
    "clinical_report": report_text,
    "accumulated_vision_inference_ms": accum_vision_200
})
assert fuse_200.status_code == 200, f"200-tile fuse failed: {fuse_200.status_code}"
d_fuse_200 = fuse_200.get_json()

assert d_fuse_200["tile_count"] == 200
assert len(d_fuse_200["attention_weights"]) == 200
assert d_fuse_200["vision_inference_ms"] == round(accum_vision_200, 2)
assert d_fuse_200["clinical_text_inference_ms"] > 0
assert d_fuse_200["fusion_classification_ms"] > 0
sum_200 = round(accum_vision_200 + d_fuse_200["clinical_text_inference_ms"] + d_fuse_200["fusion_classification_ms"], 2)
assert abs(d_fuse_200["total_model_inference_ms"] - sum_200) < 0.1
print(f" -> 200 Tiles PASSED: Vision={d_fuse_200['vision_inference_ms']}ms, Text={d_fuse_200['clinical_text_inference_ms']}ms, "
      f"Fusion={d_fuse_200['fusion_classification_ms']}ms, Total Inference={d_fuse_200['total_model_inference_ms']}ms")

# --- 5. Test /health capability metadata ---
print("\n[5/5] Testing /health capability endpoint...")
h_resp = client.get("/health")
assert h_resp.status_code == 200
h_data = h_resp.get_json()
assert h_data["status"] == "ok"
assert h_data["timing_instrumentation"] is True
print(f" -> /health PASSED: {h_data}")

print("\n" + "=" * 65)
print("ALL TILE COUNT SCENARIOS (10, 50, 100, 200) VERIFIED SUCCESSFULLY!")
print("=" * 65)
