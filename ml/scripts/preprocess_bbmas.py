"""
SafeBank AI - BB-MAS Real-World Preprocessing Pipeline
Builds leakage-free, subject-disjoint, normalized CNN and LSTM tensors from raw smartphone data.

Protocol:
- Subjects: 116 total (Samsung S6 + HTC One)
- Subject-Disjoint Split:
  * Train: Subjects 1 to 80 (80 subjects, ~69%)
  * Val:   Subjects 81 to 98 (18 subjects, ~15.5%)
  * Test:  Subjects 99 to 116 (18 subjects, ~15.5%)
- Normalization: StandardScaler fit STRICTLY on Train split; frozen transform on Val and Test.
- CNN Window: T = 128 steps (1.28s @ 100 Hz), 8 channels.
- LSTM Sequence: T = 256 steps (2.56s @ 100 Hz), 8 channels + Masking array.
- 8 Channels: [accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, touch_pressure, interaction_state]
  * interaction_state: 0.0 = passive in-hand/gait, 1.0 = touch/swipe, 2.0 = typing
- Output:
  * ml/data/processed/bbmas_cnn_dataset.npz
  * ml/data/processed/bbmas_lstm_dataset.npz
  * ml/data/processed/bbmas_scaler_params.json
  * ml/reports/bbmas_preprocessing_summary.json
"""

import os
import sys
import json
import re
import gc
import datetime
from pathlib import Path
import numpy as np
import pandas as pd

RAW_DIR = Path("ml/data/bbmas_phone")
OUT_DIR = Path("ml/data/processed")
REPORTS_DIR = Path("ml/reports")

# Split definitions
TRAIN_SUBJECTS = list(range(1, 81))
VAL_SUBJECTS = list(range(81, 99))
TEST_SUBJECTS = list(range(99, 117))

CNN_WINDOW = 128
LSTM_SEQUENCE = 256
SAMPLING_HZ = 100  # 10 ms grid

WINDOWS_PER_SUB_CNN = 150
SEQS_PER_SUB_LSTM = 75

def parse_timestamps(series):
    # Handle both YYYY-MM-DD HH:MM:SS.fff and YYYY-M-D HH:MM:SS:fff
    s = series.astype(str).str.strip()
    # If colon before milliseconds, replace last colon with period
    s = s.str.replace(r":(\d{1,3})$", r".\1", regex=True)
    return pd.to_datetime(s, format="mixed", errors="coerce")

