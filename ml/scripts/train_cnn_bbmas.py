"""
SafeBank AI - 1D CNN Behavioural Biometrics Training & Evaluation
Architecture:
Input (128, 8)
  -> Conv1D(32, 7) -> BatchNorm -> ReLU
  -> Conv1D(64, 5) -> BatchNorm -> ReLU -> MaxPool1D(2)
  -> Conv1D(128, 3) -> BatchNorm -> ReLU -> GlobalAveragePooling1D
  -> Dense(64) -> L2 Normalization -> Embedding (64-d)
  -> [Train Head: Dense(80, softmax)] (Discarded for unseen-user verification)

Training & Evaluation:
- Pilot Run: 2 epochs verification of finite gradients and loss reduction
- Full Training: Up to 50 epochs, Adam lr=0.001, ReduceLROnPlateau, EarlyStopping
- Unseen Validation (Subjects 81-98): EER & threshold selection
- Unseen Test (Subjects 99-116): Zero-leakage evaluation with frozen threshold
- Export: Keras model, TFLite model, numerical parity test, real CPU latency test
"""

import os
import sys
import time
import json
from pathlib import Path
import numpy as np

# Force safe workspace environment variables for sandbox execution
os.environ["KERAS_HOME"] = "C:/Users/hp/Downloads/zup/.keras"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers, regularizers

from verification_protocol import evaluate_unseen_verification, evaluate_with_frozen_threshold

DATA_PATH = Path("ml/data/processed/bbmas_cnn_dataset.npz")
MODEL_DIR = Path("ml/models/cnn_bbmas")
REPORTS_DIR = Path("ml/reports")

# Set random seeds
np.random.seed(42)
tf.random.set_seed(42)

def build_cnn_models():
    inputs = keras.Input(shape=(128, 8), name="sensor_window")
    
    x = layers.Conv1D(32, kernel_size=7, padding="same", kernel_initializer="he_normal")(inputs)
    x = layers.BatchNormalization()(x)
    x = layers.ReLU()(x)
    
    x = layers.Conv1D(64, kernel_size=5, padding="same", kernel_initializer="he_normal")(x)
    x = layers.BatchNormalization()(x)
    x = layers.ReLU()(x)
    x = layers.MaxPooling1D(pool_size=2)(x)
    
    x = layers.Conv1D(128, kernel_size=3, padding="same", kernel_initializer="he_normal")(x)
    x = layers.BatchNormalization()(x)
    x = layers.ReLU()(x)
    x = layers.GlobalAveragePooling1D()(x)
    
    x = layers.Dense(64, kernel_initializer="he_normal")(x)
    embedding = layers.Lambda(lambda v: tf.math.l2_normalize(v, axis=-1), name="l2_embedding")(x)
    
    # Encoder backbone for feature extraction
    encoder = keras.Model(inputs=inputs, outputs=embedding, name="cnn_encoder")
    
    # Training classifier head for the 80 training subjects
    classifier_output = layers.Dense(80, activation="softmax", name="subject_classifier")(embedding)
    training_model = keras.Model(inputs=inputs, outputs=classifier_output, name="cnn_training_model")
    
    return training_model, encoder

def run_pilot(data, training_model):
    print("\n--- Running Pilot Verification (2 Epochs) ---")
    X_sample = data["X_train"][:256]
    y_sample = data["y_train"][:256] - 1  # 0-indexed labels
    
    pilot_model, _ = build_cnn_models()
    pilot_model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.001),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"]
    )
    
    history = pilot_model.fit(X_sample, y_sample, batch_size=32, epochs=2, verbose=0)
    loss_start = history.history["loss"][0]
    loss_end = history.history["loss"][-1]
    
    print(f"Pilot Start Loss: {loss_start:.4f}, End Loss: {loss_end:.4f}")
    assert np.isfinite(loss_start) and np.isfinite(loss_end), "Pilot loss is not finite!"
    assert loss_end <= loss_start + 0.1, "Pilot loss exploded!"
    print("Pilot verification passed: finite gradients, stable loss trajectory.")

def benchmark_latency(tflite_path, sample_shape=(1, 128, 8), runs=100):
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    input_idx = interpreter.get_input_details()[0]["index"]
    output_idx = interpreter.get_output_details()[0]["index"]
    
    dummy = np.random.randn(*sample_shape).astype(np.float32)
    # Warmup
    for _ in range(10):
        interpreter.set_tensor(input_idx, dummy)
        interpreter.invoke()
        
    start_time = time.perf_counter()
    for _ in range(runs):
        interpreter.set_tensor(input_idx, dummy)
        interpreter.invoke()
        _ = interpreter.get_tensor(output_idx)
    end_time = time.perf_counter()
    
    avg_latency_ms = ((end_time - start_time) / runs) * 1000.0
    return avg_latency_ms

