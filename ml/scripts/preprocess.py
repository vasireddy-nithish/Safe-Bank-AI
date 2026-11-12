"""
preprocess.py
Load synthetic CSV → stratified split → StandardScaler → save artefacts.
"""

import os, json
import numpy as np
import pandas as pd
import joblib
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

DATA_PATH  = "ml/data/synthetic_behavioural_data.csv"
MODELS_DIR = "ml/models"
DATA_DIR   = "ml/data"

os.makedirs(MODELS_DIR, exist_ok=True)
os.makedirs(DATA_DIR,   exist_ok=True)

# ── Load ──────────────────────────────────────────────────────────────────────
df = pd.read_csv(DATA_PATH)
print("=== Dataset loaded ===")
print(f"Shape: {df.shape}")
print("Class distribution:")
print(df["label"].value_counts().sort_index())

feature_names = [c for c in df.columns if c != "label"]
X = df[feature_names].values
y = df["label"].values

# ── Split ─────────────────────────────────────────────────────────────────────
X_train, X_tmp, y_train, y_tmp = train_test_split(
    X, y, test_size=0.30, stratify=y, random_state=42
)
X_val, X_test, y_val, y_test = train_test_split(
    X_tmp, y_tmp, test_size=0.50, stratify=y_tmp, random_state=42
)

print("\n=== Split shapes ===")
for name, Xs, ys in [("train", X_train, y_train),
                      ("val",   X_val,   y_val),
                      ("test",  X_test,  y_test)]:
    unique, counts = np.unique(ys, return_counts=True)
    dist = dict(zip(unique.tolist(), counts.tolist()))
    print(f"  {name}: X={Xs.shape}  y={ys.shape}  classes={dist}")

# ── Scale (fit on train only) ─────────────────────────────────────────────────
scaler = StandardScaler()
X_train_s = scaler.fit_transform(X_train)
X_val_s   = scaler.transform(X_val)
X_test_s  = scaler.transform(X_test)

# ── Save scaler & schema ──────────────────────────────────────────────────────
joblib.dump(scaler, os.path.join(MODELS_DIR, "scaler.joblib"))
print("\nScaler saved → ml/models/scaler.joblib")

with open(os.path.join(MODELS_DIR, "feature_schema.json"), "w") as f:
    json.dump(feature_names, f, indent=2)
print("Feature schema saved → ml/models/feature_schema.json")

label_mapping = {"0": "genuine", "1": "uncertain", "2": "anomaly"}
with open(os.path.join(MODELS_DIR, "label_mapping.json"), "w") as f:
    json.dump(label_mapping, f, indent=2)
print("Label mapping saved → ml/models/label_mapping.json")

# ── Save numpy arrays ─────────────────────────────────────────────────────────
np.save(os.path.join(DATA_DIR, "X_train.npy"), X_train_s)
np.save(os.path.join(DATA_DIR, "y_train.npy"), y_train)
np.save(os.path.join(DATA_DIR, "X_val.npy"),   X_val_s)
np.save(os.path.join(DATA_DIR, "y_val.npy"),   y_val)
np.save(os.path.join(DATA_DIR, "X_test.npy"),  X_test_s)
np.save(os.path.join(DATA_DIR, "y_test.npy"),  y_test)
print("NumPy arrays saved → ml/data/X_*.npy, y_*.npy")

# ── Save scaler params for Android ───────────────────────────────────────────
scaler_params = {
    "feature_names": feature_names,
    "means": scaler.mean_.tolist(),
    "stds":  scaler.scale_.tolist(),
}
with open(os.path.join(MODELS_DIR, "scaler_params.json"), "w") as f:
    json.dump(scaler_params, f, indent=2)
print("Scaler params saved → ml/models/scaler_params.json")
print("\nPreprocessing complete.")
