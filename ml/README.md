# SafeBank AI — Behavioural ML Specification

## 1. Overview & Privacy Principles
The behavioural authentication layer operates strictly on-device to continuously evaluate whether the current user interaction is consistent with the enrolled user's profile.

### Fundamental Privacy Guarantees
1. **Raw Sensor Traces Never Leave Device**: Raw capacitive coordinates, keystroke timestamps, and raw accelerometer/gyroscope readings are buffered exclusively in volatile memory for local feature extraction, then purged.
2. **Aggregated Vector Inference**: The feature extractor outputs a 48-dimensional normalised vector `x ∈ ℝ⁴⁸`.
3. **On-Device Inference**: Inference runs locally via TensorFlow Lite / LiteRT.
4. **Anomaly ≠ Coercion**: An anomalous score indicates deviation or uncertainty (stress, physical movement, cold hands). It triggers step-up verification, never silent duress mode.

---

## 2. 48-Feature Behavioural Vector Architecture

### A. Touch Dynamics (20 Features)
- `touch_stroke_duration_mean`, `touch_stroke_duration_std`: Duration of contact gestures (ms).
- `touch_stroke_length_mean`, `touch_stroke_length_std`: Euclidean displacement across swipe events (pixels).
- `touch_stroke_velocity_mean`, `touch_stroke_velocity_max`: Velocity of swipes (pixels/ms).
- `touch_stroke_acceleration_mean`: Second derivative of trajectory.
- `touch_pressure_mean`, `touch_pressure_max`, `touch_pressure_std`: Normalised capacitive pressure reported by MotionEvent.
- `touch_major_axis_mean`, `touch_minor_axis_mean`: Touch area ellipse dimensions (`tool_major`/`tool_minor`).
- `touch_trajectory_curvature`: Path length divided by endpoint displacement.
- `touch_start_angle`, `touch_end_angle`: Directional orientation vectors (radians).
- `touch_inter_event_time_mean`, `touch_inter_event_time_std`: Latency between consecutive touch events.
- `touch_swipe_horizontal_ratio`: Proportion of primarily horizontal gestures.
- `touch_jitter_variance`: High-frequency directional displacement jitter.

### B. Typing Dynamics (12 Features)
- `typing_hold_time_mean`, `typing_hold_time_std`, `typing_hold_time_median`: Key dwell time (down to up).
- `typing_flight_time_mean`, `typing_flight_time_std`: Up-to-down interval between consecutive keys.
- `typing_digraph_latency_mean`: Down-to-down latency for common two-character sequences.
- `typing_backspace_rate`: Frequency of corrections per 100 keystrokes.
- `typing_speed_cpm`: Characters per minute moving average.
- `typing_pressure_mean`, `typing_pressure_std`: Capacitive contact force during keystrokes.
- `typing_contact_size_mean`: Contact area during virtual keyboard interactions.
- `typing_rhythm_entropy`: Regularity and rhythmicity of typing cadence.

### C. Motion Dynamics (16 Features)
- `motion_accel_x_mean`, `motion_accel_x_std`: Phone lateral acceleration (m/s²).
- `motion_accel_y_mean`, `motion_accel_y_std`: Phone longitudinal acceleration (m/s²).
- `motion_accel_z_mean`, `motion_accel_z_std`: Vertical gravity-aligned acceleration (m/s²).
- `motion_accel_magnitude_mean`, `motion_accel_magnitude_variance`: Total linear acceleration magnitude.
- `motion_gyro_pitch_mean`, `motion_gyro_pitch_std`: Rotational velocity around lateral axis (rad/s).
- `motion_gyro_roll_mean`, `motion_gyro_roll_std`: Rotational velocity around longitudinal axis (rad/s).
- `motion_gyro_yaw_mean`, `motion_gyro_yaw_std`: Rotational velocity around vertical axis (rad/s).
- `motion_device_tilt_angle_mean`: Device elevation angle relative to horizontal plane.
- `motion_micro_tremor_energy`: Spectral energy in 8–12 Hz band (physiological tremor).

---

## 3. Candidate Multi-Layer Perceptron (MLP)

```
Input Vector (48-D)
      │
      ▼
Dense(64) + BatchNorm + ReLU + Dropout(0.2)
      │
      ▼
Dense(32) + BatchNorm + ReLU + Dropout(0.1)
      │
      ▼
Dense(3, activation='softmax')
```

### Target Classes (3 Outputs)
0. **Class 0: Genuine / Consistent**: Behaviour aligns with enrolled user baseline.
1. **Class 1: Uncertain / Insufficient**: Low confidence, cold-start, or ambiguous signal.
2. **Class 2: Anomaly / Deviation**: Statistically significant divergence from baseline.
