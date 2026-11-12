# SYNTHETIC DATA — FOR PIPELINE TESTING ONLY. NOT REAL BEHAVIOURAL DATA.
"""
generate_sample_data.py
Generates synthetic behavioural biometric data for 3 classes:
  Class 0: Genuine  — consistent patterns, low variance
  Class 1: Uncertain — moderate variance, cold-start
  Class 2: Anomaly  — high variance, unusual patterns

48 features matching ml/README.md spec.
"""

import os
import numpy as np
import pandas as pd

SEED = 42
rng = np.random.default_rng(SEED)

N_PER_CLASS = 1000

# ---------------------------------------------------------------------------
# Feature names (must match ml/README.md exactly)
# ---------------------------------------------------------------------------
TOUCH_FEATURES = [
    "touch_stroke_duration_mean", "touch_stroke_duration_std",
    "touch_stroke_length_mean",   "touch_stroke_length_std",
    "touch_stroke_velocity_mean", "touch_stroke_velocity_max",
    "touch_stroke_acceleration_mean",
    "touch_pressure_mean",        "touch_pressure_max",        "touch_pressure_std",
    "touch_major_axis_mean",      "touch_minor_axis_mean",
    "touch_trajectory_curvature",
    "touch_start_angle",          "touch_end_angle",
    "touch_inter_event_time_mean","touch_inter_event_time_std",
    "touch_swipe_horizontal_ratio",
    "touch_jitter_variance",
    # extra slot to reach 20
    "touch_tap_duration_mean",
]

TYPING_FEATURES = [
    "typing_hold_time_mean",     "typing_hold_time_std",     "typing_hold_time_median",
    "typing_flight_time_mean",   "typing_flight_time_std",
    "typing_digraph_latency_mean",
    "typing_backspace_rate",
    "typing_speed_cpm",
    "typing_pressure_mean",      "typing_pressure_std",
    "typing_contact_size_mean",
    "typing_rhythm_entropy",
]

MOTION_FEATURES = [
    "motion_accel_x_mean",          "motion_accel_x_std",
    "motion_accel_y_mean",          "motion_accel_y_std",
    "motion_accel_z_mean",          "motion_accel_z_std",
    "motion_accel_magnitude_mean",  "motion_accel_magnitude_variance",
    "motion_gyro_pitch_mean",       "motion_gyro_pitch_std",
    "motion_gyro_roll_mean",        "motion_gyro_roll_std",
    "motion_gyro_yaw_mean",         "motion_gyro_yaw_std",
    "motion_device_tilt_angle_mean",
    "motion_micro_tremor_energy",
]

FEATURE_NAMES = TOUCH_FEATURES + TYPING_FEATURES + MOTION_FEATURES
assert len(FEATURE_NAMES) == 48, f"Expected 48 features, got {len(FEATURE_NAMES)}"


