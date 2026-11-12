"""
train.py
MLP: Input(48) → Dense(64,relu)+BN+Drop(0.2) → Dense(32,relu)+BN+Drop(0.1) → Dense(3,softmax)
Seeds: numpy=42, tensorflow=42
"""

import os, json
import numpy as np
import tensorflow as tf
from sklearn.utils.class_weight import compute_class_weight

# ── Reproducibility ───────────────────────────────────────────────────────────
np.random.seed(42)
tf.random.set_seed(42)

DATA_DIR   = "ml/data"
MODELS_DIR = "ml/models"
os.makedirs(MODELS_DIR, exist_ok=True)

# ── Load data ─────────────────────────────────────────────────────────────────
X_train = np.load(os.path.join(DATA_DIR, "X_train.npy"))
y_train = np.load(os.path.join(DATA_DIR, "y_train.npy"))
X_val   = np.load(os.path.join(DATA_DIR, "X_val.npy"))
y_val   = np.load(os.path.join(DATA_DIR, "y_val.npy"))

print(f"Train: {X_train.shape}, Val: {X_val.shape}")

# ── Class weights ─────────────────────────────────────────────────────────────
classes   = np.unique(y_train)
weights   = compute_class_weight("balanced", classes=classes, y=y_train)
class_w   = dict(zip(classes.tolist(), weights.tolist()))
print(f"Class weights: {class_w}")

# ── Model ─────────────────────────────────────────────────────────────────────
inputs = tf.keras.Input(shape=(48,), name="behaviour_input")
x = tf.keras.layers.Dense(64, activation="relu")(inputs)
x = tf.keras.layers.BatchNormalization()(x)
x = tf.keras.layers.Dropout(0.2)(x)
x = tf.keras.layers.Dense(32, activation="relu")(x)
x = tf.keras.layers.BatchNormalization()(x)
x = tf.keras.layers.Dropout(0.1)(x)
outputs = tf.keras.layers.Dense(3, activation="softmax", name="class_probabilities")(x)

model = tf.keras.Model(inputs, outputs, name="SafeBankBehaviourMLP")
model.summary()

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)

# ── Callbacks ─────────────────────────────────────────────────────────────────
early_stop = tf.keras.callbacks.EarlyStopping(
    monitor="val_loss",
    patience=10,
    restore_best_weights=True,
    verbose=1,
)

# ── Train ─────────────────────────────────────────────────────────────────────
history = model.fit(
    X_train, y_train,
    validation_data=(X_val, y_val),
    epochs=100,
    batch_size=64,
    class_weight=class_w,
    callbacks=[early_stop],
    verbose=2,
)

# ── Results ───────────────────────────────────────────────────────────────────
val_loss = min(history.history["val_loss"])
val_acc  = max(history.history["val_accuracy"])
print(f"\n=== Training complete ===")
print(f"Best val_loss     : {val_loss:.4f}")
print(f"Best val_accuracy : {val_acc:.4f}")

# ── Save ──────────────────────────────────────────────────────────────────────
model_path = os.path.join(MODELS_DIR, "behaviour_model.keras")
model.save(model_path)
print(f"Model saved → {model_path}")
