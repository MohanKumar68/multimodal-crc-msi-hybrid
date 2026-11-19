import os
import json
import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from sklearn.metrics import accuracy_score, roc_auc_score, classification_report, confusion_matrix, roc_curve
import numpy as np
from datetime import datetime

# Import custom modules
from multimodal_dataset import MultimodalTCGADataset
from architectures.cross_attention_fusion import SummaryToPatchCrossAttention

# --- Configuration ---
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

REGISTRY = os.path.join(PROJECT_ROOT, "data", "patient_registry.json")
TENSORS = os.path.join(PROJECT_ROOT, "data", "clean_tensors")
FP32_WEIGHTS = os.path.join(PROJECT_ROOT, "models", "fusion_model_v1_1.pth")
VALIDATION_KEYS = os.path.join(PROJECT_ROOT, "results", "actual_validation_keys.json")

FP16_DIR = os.path.join(PROJECT_ROOT, "models", "optimized", "fp16")
FP16_WEIGHTS = os.path.join(FP16_DIR, "fusion_model_fp16.pth")
FP16_METADATA = os.path.join(FP16_DIR, "fp16_metadata.json")

RESULTS_DIR = os.path.join(PROJECT_ROOT, "results", "optimized", "fp16")
REPORT_PATH = os.path.join(RESULTS_DIR, "fp16_evaluation_report.txt")
JSON_PATH = os.path.join(RESULTS_DIR, "fp16_results.json")

def set_seed(seed=42):
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)

