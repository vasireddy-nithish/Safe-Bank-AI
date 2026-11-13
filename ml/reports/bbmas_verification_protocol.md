# SafeBank AI — BB-MAS Real-World Behavioural Biometrics Verification Protocol

**Dataset:** SU-AIS BB-MAS (Smartphone Interaction Subset)  
**Authors/Reference:** E. Frank, F. Kargl et al., *IEEE DataPort* / *arXiv:1912.02736*  
**Date:** November 2026  
**Implementation:** SafeBank AI Anti-Leakage Behavioural Biometric Verification Engine

---

## 1. Executive Summary

This protocol defines the formal unseen-user zero-shot biometric verification framework implemented for SafeBank AI using real smartphone behavioural biometrics from the SU-AIS BB-MAS dataset.

Unlike closed-set identification where a model classifies among known subjects, continuous behavioural authentication on mobile devices requires **open-set verification**: the model must verify genuine users vs. impostors without having ever encountered the test identities during training or feature normalization.

---

## 2. Subject-Disjoint Partitioning

To guarantee zero identity leakage, the 116 valid subjects in the BB-MAS smartphone dataset are partitioned into three strictly disjoint sets:

| Partition | Subject IDs | Subject Count | Role in Protocol |
|:---|:---:|:---:|:---|
| **Training Split** | Subjects 1 – 80 | 80 | Model representation learning (feature extraction, metric embeddings, gradient updates) |
| **Validation Split** | Subjects 81 – 98 | 18 | Hyperparameter tuning and optimal verification threshold selection ($\theta^*$) |
| **Testing Split** | Subjects 99 – 116 | 18 | Held-out zero-shot verification performance evaluation with frozen $\theta^*$ |

$$\text{Subjects}_{\text{Train}} \cap \text{Subjects}_{\text{Val}} = \emptyset, \quad \text{Subjects}_{\text{Train}} \cap \text{Subjects}_{\text{Test}} = \emptyset, \quad \text{Subjects}_{\text{Val}} \cap \text{Subjects}_{\text{Test}} = \emptyset$$

### Preprocessing Anti-Leakage Guarantee
- `StandardScaler` parameters (mean $\mu$ and standard deviation $\sigma$) are computed **strictly** across the 80 training subjects (`X_train`).
- The fitted parameters are serialized to `ml/data/processed/bbmas_scaler_params.json` and applied as a frozen linear transformation to validation and test sequences.
- No statistical moments from validation or test subjects ever influence the preprocessing scale.

---

## 3. Modality Alignment & Window Formulation

### 3.1 Input Channels
The synchronized 100 Hz feature matrix consists of 8 physical channels:
1. `accel_x` ($m/s^2$): Lateral device acceleration
2. `accel_y` ($m/s^2$): Longitudinal device acceleration
3. `accel_z` ($m/s^2$): Perpendicular device acceleration
4. `gyro_x` ($rad/s$): Pitch rotational velocity
5. `gyro_y` ($rad/s$): Roll rotational velocity
6. `gyro_z` ($rad/s$): Yaw rotational velocity
7. `pressure` ($[0.0, 1.0]$): Normalized touch pressure (0.0 during non-touch motion)
8. `interaction_state` ($\in \{0, 1, 2\}$): 0 = Passive motion, 1 = Touch down/up event, 2 = Keystroke event

### 3.2 Temporal Window Shapes
- **1D CNN:** Window length $T = 128$ steps ($1.28\text{ seconds}$ at 100 Hz), step size = 64 (50% overlap). Shape: `(128, 8)`. 150 windows per subject.
- **LSTM:** Sequence length $T = 256$ steps ($2.56\text{ seconds}$ at 100 Hz), step size = 128 (50% overlap), with validity masking (padded values marked as -999.0). Shape: `(256, 8)`. 75 sequences per subject.

---

## 4. Enrollment Template & Query Protocol

For each evaluation subject $s \in \text{Subjects}_{\text{Eval}}$:

1. **Chronological Splitting:**
   - The first 50% of chronological windows form the **Enrollment Set** $\mathcal{E}_s$.
   - The remaining 50% of chronological windows form the **Query Set** $\mathcal{Q}_s$.
   - No temporal overlap exists between enrollment and query windows.

