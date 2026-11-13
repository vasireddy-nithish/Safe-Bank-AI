# BB-MAS Smartphone Data Preprocessing Plan & Anti-Leakage Protocol

**Target Dataset:** SU-AIS BB-MAS (IEEE DataPort DOI: 10.21227/rpaz-0h66, arXiv:1912.02736)  
**Target Modalities:** HandPhone Keyboard, HandPhone Swipes, HandPhone Accelerometer, HandPhone Gyroscope  
**Output Target:** SafeBank AI 1D CNN (short-term window detector) & LSTM (session-level temporal verifier)  
**Strict Requirement:** Zero data leakage, strict subject-disjoint partitions, zero modification of existing MLP v2 / Random Forest artifacts.

---

## 1. Subject-Disjoint Partitioning Protocol

To evaluate genuine and impostor authentication on previously unseen human participants, all 117 subjects are assigned to three mutually exclusive sets:

| Partition | Subject IDs | Subject Count | Percentage | Purpose |
|---|---|---|---|---|
| **Training Set** | `1` to `80` | 80 subjects | ~68.4% | Model weight training, parameter optimization |
| **Validation Set** | `81` to `97` | 17 subjects | ~14.5% | Hyperparameter tuning, early stopping, threshold calibration |
| **Test Set** | `98` to `117` | 20 subjects | ~17.1% | Final unbiased zero-leakage evaluation (FAR, FRR, EER, ROC-AUC) |

### Anti-Leakage Invariants:
1. **Zero Subject Overlap:** No subject present in the training set appears in validation or test sets.
2. **Zero Window Crossing:** Sliding windows are segmented strictly within a single subject's continuous task recording. A window never crosses subject boundaries, device types, or task breaks.
3. **Training-Only Normalization:** All scalers (`StandardScaler`, mean/variance vectors) are computed **solely on Training Set subjects (`1`–`80`)**. The validation and test sets are transformed using frozen parameters from training.
4. **No Hyperparameter Snooping:** The test set subjects (`98`–`117`) are sealed until final model scoring. No architecture choice, kernel size, layer depth, or dropout rate is guided by test set metrics.

---

## 2. Chronological Enrollment & Evaluation Protocol

For each evaluation subject $S$ in the test set (evaluating unseen users):

1. **Enrollment Phase (Chronological First Portion):**
   - The first 50% of the subject's chronological task recordings represent their enrolled behavioural profile.
   - For 1D CNN / LSTM, reference biometric feature embeddings $\mathbf{e}_{\text{ref}}$ are generated from these enrollment windows.
2. **Verification Phase (Chronological Later Portion):**
   - The remaining 50% of the subject's chronological task recordings serve as **Genuine Verification Trials**.
   - No temporal overlap exists between enrollment samples and verification samples.
3. **Impostor Trials (Zero-Effort Impostor Attack):**
   - Verification attempts against subject $S$'s profile are constructed using verification windows sampled from all other held-out test subjects $S' \neq S$.
   - This provides realistic zero-effort impostor evaluation without synthetic artifacts.

---

## 3. Modality Decoupling & Synchronization Strategy

The BB-MAS phone dataset contains continuous physical sensor streams alongside event-driven discrete interaction streams:

| Stream Type | Modalities | Sampling Mechanism | Frequency |
|---|---|---|---|
| **Continuous Inertial** | Accelerometer, Gyroscope | Hardware timer polling | ~100 Hz (10 ms period) |
| **Discrete Touch** | Swipes, Taps, Moves | User-initiated touch events | Irregular (0 to 60 Hz during active touch) |
| **Discrete Typing** | Key down, Key up | Virtual keyboard keystrokes | Irregular (bursts of 100–300 ms hold/flight) |

### Synchronization & Unified Feature Tensor Plan:
- **Time Base:** Unix timestamp in milliseconds (`time` column).
- **Resampling Grid:** 100 Hz (10 ms time bins).
- **8-Channel Synchronized Feature Tensor:**
  $$\mathbf{X}[t] = \left[ a_x(t), a_y(t), a_z(t), \omega_x(t), \omega_y(t), \omega_z(t), p(t), v(t) \right]$$
  - Channels 0–2: Linear acceleration along $X, Y, Z$ ($m/s^2$)
  - Channels 3–5: Angular velocity along $X, Y, Z$ ($rad/s$)
  - Channel 6: Touch pressure ($0.0$ to $1.0$; $0.0$ when no touch active)
  - Channel 7: Touch velocity ($px/ms$; $0.0$ when finger stationary or lifted)

### Handling Missing Modalities During Non-Typing / Non-Swiping Intervals:
When a user is walking or holding the phone without touching the screen (e.g., Tasks I, J, K, L):
- Touch pressure and velocity channels are explicitly zeroed.
- A **Modality Presence Mask** $M \in \{0, 1\}^C$ indicates active sensor channels.
- Models are trained with masking layers to prevent zero-padding from being misinterpreted as deliberate user behaviour.

---

## 4. Preprocessing Implementation Pipeline

The planned script `ml/scripts/preprocess_bbmas.py` will execute the following stages:

```
[Raw Phone CSVs: 117 subjects]
       │
       ▼
1. Validation & Schema Check (column verification, corrupt row rejection)
       │
       ▼
2. Chronological Sorting & Millisecond Timestamp Parsing
       │
       ▼
3. 100 Hz Resampling & 8-Channel Synchronized Matrix Generation
       │
       ▼
4. Subject-Disjoint Splitting (80 Train / 17 Val / 20 Test)
       │
       ▼
5. Training-Only Normalization Parameter Computation
       │
       ▼
6. Sliding Window Slicing:
   - CNN: T = 128 steps (1.28 s), 50% stride (64 steps)
   - LSTM: T = 256 steps (2.56 s), variable length with masking
       │
       ▼
[Output: ml/data/processed/bbmas_cnn_windows.npz & bbmas_lstm_sequences.npz]
```

### Storage Efficiency:
- Processed sliding windows will be serialized directly as compressed NumPy binary archives (`.npz`) containing `float32` arrays and `int32` labels.
- Estimated processed dataset footprint: **~140 MB**, eliminating the need to repeatedly parse gigabytes of raw CSV files during model iteration.
