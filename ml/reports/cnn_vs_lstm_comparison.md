# SafeBank AI — 1D CNN vs. LSTM Behavioural Biometric Verification Comparison

**Dataset:** SU-AIS BB-MAS Real Smartphone Behavioural Biometrics (116 Subjects)  
**Evaluation Protocol:** Subject-Disjoint Zero-Shot Verification (Train: 1–80, Val: 81–98, Test: 99–116)  
**Date:** November 2026  
**Project:** SafeBank AI (`C:\Users\hp\Downloads\zup`)

---

## 1. Executive Summary

This report presents a empirical comparative evaluation of two deep behavioural biometric architectures trained on the real-world SU-AIS BB-MAS smartphone dataset:
1. **1D Convolutional Neural Network (1D CNN):** Multi-scale spatial and local temporal feature extractor operating on 1.28-second windows (`(128, 8)`).
2. **Long Short-Term Memory Network (LSTM):** Recurrent sequential architecture operating on 2.56-second sequences (`(256, 8)`) with validity masking.

Both models were trained on 80 subjects, calibrated for optimal verification threshold ($\theta^*$) on 18 unseen validation subjects, and evaluated on 18 held-out unseen test subjects without threshold re-tuning.

---

## 2. Quantitative Performance Comparison

| Metric / Dimension | 1D CNN (Spatial & Local Temporal) | LSTM (Recurrent Sequential) | Advantage |
|:---|:---:|:---:|:---:|
| **Input Shape** | `(128, 8)` (1.28 s @ 100 Hz) | `(256, 8)` (2.56 s @ 100 Hz, masked) | CNN (lower latency window) |
| **Embedding Dimension** | 64 | 64 | Equal |
| **Total Trainable Parameters** | 45,984 | 33,216 | LSTM (+27.8% fewer params) |
| **Actual Training Duration (50 Epochs)** | **482.60 s (8.04 min)** | 2,201.53 s (36.69 min) | **CNN (4.56× faster training)** |
| **Final Training Loss** | **0.8212** | 3.1351 | **CNN (significantly better convergence)** |
| **Final Training Accuracy (80 classes)** | **81.68%** | 19.53% | **CNN (+62.15% higher discriminability)** |
| **Validation EER (Unseen 81–98)** | **24.62%** | 32.33% | **CNN (7.71% lower error)** |
| **Validation ROC-AUC** | **0.8523** | 0.7430 | **CNN (+0.1093 AUC)** |
| **Optimal Verification Threshold ($\theta^*$)** | 0.2993 | 0.2654 | Calibrated on Val cohort |
| **Test EER (Held-Out 99–116)** | **27.33%** | 41.20% | **CNN (13.87% lower error)** |
| **Test ROC-AUC** | **0.8202** | 0.6624 | **CNN (+0.1578 AUC)** |
| **Test Accuracy @ Frozen $\theta^*$** | **72.74%** | 59.34% | **CNN (+13.40% higher accuracy)** |
| **Test False Acceptance Rate (FAR)** | **26.21%** | 39.38% | **CNN (13.17% lower impostor acceptance)** |
| **Test False Rejection Rate (FRR)** | **28.44%** | 41.81% | **CNN (13.37% lower genuine rejection)** |
| **Test Precision** | **70.67%** | 62.28% | **CNN (+8.39% precision)** |
| **Test Recall** | **71.56%** | 58.19% | **CNN (+13.37% recall)** |
| **Test Macro F1** | **0.7111** | 0.6017 | **CNN (+0.1094 F1)** |
| **TFLite Model File Size** | 184.65 KB (189,080 B) | 161.82 KB (165,704 B) | LSTM (23 KB smaller) |
| **Numerical Parity (vs Keras)** | **$4.12 \times 10^{-7}$ (Passed)** | Flex Op dependent | **CNN (verified parity)** |
| **CPU Inference Latency** | **0.207 ms (0.0002 s)** | N/A (requires Flex delegate) | **CNN (exceptional real-time speed)** |
| **Mobile Deployment Compatibility** | **100% Native Built-in TFLite** | Requires Select TF Ops (Flex) | **CNN (zero external native dependencies)** |

---

## 3. Score Distributions on Held-Out Test Subjects

### 1D CNN Score Distribution
- **Genuine Comparisons ($N = 1,350$):**
  - Mean Cosine Similarity: **$+0.4604 \pm 0.2528$**
  - Score Range: $[-0.3486, +0.9244]$
- **Impostor Comparisons ($N = 1,530$):**
  - Mean Cosine Similarity: **$+0.1321 \pm 0.2420$**
  - Score Range: $[-0.5496, +0.8179]$
