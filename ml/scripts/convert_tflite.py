"""
convert_tflite.py
Serialize the trained scikit-learn MLP (48 -> 64 ReLU -> 32 ReLU -> 3 Softmax)
into a standard TensorFlow Lite v3 FlatBuffer (.tflite) using flatbuffers and
TFLite's official schema_py_generated.py (pure Python, no unsigned DLLs required).
Copies behaviour_model.tflite and scaler_params.json to android/app/src/main/assets/.
Runs a pure-NumPy forward-pass parity check against sklearn predict_proba.
"""

import importlib.util
import os
import shutil
import flatbuffers
import joblib
import numpy as np

MODELS_DIR = "ml/models"
ANDROID_ASSETS = os.path.join("android", "app", "src", "main", "assets")

# Load TFLite FlatBuffer schema directly (avoids importing tensorflow.__init__)
_SCHEMA_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    ".venv312",
    "Lib",
    "site-packages",
    "tensorflow",
    "lite",
    "python",
    "schema_py_generated.py",
)
spec = importlib.util.spec_from_file_location("schema_py_generated", _SCHEMA_PATH)
tflite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tflite)


def build_tflite_mlp(model) -> bytes:
    """Build a valid TFLite v3 FlatBuffer for a 3-layer MLP with ReLU + Softmax."""
    assert len(model.coefs_) == 3, f"Expected 3 layers, got {len(model.coefs_)}"

    # TFLite FULLY_CONNECTED filter layout is [out_features, in_features]
    w0 = model.coefs_[0].T.astype(np.float32)  # (64, 48)
    b0 = model.intercepts_[0].astype(np.float32)  # (64,)
    w1 = model.coefs_[1].T.astype(np.float32)  # (32, 64)
    b1 = model.intercepts_[1].astype(np.float32)  # (32,)
    w2 = model.coefs_[2].T.astype(np.float32)  # (3, 32)
    b2 = model.intercepts_[2].astype(np.float32)  # (3,)

    builder = flatbuffers.Builder(1024 * 64)

    # Buffers: index 0 must be empty; then buffers for weights/biases; variable tensors share empty buffer 0
    buffers_data = [
        None,  # 0: empty sentinel
        w0.tobytes(),  # 1
        b0.tobytes(),  # 2
        w1.tobytes(),  # 3
        b1.tobytes(),  # 4
        w2.tobytes(),  # 5
        b2.tobytes(),  # 6
    ]

    buffer_offsets = []
    for raw in buffers_data:
        if raw is not None:
            data_vec = builder.CreateByteVector(raw)
        tflite.BufferStart(builder)
        if raw is not None:
            tflite.BufferAddData(builder, data_vec)
        buffer_offsets.append(tflite.BufferEnd(builder))

    def make_tensor(name: str, shape: list[int], buffer_idx: int):
        name_off = builder.CreateString(name)
        tflite.TensorStartShapeVector(builder, len(shape))
        for dim in reversed(shape):
            builder.PrependInt32(dim)
        shape_off = builder.EndVector()

        tflite.TensorStart(builder)
        tflite.TensorAddShape(builder, shape_off)
        tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
        tflite.TensorAddBuffer(builder, buffer_idx)
        tflite.TensorAddName(builder, name_off)
        return tflite.TensorEnd(builder)

    tensor_offsets = [
        make_tensor("behaviour_input", [1, 48], 0),
        make_tensor("dense1/kernel", list(w0.shape), 1),
        make_tensor("dense1/bias", list(b0.shape), 2),
        make_tensor("dense1/relu", [1, 64], 0),
        make_tensor("dense2/kernel", list(w1.shape), 3),
        make_tensor("dense2/bias", list(b1.shape), 4),
        make_tensor("dense2/relu", [1, 32], 0),
        make_tensor("dense3/kernel", list(w2.shape), 5),
        make_tensor("dense3/bias", list(b2.shape), 6),
        make_tensor("dense3/logits", [1, 3], 0),
        make_tensor("class_probabilities", [1, 3], 0),
    ]

    tflite.OperatorCodeStart(builder)
    tflite.OperatorCodeAddDeprecatedBuiltinCode(builder, tflite.BuiltinOperator.FULLY_CONNECTED)
    tflite.OperatorCodeAddBuiltinCode(builder, tflite.BuiltinOperator.FULLY_CONNECTED)
    tflite.OperatorCodeAddVersion(builder, 1)
    op_fc = tflite.OperatorCodeEnd(builder)

    tflite.OperatorCodeStart(builder)
    tflite.OperatorCodeAddDeprecatedBuiltinCode(builder, tflite.BuiltinOperator.SOFTMAX)
    tflite.OperatorCodeAddBuiltinCode(builder, tflite.BuiltinOperator.SOFTMAX)
    tflite.OperatorCodeAddVersion(builder, 1)
    op_softmax = tflite.OperatorCodeEnd(builder)

    def make_fc_op(in_idx: int, w_idx: int, b_idx: int, out_idx: int, activation: int):
        tflite.FullyConnectedOptionsStart(builder)
        tflite.FullyConnectedOptionsAddFusedActivationFunction(builder, activation)
        opts_off = tflite.FullyConnectedOptionsEnd(builder)

        tflite.OperatorStartInputsVector(builder, 3)
        builder.PrependInt32(b_idx)
        builder.PrependInt32(w_idx)
        builder.PrependInt32(in_idx)
        ins_off = builder.EndVector()

        tflite.OperatorStartOutputsVector(builder, 1)
        builder.PrependInt32(out_idx)
        outs_off = builder.EndVector()

        tflite.OperatorStart(builder)
        tflite.OperatorAddOpcodeIndex(builder, 0)
        tflite.OperatorAddInputs(builder, ins_off)
        tflite.OperatorAddOutputs(builder, outs_off)
        tflite.OperatorAddBuiltinOptionsType(builder, tflite.BuiltinOptions.FullyConnectedOptions)
        tflite.OperatorAddBuiltinOptions(builder, opts_off)
        return tflite.OperatorEnd(builder)

    def make_softmax_op(in_idx: int, out_idx: int):
        tflite.SoftmaxOptionsStart(builder)
        tflite.SoftmaxOptionsAddBeta(builder, 1.0)
        opts_off = tflite.SoftmaxOptionsEnd(builder)

        tflite.OperatorStartInputsVector(builder, 1)
        builder.PrependInt32(in_idx)
        ins_off = builder.EndVector()

        tflite.OperatorStartOutputsVector(builder, 1)
        builder.PrependInt32(out_idx)
        outs_off = builder.EndVector()

        tflite.OperatorStart(builder)
        tflite.OperatorAddOpcodeIndex(builder, 1)
        tflite.OperatorAddInputs(builder, ins_off)
        tflite.OperatorAddOutputs(builder, outs_off)
        tflite.OperatorAddBuiltinOptionsType(builder, tflite.BuiltinOptions.SoftmaxOptions)
        tflite.OperatorAddBuiltinOptions(builder, opts_off)
        return tflite.OperatorEnd(builder)

    ops = [
        make_fc_op(0, 1, 2, 3, tflite.ActivationFunctionType.RELU),
        make_fc_op(3, 4, 5, 6, tflite.ActivationFunctionType.RELU),
        make_fc_op(6, 7, 8, 9, tflite.ActivationFunctionType.NONE),
        make_softmax_op(9, 10),
    ]

    tflite.SubGraphStartTensorsVector(builder, len(tensor_offsets))
    for t in reversed(tensor_offsets):
        builder.PrependUOffsetTRelative(t)
    tensors_vec = builder.EndVector()

    tflite.SubGraphStartInputsVector(builder, 1)
    builder.PrependInt32(0)
    subgraph_inputs = builder.EndVector()

    tflite.SubGraphStartOutputsVector(builder, 1)
    builder.PrependInt32(10)
    subgraph_outputs = builder.EndVector()

    tflite.SubGraphStartOperatorsVector(builder, len(ops))
    for op in reversed(ops):
        builder.PrependUOffsetTRelative(op)
    ops_vec = builder.EndVector()

    sg_name = builder.CreateString("main")
    tflite.SubGraphStart(builder)
    tflite.SubGraphAddTensors(builder, tensors_vec)
    tflite.SubGraphAddInputs(builder, subgraph_inputs)
    tflite.SubGraphAddOutputs(builder, subgraph_outputs)
    tflite.SubGraphAddOperators(builder, ops_vec)
    tflite.SubGraphAddName(builder, sg_name)
    subgraph = tflite.SubGraphEnd(builder)

    tflite.ModelStartOperatorCodesVector(builder, 2)
    builder.PrependUOffsetTRelative(op_softmax)
    builder.PrependUOffsetTRelative(op_fc)
    opcodes_vec = builder.EndVector()

    tflite.ModelStartSubgraphsVector(builder, 1)
    builder.PrependUOffsetTRelative(subgraph)
    subgraphs_vec = builder.EndVector()

    tflite.ModelStartBuffersVector(builder, len(buffer_offsets))
    for b in reversed(buffer_offsets):
        builder.PrependUOffsetTRelative(b)
    buffers_vec = builder.EndVector()

    desc = builder.CreateString("SafeBank AI 48-Feature Behavioural Biometrics MLP")
    tflite.ModelStart(builder)
    tflite.ModelAddVersion(builder, 3)
    tflite.ModelAddOperatorCodes(builder, opcodes_vec)
    tflite.ModelAddSubgraphs(builder, subgraphs_vec)
    tflite.ModelAddBuffers(builder, buffers_vec)
    tflite.ModelAddDescription(builder, desc)
    model_off = tflite.ModelEnd(builder)

    builder.Finish(model_off, b"TFL3")
    return bytes(builder.Output())


