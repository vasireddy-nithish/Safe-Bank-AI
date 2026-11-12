"""
train.py
MLP: Input(48) -> Dense(64, relu) -> Dense(32, relu) -> Dense(3, softmax)
Trained with scikit-learn MLPClassifier (early stopping, seed=42) to remain
compatible with Windows Smart App Control (which blocks unsigned TF native DLLs).
"""

import os
import joblib
import numpy as np
from sklearn.metrics import accuracy_score, log_loss
from sklearn.neural_network import MLPClassifier

DATA_DIR = "ml/data"
MODELS_DIR = "ml/models"
os.makedirs(MODELS_DIR, exist_ok=True)

X_train = np.load(os.path.join(DATA_DIR, "X_train.npy"))
y_train = np.load(os.path.join(DATA_DIR, "y_train.npy"))
X_val = np.load(os.path.join(DATA_DIR, "X_val.npy"))
y_val = np.load(os.path.join(DATA_DIR, "y_val.npy"))

print(f"Train: {X_train.shape}, Val: {X_val.shape}")

model = MLPClassifier(
    hidden_layer_sizes=(64, 32),
    activation="relu",
    solver="adam",
    alpha=1e-4,
    batch_size=64,
    learning_rate_init=1e-3,
    max_iter=200,
    early_stopping=True,
    validation_fraction=0.15,
    n_iter_no_change=10,
    random_state=42,
    verbose=False,
)

model.fit(X_train, y_train)

val_probs = model.predict_proba(X_val)
val_pred = np.argmax(val_probs, axis=1)
val_loss = log_loss(y_val, val_probs)
val_acc = accuracy_score(y_val, val_pred)

print("\n=== Training complete ===")
print(f"Epochs run        : {model.n_iter_}")
print(f"Validation loss   : {val_loss:.4f}")
print(f"Validation acc    : {val_acc:.4f}")

model_path = os.path.join(MODELS_DIR, "behaviour_model.joblib")
joblib.dump(model, model_path)
print(f"Model saved -> {model_path}")