- **Margin between Genuine and Impostor Means:** **$\Delta \mu = 0.3283$**
- **Confusion Matrix at Frozen $\theta^* = 0.2993$:**
  - True Negatives (Impostor Rejections): 1,129
  - False Positives (Impostor Acceptances): 401
  - False Negatives (Genuine Rejections): 384
  - True Positives (Genuine Acceptances): 966

### LSTM Score Distribution
- **Genuine Comparisons ($N = 684$):**
  - Mean Cosine Similarity: **$+0.3930 \pm 0.3663$**
  - Score Range: $[-0.5994, +0.9898]$
- **Impostor Comparisons ($N = 612$):**
  - Mean Cosine Similarity: **$+0.1644 \pm 0.3812$**
  - Score Range: $[-0.6676, +0.9353]$
- **Margin between Genuine and Impostor Means:** **$\Delta \mu = 0.2286$**
- **Confusion Matrix at Frozen $\theta^* = 0.2654$:**
  - True Negatives: 371
  - False Positives: 241
  - False Negatives: 286
  - True Positives: 398

---

## 4. In-Depth Architectural & Behavioural Analysis

### 4.1 Why the 1D CNN Outperformed LSTM
1. **Local Temporal Co-occurrence vs. Long-Range Forget Gates:**
   Smartphone interaction dynamics (micro-gestures during keystrokes, swipe acceleration profiles, and momentary tremor) are primarily localized burst events lasting 150–500 ms. 1D convolutions with receptive fields spanning 5 to 10 time steps (50–100 ms) naturally extract hierarchical touch-motion patterns without suffering from the vanishing gradient or forgetting issues that affect LSTMs over 256 time steps.
2. **Sensor Alignment & Spatial Convolutions:**
   The 1D CNN applies cross-channel kernel convolutions directly across the 8 physical streams (`[ax, ay, az, gx, gy, gz, pressure, state]`). This allows the filters to discover synchronized multi-modal features (e.g., negative Z-acceleration coupled with sudden pressure spike at touch contact) in the very first layer.
3. **Training Efficiency & Optimization Landscape:**
   The 1D CNN trained in 482.60 seconds (8.04 minutes) on native Windows CPU and achieved 81.68% training accuracy across the 80 training identities. The LSTM required 2,201.53 seconds (36.69 minutes) and only reached 19.53% training accuracy, indicating that the recurrent sequence-to-vector bottleneck struggled to isolate identity-defining features from long, noisy sensor sequences.

### 4.2 Mobile Deployment & Edge Inference
1. **TFLite Kernel Portability:**
   The 1D CNN compiles entirely into standard, built-in TFLite kernels (`CONV_2D/1D`, `BATCH_NORMALIZATION`, `RELU`, `MAX_POOL_2D`, `AVERAGE_POOL_2D`, `FULLY_CONNECTED`, `L2_NORMALIZATION`). It runs natively on the Android Neural Networks API (NNAPI) and GPU/CPU delegates without any external libraries.
2. **Inference Latency:**
   The 1D CNN achieves a measured CPU inference latency of **0.207 ms** (approximately 4,800 inferences per second), negligible battery consumption, and zero UI thread jank.
3. **LSTM Flex Op Dependency:**
   The LSTM requires `FlexTensorListReserve` operations because Keras 3 dynamic masking generates variable-length unrolling operations that are incompatible with pure TFLite runtimes unless the 40+ MB `org.tensorflow:tensorflow-lite-select-tf-ops` AAR dependency is packaged into the Android APK.

---

## 5. Architectural Recommendation for SafeBank AI

### Recommendation: Deploy 1D CNN as the Primary Real-World Behavioural Verification Engine

1. **Verification Superiority:**
   The 1D CNN delivers **27.33% EER** and **0.8202 ROC-AUC** on completely unseen held-out subjects, outperforming the LSTM (41.20% EER, 0.6624 ROC-AUC) by **13.87 percentage points in EER**.
2. **Android Integration Readiness:**
   The 1D CNN TFLite artifact (`ml/models/cnn_bbmas/cnn_encoder.tflite`, 184.65 KB) provides verified numerical parity ($4.12 \times 10^{-7}$ diff) and runs in **0.21 ms**, making it ideal for SafeBank AI's continuous background verification loop.
3. **Future Hybrid Roadmap:**
   For future development, the 1D CNN can be paired with SafeBank AI's existing 48-feature MLP v2 and Random Forest v2 in an ensemble:
   - **MLP v2 / Random Forest:** High-level statistical behavioural feature assessment (typing speed, touch variance, sensor energy).
   - **1D CNN:** Low-level raw temporal waveform and micro-gesture continuous verification.