2. **Template Synthesis:**
   - Each enrollment window $x_k \in \mathcal{E}_s$ is passed through the embedding encoder $f_\theta(\cdot)$ to generate a 64-dimensional unit-normalized embedding:
     $$e_k = \frac{f_\theta(x_k)}{\|f_\theta(x_k)\|_2} \in \mathbb{R}^{64}$$
   - The enrolled biometric template $\mathbf{T}_s$ is the L2-normalized mean of all enrollment embeddings:
     $$\mathbf{T}_s = \frac{\sum_{k \in \mathcal{E}_s} e_k}{\left\|\sum_{k \in \mathcal{E}_s} e_k\right\|_2}$$

3. **Genuine Comparison Construction:**
   - Every query sample $x_q \in \mathcal{Q}_s$ of subject $s$ is compared against their own template $\mathbf{T}_s$:
     $$S_{\text{genuine}}(s, q) = \cos(\mathbf{T}_s, f_\theta(x_q)) = \mathbf{T}_s \cdot \frac{f_\theta(x_q)}{\|f_\theta(x_q)\|_2}$$

4. **Impostor Comparison Construction:**
   - Every query sample $x_q \in \mathcal{Q}_s$ of subject $s$ is compared against templates $\mathbf{T}_{s'}$ of all other evaluation subjects $s' \neq s$:
     $$S_{\text{impostor}}(s, s', q) = \cos(\mathbf{T}_{s'}, f_\theta(x_q)) = \mathbf{T}_{s'} \cdot \frac{f_\theta(x_q)}{\|f_\theta(x_q)\|_2}$$
   - Impostor pairs are sampled across the full evaluation cohort to achieve balanced, statistically robust impostor representation.

---

## 5. Verification Metrics & Threshold Calibration

Given verification score $S \in [-1.0, 1.0]$ and threshold $\theta \in [-1.0, 1.0]$:
- Claimed identity is **Accepted** if $S \ge \theta$.
- Claimed identity is **Rejected** if $S < \theta$.

### 5.1 Error Rate Definitions
- **False Acceptance Rate (FAR):** Proportion of impostor attempts incorrectly accepted:
  $$\text{FAR}(\theta) = \frac{|\{S \in S_{\text{impostor}} : S \ge \theta\}|}{|S_{\text{impostor}}|}$$
- **False Rejection Rate (FRR):** Proportion of genuine attempts incorrectly rejected:
  $$\text{FRR}(\theta) = \frac{|\{S \in S_{\text{genuine}} : S < \theta\}|}{|S_{\text{genuine}}|}$$
- **Equal Error Rate (EER):** The operating point where FAR equals FRR:
  $$\theta^* = \arg\min_\theta |\text{FAR}(\theta) - \text{FRR}(\theta)|, \quad \text{EER} = \frac{\text{FAR}(\theta^*) + \text{FRR}(\theta^*)}{2}$$
- **Area Under ROC Curve (ROC-AUC):** Integral of genuine acceptance rate ($1 - \text{FRR}$) versus FAR across all potential operational thresholds $\theta \in [-1.0, 1.0]$.

### 5.2 Threshold Calibration Rigor
- $\theta^*$ is calibrated **strictly on the Validation cohort (Subjects 81–98)**.
- Once selected, $\theta^*$ is frozen and directly applied to the held-out **Test cohort (Subjects 99–116)**.
- Test performance is evaluated without threshold re-tuning to ensure true zero-shot generalization.

---

## 6. Audit & Reproducibility Verification

All dataset tensors, preprocessing scalers, training seeds, and evaluation routines are fully reproducible:
- Random Seed: `42` (NumPy, Python `random`, TensorFlow)
- Scaler Parameters: `ml/data/processed/bbmas_scaler_params.json`
- Verification Engine: `ml/scripts/verification_protocol.py`
- Preprocessed Tensors: `ml/data/processed/bbmas_cnn_dataset.npz` & `bbmas_lstm_dataset.npz`
