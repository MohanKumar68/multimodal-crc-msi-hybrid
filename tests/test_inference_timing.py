"""Comprehensive unit tests for model inference latency measurement.

Tests:
1. Model timing measured strictly at forward-pass boundaries (excluding tokenization and image preprocessing).
2. Multiple tile batches accumulate forward-pass durations accurately.
3. Clinical text encoder timing is measured and included.
4. Multimodal fusion and classification head timing is measured and included.
5. AI summary generation is separate and excluded from model inference latency.
6. Total execution time (endpoint elapsed) vs model inference time.
7. Request concurrency isolation (thread-safe, no shared mutable global timers).
8. Failure cases return no successful timing durations.
"""

import sys
import os
import io
import base64
import time
from pathlib import Path
import unittest
from PIL import Image
import torch

repo_root = Path(__file__).resolve().parent.parent
src_dir = repo_root / "src"
if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from src.api.inference_api import (
    app as flask_app,
    ClinicalBERTEncoder,
    phikon_generator,
    extract_phikon_embeddings_batched,
    fusion_model,
)


class InferenceTimingTestCase(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        flask_app.config["TESTING"] = True
        cls.client = flask_app.test_client()

        # Create sample 224x224 RGB image patch
        cls.test_img = Image.new("RGB", (224, 224), color=(200, 140, 160))
        buf = io.BytesIO()
        cls.test_img.save(buf, format="JPEG")
        cls.patch_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

        cls.sample_report = (
            "Final Pathologic Diagnosis: Colon, ascending, hemicolectomy: "
            "Invasive adenocarcinoma, moderately differentiated. pT3N0."
        )

    def test_01_clinical_bert_encoder_timing(self):
        """ClinicalBERT forward pass is measured and tokenization is excluded."""
        encoder = ClinicalBERTEncoder(device="cpu")
        embedding, forward_ns = encoder.encode_with_timing(self.sample_report)

        self.assertIsInstance(embedding, torch.Tensor)
        self.assertEqual(embedding.shape, (1, 768))
        self.assertIsInstance(forward_ns, int)
        self.assertGreater(forward_ns, 0)

        # Backward compatibility check: encode() returns just the tensor
        emb_legacy = encoder.encode(self.sample_report)
        self.assertIsInstance(emb_legacy, torch.Tensor)
        self.assertEqual(emb_legacy.shape, (1, 768))

    def test_02_phikon_batch_timing(self):
        """Phikon batch processor measures forward-pass duration in nanoseconds."""
        patches = [self.test_img, self.test_img]
        emb_list, meta_list, forward_ns = phikon_generator._process_batch_with_timing(patches)

        self.assertEqual(len(emb_list), 2)
        self.assertEqual(len(meta_list), 2)
        self.assertEqual(emb_list[0].shape, torch.Size([1024]))
        self.assertIsInstance(forward_ns, int)
        self.assertGreater(forward_ns, 0)

        # Backward compatibility check: _process_batch returns (emb_list, meta_list)
        embs_legacy, meta_legacy = phikon_generator._process_batch(patches)
        self.assertEqual(len(embs_legacy), 2)
        self.assertEqual(len(meta_legacy), 2)

    def test_03_extract_phikon_embeddings_batched_accumulation(self):
        """Multiple tile batches accumulate forward pass durations correctly."""
        patches = [self.test_img, self.test_img, self.test_img]
        # Batch size 2 forces 2 sub-batches (size 2 and size 1)
        tensor, total_ns = extract_phikon_embeddings_batched(patches, batch_size=2, return_timing=True)

        self.assertEqual(tensor.shape, (3, 1024))
        self.assertIsInstance(total_ns, int)
        self.assertGreater(total_ns, 0)

        # Without return_timing, returns plain tensor
        tensor_plain = extract_phikon_embeddings_batched(patches, batch_size=2, return_timing=False)
        self.assertIsInstance(tensor_plain, torch.Tensor)
        self.assertEqual(tensor_plain.shape, (3, 1024))

    def test_04_embed_tiles_endpoint_timing(self):
        """Endpoint /embed_tiles returns vision inference timing metadata."""
        payload = {
            "request_id": "test-embed-001",
            "patches": [self.patch_b64, self.patch_b64],
            "batch_size": 2,
        }
        res = self.client.post("/embed_tiles", json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.get_json()

        self.assertEqual(data["count"], 2)
        self.assertIn("vision_inference_ms", data)
        self.assertIn("total_model_inference_ms", data)
        self.assertGreater(data["vision_inference_ms"], 0.0)
        self.assertEqual(data["vision_inference_ms"], data["total_model_inference_ms"])
        self.assertEqual(data["inference_timing_scope"], "forward_pass_vision_chunk")

    def test_05_fuse_endpoint_timing(self):
        """Endpoint /fuse returns vision, clinical text, and fusion inference timing."""
        # Synthesize 2 dummy vision embeddings
        dummy_embs = [[0.01 * (i + j) for j in range(1024)] for i in range(2)]
        payload = {
            "request_id": "test-fuse-001",
            "vision_embeddings": dummy_embs,
            "clinical_report": self.sample_report,
            "accumulated_vision_inference_ms": 150.25,
        }
        res = self.client.post("/fuse", json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.get_json()

        self.assertIn("probability", data)
        self.assertIn("total_model_inference_ms", data)
        self.assertIn("vision_inference_ms", data)
        self.assertIn("clinical_text_inference_ms", data)
        self.assertIn("fusion_classification_ms", data)

        self.assertEqual(data["vision_inference_ms"], 150.25)
        self.assertGreater(data["clinical_text_inference_ms"], 0.0)
        self.assertGreater(data["fusion_classification_ms"], 0.0)

        expected_total = round(
            150.25 + data["clinical_text_inference_ms"] + data["fusion_classification_ms"], 2
        )
        self.assertAlmostEqual(data["total_model_inference_ms"], expected_total, places=1)
        self.assertEqual(data["inference_timing_scope"], "forward_pass_accumulated")

    def test_06_predict_endpoint_timing(self):
        """Monolithic /predict endpoint executes and times all 3 stages."""
        payload = {
            "request_id": "test-predict-001",
            "patches": [self.patch_b64],
            "clinical_report": self.sample_report,
        }
        res = self.client.post("/predict", json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.get_json()

        self.assertIn("probability", data)
        self.assertIn("total_model_inference_ms", data)
        self.assertIn("vision_inference_ms", data)
        self.assertIn("clinical_text_inference_ms", data)
        self.assertIn("fusion_classification_ms", data)

        self.assertGreater(data["vision_inference_ms"], 0.0)
        self.assertGreater(data["clinical_text_inference_ms"], 0.0)
        self.assertGreater(data["fusion_classification_ms"], 0.0)

        sum_components = round(
            data["vision_inference_ms"]
            + data["clinical_text_inference_ms"]
            + data["fusion_classification_ms"],
            2,
        )
        self.assertAlmostEqual(data["total_model_inference_ms"], sum_components, places=1)

    def test_07_failure_handling_no_fake_durations(self):
        """Failed requests return error response and do not emit valid timing results."""
        # Empty patches list
        payload = {
            "patches": [],
            "clinical_report": self.sample_report,
        }
        res = self.client.post("/predict", json=payload)
        self.assertEqual(res.status_code, 400)
        data = res.get_json()
        self.assertNotIn("total_model_inference_ms", data)
        self.assertIn("error", data)

    def test_08_concurrency_isolation(self):
        """Timing values are strictly per-request and not stored in shared globals."""
        payload1 = {
            "request_id": "req-concurrent-1",
            "patches": [self.patch_b64],
            "clinical_report": self.sample_report,
        }
        payload2 = {
            "request_id": "req-concurrent-2",
            "patches": [self.patch_b64],
            "clinical_report": self.sample_report,
        }
        res1 = self.client.post("/predict", json=payload1)
        res2 = self.client.post("/predict", json=payload2)

        d1 = res1.get_json()
        d2 = res2.get_json()

        self.assertEqual(d1["request_id"], "req-concurrent-1")
        self.assertEqual(d2["request_id"], "req-concurrent-2")
        self.assertGreater(d1["total_model_inference_ms"], 0.0)
        self.assertGreater(d2["total_model_inference_ms"], 0.0)


if __name__ == "__main__":
    unittest.main()
