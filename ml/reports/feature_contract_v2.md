# SafeBank feature contract v2

Version: `safebank.behaviour.v2`. Exactly 48 ordered float values: 29 active slots and 19 reserved zeros. The original JSON and model artifacts are preserved.

| Index | Stored name | Runtime meaning |
|---|---|---|
| 0 | `touch_stroke_duration_mean` | ms; contact duration mean |
| 1 | `touch_stroke_duration_std` | ms; population standard deviation |
| 2 | `touch_stroke_length_mean` | px; displacement magnitude mean |
| 3 | `touch_stroke_length_std` | px; population standard deviation |
| 4 | `touch_stroke_velocity_mean` | px/ms; per-gesture velocity mean |
| 5 | `touch_stroke_velocity_max` | px/ms; maximum velocity |
| 6 | `reserved_zero_6` | Reserved: exactly zero |
| 7 | `reserved_zero_7` | Reserved: exactly zero |
| 8 | `reserved_zero_8` | Reserved: exactly zero |
| 9 | `reserved_zero_9` | Reserved: exactly zero |
| 10 | `reserved_zero_10` | Reserved: exactly zero |
| 11 | `reserved_zero_11` | Reserved: exactly zero |
| 12 | `reserved_zero_12` | Reserved: exactly zero |
| 13 | `reserved_zero_13` | Reserved: exactly zero |
| 14 | `reserved_zero_14` | Reserved: exactly zero |
| 15 | `touch_inter_event_time_mean` | ms; successive gesture completion intervals mean |
| 16 | `touch_inter_event_time_std` | ms; interval population standard deviation |
| 17 | `reserved_zero_17` | Reserved: exactly zero |
| 18 | `reserved_zero_18` | Reserved: exactly zero |
| 19 | `touch_contact_duration_mean_ms` | ms; same measured contact duration mean as slot 0 |
| 20 | `reserved_zero_20` | Reserved: exactly zero |
| 21 | `reserved_zero_21` | Reserved: exactly zero |
| 22 | `reserved_zero_22` | Reserved: exactly zero |
| 23 | `typing_flight_time_mean` | ms; valid inter-edit intervals mean (no typed content) |
| 24 | `typing_flight_time_std` | ms; interval population standard deviation |
| 25 | `typing_digraph_latency_mean` | ms; interval proxy, identical to slot 23; not true digraph identity |
| 26 | `typing_backspace_rate` | percent; backspace-event count / edit-event count * 100 |
| 27 | `typing_interval_cpm` | intervals/minute; 60000 * valid interval count / sum(intervals) |
| 28 | `reserved_zero_28` | Reserved: exactly zero |
| 29 | `reserved_zero_29` | Reserved: exactly zero |
| 30 | `reserved_zero_30` | Reserved: exactly zero |
| 31 | `reserved_zero_31` | Reserved: exactly zero |
| 32 | `motion_accel_x_mean` | m/s^2; accelerometer including gravity; mean |
| 33 | `motion_accel_x_std` | m/s^2; accelerometer including gravity; population standard deviation |
| 34 | `motion_accel_y_mean` | m/s^2; accelerometer including gravity; mean |
| 35 | `motion_accel_y_std` | m/s^2; accelerometer including gravity; population standard deviation |
| 36 | `motion_accel_z_mean` | m/s^2; accelerometer including gravity; mean |
| 37 | `motion_accel_z_std` | m/s^2; accelerometer including gravity; population standard deviation |
| 38 | `motion_accel_magnitude_mean` | m/s^2; mean Euclidean accelerometer magnitude |
| 39 | `motion_accel_magnitude_variance` | (m/s^2)^2; population magnitude variance |
| 40 | `motion_gyro_pitch_mean` | rad/s; Android gyroscope x/y/z axis; mean |
| 41 | `motion_gyro_pitch_std` | rad/s; Android gyroscope x/y/z axis; population standard deviation |
| 42 | `motion_gyro_roll_mean` | rad/s; Android gyroscope x/y/z axis; mean |
| 43 | `motion_gyro_roll_std` | rad/s; Android gyroscope x/y/z axis; population standard deviation |
| 44 | `motion_gyro_yaw_mean` | rad/s; Android gyroscope x/y/z axis; mean |
| 45 | `motion_gyro_yaw_std` | rad/s; Android gyroscope x/y/z axis; population standard deviation |
| 46 | `motion_tilt_from_vertical_degrees` | degrees; atan2(hypot(mean(ax),mean(ay)), abs(mean(az))) |
| 47 | `reserved_zero_47` | Reserved: exactly zero |

Standard deviations use population variance and return 0.001 for empty, singleton or constant inputs. Missing modality means/cadence are zero; the existing empty-motion path uses 0.001 at odd indices including slot 39. Reserved slots are always zero. The synthetic training data does not represent missing-modality distributions: native execution success is not evidence of calibration for such windows.

Apply the saved StandardScaler once: `(raw - means) / stds`. Class order: 0 genuine, 1 uncertain, 2 anomaly. Android loads only `assets/v2/behaviour_model.tflite`, paired with `v2/scaler_params.json` and `v2/feature_contract.json`.

Continuous capture uses a rolling 30-second window, explicit per-user consent, foreground lifecycle gating, relative gesture displacement and typing timing only on the transfer amount field. Password, PIN, recipient and description contents are not forwarded to the collector. Motion is accelerometer plus the latest gyroscope sample (not a hardware-synchronized sequence). This is tabular window aggregation, not a CNN/LSTM input stream.

Training provenance: 3,000 seeded synthetic rows, 1,000 per class, split by stratified seed 42 into 2,100/450/450 train/validation/test. Reserved columns are zeroed; slots 19,25,27,46 are corrected by train_v2.py. Other synthetic columns remain independently generated; physical relationships and real human behaviour are not validated. Do not interpret synthetic accuracy as real biometric security performance.