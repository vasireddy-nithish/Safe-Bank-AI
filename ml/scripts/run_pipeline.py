"""
run_pipeline.py
Convenience script: runs all ML pipeline steps in order.
Execute from the project root:
    .venv312\\Scripts\\python.exe ml/scripts/run_pipeline.py
"""

import subprocess, sys, os

PYTHON = sys.executable
ROOT   = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

STEPS = [
    ("generate_sample_data", "ml/scripts/generate_sample_data.py"),
    ("preprocess",           "ml/scripts/preprocess.py"),
    ("train",                "ml/scripts/train.py"),
    ("evaluate",             "ml/scripts/evaluate.py"),
    ("convert_tflite",       "ml/scripts/convert_tflite.py"),
]

for name, script in STEPS:
    print(f"\n{'='*60}")
    print(f"STEP: {name}")
    print('='*60)
    result = subprocess.run([PYTHON, os.path.join(ROOT, script)], cwd=ROOT)
    if result.returncode != 0:
        print(f"ERROR: {name} failed with code {result.returncode}")
        sys.exit(result.returncode)

print("\n" + "="*60)
print("Pipeline complete — all steps passed.")
print("="*60)
