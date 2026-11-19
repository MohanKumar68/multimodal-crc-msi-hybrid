import torch
import time
import json
import psutil
import os
import platform
from datetime import datetime
import numpy as np
from torch.utils.data import DataLoader
from sklearn.metrics import accuracy_score, roc_auc_score, classification_report, confusion_matrix

# Import existing components
from multimodal_dataset import MultimodalTCGADataset
from architectures.cross_attention_fusion import SummaryToPatchCrossAttention

# --- Configuration ---
# Resolve paths relative to the project root (parent of src/)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

REGISTRY = os.path.join(PROJECT_ROOT, "data", "patient_registry.json")
TENSORS = os.path.join(PROJECT_ROOT, "data", "clean_tensors")
MODEL_WEIGHTS = os.path.join(PROJECT_ROOT, "models", "fusion_model_v1_1.pth")
VALIDATION_KEYS = os.path.join(PROJECT_ROOT, "results", "actual_validation_keys.json")

# Global performance metrics storage
performance_metrics = {}
validation_details = {}

def get_system_info():
    """Collect system and environment information"""
    info = {}

    # CPU information
    try:
        import cpuinfo
        info['cpu_model'] = cpuinfo.get_cpu_info().get('brand_raw', 'Unknown')
    except ImportError:
        info['cpu_model'] = 'CPU info not available (cpuinfo package required)'

    # Get system memory
    process = psutil.Process(os.getpid())
    info['process_memory_mb'] = process.memory_info().rss / 1024 / 1024

    # System memory
    try:
        info['system_ram_gb'] = psutil.virtual_memory().total / (1024**3)
        info['system_ram_available_gb'] = psutil.virtual_memory().available / (1024**3)
    except:
        info['system_ram_gb'] = 'Unknown'
        info['system_ram_available_gb'] = 'Unknown'

    # Hardware info
    info['physical_cores'] = psutil.cpu_count(logical=False)
    info['logical_cores'] = psutil.cpu_count(logical=True)
    info['cpu_percent'] = psutil.cpu_percent(interval=0.1)

    # GPU info
    info['gpu_available'] = torch.cuda.is_available()
    if info['gpu_available']:
        info['gpu_name'] = torch.cuda.get_device_name(0)
        info['gpu_memory_gb'] = torch.cuda.get_device_properties(0).total_memory / (1024**3)
        info['gpu_utilization'] = torch.cuda.utilization()
        info['gpu_memory_used_gb'] = torch.cuda.memory_allocated(0) / (1024**3)
    else:
        info['gpu_name'] = 'None'
        info['gpu_memory_gb'] = 0
        info['gpu_utilization'] = 0
        info['gpu_memory_used_gb'] = 0

    # OS info
    info['os'] = platform.system()
    info['os_version'] = platform.version()

    # Python and PyTorch versions
    info['python_version'] = platform.python_version()
    info['pytorch_version'] = torch.__version__

    # Timestamp
    info['timestamp'] = datetime.now().isoformat()

    return info

def measure_ram_memory():
    """Measure current RAM usage in MB"""
    process = psutil.Process(os.getpid())
    return process.memory_info().rss / 1024 / 1024

def load_model_with_memory_tracking(model_path, device):
    """Load model with RAM tracking"""
    ram_before = measure_ram_memory()

    model = SummaryToPatchCrossAttention().to(device)
    model.load_state_dict(torch.load(model_path, map_location=device))
    model.eval()

    ram_after = measure_ram_memory()

    return model, ram_before, ram_after

def count_parameters(model):
    """Count trainable and total parameters"""
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total_params = sum(p.numel() for p in model.parameters())

    return trainable_params, total_params

def measure_model_size(model_path):
    """Get model file size in MB"""
    return os.path.getsize(model_path) / (1024 * 1024)