def generate_class(label: int, n: int) -> np.ndarray:
    """
    Generate n samples for one class.
    Each feature group gets class-specific means/stds so distributions are distinct.
    """
    if label == 0:  # Genuine — low variance, consistent
        # Touch (0-19)
        touch = rng.normal(
            loc=[150, 10, 200, 15, 1.5, 3.0, 0.02,
                 0.6, 0.85, 0.05, 12, 8, 1.1,
                 0.3, 0.8, 80, 12, 0.6, 0.002, 95],
            scale=[15, 2, 20, 3, 0.15, 0.3, 0.005,
                   0.04, 0.05, 0.01, 1.5, 1.0, 0.05,
                   0.05, 0.08, 10, 2, 0.05, 0.0003, 10],
            size=(n, 20)
        )
        # Typing (20-31)
        typing = rng.normal(
            loc=[120, 15, 115, 85, 20, 95, 2.0, 220, 0.55, 0.06, 18, 0.3],
            scale=[12, 3, 12, 10, 4, 10, 0.5, 20, 0.04, 0.01, 2, 0.04],
            size=(n, 12)
        )
        # Motion (32-47)
        motion = rng.normal(
            loc=[0.05, 0.1, 0.02, 0.08, 9.8, 0.15, 9.82, 0.05,
                 0.01, 0.02, 0.005, 0.015, 0.008, 0.012, 65, 0.001],
            scale=[0.02, 0.02, 0.02, 0.02, 0.1, 0.03, 0.12, 0.01,
                   0.005, 0.008, 0.003, 0.006, 0.004, 0.005, 5, 0.0005],
            size=(n, 16)
        )

    elif label == 1:  # Uncertain — moderate variance, cold-start
        touch = rng.normal(
            loc=[180, 25, 250, 40, 1.8, 3.8, 0.04,
                 0.5, 0.78, 0.09, 14, 9, 1.3,
                 0.5, 1.2, 100, 25, 0.5, 0.005, 110],
            scale=[40, 10, 60, 15, 0.4, 0.8, 0.015,
                   0.1, 0.12, 0.04, 3, 2, 0.15,
                   0.15, 0.25, 25, 8, 0.12, 0.0015, 25],
            size=(n, 20)
        )
        typing = rng.normal(
            loc=[150, 35, 145, 110, 40, 120, 5.0, 170, 0.5, 0.1, 20, 0.6],
            scale=[35, 12, 35, 30, 15, 30, 2.0, 40, 0.1, 0.04, 5, 0.12],
            size=(n, 12)
        )
        motion = rng.normal(
            loc=[0.15, 0.25, 0.1, 0.2, 9.75, 0.35, 9.85, 0.15,
                 0.04, 0.06, 0.02, 0.05, 0.03, 0.04, 55, 0.004],
            scale=[0.08, 0.1, 0.07, 0.09, 0.3, 0.1, 0.35, 0.06,
                   0.02, 0.025, 0.012, 0.022, 0.015, 0.018, 12, 0.002],
            size=(n, 16)
        )

    else:  # Anomaly — high variance, unusual patterns (label == 2)
        touch = rng.normal(
            loc=[250, 60, 350, 90, 2.8, 5.5, 0.09,
                 0.35, 0.65, 0.18, 18, 12, 1.8,
                 1.0, 2.0, 160, 55, 0.35, 0.015, 150],
            scale=[80, 25, 120, 40, 0.9, 1.5, 0.04,
                   0.15, 0.2, 0.08, 5, 4, 0.4,
                   0.4, 0.6, 55, 20, 0.2, 0.006, 55],
            size=(n, 20)
        )
        typing = rng.normal(
            loc=[200, 70, 190, 160, 80, 180, 15.0, 110, 0.4, 0.18, 25, 1.2],
            scale=[70, 30, 65, 60, 35, 60, 6.0, 55, 0.18, 0.08, 8, 0.3],
            size=(n, 12)
        )
        motion = rng.normal(
            loc=[0.5, 0.7, 0.4, 0.6, 9.5, 0.9, 10.0, 0.6,
                 0.15, 0.25, 0.1, 0.2, 0.12, 0.18, 40, 0.02],
            scale=[0.25, 0.3, 0.2, 0.28, 0.6, 0.35, 0.65, 0.25,
                   0.07, 0.1, 0.05, 0.09, 0.06, 0.08, 18, 0.009],
            size=(n, 16)
        )

    return np.hstack([touch, typing, motion])


def main():
    os.makedirs("ml/data", exist_ok=True)

    all_data = []
    all_labels = []
    for cls in range(3):
        data = generate_class(cls, N_PER_CLASS)
        all_data.append(data)
        all_labels.extend([cls] * N_PER_CLASS)

    X = np.vstack(all_data)
    y = np.array(all_labels)

    df = pd.DataFrame(X, columns=FEATURE_NAMES)
    df["label"] = y

    out_path = "ml/data/synthetic_behavioural_data.csv"
    df.to_csv(out_path, index=False)

    print(f"Shape: {df.shape}")
    print("Class distribution:")
    print(df["label"].value_counts().sort_index())
    print(f"Saved to {out_path}")


if __name__ == "__main__":
    main()