def load_subject_streams(sub_id):
    # Locate files
    acc_files = list(RAW_DIR.glob(f"{sub_id}_HandPhone_Accelerometer_*.csv"))
    gyro_files = list(RAW_DIR.glob(f"{sub_id}_HandPhone_Gyroscope_*.csv"))
    touch_files = list(RAW_DIR.glob(f"{sub_id}_HandPhone_TouchEvent_*.csv"))
    key_files = list(RAW_DIR.glob(f"{sub_id}_HandPhone_Keyboard_*.csv"))
    
    if not (acc_files and gyro_files and touch_files and key_files):
        return None
        
    # Read motion streams
    df_acc = pd.read_csv(acc_files[0], usecols=["Xvalue", "Yvalue", "Zvalue", "time"])
    df_gyro = pd.read_csv(gyro_files[0], usecols=["Xvalue", "Yvalue", "Zvalue", "time"])
    
    # Parse times
    df_acc["dt"] = parse_timestamps(df_acc["time"])
    df_gyro["dt"] = parse_timestamps(df_gyro["time"])
    
    df_acc = df_acc.dropna(subset=["dt"]).sort_values("dt").reset_index(drop=True)
    df_gyro = df_gyro.dropna(subset=["dt"]).sort_values("dt").reset_index(drop=True)
    
    if len(df_acc) < 500 or len(df_gyro) < 500:
        return None

    # Merge motion on 10ms tolerance
    df_motion = pd.merge_asof(
        df_acc.rename(columns={"Xvalue": "ax", "Yvalue": "ay", "Zvalue": "az"}),
        df_gyro.rename(columns={"Xvalue": "gx", "Yvalue": "gy", "Zvalue": "gz"}),
        on="dt",
        direction="nearest",
        tolerance=pd.Timedelta("25ms")
    ).dropna(subset=["ax", "ay", "az", "gx", "gy", "gz"]).reset_index(drop=True)
    
    if len(df_motion) < 500:
        return None
        
    # Add interaction channels: touch_pressure and interaction_state
    df_motion["pressure"] = 0.0
    df_motion["interaction"] = 0.0  # 0=passive, 1=touch, 2=typing
    
    # Process TouchEvent
    try:
        df_touch = pd.read_csv(touch_files[0], usecols=["pressure", "time"])
        df_touch["dt"] = parse_timestamps(df_touch["time"])
        df_touch = df_touch.dropna(subset=["dt", "pressure"]).sort_values("dt").reset_index(drop=True)
        if len(df_touch) > 0:
            # Map touches to nearest motion step within 50ms
            t_merge = pd.merge_asof(
                df_motion[["dt"]].reset_index(),
                df_touch[["dt", "pressure"]],
                on="dt",
                direction="nearest",
                tolerance=pd.Timedelta("50ms")
            )
            has_touch = t_merge["pressure"].notnull()
            df_motion.loc[has_touch, "pressure"] = t_merge.loc[has_touch, "pressure"].clip(0.0, 1.0)
            df_motion.loc[has_touch, "interaction"] = 1.0
    except Exception as e:
        pass
        
    # Process Keyboard
    try:
        df_key = pd.read_csv(key_files[0], usecols=["time"])
        df_key["dt"] = parse_timestamps(df_key["time"])
        df_key = df_key.dropna(subset=["dt"]).sort_values("dt").reset_index(drop=True)
        if len(df_key) > 0:
            k_merge = pd.merge_asof(
                df_motion[["dt"]].reset_index(),
                df_key[["dt"]].assign(has_key=1),
                on="dt",
                direction="nearest",
                tolerance=pd.Timedelta("60ms")
            )
            has_key = k_merge["has_key"] == 1
            df_motion.loc[has_key, "interaction"] = 2.0
    except Exception as e:
        pass

    cols = ["ax", "ay", "az", "gx", "gy", "gz", "pressure", "interaction"]
    arr = df_motion[cols].to_numpy(dtype=np.float32)
    return arr

