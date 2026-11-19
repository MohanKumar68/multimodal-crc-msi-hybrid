import os
import sys
import json
import torch
import time
import psutil
import platform
import numpy as np
import pandas as pd
from datetime import datetime
from sklearn.metrics import accuracy_score, roc_auc_score, classification_report, confusion_matrix
from torch.utils.data import DataLoader

# Add project root to Python path
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, PROJECT_ROOT)

# Import components from existing project structure
import importlib
import importlib.util
from src.multimodal_dataset import MultimodalTCGADataset
from src.architectures.cross_attention_fusion import SummaryToPatchCrossAttention

# Paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BENCHMARK_DIR = os.path.join(PROJECT_ROOT, "benchmarks", "fp16")
REPORT_PATH = os.path.join(BENCHMARK_DIR, "fp16_report.txt")
JSON_PATH = os.path.join(BENCHMARK_DIR, "fp16_results.json")
CSV_PATH = os.path.join(BENCHMARK_DIR, "fp16_results.csv")

REGISTRY = os.path.join(PROJECT_ROOT, "data", "patient_registry.json")
TENSORS = os.path.join(PROJECT_ROOT, "data", "clean_tensors")
FP16_WEIGHTS = os.path.join(PROJECT_ROOT, "models", "optimized", "fp16", "fusion_model_fp16.pth")
VALIDATION_KEYS = os.path.join(PROJECT_ROOT, "results", "actual_validation_keys.json")

def set_seed(seed=42):
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)

def get_system_info():
    info = {
        'cpu_model': platform.processor(),
        'physical_cores': psutil.cpu_count(logical=False),
        'logical_cores': psutil.cpu_count(logical=True),
        'system_ram_gb': psutil.virtual_memory().total / (1024**3),
        'os': platform.platform(),
        'python_version': platform.python_version(),
        'pytorch_version': torch.__version__,
        'gpu_available': torch.cuda.is_available()
    }
    return info

