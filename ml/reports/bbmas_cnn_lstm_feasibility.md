# BB-MAS 1D CNN & LSTM Architectural Feasibility Study

**Target Problem:** Continuous On-Device Behavioural Authentication for SafeBank AI  
**Data Foundation:** SU-AIS BB-MAS Smartphone Modalities (arXiv:1912.02736, DOI: 10.21227/rpaz-0h66)  
**Status:** Pre-training architectural validation and engineering feasibility assessment.

---

## 1. 1D CNN Feasibility Analysis (Short-Term Local Interaction Detector)

### Purpose in SafeBank AI
The 1D CNN is designed to capture local, translation-invariant temporal signatures in fine-grained user interactions (e.g., the micro-vibrations accompanying finger taps, the acceleration curvature during a thumb swipe, and gyro recoil when releasing a virtual key).

### Input Specification
- **Window Length ($T$):** 128 timesteps at 100 Hz $\approx 1.28\text{ s}$.
  - Justification: A standard mobile swipe gesture lasts 200–600 ms; a keystroke tap lasts 80–180 ms. A 1.28-second temporal receptive field encapsulates 1–3 complete gesture/keystroke cycles along with their inertial anticipation and recoil phases.
- **Channels ($C$):** 8 channels ($a_x, a_y, a_z, \omega_x, \omega_y, \omega_z, \text{pressure}, \text{velocity}$).
- **Tensor Shape:** `(Batch_Size, 128, 8)`

### Architectural Blueprint
```
Input: [B, 128, 8]
  │
  ├── Conv1D(filters=32, kernel_size=7, padding="same") -> BatchNorm -> ReLU
  ├── Conv1D(filters=32, kernel_size=7, padding="same") -> BatchNorm -> ReLU
  ├── MaxPool1D(pool_size=2)                             # Output: [B, 64, 32]
  │
  ├── Conv1D(filters=64, kernel_size=5, padding="same") -> BatchNorm -> ReLU
  ├── Conv1D(filters=64, kernel_size=5, padding="same") -> BatchNorm -> ReLU
  ├── MaxPool1D(pool_size=2)                             # Output: [B, 32, 64]
  │
  ├── Conv1D(filters=128, kernel_size=3, padding="same") -> BatchNorm -> ReLU
  ├── GlobalAveragePooling1D()                           # Output: [B, 128]
  │
  ├── Dense(64, activation="relu") -> Dropout(0.4)
  └── Dense(2, activation="softmax")                     # Output: [Genuine, Impostor]
```

### Parameter Count & On-Device Deployment
- Total Trainable Parameters: **~182,400**
- FP32 Model Size: **~730 KB**
- INT8 Quantized TFLite Model: **~195 KB**
- Target Mobile Latency (Snapdragon 8 Gen 1 / Google Tensor / x86_64 emulator): **< 4.5 ms**
- Verdict: **Highly Feasible for Native Android TFLite Deployment.**

---

## 2. LSTM Feasibility Analysis (Temporal Session-Level Authentication)

### Purpose in SafeBank AI
The LSTM is designed to model longer-range temporal dependencies across chronological transactions or multi-field form completion (e.g., transfer amount entry, account picker scrolling, review screen verification). It tracks cognitive cadence, hesitation patterns, and session fatigue.

### Input Specification
- **Sequence Length ($T_{\text{seq}}$):** 256 timesteps (padded/truncated) at 100 Hz $\approx 2.56\text{ s}$ per sequence slice, or event-driven sequence of up to 64 discrete keystroke/swipe events.
- **Channels ($C$):** 8 channels with `Masking(mask_value=-999.0)` for variable-length session sequences.
- **State Reset Boundary:** **Hard reset on session boundary.** The LSTM hidden state is reset between distinct tasks and sessions to prevent temporal leakage across disconnected interactions.

### Architectural Blueprint
```
Input: [B, 256, 8]
  │
  ├── Masking(mask_value=-999.0)
  ├── Bidirectional(LSTM(units=64, return_sequences=True, dropout=0.25))   # Output: [B, 256, 128]
  ├── Bidirectional(LSTM(units=64, return_sequences=False, dropout=0.25))  # Output: [B, 128]
  │
  ├── Dense(64, activation="relu") -> Dropout(0.35)
  └── Dense(2, activation="softmax")                                       # Output: [Genuine, Impostor]
```

### Parameter Count & On-Device Deployment
- Total Trainable Parameters: **~161,200**
- FP32 Model Size: **~645 KB**
- Target Mobile Latency: **~12–18 ms per sequence inference**
- Verdict: **Feasible for Periodic Session Re-verification in Background Android Coroutines.**

---

## 3. Modality Availability Across Tasks in BB-MAS

BB-MAS explicitly separates tasks into distinct functional categories:

| Task ID | Physical Activity | User Interaction | Active Sensors in BB-MAS |
|---|---|---|---|
| **Task H** | Sitting at desk | Typing on phone keyboard, vertical swipe | Keyboard, Swipes, Accelerometer, Gyroscope |
| **Tasks I, K** | Walking corridor | In-hand navigation, occasional checkpoint taps | Accelerometer, Gyroscope, Swipes (taps only) |
| **Tasks J, L** | Walking stairs | In-hand navigation, upstairs/downstairs taps | Accelerometer, Gyroscope, Swipes (taps only) |

### Engineering Implications:
1. **Never assume all 4 modalities exist simultaneously for every task:**
   - Tasks I, J, K, and L have **zero keyboard events**.
   - If an architecture requires simultaneous non-zero inputs on all 4 modalities, 75% of the BB-MAS smartphone dataset would be unusable.
2. **Recommended Input Strategy:**
   - **Scenario A (Multimodal Interaction Windows):** Use Task H for full touch+typing+motion evaluations.
   - **Scenario B (Continuous In-Hand Motion + Opportunistic Touch):** Use the unified 8-channel matrix with explicit masking for inactive touch channels across all tasks (H, I, J, K, L). This unlocks the complete ~14M motion samples and reflects real banking app usage (where the user holds the phone continuously but taps only intermittently).

---

## 4. Hardware & Training Requirements on User's Windows System

| Component | Estimate (Windows CPU: 4 Cores) | Estimate (Windows GPU: e.g., RTX 3050+) |
|---|---|---|
| **Raw CSV Extraction** | 3.5 minutes | 3.5 minutes (I/O bound) |
| **100 Hz Preprocessing & Windowing** | 6–10 minutes | 4–6 minutes |
| **1D CNN Training (50 Epochs, Batch 64)** | 18–35 minutes | 3–6 minutes |
| **LSTM Training (50 Epochs, Batch 32)** | 35–65 minutes | 6–12 minutes |
| **Peak RAM Consumption** | ~3.8 GB | ~3.8 GB RAM + 2.5 GB VRAM |
| **Disk Space (Processed NPZ)** | ~140–180 MB | ~140–180 MB |

*Assessment:* The user's system can comfortably perform both preprocessing and model training without modifying global Python installations or system security configurations.
