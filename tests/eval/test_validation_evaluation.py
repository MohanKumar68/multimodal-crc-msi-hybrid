"""
Unit and integration tests for Phase 3C reproducible validation evaluation outputs.

Verifies:
1. Exact row count (66 patients) in results/validation_predictions.csv
2. Uniqueness and validity of patient IDs
3. Zero overlap with training cohort
4. Label integrity (0 for MSS, 1 for MSI)
5. Valid probability ranges [0, 1]
6. Exact concordance between CSV predictions and validation_metrics.json
7. Strict confusion matrix sums (TN + FP + FN + TP == 66)
"""

import os
import json
import csv
import pytest
import numpy as np

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PREDICTIONS_CSV = os.path.join(PROJECT_ROOT, "results", "validation_predictions.csv")
METRICS_JSON = os.path.join(PROJECT_ROOT, "results", "validation_metrics.json")
REPORT_MD = os.path.join(PROJECT_ROOT, "results", "validation_evaluation_report.md")
VAL_KEYS_PATH = os.path.join(PROJECT_ROOT, "results", "actual_validation_keys.json")
REGISTRY_PATH = os.path.join(PROJECT_ROOT, "data", "patient_registry.json")


def test_artifacts_exist():
    """Verify that all required output artifacts were generated."""
    assert os.path.exists(PREDICTIONS_CSV), "Missing validation_predictions.csv"
    assert os.path.exists(METRICS_JSON), "Missing validation_metrics.json"
    assert os.path.exists(REPORT_MD), "Missing validation_evaluation_report.md"
    assert os.path.getsize(PREDICTIONS_CSV) > 0
    assert os.path.getsize(METRICS_JSON) > 0
    assert os.path.getsize(REPORT_MD) > 0


def test_predictions_csv_integrity():
    """Verify row count, unique IDs, probability boundaries, and zero overlap."""
    with open(VAL_KEYS_PATH, "r") as f:
        expected_val_keys = json.load(f)
    with open(REGISTRY_PATH, "r") as f:
        registry = json.load(f)

    expected_set = set(expected_val_keys)
    train_set = set(registry.keys()) - expected_set

    with open(PREDICTIONS_CSV, "r", encoding="utf-8") as f:
        reader = list(csv.DictReader(f))

    assert len(reader) == 66, f"Expected 66 rows in predictions CSV, found {len(reader)}"

    csv_pids = [row["patient_id"] for row in reader]
    assert len(set(csv_pids)) == 66, "Duplicate patient IDs found in predictions CSV"
    assert set(csv_pids) == expected_set, "Mismatch between CSV patient IDs and actual_validation_keys.json"
    assert len(set(csv_pids).intersection(train_set)) == 0, "Patient overlap detected with training cohort!"

    # Verify fields and types
    for row in reader:
        pid = row["patient_id"]
        true_lbl = int(row["ground_truth_label"])
        true_cls = row["ground_truth_class"]
        
        # Concordance with registry
        assert registry[pid]["label"] == true_lbl
        assert registry[pid]["class_name"] == true_cls
        assert true_lbl in (0, 1)
        assert (true_lbl == 1 and true_cls == "MSI") or (true_lbl == 0 and true_cls == "MSS")

        # Probabilities
        p_ca = float(row["cross_attention_probability_det"])
        p_lf = float(row["late_fusion_probability_det"])
        assert 0.0 <= p_ca <= 1.0, f"CA probability out of range: {p_ca}"
        assert 0.0 <= p_lf <= 1.0, f"LF probability out of range: {p_lf}"

        # Binary predictions at tau = 0.83
        pred_ca = int(row["cross_attention_prediction_tau_0_83_det"])
        pred_lf = int(row["late_fusion_prediction_tau_0_83_det"])
        assert pred_ca == (1 if p_ca >= 0.83 else 0)
        assert pred_lf == (1 if p_lf >= 0.83 else 0)


def test_metrics_json_concordance():
    """Verify concordance between predictions CSV and metrics JSON."""
    with open(PREDICTIONS_CSV, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    with open(METRICS_JSON, "r", encoding="utf-8") as f:
        metrics = json.load(f)

    # Check metadata
    assert metrics["metadata"]["validation_patient_count"] == 66
    assert metrics["metadata"]["mss_count"] == 50
    assert metrics["metadata"]["msi_count"] == 16
    assert metrics["metadata"]["operating_threshold"] == 0.83

    # Recompute CA confusion matrix from CSV
    ca_cm = metrics["cross_attention"]["deterministic_subsampling_tau_0_83"]["confusion_matrix"]
    tn_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 0 and int(r["cross_attention_prediction_tau_0_83_det"]) == 0)
    fp_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 0 and int(r["cross_attention_prediction_tau_0_83_det"]) == 1)
    fn_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 1 and int(r["cross_attention_prediction_tau_0_83_det"]) == 0)
    tp_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 1 and int(r["cross_attention_prediction_tau_0_83_det"]) == 1)

    assert ca_cm["tn"] == tn_calc
    assert ca_cm["fp"] == fp_calc
    assert ca_cm["fn"] == fn_calc
    assert ca_cm["tp"] == tp_calc
    assert (ca_cm["tn"] + ca_cm["fp"] + ca_cm["fn"] + ca_cm["tp"]) == 66

    # Recompute LF confusion matrix from CSV
    lf_cm = metrics["late_fusion"]["deterministic_subsampling_tau_0_83"]["confusion_matrix"]
    lf_tn_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 0 and int(r["late_fusion_prediction_tau_0_83_det"]) == 0)
    lf_fp_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 0 and int(r["late_fusion_prediction_tau_0_83_det"]) == 1)
    lf_fn_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 1 and int(r["late_fusion_prediction_tau_0_83_det"]) == 0)
    lf_tp_calc = sum(1 for r in rows if int(r["ground_truth_label"]) == 1 and int(r["late_fusion_prediction_tau_0_83_det"]) == 1)

    assert lf_cm["tn"] == lf_tn_calc
    assert lf_cm["fp"] == lf_fp_calc
    assert lf_cm["fn"] == lf_fn_calc
    assert lf_cm["tp"] == lf_tp_calc
    assert (lf_cm["tn"] + lf_cm["fp"] + lf_cm["fn"] + lf_cm["tp"]) == 66

    # Accuracy check
    ca_acc = metrics["cross_attention"]["deterministic_subsampling_tau_0_83"]["accuracy"]
    assert abs(ca_acc - ((tn_calc + tp_calc) / 66.0)) < 1e-6


def test_bootstrap_intervals_plausibility():
    """Verify that bootstrap confidence intervals encompass the point estimates."""
    with open(METRICS_JSON, "r", encoding="utf-8") as f:
        metrics = json.load(f)

    ca_data = metrics["cross_attention"]["deterministic_subsampling_tau_0_83"]
    ci = ca_data["bootstrap_95ci"]

    assert ci["n_replicates"] == 1000
    assert ci["auc_valid_replicates"] >= 995

    # Check intervals bounds
    assert ci["accuracy_95ci"][0] <= ca_data["accuracy"] <= ci["accuracy_95ci"][1]
    assert ci["roc_auc_95ci"][0] <= ca_data["roc_auc"] <= ci["roc_auc_95ci"][1]
    assert ci["sensitivity_95ci"][0] <= ca_data["sensitivity"] <= ci["sensitivity_95ci"][1]
    assert ci["specificity_95ci"][0] <= ca_data["specificity"] <= ci["specificity_95ci"][1]
    assert ci["precision_95ci"][0] <= ca_data["precision"] <= ci["precision_95ci"][1]
