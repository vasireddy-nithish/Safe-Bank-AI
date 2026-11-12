# SafeBank AI — ML Scripts

> **SYNTHETIC DATA NOTICE**: All data in this pipeline is artificially generated for integration-testing purposes. The statistical distributions do not reflect real user behaviour. Models trained on this data must not be used for production fraud-detection decisions.

---

## Quick Start

Run all steps from the **project root** (`SafeBank AI/`):

```powershell
# 1. Install dependencies (once)
.venv312\Scripts\python.exe -m pip install numpy pandas scikit-learn tensorflow matplotlib joblib

# 2. Full pipeline
.venv312\Scripts\python.exe ml/scripts/generate_sample_data.py
.venv312\Scripts\python.exe ml/scripts/preprocess.py
.venv312\Scripts\python.exe ml/scripts/train.py
.venv312\Scripts\python.exe ml/scripts/evaluate.py
.venv312\Scripts\python.exe ml/scripts/convert_tflite.py

# Or use the convenience runner:
.venv312\Scripts\python.exe ml/scripts/run_pipeline.py
```

---

## Script Reference

| Script | Input | Output |
|--------|-------|--------|
| `generate_sample_data.py` | — | `ml/data/synthetic_behavioural_data.csv` |
| `preprocess.py` | CSV | `ml/data/X_*.npy`, `y_*.npy`, `ml/models/scaler.joblib`, `scaler_params.json`, `feature_schema.json`, `label_mapping.json` |
| `train.py` | `.npy` splits | `ml/models/behaviour_model.keras` |
| `evaluate.py` | model + test split | `ml/models/evaluation_report.json` |
| `convert_tflite.py` | `.keras` | `ml/models/behaviour_model.tflite`, `android/app/src/main/assets/behaviour_model.tflite` |

---

## 48-Feature Vector

### Touch Dynamics (indices 0–19)

| Index | Feature Name |
|-------|-------------|
| 0 | `touch_stroke_duration_mean` |
| 1 | `touch_stroke_duration_std` |
| 2 | `touch_stroke_length_mean` |
| 3 | `touch_stroke_length_std` |
| 4 | `touch_stroke_velocity_mean` |
| 5 | `touch_stroke_velocity_max` |
| 6 | `touch_stroke_acceleration_mean` |
| 7 | `touch_pressure_mean` |
| 8 | `touch_pressure_max` |
| 9 | `touch_pressure_std` |
| 10 | `touch_major_axis_mean` |
| 11 | `touch_minor_axis_mean` |
| 12 | `touch_trajectory_curvature` |
| 13 | `touch_start_angle` |
| 14 | `touch_end_angle` |
| 15 | `touch_inter_event_time_mean` |
| 16 | `touch_inter_event_time_std` |
| 17 | `touch_swipe_horizontal_ratio` |
| 18 | `touch_jitter_variance` |
| 19 | `touch_tap_duration_mean` |

### Typing Dynamics (indices 20–31)

| Index | Feature Name |
|-------|-------------|
| 20 | `typing_hold_time_mean` |
| 21 | `typing_hold_time_std` |
| 22 | `typing_hold_time_median` |
| 23 | `typing_flight_time_mean` |
| 24 | `typing_flight_time_std` |
| 25 | `typing_digraph_latency_mean` |
| 26 | `typing_backspace_rate` |
| 27 | `typing_speed_cpm` |
| 28 | `typing_pressure_mean` |
| 29 | `typing_pressure_std` |
| 30 | `typing_contact_size_mean` |
| 31 | `typing_rhythm_entropy` |

### Motion Dynamics (indices 32–47)

| Index | Feature Name |
|-------|-------------|
| 32 | `motion_accel_x_mean` |
| 33 | `motion_accel_x_std` |
| 34 | `motion_accel_y_mean` |
| 35 | `motion_accel_y_std` |
| 36 | `motion_accel_z_mean` |
| 37 | `motion_accel_z_std` |
| 38 | `motion_accel_magnitude_mean` |
| 39 | `motion_accel_magnitude_variance` |
| 40 | `motion_gyro_pitch_mean` |
| 41 | `motion_gyro_pitch_std` |
| 42 | `motion_gyro_roll_mean` |
| 43 | `motion_gyro_roll_std` |
| 44 | `motion_gyro_yaw_mean` |
| 45 | `motion_gyro_yaw_std` |
| 46 | `motion_device_tilt_angle_mean` |
| 47 | `motion_micro_tremor_energy` |

---

## Model Architecture

```
Input (48,)
  │
  ▼
Dense(64, relu) → BatchNormalization → Dropout(0.2)
  │
  ▼
Dense(32, relu) → BatchNormalization → Dropout(0.1)
  │
  ▼
Dense(3, softmax)   →  [P(genuine), P(uncertain), P(anomaly)]
```

**TFLite input tensor**: `[1, 48]` float32  
**TFLite output tensor**: `[1, 3]` float32 (sum = 1.0)

---

## Android Integration

### Preprocessing (must replicate Python `StandardScaler`)

Load `ml/models/scaler_params.json`:

```json
{
  "feature_names": [...],
  "mean": [μ₀, μ₁, ..., μ₄₇],
  "std":  [σ₀, σ₁, ..., σ₄₇]
}
```

Apply before inference:

```kotlin
val scaled = FloatArray(48) { i -> (raw[i] - mean[i]) / std[i] }
```

### Running Inference

```kotlin
val interpreter = Interpreter(loadModelFile(context, "behaviour_model.tflite"))
val input  = Array(1) { scaled }
val output = Array(1) { FloatArray(3) }
interpreter.run(input, output)

val probs = output[0]   // [P(genuine), P(uncertain), P(anomaly)]
val label = probs.indices.maxByOrNull { probs[it] }
```

### Label Mapping

| Index | Label | Meaning |
|-------|-------|---------|
| 0 | genuine | Behaviour matches enrolled profile |
| 1 | uncertain | Low confidence / cold-start |
| 2 | anomaly | Statistically significant deviation |

---

## Synthetic Data Limitations

- Distributions are hand-crafted Gaussian blobs, not derived from real users.
- No within-class temporal correlation, enrollment drift, or covariate shift.
- Accuracy figures from `evaluation_report.json` are **not** indicative of real-world performance.
- Replace with real enrolled-user data before any production use.