def validate_with_timing(model, eval_loader, device):
    """Run validation with timing measurements"""
    true_labels = []
    pred_probs = []
    pred_classes = []

    # Store per-patient timing
    patient_timings = []

    print("\n[INFO]  Running FP32 inference timing measurements...")

    with torch.no_grad():
        for batch_idx, batch in enumerate(eval_loader):
            # Start timing for this patient
            patient_start_time = time.perf_counter()

            vision = batch["vision"].to(device)
            text = batch["text"].to(device)
            label = batch["label"].to(device).item()

            # Measure inference time
            inference_start = time.perf_counter()
            prob, _ = model(vision, text)
            inference_end = time.perf_counter()

            patient_end_time = time.perf_counter()

            prob_val = prob.item()

            # Calculate times
            inference_time = (inference_end - inference_start) * 1000  # Convert to ms
            total_time = (patient_end_time - patient_start_time) * 1000

            patient_timings.append({
                'patient_id': batch['patient_id'][0] if 'patient_id' in batch else f'patient_{batch_idx}',
                'inference_time_ms': inference_time,
                'total_time_ms': total_time,
                'label': label,
                'prob': prob_val
            })

            true_labels.append(label)
            pred_probs.append(prob_val)
            pred_classes.append(1 if prob_val >= 0.83 else 0)

    return true_labels, pred_probs, pred_classes, patient_timings