def run_benchmark():
    os.makedirs(BENCHMARK_DIR, exist_ok=True)
    device = torch.device('cpu')
    set_seed(42)

    # Load dataset
    print("[INFO] Loading validation dataset...")
    full_dataset = MultimodalTCGADataset(REGISTRY, TENSORS, max_patches=1000)
    with open(VALIDATION_KEYS, "r") as f:
        val_keys = set(json.load(f))
    eval_patients = [p for p in full_dataset.valid_patients if p["id"] in val_keys]
    full_dataset.valid_patients = eval_patients
    eval_loader = DataLoader(full_dataset, batch_size=1, shuffle=False)
    print(f"[OK] Loaded {len(eval_patients)} validation patients")

    # Load FP16 Model
    print("[INFO] Loading FP16 model...")
    model = SummaryToPatchCrossAttention().to(device).half()
    model.load_state_dict(torch.load(FP16_WEIGHTS, map_location=device))
    model.eval()

    # Warmup
    print("[INFO] Warming up...")
    with torch.no_grad():
        for batch in eval_loader:
            model(batch["vision"].half(), batch["text"].half())
            break

    # Benchmarking
    print("[INFO] Benchmarking...")
    true_labels = []
    pred_probs = []
    latencies = []
    patient_ids = []

    start_all = time.perf_counter()
    with torch.no_grad():
        for batch in eval_loader:
            vision = batch["vision"].half()
            text = batch["text"].half()
            label = batch["label"].item()
            patient_id = batch["patient_id"][0]

            start_inf = time.perf_counter()
            prob, _ = model(vision, text)
            end_inf = time.perf_counter()

            latencies.append((end_inf - start_inf) * 1000)
            true_labels.append(label)
            pred_probs.append(prob.item())
            patient_ids.append(patient_id)
    end_all = time.perf_counter()

    # Metrics
    preds = [1 if p >= 0.83 else 0 for p in pred_probs]
    acc = accuracy_score(true_labels, preds)
    auc = roc_auc_score(true_labels, pred_probs)

    # Load FP32 results for comparison
    fp32_results = {}
    with open(os.path.join(PROJECT_ROOT, "benchmarks", "fp32", "fp32_results.json"), "r") as f:
        fp32_results = json.load(f)

    # Save Results
    results = {
        "timestamp": datetime.now().isoformat(),
        "accuracy": acc,
        "roc_auc": auc,
        "avg_latency_ms": np.mean(latencies),
        "throughput_pts_sec": len(eval_patients) / (end_all - start_all),
        "throughput_pts_min": len(eval_patients) / (end_all - start_all) * 60,
        "peak_ram_mb": psutil.Process(os.getpid()).memory_info().rss / 1024 / 1024,
        "patient_ids": patient_ids,
        "fp32_results": fp32_results
    }

    with open(JSON_PATH, "w") as f:
        json.dump(results, f, indent=2)

    # Save CSV
    csv_data = {
        'timestamp': datetime.now().isoformat(),
        'accuracy': acc,
        'roc_auc': auc,
        'avg_latency_ms': np.mean(latencies),
        'throughput_pts_sec': len(eval_patients) / (end_all - start_all),
        'throughput_pts_min': len(eval_patients) / (end_all - start_all) * 60,
        'peak_ram_mb': psutil.Process(os.getpid()).memory_info().rss / 1024 / 1024,
        'python_version': platform.python_version(),
        'pytorch_version': torch.__version__
    }

    df = pd.DataFrame([csv_data])
    df.to_csv(CSV_PATH, index=False)

    # Save Report
    with open(REPORT_PATH, "w") as f:
        f.write("FP16 PERFORMANCE BENCHMARK REPORT\n")
        f.write("=" * 50 + "\n\n")
        f.write(f"Generated on: {datetime.now().isoformat()}\n")
        f.write(f"Model: FP16 (fusion_model_fp16.pth)\n")
        f.write(f"Patients: {len(eval_patients)}\n")
        f.write(f"Accuracy: {acc:.4f}\n")
        f.write(f"ROC-AUC: {auc:.4f}\n")
        f.write(f"Average Latency: {np.mean(latencies):.2f} ms\n")
        f.write(f"Throughput: {len(eval_patients) / (end_all - start_all):.2f} patients/sec ({len(eval_patients) / (end_all - start_all) * 60:.2f} patients/min)\n")
        f.write(f"Peak RAM: {psutil.Process(os.getpid()).memory_info().rss / 1024 / 1024:.2f} MB\n")
        f.write(f"\nFP32 vs FP16 Comparison:\n")
        f.write(f"  Accuracy: FP32 = {fp32_results['predictive_performance']['accuracy']:.4f} | FP16 = {acc:.4f} | Diff = {abs(fp32_results['predictive_performance']['accuracy'] - acc):.4f}\n")
        f.write(f"  ROC-AUC: FP32 = {fp32_results['predictive_performance']['roc_auc']:.4f} | FP16 = {auc:.4f} | Diff = {abs(fp32_results['predictive_performance']['roc_auc'] - auc):.4f}\n")
        f.write(f"  Avg Latency: FP32 = {fp32_results['inference_latency']['average_latency_ms_per_patient']:.2f} ms | FP16 = {np.mean(latencies):.2f} ms | Diff = {abs(fp32_results['inference_latency']['average_latency_ms_per_patient'] - np.mean(latencies)):.2f} ms\n")
        f.write(f"  Model Size: FP32 = {fp32_results['model_info']['model_file_size_mb']:.2f} MB | FP16 = {os.path.getsize(FP16_WEIGHTS) / (1024 * 1024):.2f} MB | Diff = {abs(fp32_results['model_file_size'] - os.path.getsize(FP16_WEIGHTS) / (1024 * 1024)):.2f} MB\n")
        f.write(f"  Throughput: FP32 = {fp32_results['inference_latency']['throughput_patients_per_sec']:.2f} patients/sec | FP16 = {len(eval_patients) / (end_all - start_all):.2f} patients/sec | Diff = {abs(fp32_results['inference_latency']['throughput_patients_per_sec'] - len(eval_patients) / (end_all - start_all)):.2f} patients/sec\n")

    print(f"[OK] Benchmark complete. Results saved to: {BENCHMARK_DIR}")

if __name__ == "__main__":
    run_benchmark()