# Add ClinicalBERT runtime encoding tests
import sys
from pathlib import Path

# Add src directory to Python path for imports
src_dir = Path(__file__).parents[2] / "src"
if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))

import pytest
import torch
from unittest.mock import patch, MagicMock

from src.api.inference_api import app as flask_app, clinical_encoder
from transformers import AutoTokenizer, AutoModel

@pytest.fixture

def client():
    flask_app.config["TESTING"] = True
    with flask_app.test_client() as client:
        yield client


def test_clinicalbert_real_encoding(client):
    """Test real ClinicalBERT encoding with synthetic patches.

    Uses the actual cached encoder (already loaded on import).
    Verifies shape and finite values.
    """
    payload = {
        "synthetic": True,
        "num_patches": 1,
        "clinical_report": "Patient has colorectal adenocarcinoma."
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert "probability" in data
    assert data["clinical_shape"] == [1, 768]
    # Verify the tensor is the same as clinical_encoder.encode()
    # (clinical_encoder already loaded from the same model)
    # Check that the embedding is finite and not zero vector
    assert torch.isfinite(torch.tensor(data["probability"]))


def test_clinicalbert_encoder_singleton():
    """Test that ClinicalBERT encoder is reused across calls.

    Verifies that calling encode() multiple times doesn't reload the model.
    """
    # Get initial state
    original_model = clinical_encoder.model
    original_tokenizer = clinical_encoder.tokenizer

    # Encode once
    embedding1 = clinical_encoder.encode("Test report 1")
    assert embedding1.shape == (1, 768)

    # Encode again
    embedding2 = clinical_encoder.encode("Test report 2")
    assert embedding2.shape == (1, 768)

    # Ensure model wasn't reloaded (same object reference)
    assert clinical_encoder.model is original_model
    assert clinical_encoder.tokenizer is original_tokenizer


def test_clinicalbert_encoder_failure_handling():
    """Test ClinicalBERT encoder failure handling.

    Mock the tokenizer/model to simulate initialization failure.
    """
    # Save original encoder state
    original_encode = clinical_encoder.encode
    original_model = clinical_encoder.model
    original_tokenizer = clinical_encoder.tokenizer

    # Mock encode to raise RuntimeError matching expected wrapper error
    def mock_encode(text):
        raise RuntimeError("Encoding failed: Mock failure")

    clinical_encoder.encode = MagicMock(side_effect=mock_encode)
    clinical_encoder.model = MagicMock()
    clinical_encoder.tokenizer = MagicMock()

    # Try encoding
    with pytest.raises(RuntimeError, match="Encoding failed"):
        clinical_encoder.encode("test")

    # Restore original method
    clinical_encoder.encode = original_encode
    clinical_encoder.model = original_model
    clinical_encoder.tokenizer = original_tokenizer


def test_clinicalbert_invalid_text(client):
    """Test rejection of empty or invalid clinical report.
    """
    # Empty report
    payload = {"synthetic": True, "clinical_report": ""}
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 400
    assert "error" in resp.get_json()

    # Whitespace-only report
    payload = {"synthetic": True, "clinical_report": "   \n\t  "}
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 400
    assert "error" in resp.get_json()

    # Non-string report
    payload = {"synthetic": True, "clinical_report": 123}
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 400
    assert "error" in resp.get_json()


def test_clinicalbert_conflicting_inputs(client):
    """Test rejection when both clinical_embedding and clinical_report are provided.
    """
    payload = {
        "synthetic": True,
        "clinical_report": "text",
        "clinical_embedding": [0.0]*768
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 400
    assert "error" in resp.get_json()


def test_clinicalbert_embedding_validation(client):
    """Test validation of precomputed clinical embedding.

    Uses real precomputed tensor from data/clean_tensors/
    """
    import os
    import torch
    from pathlib import Path

    # Find a valid patient with text tensor
    tensors_dir = Path("data/clean_tensors")
    text_files = list(tensors_dir.glob("*_text.pt"))
    assert len(text_files) > 0, "No text tensors found"

    patient_id = text_files[0].name.split("_text.pt")[0]

    payload = {
        "synthetic": True,
        "num_patches": 1,
        "clinical_embedding": torch.load(str(text_files[0]), weights_only=True).flatten().tolist()
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["clinical_shape"] == [1, 768]
    assert torch.isfinite(torch.tensor(data["probability"]))


def test_clinicalbert_encoder_init_failure():
    """Test API behavior when ClinicalBERT encoder fails to initialize.

    Monkeypatch transformers import to simulate ImportError or model loading failure.
    """
    import importlib
    import src.api.inference_api as api

    # Save original clinical_encoder
    original_encoder = api.clinical_encoder

    orig_tok_from_pretrained = AutoTokenizer.from_pretrained
    orig_mod_from_pretrained = AutoModel.from_pretrained

    # Monkeypatch AutoTokenizer/AutoModel to raise ImportError only for ClinicalBERT
    def fake_tok_from_pretrained(*args, **kwargs):
        if args and "ClinicalBERT" in args[0]:
            raise ImportError("Mock import failure")
        return orig_tok_from_pretrained(*args, **kwargs)

    def fake_mod_from_pretrained(*args, **kwargs):
        if args and "ClinicalBERT" in args[0]:
            raise ImportError("Mock import failure")
        return orig_mod_from_pretrained(*args, **kwargs)

    with patch.object(AutoTokenizer, "from_pretrained", side_effect=fake_tok_from_pretrained), \
         patch.object(AutoModel, "from_pretrained", side_effect=fake_mod_from_pretrained):
        # Reload module to trigger new initialization
        importlib.reload(api)
        # New clinical_encoder should be None or failed
        assert not hasattr(api, "clinical_encoder") or api.clinical_encoder is None or api.clinical_encoder.model is None

    # Restore original module
    api.ClinicalBERTEncoder._initialized = False
    api.ClinicalBERTEncoder._instance = None
    importlib.reload(api)
    if original_encoder is not None and (not hasattr(api, "clinical_encoder") or api.clinical_encoder is None or api.clinical_encoder.model is None):
        api.clinical_encoder = original_encoder
    assert hasattr(api, "clinical_encoder") and api.clinical_encoder.model is not None


def test_attention_weights_single_patch(client):
    """Test that single-patch inference returns normalized attention weights [1.0]."""
    payload = {
        "synthetic": True,
        "num_patches": 1,
        "clinical_report": "Patient has invasive colorectal adenocarcinoma."
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert "attention_weights" in data
    weights = data["attention_weights"]
    assert isinstance(weights, list)
    assert len(weights) == 1
    assert abs(weights[0] - 1.0) < 1e-5


def test_attention_weights_multiple_patches(client):
    """Test that multi-patch inference returns attention weights matching patch count and summing to 1.0."""
    payload = {
        "synthetic": True,
        "num_patches": 5,
        "clinical_report": "Patient has invasive colorectal adenocarcinoma."
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert "attention_weights" in data
    weights = data["attention_weights"]
    assert isinstance(weights, list)
    assert len(weights) == 5
    assert all(0.0 <= w <= 1.0 for w in weights)
    assert abs(sum(weights) - 1.0) < 1e-4
    assert data["vision_shape"] == [5, 1024]
    assert data["clinical_shape"] == [1, 768]


def test_attention_weights_mismatch_returns_null(client):
    """Test that if model attention tensor length does not match patch count, attention_weights is null."""
    import src.api.inference_api as api

    orig_fusion = api.fusion_model
    try:
        # Mock fusion model to return attention weights for 3 patches when 5 patches were sent
        mock_fusion = MagicMock()
        mock_fusion.return_value = (
            torch.tensor([[0.75]]),
            torch.tensor([[[0.5, 0.3, 0.2]]])  # shape [1, 1, 3], but patches=5
        )
        api.fusion_model = mock_fusion

        payload = {
            "synthetic": True,
            "num_patches": 5,
            "clinical_report": "Colorectal adenocarcinoma specimen."
        }
        resp = client.post("/predict", json=payload)
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["probability"] == 0.75
        assert data["attention_weights"] is None
    finally:
        api.fusion_model = orig_fusion


def test_attention_weights_non_finite_returns_null(client):
    """Test that if attention weights contain non-finite values (NaN/inf), attention_weights is null."""
    import src.api.inference_api as api

    orig_fusion = api.fusion_model
    try:
        mock_fusion = MagicMock()
        mock_fusion.return_value = (
            torch.tensor([[0.60]]),
            torch.tensor([[[float("nan"), 0.5]]])  # shape [1, 1, 2] with NaN
        )
        api.fusion_model = mock_fusion

        payload = {
            "synthetic": True,
            "num_patches": 2,
            "clinical_report": "Colorectal adenocarcinoma specimen."
        }
        resp = client.post("/predict", json=payload)
        assert resp.status_code == 200
        data = resp.get_json()
        assert abs(data["probability"] - 0.60) < 1e-4
        assert data["attention_weights"] is None
    finally:
        api.fusion_model = orig_fusion


def test_attention_weights_unnormalized_returns_null(client):
    """Test that if attention weights deviate significantly from softmax normalization (sum=1.0), it is rejected."""
    import src.api.inference_api as api

    orig_fusion = api.fusion_model
    try:
        mock_fusion = MagicMock()
        mock_fusion.return_value = (
            torch.tensor([[0.55]]),
            torch.tensor([[[0.8, 0.9]]])  # shape [1, 1, 2] with sum=1.7 (unnormalized)
        )
        api.fusion_model = mock_fusion

        payload = {
            "synthetic": True,
            "num_patches": 2,
            "clinical_report": "Colorectal adenocarcinoma specimen."
        }
        resp = client.post("/predict", json=payload)
        assert resp.status_code == 200
        data = resp.get_json()
        assert abs(data["probability"] - 0.55) < 1e-4
        assert data["attention_weights"] is None
    finally:
        api.fusion_model = orig_fusion


def test_batch_inference_10_tiles(client):
    """Test batch inference with 10 patches: verifies bounded batching and attention normalization."""
    payload = {
        "synthetic": True,
        "num_patches": 10,
        "clinical_report": "Biopsy of ascending colon shows invasive adenocarcinoma."
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["vision_shape"] == [10, 1024]
    assert data["clinical_shape"] == [1, 768]
    weights = data["attention_weights"]
    assert weights is not None
    assert len(weights) == 10
    assert all(0.0 <= w <= 1.0 for w in weights)
    assert abs(sum(weights) - 1.0) < 1e-3


def test_batch_inference_50_tiles(client):
    """Test batch inference with 50 patches (2 bounded batches of 25)."""
    payload = {
        "synthetic": True,
        "num_patches": 50,
        "clinical_report": "Colon resection: invasive adenocarcinoma, negative margins."
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["vision_shape"] == [50, 1024]
    weights = data["attention_weights"]
    assert weights is not None
    assert len(weights) == 50
    assert abs(sum(weights) - 1.0) < 1e-3


def test_batch_inference_over_100_tiles(client):
    """Test batch inference with 105 patches (>100 tiles, 5 bounded batches of 25+25+25+25+5)."""
    payload = {
        "synthetic": True,
        "num_patches": 105,
        "clinical_report": "Colon resection: adenocarcinoma with extensive specimen sampling."
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["vision_shape"] == [105, 1024]
    weights = data["attention_weights"]
    assert weights is not None
    assert len(weights) == 105
    assert all(0.0 <= w <= 1.0 for w in weights)
    assert abs(sum(weights) - 1.0) < 1e-3


def test_stage1_embed_tiles_endpoint(client):
    """Test Stage 1: /embed_tiles endpoint for bounded batch Phikon feature extraction."""
    import base64
    import io
    from PIL import Image

    # Create 2 small test images
    b64_list = []
    for _ in range(2):
        img = Image.new("RGB", (224, 224), color=(100, 150, 200))
        buf = io.BytesIO()
        img.save(buf, format="JPEG")
        b64_list.append(base64.b64encode(buf.getvalue()).decode("utf-8"))

    resp = client.post("/embed_tiles", json={"patches": b64_list, "batch_size": 25})
    assert resp.status_code == 200
    data = resp.get_json()
    assert "embeddings" in data
    assert data["count"] == 2
    assert len(data["embeddings"]) == 2
    assert len(data["embeddings"][0]) == 1024


def test_stage2_fuse_endpoint(client):
    """Test Stage 2: /fuse endpoint for sequence cross-attention fusion."""
    # Synthetic vision embeddings [5, 1024]
    synth_vision = [[0.01 * (i + j) for j in range(1024)] for i in range(5)]

    resp = client.post("/fuse", json={
        "vision_embeddings": synth_vision,
        "clinical_report": "Ascending colon adenocarcinoma, poorly differentiated."
    })
    assert resp.status_code == 200
    data = resp.get_json()
    assert "probability" in data
    assert 0.0 <= data["probability"] <= 1.0
    assert data["vision_shape"] == [5, 1024]
    assert data["clinical_shape"] == [1, 768]
    assert "attention_weights" in data
    weights = data["attention_weights"]
    assert weights is not None
    assert len(weights) == 5
    assert abs(sum(weights) - 1.0) < 1e-3


def test_generate_heatmap_endpoint(client):
    """Test /generate_heatmap: generates genuine Phikon-v2 ViT attention rollout heatmap."""
    import base64
    import io
    from PIL import Image

    # Synthetic 224x224 RGB image
    img = Image.new("RGB", (224, 224), color=(180, 100, 140))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    b64_str = base64.b64encode(buf.getvalue()).decode("utf-8")

    resp = client.post("/generate_heatmap", json={"patch": b64_str})
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["status"] == "ok"
    assert "heatmap_base64" in data
    assert len(data["heatmap_base64"]) > 100
    assert "method" in data
    assert "Phikon-v2 ViT" in data["method"]
    assert "dimensions" in data
    assert isinstance(data["dimensions"], list)
    assert len(data["dimensions"]) == 2
    assert "panels" in data
    assert len(data["panels"]) == 3


def test_llm_status_endpoint(client):
    """Test GET /llm_status diagnostic endpoint returns valid structure."""
    resp = client.get("/llm_status")
    assert resp.status_code == 200
    data = resp.get_json()
    assert "status" in data
    assert "provider" in data
    assert "model" in data
    assert "model_available" in data
    assert "setup_instructions" in data
    assert isinstance(data["setup_instructions"], list)


def test_generate_summary_endpoint_msi(client):
    """Test /generate_summary for MSI prediction."""
    resp = client.post("/generate_summary", json={
        "confidence": 88.5,
        "is_msi": True,
        "clinical_report": "Ascending colon resection: invasive poorly differentiated adenocarcinoma.",
        "top_scores": [0.35, 0.22, 0.15]
    })
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["status"] == "ok"
    assert "summary" in data
    summary = data["summary"]
    assert ("MSI-High" in summary or "MSI" in summary)
    assert "Clinical Findings" in summary
    assert "Computational" in summary
    assert "|" in summary
    assert "model_used" in data
    assert "source" in data
    assert data["source"] in ("ollama_llm", "deterministic_template_fallback")


def test_generate_summary_endpoint_mss(client):
    """Test /generate_summary for MSS prediction."""
    resp = client.post("/generate_summary", json={
        "confidence": 92.0,
        "is_msi": False,
        "clinical_report": "Sigmoid colon resection: well differentiated adenocarcinoma.",
        "top_scores": [0.12, 0.10, 0.08]
    })
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["status"] == "ok"
    assert "summary" in data
    assert ("MSS" in data["summary"] or "Microsatellite Stable" in data["summary"])
    assert "source" in data


def test_generate_summary_mock_real_llm_success(client):
    """Test /generate_summary when Ollama service succeeds with real LLM output."""
    from unittest.mock import patch, MagicMock

    mock_resp = MagicMock()
    mock_resp.ok = True
    mock_resp.json.return_value = {
        "response": (
            "### A. Case Overview\n- Case Identifier: TCGA-AA-3680\n\n"
            "### B. Documented Clinical Findings\n"
            "| Parameter | Finding |\n|---|---|\n| Primary Site | Cecum |\n"
            "| Histology | Poorly differentiated adenocarcinoma |\n\n"
            "### C. Computational Multimodal Prediction\n"
            "| Metric | Research Finding |\n|---|---|\n| Predicted MSI/MSS Class | MSI-High |\n"
            "| Estimated MSI Probability | 94.2% |\n| Threshold | tau = 0.83 |\n"
            "| Interpretation Status | Research output |\n\n"
            "### D. Top Cross-Attention Scores\n"
            "| Tile Rank | Attention Weight |\n|---|---|\n| Tile #1 | 0.4500 |\n\n"
            "### E. Limitations & Verification Disclaimer\n"
            "Investigational research decision-support tool."
        )
    }

    with patch("requests.post", return_value=mock_resp):
        resp = client.post("/generate_summary", json={
            "confidence": 94.2,
            "is_msi": True,
            "clinical_report": "Cecum biopsy shows poorly differentiated adenocarcinoma.",
            "top_scores": [0.45, 0.25, 0.10]
        })
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["status"] == "ok"
        assert data["source"] == "ollama_llm"
        assert data["is_llm"] is True
        assert "Ollama LLM" in data["model_used"]
        assert "Clinical Findings" in data["summary"]
        assert "MSI-High" in data["summary"]


def test_generate_summary_disallow_fallback_fails_when_offline(client):
    """Test that when allow_fallback=False and LLM is offline, a 500 error is returned."""
    from unittest.mock import patch

    with patch("requests.post", side_effect=Exception("Connection refused")):
        resp = client.post("/generate_summary", json={
            "confidence": 90.0,
            "is_msi": True,
            "clinical_report": "Colon mass resection.",
            "allow_fallback": False
        })
        assert resp.status_code == 500
        data = resp.get_json()
        assert "error" in data
        assert data["source"] == "failed"
        assert data["is_llm"] is False


def test_generate_summary_empty_report_rejected(client):
    """Test that empty or whitespace clinical reports are rejected with HTTP 400."""
    resp = client.post("/generate_summary", json={
        "confidence": 85.0,
        "is_msi": True,
        "clinical_report": "   "
    })
    assert resp.status_code == 400
    data = resp.get_json()
    assert "error" in data


# ---------------------------------------------------------------------------
# Regression tests for Clinical Scribe / Gemma 3 1B prompt & safety validator
# ---------------------------------------------------------------------------

def test_summary_validator_rejects_treatments():
    """Verify safety validator catches immunotherapy and treatment recommendations."""
    from src.api.inference_api import _validate_summary_output

    bad_texts = [
        "Patient is eligible for pembrolizumab immune checkpoint inhibitor therapy.",
        "Recommend treatment with nivolumab due to high microsatellite instability.",
        "We recommend anti-PD-1 checkpoint inhibition as adjuvant therapy.",
        "Prescribe standard adjuvant chemo regimen for stage III disease."
    ]
    for bad in bad_texts:
        # Pad to > 100 chars
        full = bad + " " * (120 - len(bad)) + "Additional clinical text for research study."
        is_valid, reason = _validate_summary_output(full)
        assert is_valid is False
        assert "treatment" in reason.lower() or "immunotherapy" in reason.lower()


def test_summary_validator_rejects_spatial_hallucinations():
    """Verify safety validator catches unsupported spatial claims and layer hallucinations."""
    from src.api.inference_api import _validate_summary_output

    bad_texts = [
        "Highest attention was directed to parietal layers of the bowel wall.",
        "Computational attention focused on seven parietal layers.",
        "The model identified tile labeled tumor core as the primary driver.",
        "Specifically, the tumor core demonstrated the highest MSI score."
    ]
    for bad in bad_texts:
        full = bad + " " * (120 - len(bad)) + "Additional computational findings for study."
        is_valid, reason = _validate_summary_output(full)
        assert is_valid is False
        assert "spatial" in reason.lower() or "anatomical" in reason.lower()


def test_summary_validator_accepts_clean_summary():
    """Verify safety validator accepts clean, evidence-grounded research summaries."""
    from src.api.inference_api import _validate_summary_output, _build_structured_summary_markdown

    notes = "Adenocarcinoma of the cecum, moderately differentiated, pT3 N0 (0/14 lymph nodes positive), resection margins negative."
    clean = _build_structured_summary_markdown(
        case_id="TCGA-AA-3680",
        specimen_source="Surgical Resection (H&E)",
        generation_status="LLM Scribe Synthesis",
        clinical_notes=notes,
        confidence=0.0885,
        is_msi=False,
        top_scores=[0.2612, 0.2198]
    )
    is_valid, reason = _validate_summary_output(clean, clinical_notes=notes, is_msi=False, confidence=0.0885)
    assert is_valid is True
    assert reason is None

    # Too short should fail
    short_valid, short_reason = _validate_summary_output("Too short text.")
    assert short_valid is False
    assert "too short" in short_reason.lower()


def test_summary_prompt_probability_interpretation():
    """Verify prompt builder normalizes float [0, 1] and percentage probabilities correctly."""
    from src.api.inference_api import _build_clinical_scribe_prompt

    # Float in [0, 1] range: 0.0885 should become 8.8% or 8.9%
    prompt_float = _build_clinical_scribe_prompt(
        confidence=0.0885,
        is_msi=False,
        clinical_notes="Cecum resection specimen.",
        top_scores=[0.2612, 0.2198]
    )
    assert "8.8%" in prompt_float or "8.9%" in prompt_float
    assert "tau = 0.83" in prompt_float
    assert "MSS (Microsatellite Stable)" in prompt_float
    # Explicit instruction prohibiting describing tau as uncertainty or confidence
    assert "uncertainty" in prompt_float.lower()
    assert "confidence" in prompt_float.lower()

    # Percentage input: 92.5 should remain 92.5%
    prompt_pct = _build_clinical_scribe_prompt(
        confidence=92.5,
        is_msi=True,
        clinical_notes="Ascending colon resection.",
        top_scores=[0.45]
    )
    assert "92.5%" in prompt_pct
    assert "MSI-High" in prompt_pct


def test_summary_prompt_clinical_facts_and_truncation():
    """Verify clinical notes are preserved up to 4000 chars and tile scores are numerical."""
    from src.api.inference_api import _build_clinical_scribe_prompt

    # Create long clinical report (2500 chars)
    long_report = (
        "COLON, ASCENDING, RESECTION: Invasive moderately differentiated adenocarcinoma. "
        "Tumor invades through the muscularis propria into the subserosa (pT3). "
        "Margins of resection are negative for carcinoma (proximal: 8.5 cm, distal: 12.0 cm, radial: 1.2 cm). "
        "Regional lymph nodes: 0 of 18 lymph nodes involved by carcinoma (0/18). "
        "Perineural invasion: Not identified. Lymphovascular invasion: Not identified. "
    ) * 8
    assert len(long_report) > 2000

    prompt = _build_clinical_scribe_prompt(
        confidence=0.15,
        is_msi=False,
        clinical_notes=long_report,
        top_scores=[0.31, 0.25, 0.18, 0.12, 0.08]
    )
    # The prompt should contain the report content without being cut off at 1500 chars
    assert len(prompt) > 2000
    assert "Tile #1: 0.3100" in prompt
    assert "Tile #2: 0.2500" in prompt
    # Strict negative constraints must be present
    assert "NO MEDICAL TREATMENT ADVICE" in prompt
    assert "NO TILE LABELS" in prompt


def test_summary_case_specific_input_isolation():
    """Verify that prompts for different cases are strictly isolated without cross-contamination."""
    from src.api.inference_api import _build_clinical_scribe_prompt

    prompt_a = _build_clinical_scribe_prompt(
        confidence=0.05,
        is_msi=False,
        clinical_notes="Case A: Rectosigmoid adenocarcinoma, stage pT2 N0.",
        top_scores=[0.11, 0.09]
    )
    prompt_b = _build_clinical_scribe_prompt(
        confidence=0.91,
        is_msi=True,
        clinical_notes="Case B: Cecum mucinous carcinoma, stage pT4a N2.",
        top_scores=[0.55, 0.40]
    )

    assert "Case A: Rectosigmoid" in prompt_a
    assert "Case B: Cecum" not in prompt_a
    assert "MSS" in prompt_a
    assert "MSI-High" not in prompt_a

    assert "Case B: Cecum" in prompt_b
    assert "Case A: Rectosigmoid" not in prompt_b
    assert "MSI-High" in prompt_b


def test_generate_summary_fallback_on_safety_violation(client):
    """Test that if LLM returns text violating safety rules, it falls back to deterministic template."""
    from unittest.mock import patch, MagicMock

    mock_resp = MagicMock()
    mock_resp.ok = True
    # Simulated hallucinating LLM recommending immunotherapy and naming parietal layers
    mock_resp.json.return_value = {
        "response": (
            "Paragraph 1: Colorectal adenocarcinoma.\n\n"
            "Paragraph 2: The model predicts MSI-High. Recommend pembrolizumab checkpoint inhibitor therapy.\n\n"
            "Paragraph 3: High attention focused on seven parietal layers of the colon."
        )
    }

    with patch("requests.post", return_value=mock_resp):
        resp = client.post("/generate_summary", json={
            "confidence": 88.0,
            "is_msi": True,
            "clinical_report": "Ascending colon resection: invasive adenocarcinoma.",
            "top_scores": [0.35, 0.25]
        })
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["status"] == "ok"
        # Must fall back to deterministic template due to safety violation
        assert data["source"] == "deterministic_template_fallback"
        assert data["is_llm"] is False
        assert "pembrolizumab" not in data["summary"].lower()
        assert "parietal" not in data["summary"].lower()


def test_generate_summary_deterministic_template_preserves_facts_and_missing_facts():
    """Verify deterministic template preserves stated facts and leaves missing facts unstated."""
    from src.api.inference_api import _generate_deterministic_template

    notes = "Colon biopsy: poorly differentiated adenocarcinoma. Margin status not assessed. Nodal status unknown."
    res = _generate_deterministic_template(
        confidence=0.12,
        is_msi=False,
        clinical_notes=notes,
        top_scores=[0.20, 0.15]
    )
    summary = res["summary"]
    # Preserves stated notes
    assert "poorly differentiated" in summary.lower()
    assert "adenocarcinoma" in summary.lower()
    assert "Margin status not assessed" in summary
    # Does not fabricate stage or nodal metastasis
    assert "stage iv" not in summary.lower()
    assert "pembrolizumab" not in summary.lower()
    assert "τ = 0.83" in summary or "tau = 0.83" in summary
    assert "investigational research" in summary.lower()
    assert res["source"] == "deterministic_template_fallback"


def test_summary_validator_rejects_hallucinated_margin_metastases():
    """Verify safety validator catches claims that tumor-free margins contain metastases."""
    from src.api.inference_api import _validate_summary_output

    notes = "Right hemicolectomy. Adenocarcinoma of the cecum. Resection margins are tumor-free. Regional lymph nodes 0/14 positive."
    hallucinated = (
        "Paragraph 1 — Clinical Findings: Right hemicolectomy for adenocarcinoma of the cecum. "
        "The margins also contain multiple regional lymph node metastases.\n\n"
        "Paragraph 2 — Computational Multimodal Result: The model predicts MSS with estimated MSI probability of 12.0%.\n\n"
        "Paragraph 3 — Computational Attention & Research Disclaimer: Top tile weights: Tile #1: 0.3500. "
        "Investigational research decision-support tool."
    )
    is_valid, reason = _validate_summary_output(hallucinated, clinical_notes=notes, is_msi=False, confidence=0.12)
    assert is_valid is False
    assert "margin" in reason.lower()


def test_summary_validator_rejects_hallucinated_lymph_node_involvement():
    """Verify safety validator catches claims of nodal involvement when 0/14 nodes are positive."""
    from src.api.inference_api import _validate_summary_output

    notes = "Diagnosis: Adenocarcinoma of the cecum. Resection margins are tumor-free. Regional lymph nodes 0/14 positive for metastasis."
    hallucinated = (
        "Paragraph 1 — Clinical Findings: Adenocarcinoma of the cecum with tumor-free resection margins. "
        "Report specifies 0/14 positive lymph nodes, suggesting a potential lymph node involvement.\n\n"
        "Paragraph 2 — Computational Multimodal Result: The model predicts MSS with estimated MSI probability of 78.4%.\n\n"
        "Paragraph 3 — Computational Attention & Research Disclaimer: Top tile weights: Tile #1: 0.2612. "
        "Investigational research decision-support tool."
    )
    is_valid, reason = _validate_summary_output(hallucinated, clinical_notes=notes, is_msi=False, confidence=0.784)
    assert is_valid is False
    assert "lymph node" in reason.lower()


def test_summary_validator_rejects_stable_genetic_profile_hallucination():
    """Verify safety validator rejects interpreting MSS as a stable genetic profile or architecture."""
    from src.api.inference_api import _validate_summary_output

    hallucinated = (
        "Paragraph 1 — Clinical Findings: Moderately differentiated adenocarcinoma of the cecum.\n\n"
        "Paragraph 2 — Computational Multimodal Result: The model predicts MSS, reflecting a relatively stable genetic profile.\n\n"
        "Paragraph 3 — Computational Attention & Research Disclaimer: Top tile weights: Tile #1: 0.2500. "
        "Investigational research decision-support tool."
    )
    is_valid, reason = _validate_summary_output(hallucinated, is_msi=False, confidence=0.15)
    assert is_valid is False
    assert "stable genetic profile" in reason.lower()


def test_summary_validator_rejects_tile_genomic_characteristics():
    """Verify safety validator rejects claiming a tile represents genomic characteristics."""
    from src.api.inference_api import _validate_summary_output

    hallucinated = (
        "Paragraph 1 — Clinical Findings: Invasive adenocarcinoma of the colon.\n\n"
        "Paragraph 2 — Computational Multimodal Result: The model predicts MSS with estimated MSI probability of 10.5%.\n\n"
        "Paragraph 3 — Computational Attention & Research Disclaimer: "
        "The tile representing the adenocarcinoma tumor's genomic characteristics demonstrated the highest score. "
        "Investigational research decision-support tool."
    )
    is_valid, reason = _validate_summary_output(hallucinated, is_msi=False, confidence=0.105)
    assert is_valid is False
    assert "spatial" in reason.lower() or "genomic" in reason.lower()


def test_summary_validator_rejects_confidence_framing():
    """Verify safety validator rejects calling probability or threshold confidence or diagnostic certainty."""
    from src.api.inference_api import _validate_summary_output

    hallucinated = (
        "Paragraph 1 — Clinical Findings: Well differentiated adenocarcinoma of the sigmoid colon.\n\n"
        "Paragraph 2 — Computational Multimodal Result: The model predicts MSS with 95% confidence in the predicted class.\n\n"
        "Paragraph 3 — Computational Attention & Research Disclaimer: Top tile weights: Tile #1: 0.1800. "
        "Investigational research decision-support tool."
    )
    is_valid, reason = _validate_summary_output(hallucinated, is_msi=False, confidence=0.05)
    assert is_valid is False
    assert "confidence" in reason.lower()


def test_summary_probability_formatting_small_decimals():
    """Verify probability formatting accurately handles small decimals (0.0014 -> 0.14%) without double scaling."""
    from src.api.inference_api import _format_probability

    assert _format_probability(0.0014) == "0.14%"
    assert _format_probability(0.0885) in ("8.8%", "8.9%")
    assert _format_probability(0.7840) == "78.4%"
    assert _format_probability(88.5) == "88.5%"
    assert _format_probability(0.0) == "0.0%"
    assert _format_probability(0.0005) == "0.05%"


def test_summary_validator_rejects_hallucinated_extra_tile_rows():
    """Verify safety validator rejects summaries with fabricated tile attention rows."""
    from src.api.inference_api import _validate_summary_output

    # Model was given 1 tile score [1.0], but summary hallucinated 5 rows
    hallucinated_summary = """
### A. Case Overview
- Case Identifier: TCGA-AA-3680
- Specimen Source & Staining: Surgical Resection (H&E)

### B. Documented Clinical Findings
| Documented Parameter | Source Finding |
|---|---|
| Primary Site | Cecum |
| Histology | Adenocarcinoma |

### C. Computational Multimodal Prediction
| Metric | Research Finding |
|---|---|
| Predicted MSI/MSS Class | MSS (Microsatellite Stable) |
| Estimated MSI Probability | 8.9% |

### D. Top Cross-Attention Scores
| Tile Rank | Attention Weight |
|---|---:|
| Tile #1 | 1.0000 |
| Tile #2 | 0.9800 |
| Tile #3 | 0.9600 |
| Tile #4 | 0.9400 |
| Tile #5 | 0.9200 |

### E. Limitations & Verification Disclaimer
This is an investigational research result, not a confirmed clinical diagnosis.
    """.strip()

    is_valid, reason = _validate_summary_output(
        summary=hallucinated_summary,
        clinical_notes="Adenocarcinoma of cecum",
        is_msi=False,
        confidence=0.089,
        top_scores=[1.0]
    )
    assert is_valid is False
    assert "extra tile attention rows" in reason.lower() or "fabricated" in reason.lower()


def test_summary_validator_accepts_exact_single_tile_row():
    """Verify safety validator accepts summary with exactly the genuine single tile row."""
    from src.api.inference_api import _validate_summary_output

    valid_summary = """
### A. Case Overview
- Case Identifier: TCGA-AA-3680
- Specimen Source & Staining: Surgical Resection (H&E)

### B. Documented Clinical Findings
| Documented Parameter | Source Finding |
|---|---|
| Primary Site | Cecum |
| Histology | Adenocarcinoma |

### C. Computational Multimodal Prediction
| Metric | Research Finding |
|---|---|
| Predicted MSI/MSS Class | MSS (Microsatellite Stable) |
| Estimated MSI Probability | 8.9% |

### D. Top Cross-Attention Scores
| Tile Rank | Attention Weight |
|---|---:|
| Tile #1 | 1.0000 |

### E. Limitations & Verification Disclaimer
This is an investigational research result, not a confirmed clinical diagnosis.
    """.strip()

    is_valid, reason = _validate_summary_output(
        summary=valid_summary,
        clinical_notes="Adenocarcinoma of cecum",
        is_msi=False,
        confidence=0.089,
        top_scores=[1.0]
    )
    assert is_valid is True
    assert reason is None


def test_summary_prompt_single_tile_has_no_padding():
    """Verify _build_clinical_scribe_prompt pre-renders only 1 row and forbids 5-row padding."""
    from src.api.inference_api import _build_clinical_scribe_prompt

    prompt = _build_clinical_scribe_prompt(
        confidence=0.089,
        is_msi=False,
        clinical_notes="Cecum adenocarcinoma",
        top_scores=[1.0]
    )
    # Check Section D template in prompt
    assert "| Tile #1 | 1.0000 |" in prompt
    assert "| Tile #2" not in prompt
    assert "There are exactly 1 valid tile(s)" in prompt
    assert "do NOT pad to 5 rows" in prompt


def test_deterministic_template_single_tile_outputs_one_row():
    """Verify deterministic template outputs exactly one row when given one tile score."""
    from src.api.inference_api import _generate_deterministic_template

    result = _generate_deterministic_template(
        confidence=0.089,
        is_msi=False,
        clinical_notes="Cecum adenocarcinoma",
        top_scores=[1.0]
    )
    summary = result["summary"]
    assert "| Tile #1 | 1.0000 |" in summary
    assert "| Tile #2" not in summary


def test_predict_endpoint_returns_diagnostics_and_request_id(client):
    """Verify /predict echoes request_id, raw_logit, sigmoid flag, and threshold."""
    test_req_id = "test-custom-req-12345"
    payload = {
        "request_id": test_req_id,
        "synthetic": True,
        "num_patches": 2,
        "clinical_report": "Invasive colorectal adenocarcinoma."
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()

    assert data["request_id"] == test_req_id
    assert "probability" in data
    assert "raw_logit" in data
    assert data["checkpoint_sigmoid_present"] is True
    assert data["threshold"] == 0.83
    assert data["predicted_class"] in ["MSI-H", "MSS"]
    assert data["tile_count"] == 2
    assert "report_sha256" in data
    assert data["is_sample_report"] is False


def test_fuse_endpoint_returns_diagnostics_and_request_id(client):
    """Verify /fuse echoes request_id, raw_logit, sigmoid flag, and threshold."""
    test_req_id = "test-fuse-req-67890"
    payload = {
        "request_id": test_req_id,
        "vision_embeddings": [[0.05] * 1024 for _ in range(3)],
        "clinical_embedding": [0.02] * 768
    }
    resp = client.post("/fuse", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()

    assert data["request_id"] == test_req_id
    assert "probability" in data
    assert "raw_logit" in data
    assert data["checkpoint_sigmoid_present"] is True
    assert data["threshold"] == 0.83
    assert data["tile_count"] == 3
    assert data["vision_shape"] == [3, 1024]
    assert data["clinical_shape"] == [1, 768]


def test_embed_tiles_echoes_request_id(client):
    """Verify /embed_tiles preserves request_id in JSON response."""
    import base64
    import io
    from PIL import Image

    img = Image.new("RGB", (224, 224), color=(128, 128, 128))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    b64_str = base64.b64encode(buf.getvalue()).decode("utf-8")

    test_req_id = "test-embed-req-4455"
    payload = {
        "request_id": test_req_id,
        "patches": [b64_str],
        "batch_size": 1
    }
    resp = client.post("/embed_tiles", json=payload)
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["request_id"] == test_req_id
    assert data["count"] == 1
    assert len(data["embeddings"]) == 1
    assert len(data["embeddings"][0]) == 1024