def collect_performance_metrics():
    """Collect all performance metrics"""
    print("=" * 80)
    print("FP32 BENCHMARKING - PERFORMANCE MEASUREMENTS")
    print("=" * 80)

    # Get system information
    print("\n[INFO] Collecting system information...")
    system_info = get_system_info()
    performance_metrics['system_info'] = system_info

    # Determine device
    if torch.cuda.is_available():
        device = torch.device('cuda')
        print(f"Executing on device: {device} (CUDA)")
    elif torch.backends.mps.is_available():
        device = torch.device('mps')
        print(f"Executing on device: {device} (Apple Silicon)")
    else:
        device = torch.device('cpu')
        print(f"Executing on device: {device} (CPU)")

    # Load dataset
    print("\n[INFO] Loading validation dataset...")
    full_dataset = MultimodalTCGADataset(REGISTRY, TENSORS, max_patches=1000)

    # Load validation keys
    with open(VALIDATION_KEYS, "r") as f:
        val_keys = set(json.load(f))
    print(f"[INFO] Found {len(val_keys)} validation patients")

    # Filter validation patients
    eval_patients = [p for p in full_dataset.valid_patients if p["id"] in val_keys]
    full_dataset.valid_patients = eval_patients

    print(f"[OK] Loaded {len(eval_patients)} validation patients")

    # Create DataLoader
    eval_loader = DataLoader(full_dataset, batch_size=1, shuffle=False)

    # --- PART A: MODEL INFORMATION ---
    print("\n" + "=" * 80)
    print("A. MODEL INFORMATION")
    print("=" * 80)

    # Model file size
    model_size_mb = measure_model_size(MODEL_WEIGHTS)
    performance_metrics['model_info'] = {
        'model_file_path': os.path.abspath(MODEL_WEIGHTS),
        'model_file_size_mb': round(model_size_mb, 2)
    }
    print(f"[OK] Model file path: {performance_metrics['model_info']['model_file_path']}")
    print(f"[OK] Model file size: {performance_metrics['model_info']['model_file_size_mb']:.2f} MB")

    # Count parameters and load model
    model, ram_before_model, ram_after_model = load_model_with_memory_tracking(MODEL_WEIGHTS, device)

    trainable_params, total_params = count_parameters(model)
    performance_metrics['model_info']['trainable_params'] = trainable_params
    performance_metrics['model_info']['total_params'] = total_params
    performance_metrics['model_info']['parameter_dtype'] = str(next(model.parameters()).dtype)
    performance_metrics['model_info']['model_architecture'] = 'SummaryToPatchCrossAttention'

    print(f"[OK] Trainable parameters: {trainable_params:,}")
    print(f"[OK] Total parameters: {total_params:,}")
    print(f"[OK] Parameter dtype: {performance_metrics['model_info']['parameter_dtype']}")
    print(f"[OK] Model architecture: {performance_metrics['model_info']['model_architecture']}")
    print(f"[OK] PyTorch version: {system_info['pytorch_version']}")
    print(f"[OK] Python version: {system_info['python_version']}")

    performance_metrics['model_info']['ram_before_loading_mb'] = round(ram_before_model, 2)
    performance_metrics['model_info']['ram_after_loading_mb'] = round(ram_after_model, 2)
    performance_metrics['model_info']['ram_increase_mb'] = round(ram_after_model - ram_before_model, 2)
    print(f"[OK] RAM before model loading: {performance_metrics['model_info']['ram_before_loading_mb']:.2f} MB")
    print(f"[OK] RAM after model loading: {performance_metrics['model_info']['ram_after_loading_mb']:.2f} MB")
    print(f"[OK] RAM increase: {performance_metrics['model_info']['ram_increase_mb']:.2f} MB")

    # --- PART B: PREDICTIVE PERFORMANCE ---
    print("\n" + "=" * 80)
    print("B. PREDICTIVE PERFORMANCE")
    print("=" * 80)

    # Run validation with timing
    true_labels, pred_probs, pred_classes, patient_timings = validate_with_timing(
        model, eval_loader, device
    )

    # Calculate metrics
    auc = roc_auc_score(true_labels, pred_probs)
    acc = accuracy_score(true_labels, pred_classes)

    # Target names mapping
    target_names = ['MSS (Class 0)', 'MSI (Class 1)']

    report_dict = classification_report(
        true_labels, pred_classes,
        target_names=target_names,
        zero_division=0,
        output_dict=True
    )

    cm = confusion_matrix(true_labels, pred_classes)

    # Count samples
    mss_count = sum(1 for label in true_labels if label == 0)
    msi_count = sum(1 for label in true_labels if label == 1)

    # Store predictive metrics
    performance_metrics['predictive_performance'] = {
        'accuracy': acc,
        'roc_auc': auc,
        'precision_mss': report_dict['MSS (Class 0)']['precision'],
        'recall_mss': report_dict['MSS (Class 0)']['recall'],
        'f1_mss': report_dict['MSS (Class 0)']['f1-score'],
        'precision_msi': report_dict['MSI (Class 1)']['precision'],
        'recall_msi': report_dict['MSI (Class 1)']['recall'],
        'f1_msi': report_dict['MSI (Class 1)']['f1-score'],
        'macro_f1': report_dict['macro avg']['f1-score'],
        'weighted_f1': report_dict['weighted avg']['f1-score'],
        'confusion_matrix': cm.tolist(),
        'mss_count': mss_count,
        'msi_count': msi_count
    }

    print(f"[OK] Accuracy: {acc:.4f}")
    print(f"[OK] ROC-AUC: {auc:.4f}")
    print(f"[OK] Precision (MSS): {report_dict['MSS (Class 0)']['precision']:.4f}")
    print(f"[OK] Recall (MSS): {report_dict['MSS (Class 0)']['recall']:.4f}")
    print(f"[OK] F1-Score (MSS): {report_dict['MSS (Class 0)']['f1-score']:.4f}")
    print(f"[OK] Precision (MSI): {report_dict['MSI (Class 1)']['precision']:.4f}")
    print(f"[OK] Recall (MSI): {report_dict['MSI (Class 1)']['recall']:.4f}")
    print(f"[OK] F1-Score (MSI): {report_dict['MSI (Class 1)']['f1-score']:.4f}")
    print(f"[OK] Macro F1: {report_dict['macro avg']['f1-score']:.4f}")
    print(f"[OK] Weighted F1: {report_dict['weighted avg']['f1-score']:.4f}")
    print(f"[OK] Confusion Matrix:\n{cm}")
    print(f"[OK] MSS samples: {mss_count}")
    print(f"[OK] MSI samples: {msi_count}")

    # --- PART C: INFERENCE LATENCY ---
    print("\n" + "=" * 80)
    print("C. INFERENCE LATENCY")
    print("=" * 80)

    # Extract timing data
    inference_times = [p['inference_time_ms'] for p in patient_timings]
    total_times = [p['total_time_ms'] for p in patient_timings]

    # Calculate statistics
    total_inference_time_sec = sum(inference_times) / 1000
    avg_latency_ms = np.mean(inference_times)
    median_latency_ms = np.median(inference_times)
    min_latency_ms = np.min(inference_times)
    max_latency_ms = np.max(inference_times)
    p95_latency_ms = np.percentile(inference_times, 95)
    throughput_patients_per_sec = len(eval_patients) / total_inference_time_sec
    throughput_patients_per_min = throughput_patients_per_sec * 60

    performance_metrics['inference_latency'] = {
        'total_inference_time_sec': total_inference_time_sec,
        'average_latency_ms_per_patient': avg_latency_ms,
        'median_latency_ms': median_latency_ms,
        'minimum_latency_ms': min_latency_ms,
        'maximum_latency_ms': max_latency_ms,
        'p95_latency_ms': p95_latency_ms,
        'throughput_patients_per_sec': throughput_patients_per_sec,
        'throughput_patients_per_min': throughput_patients_per_min,
        'per_patient_data': patient_timings
    }

    print(f"[OK] Total inference time: {total_inference_time_sec:.4f} sec")
    print(f"[OK] Average latency: {avg_latency_ms:.2f} ms/patient")
    print(f"[OK] Median latency: {median_latency_ms:.2f} ms")
    print(f"[OK] P95 latency: {p95_latency_ms:.2f} ms")
    print(f"[OK] Throughput: {throughput_patients_per_sec:.2f} patients/sec ({throughput_patients_per_min:.2f} patients/min)")

    # --- PART D: MEMORY USAGE ---
    print("\n" + "=" * 80)
    print("D. MEMORY USAGE")
    print("=" * 80)

    # Measure peak RAM during inference
    process = psutil.Process(os.getpid())

    # Baseline RAM before model loading
    ram_baseline = measure_ram_memory()

    # Model loading RAM
    ram_model_loaded = performance_metrics['model_info']['ram_after_loading_mb']

    # Peak RAM during inference
    peak_ram = process.memory_info().rss / 1024 / 1024  # Current peak
    # Note: psutil doesn't easily give historical peak, so we use current
    # For more accurate peak tracking, we could use memory_profiler

    # Measure RAM during inference (sample at end)
    ram_end_inference = measure_ram_memory()

    performance_metrics['memory_usage'] = {
        'ram_baseline_mb': round(ram_baseline, 2),
        'ram_model_loaded_mb': ram_model_loaded,
        'ram_end_inference_mb': round(ram_end_inference, 2),
        'peak_ram_mb': round(peak_ram, 2),
        'ram_increase_inference_mb': round(ram_end_inference - ram_baseline, 2)
    }

    print(f"[OK] RAM baseline (before model): {performance_metrics['memory_usage']['ram_baseline_mb']:.2f} MB")
    print(f"[OK] RAM during model loaded: {performance_metrics['memory_usage']['ram_model_loaded_mb']:.2f} MB")
    print(f"[OK] RAM during inference: {performance_metrics['memory_usage']['ram_end_inference_mb']:.2f} MB")
    print(f"[OK] Peak RAM measured: {performance_metrics['memory_usage']['peak_ram_mb']:.2f} MB")
    print(f"[OK] RAM increase during inference: {performance_metrics['memory_usage']['ram_increase_inference_mb']:.2f} MB")

    # --- PART E: CPU / GPU UTILIZATION ---
    print("\n" + "=" * 80)
    print("E. CPU / GPU UTILIZATION")
    print("=" * 80)

    # CPU utilization was captured in system info
    cpu_utilization = system_info.get('cpu_percent', 0)

    performance_metrics['hardware_utilization'] = {
        'cpu_model': system_info.get('cpu_model', 'Unknown'),
        'physical_cores': system_info.get('physical_cores', 0),
        'logical_cores': system_info.get('logical_cores', 0),
        'cpu_utilization_percent': cpu_utilization,
        'gpu_available': system_info.get('gpu_available', False),
        'gpu_name': system_info.get('gpu_name', 'None'),
        'gpu_memory_gb': system_info.get('gpu_memory_gb', 0),
        'gpu_utilization_percent': system_info.get('gpu_utilization', 0),
        'gpu_memory_used_gb': system_info.get('gpu_memory_used_gb', 0)
    }

    print(f"[OK] CPU model: {performance_metrics['hardware_utilization']['cpu_model']}")
    print(f"[OK] Physical cores: {performance_metrics['hardware_utilization']['physical_cores']}")
    print(f"[OK] Logical cores: {performance_metrics['hardware_utilization']['logical_cores']}")
    print(f"[OK] CPU utilization during benchmark: {performance_metrics['hardware_utilization']['cpu_utilization_percent']:.1f}%")
    print(f"[OK] GPU available: {performance_metrics['hardware_utilization']['gpu_available']}")

    if performance_metrics['hardware_utilization']['gpu_available']:
        print(f"[OK] GPU name: {performance_metrics['hardware_utilization']['gpu_name']}")
        print(f"[OK] GPU memory: {performance_metrics['hardware_utilization']['gpu_memory_gb']:.2f} GB")
        print(f"[OK] GPU utilization: {performance_metrics['hardware_utilization']['gpu_utilization_percent']:.1f}%")
        print(f"[OK] GPU memory used: {performance_metrics['hardware_utilization']['gpu_memory_used_gb']:.2f} GB")

    # --- PART F: HARDWARE / SYSTEM INFORMATION ---
    print("\n" + "=" * 80)
    print("F. HARDWARE / SYSTEM INFORMATION")
    print("=" * 80)

    print(f"[OK] Operating system: {system_info.get('os', 'Unknown')}")
    print(f"[OK] System RAM: {system_info.get('system_ram_gb', 'Unknown'):.2f} GB")
    print(f"[OK] Available RAM: {system_info.get('system_ram_available_gb', 'Unknown'):.2f} GB")
    print(f"[OK] Python version: {system_info.get('python_version', 'Unknown')}")
    print(f"[OK] PyTorch version: {system_info.get('pytorch_version', 'Unknown')}")
    print(f"[OK] Timestamp: {system_info.get('timestamp', 'Unknown')}")

    # Store system info
    performance_metrics['system_info'] = system_info

    # --- PART G: POWER / ENERGY ---
    print("\n" + "=" * 80)
    print("G. POWER / ENERGY")
    print("=" * 80)

    try:
        # Try to get power information on NVIDIA GPU
        if torch.cuda.is_available():
            # For NVIDIA GPU with power monitoring support
            power_info = {}
            # Note: Direct power measurement may not be available on all systems
            # This is a placeholder for where power measurement could be implemented
            performance_metrics['power_info'] = {
                'status': 'Power measurement not directly available via PyTorch',
                'note': 'Consider using external tools like nvidia-smi or platform-specific APIs'
            }
            print("[WARN]  Power measurement unavailable on this hardware/software configuration.")
        else:
            performance_metrics['power_info'] = {
                'status': 'Power measurement unavailable on this hardware/software configuration.'
            }
            print("[WARN]  Power measurement unavailable on this hardware/software configuration.")
    except Exception as e:
        performance_metrics['power_info'] = {
            'status': f'Error measuring power: {str(e)}',
            'note': 'May be due to hardware limitations'
        }
        print(f"[WARN]  Error measuring power: {e}")

    # --- SUMMARY REPORT ---
    print("\n" + "=" * 80)
    print("FP32 BASELINE SUMMARY")
    print("=" * 80)
    print(f"Patients: {len(eval_patients)}")
    print(f"Accuracy: {acc:.4f}")
    print(f"ROC-AUC: {auc:.4f}")
    print(f"F1: {report_dict['weighted avg']['f1-score']:.4f}")
    print(f"\nModel size: {model_size_mb:.2f} MB")
    print(f"Parameters: {total_params:,}")
    print(f"\nTotal inference: {total_inference_time_sec:.4f} sec")
    print(f"Average latency: {avg_latency_ms:.2f} ms/patient")
    print(f"Throughput: {throughput_patients_per_sec:.2f} patients/sec")
    print(f"\nRAM before model: {performance_metrics['model_info']['ram_before_loading_mb']:.2f} MB")
    print(f"Peak RAM: {performance_metrics['memory_usage']['peak_ram_mb']:.2f} MB")
    print(f"\nCPU: {performance_metrics['hardware_utilization']['cpu_model']}")
    print(f"CPU cores: {performance_metrics['hardware_utilization']['logical_cores']}")
    print(f"CPU utilization: {performance_metrics['hardware_utilization']['cpu_utilization_percent']:.1f}%")
    print(f"\nGPU: {performance_metrics['hardware_utilization']['gpu_name']}")
    print(f"\nPower: Power measurement unavailable on this hardware/software configuration.")

    # Save results
    save_benchmark_results(performance_metrics, eval_patients)

    return performance_metrics

