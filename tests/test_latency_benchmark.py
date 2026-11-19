import sys
from pathlib import Path
import time
import io
import base64
from PIL import Image

repo_root = Path(".").resolve()
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))
src_dir = repo_root / "src"
if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))

from src.api.inference_api import app as flask_app

def run_benchmark():
    flask_app.config["TESTING"] = True
    client = flask_app.test_client()

    img = Image.new("RGB", (224, 224), color=(220, 150, 180))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    patch_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

    report = "Colon, ascending, hemicolectomy: Invasive adenocarcinoma, moderately differentiated. pT3N0."

    print("--- Starting Latency Benchmark ---")
    t0 = time.perf_counter()
    resp = client.post("/predict", json={"patches": [patch_b64], "clinical_report": report})
    w_time = (time.perf_counter() - t0) * 1000
    print(f"Warm-up: status={resp.status_code}, time={w_time:.1f} ms")

    times = []
    for i in range(3):
        t0 = time.perf_counter()
        resp = client.post("/predict", json={"patches": [patch_b64], "clinical_report": report})
        dt = (time.perf_counter() - t0) * 1000
        times.append(dt)
        prob = resp.get_json()["probability"]
        print(f"Run {i+1}: status={resp.status_code}, prob={prob:.6f}, time={dt:.1f} ms")

    avg_time = sum(times) / len(times)
    print(f"Average timed latency: {avg_time:.1f} ms (min={min(times):.1f} ms, max={max(times):.1f} ms)")
    return {"warmup": w_time, "runs": times, "avg": avg_time}

if __name__ == "__main__":
    run_benchmark()