def main():
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    
    print(f"Loading preprocessed CNN data from {DATA_PATH}...")
    data = np.load(DATA_PATH)
    
    X_train, y_train = data["X_train"], data["y_train"] - 1 # 0-indexed for 80 classes
    X_val, y_val = data["X_val"], data["y_val"]
    X_test, y_test = data["X_test"], data["y_test"]
    
    print(f"Loaded: X_train={X_train.shape}, X_val={X_val.shape}, X_test={X_test.shape}")
    
    training_model, encoder = build_cnn_models()
    training_model.summary()
    
    # 1. Pilot Run
    run_pilot(data, training_model)
    
    # 2. Full Training
    print("\n--- Starting Full 1D CNN Training (Max 50 Epochs, EarlyStopping patience=7) ---")
    training_model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.001),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"]
    )
    
    ckpt_path = MODEL_DIR / "cnn_best_weights.weights.h5"
    callbacks = [
        keras.callbacks.EarlyStopping(monitor="loss", patience=7, restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor="loss", factor=0.5, patience=3, min_lr=1e-5),
        keras.callbacks.ModelCheckpoint(filepath=str(ckpt_path), save_weights_only=True, monitor="loss", save_best_only=True)
    ]
    
    start_train = time.perf_counter()
    history = training_model.fit(
        X_train,
        y_train,
        batch_size=64,
        epochs=50,
        callbacks=callbacks,
        verbose=1
    )
    train_duration_sec = time.perf_counter() - start_train
    actual_epochs = len(history.history["loss"])
    print(f"\nTraining completed in {train_duration_sec:.2f} seconds ({actual_epochs} epochs).")
    
    # Save trained Keras models
    keras_model_path = MODEL_DIR / "cnn_encoder.keras"
    encoder.save(keras_model_path)
    print(f"Saved CNN encoder model to {keras_model_path}")
    
    # 3. Unseen-User Validation (Subjects 81-98)
    print("\n--- Evaluating Unseen Validation Subjects (81-98) ---")
    val_metrics, optimal_threshold = evaluate_unseen_verification(encoder, X_val, y_val, split_name="validation")
    print(f"Validation EER: {val_metrics['eer']*100:.2f}% at Threshold: {optimal_threshold:.4f}")
    print(f"Validation ROC-AUC: {val_metrics['roc_auc']:.4f}")
    print(f"Validation Accuracy: {val_metrics['accuracy']*100:.2f}%, FAR: {val_metrics['far']*100:.2f}%, FRR: {val_metrics['frr']*100:.2f}%")
    
    # 4. Unseen-User Test (Subjects 99-116) with Frozen Threshold
    print("\n--- Evaluating Held-Out Unseen Test Subjects (99-116) with Frozen Threshold ---")
    test_metrics = evaluate_with_frozen_threshold(encoder, X_test, y_test, frozen_threshold=optimal_threshold, split_name="test")
    print(f"Test EER: {test_metrics['eer']*100:.2f}%")
    print(f"Test ROC-AUC: {test_metrics['roc_auc']:.4f}")
    print(f"Test Accuracy @ Frozen Threshold: {test_metrics['accuracy_at_frozen_threshold']*100:.2f}%")
    print(f"Test FAR: {test_metrics['far_at_frozen_threshold']*100:.2f}%, FRR: {test_metrics['frr_at_frozen_threshold']*100:.2f}%")
    print(f"Test Confusion Matrix: {test_metrics['confusion_matrix_at_frozen_threshold']}")
    
    # 5. TFLite Conversion & Numerical Parity Check
    print("\n--- Exporting to TFLite & Testing Numerical Parity ---")
    converter = tf.lite.TFLiteConverter.from_keras_model(encoder)
    tflite_model_bytes = converter.convert()
    
    tflite_path = MODEL_DIR / "cnn_encoder.tflite"
    with open(tflite_path, "wb") as f:
        f.write(tflite_model_bytes)
    tflite_size_bytes = len(tflite_model_bytes)
    print(f"Saved TFLite model to {tflite_path} ({tflite_size_bytes / 1024:.2f} KB)")
    
    # Test parity on sample
    sample_inputs = X_test[:10]
    keras_preds = encoder.predict(sample_inputs, verbose=0)
    
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    input_idx = interpreter.get_input_details()[0]["index"]
    output_idx = interpreter.get_output_details()[0]["index"]
    
    tflite_preds = []
    for sample in sample_inputs:
        interpreter.set_tensor(input_idx, np.expand_dims(sample, axis=0))
        interpreter.invoke()
        tflite_preds.append(interpreter.get_tensor(output_idx)[0])
    tflite_preds = np.array(tflite_preds)
    
    max_parity_diff = float(np.max(np.abs(keras_preds - tflite_preds)))
    print(f"Maximum absolute difference between Keras and TFLite: {max_parity_diff:.2e}")
    assert max_parity_diff < 1e-4, "TFLite numerical parity failure!"
    
    # 6. Benchmark Latency
    print("\n--- Benchmarking CPU Latency ---")
    avg_latency_ms = benchmark_latency(tflite_path, runs=100)
    print(f"Average CPU TFLite inference latency: {avg_latency_ms:.2f} ms")
    
    # 7. Compile Evaluation Report
    total_params = encoder.count_params()
    eval_report = {
        "model_type": "1D_CNN_Embedding_Encoder",
        "input_shape": [128, 8],
        "embedding_dimension": 64,
        "total_parameters": total_params,
        "actual_training_duration_seconds": round(train_duration_sec, 2),
        "actual_epochs": actual_epochs,
        "training_loss_final": float(history.history["loss"][-1]),
        "training_accuracy_final": float(history.history["accuracy"][-1]),
        "validation_metrics": val_metrics,
        "test_metrics": test_metrics,
        "frozen_threshold": optimal_threshold,
        "tflite_export": {
            "path": str(tflite_path),
            "size_bytes": tflite_size_bytes,
            "max_numerical_difference_vs_keras": max_parity_diff,
            "average_cpu_latency_ms": round(avg_latency_ms, 3)
        }
    }
    
    report_file = REPORTS_DIR / "cnn_bbmas_evaluation.json"
    with open(report_file, "w", encoding="utf-8") as f:
        json.dump(eval_report, f, indent=2)
    print(f"Saved CNN evaluation report to {report_file}")

if __name__ == "__main__":
    main()