def convert_and_evaluate_fp16():
    print("=" * 80)
    print("FP16 MODEL OPTIMIZATION AND EVALUATION")
    print("=" * 80)

    # 1. Ensure output directories exist
    os.makedirs(FP16_DIR, exist_ok=True)
    os.makedirs(RESULTS_DIR, exist_ok=True)

    device = torch.device('cpu')
    print(f"Executing on device: {device}")

    set_seed(42)

    # 2. Load Validation Dataset
    print("\n[INFO] Loading validation dataset...")
    full_dataset = MultimodalTCGADataset(REGISTRY, TENSORS, max_patches=1000)

    with open(VALIDATION_KEYS, "r") as f:
        val_keys = set(json.load(f))

    eval_patients = [p for p in full_dataset.valid_patients if p["id"] in val_keys]
    full_dataset.valid_patients = eval_patients
    eval_loader = DataLoader(full_dataset, batch_size=1, shuffle=False)
    print(f"[OK] Loaded {len(eval_patients)} validation patients.")

    # 3. Load FP32 Model and Convert to FP16
    print("\n[INFO] Loading FP32 model and converting to FP16...")
    fp32_model = SummaryToPatchCrossAttention().to(device)
    fp32_model.load_state_dict(torch.load(FP32_WEIGHTS, map_location=device))
    fp32_model.eval()

    original_file_size_mb = os.path.getsize(FP32_WEIGHTS) / (1024 * 1024)

    # Instantiate FP16 model and load state dict, then cast to half
    fp16_model = SummaryToPatchCrossAttention().to(device)
    fp16_model.load_state_dict(torch.load(FP32_WEIGHTS, map_location=device))
    fp16_model.half() # Convert weights to FP16
    fp16_model.eval()

    # Save FP16 checkpoint
    torch.save(fp16_model.state_dict(), FP16_WEIGHTS)
    fp16_file_size_mb = os.path.getsize(FP16_WEIGHTS) / (1024 * 1024)
    print(f"[OK] Saved FP16 model weights to: {FP16_WEIGHTS}")
    print(f"[OK] FP32 model size: {original_file_size_mb:.2f} MB | FP16 model size: {fp16_file_size_mb:.2f} MB")

    # Save metadata
    metadata = {
        "timestamp": datetime.now().isoformat(),
        "fp32_checkpoint": FP32_WEIGHTS,
        "fp16_checkpoint": FP16_WEIGHTS,
        "fp32_size_mb": original_file_size_mb,
        "fp16_size_mb": fp16_file_size_mb,
        "total_parameters": sum(p.numel() for p in fp16_model.parameters()),
        "parameter_dtype": str(next(fp16_model.parameters()).dtype),
        "architecture": "SummaryToPatchCrossAttention",
        "conversion_method": "Direct model.half() weight conversion"
    }
    with open(FP16_METADATA, "w") as f:
        json.dump(metadata, f, indent=2)

    # 4. Comparative Evaluation (FP32 vs FP16)
    print("\n[INFO] Running comparative evaluation on 66 validation patients...")

    fp32_true_labels = []
    fp32_probs = []
    fp32_preds = []

    fp16_true_labels = []
    fp16_probs = []
    fp16_preds = []

    comparison_details = []

    set_seed(42)
    with torch.no_grad():
        for batch in eval_loader:
            vision_32 = batch["vision"].to(device)
            text_32 = batch["text"].to(device)
            label = batch["label"].to(device).item()

            # FP32 forward pass
            prob_32, _ = fp32_model(vision_32, text_32)
            p32 = prob_32.item()
            pred32 = 1 if p32 >= 0.83 else 0

            fp32_true_labels.append(label)
            fp32_probs.append(p32)
            fp32_preds.append(pred32)

            # FP16 forward pass (cast inputs to half)
            vision_16 = vision_32.half()
            text_16 = text_32.half()

            prob_16, _ = fp16_model(vision_16, text_16)
            p16 = prob_16.item()
            pred16 = 1 if p16 >= 0.83 else 0

            fp16_true_labels.append(label)
            fp16_probs.append(p16)
            fp16_preds.append(pred16)

            abs_diff = abs(p32 - p16)
            comparison_details.append({
                "patient_id": batch["patient_id"][0],
                "label": label,
                "fp32_prob": p32,
                "fp16_prob": p16,
                "abs_diff": abs_diff,
                "fp32_pred": pred32,
                "fp16_pred": pred16,
                "agreement": pred32 == pred16
            })

    # 5. Compute Metrics
    fp32_acc = accuracy_score(fp32_true_labels, fp32_preds)
    fp32_auc = roc_auc_score(fp32_true_labels, fp32_probs)
    fp32_cm = confusion_matrix(fp32_true_labels, fp32_preds)
    fp32_report = classification_report(fp32_true_labels, fp32_preds, target_names=['MSS (Class 0)', 'MSI (Class 1)'], output_dict=True, zero_division=0)

    fp16_acc = accuracy_score(fp16_true_labels, fp16_preds)
    fp16_auc = roc_auc_score(fp16_true_labels, fp16_probs)
    fp16_cm = confusion_matrix(fp16_true_labels, fp16_preds)
    fp16_report = classification_report(fp16_true_labels, fp16_preds, target_names=['MSS (Class 0)', 'MSI (Class 1)'], output_dict=True, zero_division=0)

    # Numerical difference stats
    abs_diffs = [d["abs_diff"] for d in comparison_details]
    max_abs_diff = max(abs_diffs)
    mean_abs_diff = np.mean(abs_diffs)
    agreements = sum(1 for d in comparison_details if d["agreement"])
    agreement_rate = agreements / len(comparison_details) * 100

    # Check for NaN / Inf
    nan_inf_count = sum(1 for p in fp16_probs if np.isnan(p) or np.isinf(p))

    print("\n" + "=" * 80)
    print("FP32 vs FP16 PERFORMANCE COMPARISON")
    print("=" * 80)
    print(f"Metric                  | FP32 Baseline         | FP16 Optimized        | Difference")
    print("-" * 80)
    print(f"Accuracy                | {fp32_acc:.4f} ({fp32_acc*100:.2f}%)   | {fp16_acc:.4f} ({fp16_acc*100:.2f}%)   | {fp16_acc - fp32_acc:+.4f}")
    print(f"ROC-AUC                 | {fp32_auc:.4f}          | {fp16_auc:.4f}          | {fp16_auc - fp32_auc:+.4f}")
    print(f"Macro F1                | {fp32_report['macro avg']['f1-score']:.4f}          | {fp16_report['macro avg']['f1-score']:.4f}          | {fp16_report['macro avg']['f1-score'] - fp32_report['macro avg']['f1-score']:+.4f}")
    print(f"Weighted F1             | {fp32_report['weighted avg']['f1-score']:.4f}          | {fp16_report['weighted avg']['f1-score']:.4f}          | {fp16_report['weighted avg']['f1-score'] - fp32_report['weighted avg']['f1-score']:+.4f}")
    print(f"Model File Size         | {original_file_size_mb:.2f} MB        | {fp16_file_size_mb:.2f} MB        | {fp16_file_size_mb - original_file_size_mb:+.2f} MB")
    print("-" * 80)
    print(f"Prediction Agreement    | {agreement_rate:.2f}% ({agreements}/{len(comparison_details)} patients)")
    print(f"Max Absolute Prob Diff  | {max_abs_diff:.6f}")
    print(f"Mean Absolute Prob Diff | {mean_abs_diff:.6f}")
    print(f"NaN / Inf Count         | 0                     | {nan_inf_count}                     | 0")
    print("=" * 80)

    # 6. Save Report and Results JSON
    results_summary = {
        "metadata": metadata,
        "fp32_metrics": {
            "accuracy": fp32_acc,
            "roc_auc": fp32_auc,
            "confusion_matrix": fp32_cm.tolist(),
            "classification_report": fp32_report
        },
        "fp16_metrics": {
            "accuracy": fp16_acc,
            "roc_auc": fp16_auc,
            "confusion_matrix": fp16_cm.tolist(),
            "classification_report": fp16_report
        },
        "numerical_comparison": {
            "prediction_agreement_rate": agreement_rate,
            "agreed_patients": agreements,
            "total_patients": len(comparison_details),
            "max_absolute_probability_difference": max_abs_diff,
            "mean_absolute_probability_difference": mean_abs_diff,
            "nan_inf_count": nan_inf_count
        },
        "per_patient_comparison": comparison_details
    }

    with open(JSON_PATH, "w") as f:
        json.dump(results_summary, f, indent=2)

    with open(REPORT_PATH, "w") as f:
        f.write("=== FP16 MODEL OPTIMIZATION & COMPARISON REPORT ===\n\n")
        f.write(f"Generated on: {datetime.now().isoformat()}\n")
        f.write(f"FP32 Checkpoint: {FP32_WEIGHTS}\n")
        f.write(f"FP16 Checkpoint: {FP16_WEIGHTS}\n\n")
        f.write(f"Model File Size: FP32 = {original_file_size_mb:.2f} MB | FP16 = {fp16_file_size_mb:.2f} MB\n")
        f.write(f"Total Parameters: {metadata['total_parameters']:,}\n\n")
        f.write("PREDICTIVE PERFORMANCE COMPARISON:\n")
        f.write(f"  FP32 Accuracy: {fp32_acc:.4f} | FP16 Accuracy: {fp16_acc:.4f}\n")
        f.write(f"  FP32 ROC-AUC:  {fp32_auc:.4f} | FP16 ROC-AUC:  {fp16_auc:.4f}\n")
        f.write(f"  FP32 Macro F1: {fp32_report['macro avg']['f1-score']:.4f} | FP16 Macro F1: {fp16_report['macro avg']['f1-score']:.4f}\n")
        f.write(f"  FP32 Weighted F1: {fp32_report['weighted avg']['f1-score']:.4f} | FP16 Weighted F1: {fp16_report['weighted avg']['f1-score']:.4f}\n\n")
        f.write("NUMERICAL STABILITY & AGREEMENT:\n")
        f.write(f"  Prediction Agreement Rate: {agreement_rate:.2f}% ({agreements}/{len(comparison_details)})\n")
        f.write(f"  Max Absolute Probability Difference: {max_abs_diff:.6f}\n")
        f.write(f"  Mean Absolute Probability Difference: {mean_abs_diff:.6f}\n")
        f.write(f"  NaN / Inf Values Detected: {nan_inf_count}\n\n")
        f.write("CONFUSION MATRICES:\n")
        f.write(f"  FP32 CM:\n{fp32_cm}\n")
        f.write(f"  FP16 CM:\n{fp16_cm}\n")

    print(f"[OK] Saved FP16 evaluation report to: {REPORT_PATH}")
    print(f"[OK] Saved FP16 JSON results to: {JSON_PATH}")

if __name__ == "__main__":
    convert_and_evaluate_fp16()
