import unittest, json, runpy, hashlib
from pathlib import Path
import numpy as np
import joblib
from sklearn.model_selection import train_test_split

class ContractV2Tests(unittest.TestCase):
    def test_legacy_artifacts_preserved(self):
        self.assertEqual(hashlib.sha256(Path("ml/models/behaviour_model.tflite").read_bytes()).hexdigest(),
            "bb4f4e8eafcdc9b87c4ea91d704810c1686e7539bf2fa11ce1cebfb28e1b05ce")

    def test_assets_scaler_and_golden(self):
        base=Path("ml/models/v2")
        for name in ("behaviour_model.tflite","scaler_params.json","feature_contract.json","golden_predictions.json"):
            self.assertEqual((base/name).read_bytes(),(Path("android/app/src/main/assets/v2")/name).read_bytes())
        scaler=joblib.load(base/"scaler.joblib");model=joblib.load(base/"behaviour_model.joblib")
        params=json.loads((base/"scaler_params.json").read_text())
        np.testing.assert_allclose(scaler.mean_,params["means"])
        np.testing.assert_allclose(scaler.scale_,params["stds"])
        contract=json.loads((base/"feature_contract.json").read_text())
        self.assertEqual(len(contract["feature_names"]),48)
        self.assertEqual(contract["version"],params["contract_version"])
        for row in json.loads((base/"golden_predictions.json").read_text())["rows"]:
            np.testing.assert_allclose(model.predict_proba(scaler.transform([row["raw"]]))[0],row["probabilities"],atol=1e-10)

    def test_exported_weights_match_on_held_out_rows(self):
        training=runpy.run_path("ml/scripts/generate_sample_data.py",run_name="definitions")
        contract=json.loads(Path("ml/models/v2/feature_contract.json").read_text())
        X=np.vstack([training["generate_class"](c,1000) for c in range(3)]);y=np.repeat(range(3),1000)
        X[:,contract["reserved_zero_indices"]]=0
        X[:,19]=X[:,0];X[:,27]=60000/np.maximum(X[:,23],1)
        X[:,46]=np.degrees(np.arctan2(np.hypot(X[:,32],X[:,34]),np.abs(X[:,36])));X[:,25]=X[:,23]
        _,tmp=train_test_split(np.arange(3000),test_size=.3,stratify=y,random_state=42)
        _,test=train_test_split(tmp,test_size=.5,stratify=y[tmp],random_state=42)
        m=joblib.load("ml/models/v2/behaviour_model.joblib");scaler=joblib.load("ml/models/v2/scaler.joblib")
        z=scaler.transform(X[test]);expected=m.predict_proba(z)
        converter=runpy.run_path("ml/scripts/convert_tflite.py",run_name="definitions")
        schema=converter["tflite"];model=schema.Model.GetRootAs(Path("ml/models/v2/behaviour_model.tflite").read_bytes(),0);graph=model.Subgraphs(0)
        a=z.astype(np.float32)
        for i in range(3):
            op=graph.Operators(i);w=graph.Tensors(op.Inputs(1));b=graph.Tensors(op.Inputs(2))
            weight=np.frombuffer(model.Buffers(w.Buffer()).DataAsNumpy().tobytes(),dtype=np.float32).reshape(w.ShapeAsNumpy())
            bias=np.frombuffer(model.Buffers(b.Buffer()).DataAsNumpy().tobytes(),dtype=np.float32)
            a=a@weight.T+bias
            if i<2:a=np.maximum(a,0)
        a=np.exp(a-a.max(1,keepdims=True));a/=a.sum(1,keepdims=True)
        np.testing.assert_allclose(a,expected,atol=1e-5)
        self.assertTrue(np.array_equal(a.argmax(1),expected.argmax(1)))

if __name__=="__main__":unittest.main()
