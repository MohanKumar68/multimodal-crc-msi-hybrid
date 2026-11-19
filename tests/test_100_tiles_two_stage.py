import sys
import io
import base64
import time
from pathlib import Path
from PIL import Image

repo_root = Path(__file__).resolve().parent.parent
src_dir = repo_root / "src"
if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from src.api.inference_api import app

print("Initializing test client...")
app.config["TESTING"] = True
client = app.test_client()

# Create a sample tile
img = Image.new("RGB", (224, 224), color=(180, 120, 150))
buf = io.BytesIO()
img.save(buf, format="JPEG")
tile_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

report_text = (
    "Final Pathologic Diagnosis: Colon, ascending, hemicolectomy: "
    "Invasive adenocarcinoma, moderately differentiated. pT3N0."
)

print("Simulating 100 tiles (4 chunks of 25 tiles)...")
chunk_size = 25
total_tiles = 100
all_embeddings = []
accumulated_vision_ms = 0.0

start_wall = time.time()
for chunk_idx in range(4):
    t_chunk_start = time.time()
    chunk_patches = [tile_b64] * chunk_size
    payload = {
        "request_id": f"test-req-chunk-{chunk_idx + 1}",
        "patches": chunk_patches,
        "batch_size": chunk_size
    }
    resp = client.post("/embed_tiles", json=payload)
    t_chunk_end = time.time()
    
    assert resp.status_code == 200, f"Chunk {chunk_idx + 1} failed: {resp.status_code} {resp.get_data(as_text=True)}"
    data = resp.get_json()
    
    embeddings = data.get("embeddings")
    assert len(embeddings) == chunk_size, f"Expected {chunk_size} embeddings, got {len(embeddings)}"
    all_embeddings.extend(embeddings)
    
    vision_ms = data.get("vision_inference_ms")
    total_model_ms = data.get("total_model_inference_ms")
    assert vision_ms is not None and vision_ms > 0, f"Missing or invalid vision_inference_ms: {vision_ms}"
    assert total_model_ms is not None and total_model_ms > 0, f"Missing or invalid total_model_inference_ms: {total_model_ms}"
    
    accumulated_vision_ms += vision_ms
    print(f"Chunk {chunk_idx + 1}/4 done: wall={t_chunk_end - t_chunk_start:.2f}s, vision_ms={vision_ms:.2f}ms")

print(f"All 4 chunks embedded: total embeddings={len(all_embeddings)}, accumulated_vision_ms={accumulated_vision_ms:.2f}ms")

# Stage 2: Fuse
print("Calling /fuse with 100 embeddings and accumulated_vision_inference_ms...")
t_fuse_start = time.time()
fuse_payload = {
    "request_id": "test-req-fuse",
    "vision_embeddings": all_embeddings,
    "clinical_report": report_text,
    "accumulated_vision_inference_ms": accumulated_vision_ms
}
fuse_resp = client.post("/fuse", json=fuse_payload)
t_fuse_end = time.time()

assert fuse_resp.status_code == 200, f"/fuse failed: {fuse_resp.status_code} {fuse_resp.get_data(as_text=True)}"
fuse_data = fuse_resp.get_json()

print("/fuse response keys:", list(fuse_data.keys()))
print("Probability:", fuse_data.get("probability"))
print("Tile count:", fuse_data.get("tile_count"))
print("Vision shape:", fuse_data.get("vision_shape"))
print("Clinical shape:", fuse_data.get("clinical_shape"))
print("total_model_inference_ms:", fuse_data.get("total_model_inference_ms"))
print("vision_inference_ms:", fuse_data.get("vision_inference_ms"))
print("clinical_text_inference_ms:", fuse_data.get("clinical_text_inference_ms"))
print("fusion_classification_ms:", fuse_data.get("fusion_classification_ms"))
print("inference_timing_scope:", fuse_data.get("inference_timing_scope"))

total_wall = time.time() - start_wall
print(f"Total test wall time: {total_wall:.2f}s")