def extract_windows_for_subject(arr, sub_id, num_cnn=WINDOWS_PER_SUB_CNN, num_lstm=SEQS_PER_SUB_LSTM):
    total_len = len(arr)
    if total_len < LSTM_SEQUENCE:
        return None, None
        
    # Prefer sampling portions with active interaction (typing/touch) as well as passive motion
    interaction_weights = (arr[:, 7] > 0).astype(float) * 2.0 + 1.0
    
    # CNN Windows (T=128)
    cnn_windows = []
    # Stride linearly across the recording
    step_cnn = max(1, (total_len - CNN_WINDOW) // num_cnn)
    for i in range(num_cnn):
        start = i * step_cnn
        end = start + CNN_WINDOW
        if end <= total_len:
            cnn_windows.append(arr[start:end])
    cnn_windows = np.array(cnn_windows, dtype=np.float32)
    
    # LSTM Sequences (T=256)
    lstm_seqs = []
    step_lstm = max(1, (total_len - LSTM_SEQUENCE) // num_lstm)
    for i in range(num_lstm):
        start = i * step_lstm
        end = start + LSTM_SEQUENCE
        if end <= total_len:
            lstm_seqs.append(arr[start:end])
    lstm_seqs = np.array(lstm_seqs, dtype=np.float32)
    
    return cnn_windows, lstm_seqs

def run_preprocessing():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    
    print("="*60)
    print("SafeBank AI - Real-World BB-MAS Preprocessing Starting")
    print("="*60)
    
    splits = {
        "train": TRAIN_SUBJECTS,
        "val": VAL_SUBJECTS,
        "test": TEST_SUBJECTS
    }
    
    raw_data = {"train": {"cnn": [], "lstm": [], "sub_cnn": [], "sub_lstm": []},
                "val":   {"cnn": [], "lstm": [], "sub_cnn": [], "sub_lstm": []},
                "test":  {"cnn": [], "lstm": [], "sub_cnn": [], "sub_lstm": []}}
                
    processed_counts = {"train": 0, "val": 0, "test": 0}
    
    for split_name, sub_list in splits.items():
        print(f"\nProcessing {split_name.upper()} split ({len(sub_list)} subjects)...")
        for sub_id in sub_list:
            arr = load_subject_streams(sub_id)
            if arr is None:
                print(f"  [WARN] Subject {sub_id} could not be loaded or too short.")
                continue
                
            cnn_w, lstm_s = extract_windows_for_subject(arr, sub_id)
            if cnn_w is None or len(cnn_w) == 0:
                print(f"  [WARN] Subject {sub_id} window extraction yielded 0 samples.")
                continue
                
            raw_data[split_name]["cnn"].append(cnn_w)
            raw_data[split_name]["lstm"].append(lstm_s)
            raw_data[split_name]["sub_cnn"].extend([sub_id] * len(cnn_w))
            raw_data[split_name]["sub_lstm"].extend([sub_id] * len(lstm_s))
            processed_counts[split_name] += 1
            
            if processed_counts[split_name] % 20 == 0:
                print(f"  Processed {processed_counts[split_name]} subjects in {split_name}...")
                gc.collect()

    print("\nAssembling arrays...")
    for split_name in splits:
        raw_data[split_name]["cnn"] = np.concatenate(raw_data[split_name]["cnn"], axis=0)
        raw_data[split_name]["lstm"] = np.concatenate(raw_data[split_name]["lstm"], axis=0)
        raw_data[split_name]["sub_cnn"] = np.array(raw_data[split_name]["sub_cnn"], dtype=np.int32)
        raw_data[split_name]["sub_lstm"] = np.array(raw_data[split_name]["sub_lstm"], dtype=np.int32)
        print(f"  {split_name} CNN: {raw_data[split_name]['cnn'].shape}, LSTM: {raw_data[split_name]['lstm'].shape}")

    # Compute normalization STRICTLY on Train split
    print("\nComputing normalization statistics STRICTLY on Training Split (Subjects 1-80)...")
    train_cnn_flat = raw_data["train"]["cnn"].reshape(-1, 8)
    
    # We normalize continuous channels (ax, ay, az, gx, gy, gz) with standard score
    # We keep pressure [0, 1] and interaction [0, 1, 2] bounded
    means = np.mean(train_cnn_flat, axis=0)
    stds = np.std(train_cnn_flat, axis=0)
    
    # Guard zero or tiny std
    stds = np.where(stds < 1e-4, 1.0, stds)
    
    # For pressure and interaction, preserve raw scale
    means[6] = 0.0
    stds[6] = 1.0
    means[7] = 0.0
    stds[7] = 1.0
    
    print(f"Fitted Means: {means.tolist()}")
    print(f"Fitted Stds:  {stds.tolist()}")
    
    # Save scaler params
    scaler_dict = {
        "dataset": "SU-AIS BB-MAS",
        "fitted_on": "training_split_subjects_1_to_80",
        "channels": ["ax", "ay", "az", "gx", "gy", "gz", "pressure", "interaction"],
        "means": [float(x) for x in means],
        "stds": [float(x) for x in stds],
        "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat()
    }
    with open(OUT_DIR / "bbmas_scaler_params.json", "w", encoding="utf-8") as f:
        json.dump(scaler_dict, f, indent=2)

    # Normalize all splits using training parameters
    print("\nApplying frozen normalization to all splits...")
    cnn_dataset = {}
    lstm_dataset = {}
    
    for split_name in splits:
        cnn_norm = (raw_data[split_name]["cnn"] - means) / stds
        lstm_norm = (raw_data[split_name]["lstm"] - means) / stds
        
        # Verify no NaN or Inf
        assert np.isfinite(cnn_norm).all(), f"NaN or Inf found in {split_name} CNN!"
        assert np.isfinite(lstm_norm).all(), f"NaN or Inf found in {split_name} LSTM!"
        
        cnn_dataset[f"X_{split_name}"] = cnn_norm.astype(np.float32)
        cnn_dataset[f"y_{split_name}"] = raw_data[split_name]["sub_cnn"]
        
        lstm_dataset[f"X_{split_name}"] = lstm_norm.astype(np.float32)
        lstm_dataset[f"y_{split_name}"] = raw_data[split_name]["sub_lstm"]
        
        # Create mask for LSTM (all 1.0 since these sequences are complete 256-step windows)
        lstm_dataset[f"mask_{split_name}"] = np.ones((len(lstm_norm), LSTM_SEQUENCE), dtype=np.float32)

    # Save binary datasets
    cnn_file = OUT_DIR / "bbmas_cnn_dataset.npz"
    lstm_file = OUT_DIR / "bbmas_lstm_dataset.npz"
    
    print(f"\nSaving processed CNN tensors to {cnn_file}...")
    np.savez_compressed(cnn_file, **cnn_dataset)
    
    print(f"Saving processed LSTM tensors to {lstm_file}...")
    np.savez_compressed(lstm_file, **lstm_dataset)
    
    cnn_size_mb = cnn_file.stat().st_size / (1024 * 1024)
    lstm_size_mb = lstm_file.stat().st_size / (1024 * 1024)
    
    # Preprocessing Summary
    summary = {
        "preprocessing_timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "usable_subjects_processed": processed_counts,
        "train_subjects_range": "1 to 80 (80 subjects)",
        "val_subjects_range": "81 to 98 (18 subjects)",
        "test_subjects_range": "99 to 116 (18 subjects)",
        "tensors": {
            "cnn": {
                "file": str(cnn_file),
                "file_size_mb": round(cnn_size_mb, 2),
                "train_shape": list(cnn_dataset["X_train"].shape),
                "val_shape": list(cnn_dataset["X_val"].shape),
                "test_shape": list(cnn_dataset["X_test"].shape),
                "window_timesteps": CNN_WINDOW,
                "window_duration_seconds": round(CNN_WINDOW / SAMPLING_HZ, 2),
                "channels_count": 8
            },
            "lstm": {
                "file": str(lstm_file),
                "file_size_mb": round(lstm_size_mb, 2),
                "train_shape": list(lstm_dataset["X_train"].shape),
                "val_shape": list(lstm_dataset["X_val"].shape),
                "test_shape": list(lstm_dataset["X_test"].shape),
                "sequence_timesteps": LSTM_SEQUENCE,
                "sequence_duration_seconds": round(LSTM_SEQUENCE / SAMPLING_HZ, 2),
                "channels_count": 8
            }
        },
        "normalization": {
            "strategy": "StandardScaler computed exclusively on train split",
            "parameters_file": str(OUT_DIR / "bbmas_scaler_params.json")
        },
        "data_integrity": {
            "nan_or_inf_in_tensors": False,
            "leakage_free": True,
            "subject_disjoint": True
        }
    }
    
    with open(REPORTS_DIR / "bbmas_preprocessing_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
        
    print(f"\nPreprocessing successfully completed!")
    print(f"CNN Dataset: {cnn_file} ({cnn_size_mb:.2f} MB)")
    print(f"LSTM Dataset: {lstm_file} ({lstm_size_mb:.2f} MB)")
    print(f"Summary Report: {REPORTS_DIR / 'bbmas_preprocessing_summary.json'}")

if __name__ == "__main__":
    run_preprocessing()
