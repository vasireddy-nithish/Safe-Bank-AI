import hashlib
import json
import runpy
import unittest
from pathlib import Path
import joblib
import numpy as np

class RandomForestV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base=Path("ml/models/random_forest_v2")
        cls.report=json.loads((cls.base/"evaluation_report.json").read_text())
        cls.X,cls.y,cls.train,cls.validation,cls.test=runpy.run_path("ml/scripts/train_v2.py",run_name="definitions")["load_dataset"]()

    def test_exact_partitions_are_disjoint_and_complete(self):
        saved=np.load(self.base/"partitions.npz")
        for name,ids in [("train",self.train),("validation",self.validation),("test",self.test)]:
            np.testing.assert_array_equal(saved[name],ids)
            self.assertEqual(hashlib.sha256(ids.astype("<i8").tobytes()).hexdigest(),self.report["partition_sha256"][name])
        all_ids=np.concatenate([self.train,self.validation,self.test])
        self.assertEqual(len(np.unique(all_ids)),3000)
        np.testing.assert_array_equal(np.sort(all_ids),np.arange(3000))
        self.assertEqual((self.base/"feature_contract.json").read_bytes(),Path("ml/models/v2/feature_contract.json").read_bytes())

    def test_saved_model_reproduces_all_reported_metrics(self):
        model=joblib.load(self.base/"random_forest.joblib")
        evaluate=runpy.run_path("ml/scripts/train_random_forest_v2.py",run_name="definitions")["evaluate"]
        for name,ids in [("train",self.train),("validation",self.validation),("test",self.test)]:
            actual=evaluate(model,self.X[ids],self.y[ids])
            for key in ["accuracy","macro_precision","macro_recall","macro_f1","roc_auc_ovr","confusion_matrix","precision","recall","f1"]:
                self.assertEqual(actual[key],self.report[name][key])
        self.assertEqual(self.report["validation"]["macro_f1"],max(t["validation"]["macro_f1"] for t in self.report["trials"]))
        self.assertFalse(self.report["deployed_to_android"])

    def test_serialization_probability_round_trip(self):
        import io
        original=joblib.load(self.base/"random_forest.joblib")
        buffer=io.BytesIO();joblib.dump(original,buffer);buffer.seek(0)
        restored=joblib.load(buffer)
        before=original.predict_proba(self.X[self.test]);after=restored.predict_proba(self.X[self.test])
        np.testing.assert_allclose(before,after,rtol=0,atol=1e-14)
        np.testing.assert_array_equal(before.argmax(1),after.argmax(1))

    def test_mlp_artifacts_unchanged(self):
        hashes=json.loads(Path("ml/reports/mlp_v2_preservation_hashes.json").read_text())
        for name,expected in hashes.items():
            self.assertEqual(hashlib.sha256(Path(name).read_bytes()).hexdigest(),expected,name)

if __name__=="__main__":unittest.main()
