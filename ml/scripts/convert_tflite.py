"""
convert_tflite.py
Convert behaviour_model.keras → TFLite (float32, no quantization).
Copies the .tflite file to android/app/src/main/assets/.
Runs a quick inference sanity check.
"""

import os, shutil
import numpy as np
import tensorflow as tf

MODELS_DIR  = "ml/models"
ANDROID_ASSETS = os.path.join("android", "app", "src", "main", "assets")

keras_path  = os.path.join(MODELS_DIR, "behaviour_model.keras")
tflite_path = os.path.join(MODELS_DIR, "behaviour_model.tflite")

# ── Load Keras model ──────────────────────────────────────────────────────────
model = tf.keras.models.load_model(keras_path)
print(f"Loaded Keras model from {keras_path}")

# ── Convert to TFLite ─────────────────────────────────────────────────────────
converter = tf.lite.TFLiteConverter.from_keras_model(model)
# No quantization — maximum compatibility with Android TFLite runtime
tflite_model = converter.convert()

with open(tflite_path, "wb") as f:
    f.write(tflite_model)
size_kb = os.path.getsize(tflite_path) / 1024
print(f"TFLite model saved → {tflite_path}  ({size_kb:.1f} KB)")

# ── Copy to Android assets ────────────────────────────────────────────────────
os.makedirs(ANDROID_ASSETS, exist_ok=True)
android_dest = os.path.join(ANDROID_ASSETS, "behaviour_model.tflite")
shutil.copy(tflite_path, android_dest)
print(f"Copied → {android_dest}")

# ── Inspect input/output tensors ──────────────────────────────────────────────
interpreter = tf.lite.Interpreter(model_path=tflite_path)
interpreter.allocate_tensors()

input_details  = interpreter.get_input_details()
output_details = interpreter.get_output_details()

print("\n--- TFLite Tensor Shapes ---")
print(f"Input  : {input_details[0]['shape']}  dtype={input_details[0]['dtype']}")
print(f"Output : {output_details[0]['shape']}  dtype={output_details[0]['dtype']}")

# ── Quick inference test ──────────────────────────────────────────────────────
dummy = np.zeros((1, 48), dtype=np.float32)
interpreter.set_tensor(input_details[0]["index"], dummy)
interpreter.invoke()
out = interpreter.get_tensor(output_details[0]["index"])

print(f"\n--- Inference sanity check (all-zeros input) ---")
print(f"Output probabilities: {out[0]}")
print(f"Predicted class: {int(np.argmax(out[0]))}  "
      f"(0=genuine, 1=uncertain, 2=anomaly)")
print("\nTFLite conversion and verification complete.")