def save_benchmark_results(metrics, eval_patients):
    """Save benchmark results to files"""
    # Create directory if it doesn't exist
    benchmark_dir = "benchmarks/fp32"
    os.makedirs(benchmark_dir, exist_ok=True)

    # 1. Human-readable report
    report_path = os.path.join(benchmark_dir, "fp32_report.txt")
    with open(report_path, "w") as f:
        f.write("FP32 BASELINE BENCHMARK REPORT\n")
        f.write("=" * 50 + "\n\n")

        f.write(f"Generated on: {datetime.now().isoformat()}\n")
        f.write(f"Model file: {metrics['model_info']['model_file_path']}\n")
        f.write(f"Model file size: {metrics['model_info']['model_file_size_mb']:.2f} MB\n")
        f.write(f"Total parameters: {metrics['model_info']['total_params']:,}\n")
        f.write(f"Trainable parameters: {metrics['model_info']['trainable_params']:,}\n\n")

        f.write("PREDICTIVE PERFORMANCE:\n")
        f.write(f"  Accuracy: {metrics['predictive_performance']['accuracy']:.4f}\n")
        f.write(f"  ROC-AUC: {metrics['predictive_performance']['roc_auc']:.4f}\n")
        f.write(f"  F1-Score (Macro): {metrics['predictive_performance']['macro_f1']:.4f}\n")
        f.write(f"  F1-Score (Weighted): {metrics['predictive_performance']['weighted_f1']:.4f}\n")
        f.write(f"  MSS samples: {metrics['predictive_performance']['mss_count']}\n")
        f.write(f"  MSI samples: {metrics['predictive_performance']['msi_count']}\n\n")

        f.write("INFERENCE LATENCY:\n")
        f.write(f"  Total inference time: {metrics['inference_latency']['total_inference_time_sec']:.4f} sec\n")
        f.write(f"  Average latency: {metrics['inference_latency']['average_latency_ms_per_patient']:.2f} ms/patient\n")
        f.write(f"  Median latency: {metrics['inference_latency']['median_latency_ms']:.2f} ms\n")
        f.write(f"  P95 latency: {metrics['inference_latency']['p95_latency_ms']:.2f} ms\n")
        f.write(f"  Throughput: {metrics['inference_latency']['throughput_patients_per_sec']:.2f} patients/sec\n\n")

        f.write("MEMORY USAGE:\n")
        f.write(f"  RAM baseline: {metrics['memory_usage']['ram_baseline_mb']:.2f} MB\n")
        f.write(f"  RAM after model load: {metrics['memory_usage']['ram_model_loaded_mb']:.2f} MB\n")
        f.write(f"  Peak RAM: {metrics['memory_usage']['peak_ram_mb']:.2f} MB\n\n")

        f.write("HARDWARE:\n")
        f.write(f"  CPU: {metrics['hardware_utilization']['cpu_model']}\n")
        f.write(f"  Cores: {metrics['hardware_utilization']['logical_cores']} logical, {metrics['hardware_utilization']['physical_cores']} physical\n")
        f.write(f"  CPU utilization: {metrics['hardware_utilization']['cpu_utilization_percent']:.1f}%\n")
        f.write(f"  GPU: {metrics['hardware_utilization']['gpu_name']}\n\n")

        f.write("ENVIRONMENT:\n")
        f.write(f"  OS: {metrics['system_info']['os']} {metrics['system_info']['os_version']}\n")
        f.write(f"  Python: {metrics['system_info']['python_version']}\n")
        f.write(f"  PyTorch: {metrics['system_info']['pytorch_version']}\n")

    print(f"\n[OK] Saved human-readable report to: {report_path}")

    # 2. JSON results (machine-readable)
    json_path = os.path.join(benchmark_dir, "fp32_results.json")

    # Convert numpy arrays to lists for JSON serialization
    json_metrics = metrics.copy()
    json_metrics['predictive_performance']['confusion_matrix'] = metrics['predictive_performance']['confusion_matrix']

    with open(json_path, "w") as f:
        json.dump(json_metrics, f, indent=2, default=str)

    print(f"[OK] Saved JSON results to: {json_path}")

    # 3. CSV summary (one-row summary)
    csv_path = os.path.join(benchmark_dir, "fp32_results.csv")

    # Extract key metrics for CSV
    csv_data = {
        'timestamp': datetime.now().isoformat(),
        'model_file_path': metrics['model_info']['model_file_path'],
        'model_file_size_mb': metrics['model_info']['model_file_size_mb'],
        'total_params': metrics['model_info']['total_params'],
        'accuracy': metrics['predictive_performance']['accuracy'],
        'roc_auc': metrics['predictive_performance']['roc_auc'],
        'f1_macro': metrics['predictive_performance']['macro_f1'],
        'f1_weighted': metrics['predictive_performance']['weighted_f1'],
        'mss_samples': metrics['predictive_performance']['mss_count'],
        'msi_samples': metrics['predictive_performance']['msi_count'],
        'total_inference_time_sec': metrics['inference_latency']['total_inference_time_sec'],
        'avg_latency_ms': metrics['inference_latency']['average_latency_ms_per_patient'],
        'median_latency_ms': metrics['inference_latency']['median_latency_ms'],
        'p95_latency_ms': metrics['inference_latency']['p95_latency_ms'],
        'throughput_patients_per_sec': metrics['inference_latency']['throughput_patients_per_sec'],
        'ram_baseline_mb': metrics['memory_usage']['ram_baseline_mb'],
        'peak_ram_mb': metrics['memory_usage']['peak_ram_mb'],
        'cpu_cores': metrics['hardware_utilization']['logical_cores'],
        'gpu_available': metrics['hardware_utilization']['gpu_available'],
        'gpu_name': metrics['hardware_utilization']['gpu_name'],
        'python_version': metrics['system_info']['python_version'],
        'pytorch_version': metrics['system_info']['pytorch_version']
    }

    import pandas as pd
    df = pd.DataFrame([csv_data])
    df.to_csv(csv_path, index=False)

    print(f"[OK] Saved CSV summary to: {csv_path}")

if __name__ == "__main__":
    # Run the benchmark
    results = collect_performance_metrics()

    # Print summary
    print("\n" + "=" * 80)
    print("BENCHMARK COMPLETED SUCCESSFULLY")
    print("=" * 80)
    print(f"Results saved to: benchmarks/fp32/")
    print(f"- fp32_report.txt (human-readable)")
    print(f"- fp32_results.json (machine-readable)")
    print(f"- fp32_results.csv (summary)")
    print("=" * 80)
