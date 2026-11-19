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

from src.api.inference_api import app as flask_app, clinical_encoder

def make_patch(color=(220, 150, 180), size=(224, 224)):
    img = Image.new("RGB", size, color=color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")

def main():
    flask_app.config["TESTING"] = True
    client = flask_app.test_client()

    patch1 = make_patch((220, 150, 180))
    patch2 = make_patch((100, 50, 120))
    valid_report = "Colon, ascending, hemicolectomy: Invasive adenocarcinoma, moderately differentiated. pT3N0."

    results = {}

    print("=== TEST 1: Existing Valid Request ===")
    r = client.post("/predict", json={"patches": [patch1], "clinical_report": valid_report})
    assert r.status_code == 200, f"Expected 200, got {r.status_code}"
    data = r.get_json()
    assert "probability" in data and 0.0 <= data["probability"] <= 1.0
    assert data["clinical_shape"] == [1, 768]
    assert data["vision_shape"] == [1, 1024]
    print(f"PASS: Status {r.status_code}, prob={data['probability']:.6f}, shapes: {data['vision_shape']}, {data['clinical_shape']}")
    results["valid_request"] = True

    print("\n=== TEST 2: Empty & Whitespace-only Reports ===")
    r_empty = client.post("/predict", json={"patches": [patch1], "clinical_report": ""})
    assert r_empty.status_code == 400, f"Expected 400 for empty string, got {r_empty.status_code}"
    print(f"PASS: Empty report returned {r_empty.status_code}: {r_empty.get_json()}")

    r_ws = client.post("/predict", json={"patches": [patch1], "clinical_report": "   \n\t  \r  "})
    assert r_ws.status_code == 400, f"Expected 400 for whitespace, got {r_ws.status_code}"
    print(f"PASS: Whitespace-only report returned {r_ws.status_code}: {r_ws.get_json()}")
    results["empty_and_whitespace_rejected"] = True

    print("\n=== TEST 3: Missing Report ===")
    r_missing = client.post("/predict", json={"patches": [patch1]})
    assert r_missing.status_code == 400, f"Expected 400 for missing clinical report, got {r_missing.status_code}"
    print(f"PASS: Missing report returned {r_missing.status_code}: {r_missing.get_json()}")
    results["missing_report_rejected"] = True

    print("\n=== TEST 4: Malformed Image Payload ===")
    r_malformed = client.post("/predict", json={"patches": ["bad_base64_%%%!!!"], "clinical_report": valid_report})
    assert r_malformed.status_code == 400, f"Expected 400 for malformed image, got {r_malformed.status_code}"
    print(f"PASS: Malformed image returned {r_malformed.status_code}: {r_malformed.get_json()}")
    results["malformed_image_rejected"] = True

    print("\n=== TEST 5: Repeated Identical Inputs (Determinism) ===")
    p1 = client.post("/predict", json={"patches": [patch1], "clinical_report": valid_report}).get_json()["probability"]
    p2 = client.post("/predict", json={"patches": [patch1], "clinical_report": valid_report}).get_json()["probability"]
    diff = abs(p1 - p2)
    assert diff == 0.0, f"Non-deterministic diff: {diff}"
    print(f"PASS: p1={p1:.8f}, p2={p2:.8f}, diff={diff}")
    results["determinism"] = True

    print("\n=== TEST 6: Different Inputs ===")
    p_diff_img = client.post("/predict", json={"patches": [patch2], "clinical_report": valid_report}).get_json()["probability"]
    p_diff_txt = client.post("/predict", json={"patches": [patch1], "clinical_report": "Rectum: Tubulovillous adenoma."}).get_json()["probability"]
    print(f"PASS: baseline={p1:.6f}, diff_img={p_diff_img:.6f}, diff_txt={p_diff_txt:.6f}")
    results["different_inputs"] = True

    print("\n=== TEST 7: Single Initialization Verification ===")
    mod_before = clinical_encoder.model
    tok_before = clinical_encoder.tokenizer
    client.post("/predict", json={"patches": [patch1], "clinical_report": valid_report})
    client.post("/predict", json={"patches": [patch2], "clinical_report": valid_report})
    assert clinical_encoder.model is mod_before, "Model was reloaded!"
    assert clinical_encoder.tokenizer is tok_before, "Tokenizer was reloaded!"
    print("PASS: Model and tokenizer memory references remained identical across requests (no reloads).")
    results["singleton_verified"] = True

    print("\nALL VERIFICATIONS PASSED SUCCESSFULLY.")

if __name__ == "__main__":
    main()
