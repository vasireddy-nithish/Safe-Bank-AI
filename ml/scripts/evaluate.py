"""
evaluate.py
Evaluate the trained MLP model on the held-out test set.
Outputs: accuracy, per-class precision/recall/F1, macro F1, confusion matrix, ROC-AUC.

NOTE: Metrics are on synthetic data only.
Do not interpret as real-world fraud detection performance.
"""

import json
import os
import joblib
import numpy as np
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
    roc_auc_score,
)

DATA_DIR = "ml/data"
MODELS_DIR = "ml/models"

model = joblib.load(os.path.join(MODELS_DIR, "behaviour_model.joblib"))
X_test = np.load(os.path.join(DATA_DIR, "X_test.npy"))
y_test = np.load(os.path.join(DATA_DIR, "y_test.npy"))

CLASS_NAMES = ["genuine", "uncertain", "anomaly"]

print("=== SafeBank AI — Evaluation Report ===")
print(
    "NOTE: Metrics are on synthetic data only. "
    "Do not interpret as real-world fraud detection performance.\n"
)

probs = model.predict_proba(X_test)
y_pred = np.argmax(probs, axis=1)

acc = accuracy_score(y_test, y_pred)
print(f"Test Accuracy : {acc:.4f}")

print("\n--- Classification Report ---")
print(classification_report(y_test, y_pred, target_names=CLASS_NAMES, digits=4))

macro_f1 = f1_score(y_test, y_pred, average="macro")
print(f"Macro F1      : {macro_f1:.4f}")

cm = confusion_matrix(y_test, y_pred)
print("\n--- Confusion Matrix ---")
header = "         " + "  ".join(f"{n:>9}" for n in CLASS_NAMES)
print(header)
for i, row in enumerate(cm):
    print(f"{CLASS_NAMES[i]:>9}  " + "  ".join(f"{v:>9}" for v in row))

roc_auc = roc_auc_score(y_test, probs, multi_class="ovr")
print(f"\nROC-AUC (OvR) : {roc_auc:.4f}")

precision, recall, f1, support = precision_recall_fscore_support(
    y_test, y_pred, labels=[0, 1, 2]
)

report = {
    "note": "Metrics are on synthetic data only. Do not interpret as real-world fraud detection performance.",
    "accuracy": float(acc),
    "macro_f1": float(macro_f1),
    "roc_auc_ovr": float(roc_auc),
    "per_class": {
        CLASS_NAMES[i]: {
            "precision": float(precision[i]),
            "recall": float(recall[i]),
            "f1": float(f1[i]),
            "support": int(support[i]),
        }
        for i in range(3)
    },
    "confusion_matrix": cm.tolist(),
}

report_path = os.path.join(MODELS_DIR, "evaluation_report.json")
with open(report_path, "w") as fh:
    json.dump(report, fh, indent=2)
print(f"\nReport saved -> {report_path}")