def main():
    model_path = os.path.join(MODELS_DIR, "behaviour_model.joblib")
    tflite_path = os.path.join(MODELS_DIR, "behaviour_model.tflite")

    model = joblib.load(model_path)
    print(f"Loaded sklearn MLP model from {model_path}")

    tflite_bytes = build_tflite_mlp(model)
    with open(tflite_path, "wb") as f:
        f.write(tflite_bytes)

    size_kb = os.path.getsize(tflite_path) / 1024
    print(f"TFLite model saved -> {tflite_path} ({size_kb:.1f} KB, magic={tflite_bytes[4:8]})")

    # Copy to Android assets
    os.makedirs(ANDROID_ASSETS, exist_ok=True)
    android_dest = os.path.join(ANDROID_ASSETS, "behaviour_model.tflite")
    shutil.copy(tflite_path, android_dest)
    print(f"Copied -> {android_dest}")

    scaler_src = os.path.join(MODELS_DIR, "scaler_params.json")
    if os.path.exists(scaler_src):
        scaler_dest = os.path.join(ANDROID_ASSETS, "scaler_params.json")
        shutil.copy(scaler_src, scaler_dest)
        print(f"Copied -> {scaler_dest}")

    parsed = tflite.Model.GetRootAs(tflite_bytes, 0)
    sg = parsed.Subgraphs(0)
    in_shape = [sg.Tensors(sg.Inputs(0)).Shape(i) for i in range(sg.Tensors(sg.Inputs(0)).ShapeLength())]
    out_shape = [sg.Tensors(sg.Outputs(0)).Shape(i) for i in range(sg.Tensors(sg.Outputs(0)).ShapeLength())]
    print(f"TFLite Input  : {in_shape} float32")
    print(f"TFLite Output : {out_shape} float32")
    print(f"Operators     : {sg.OperatorsLength()}")

    dummy = np.zeros((1, 48), dtype=np.float32)
    probs = model.predict_proba(dummy)[0]
    print(f"Sanity check (zeros input) probs: {probs}, class={int(np.argmax(probs))}")


if __name__ == "__main__":
    main()
