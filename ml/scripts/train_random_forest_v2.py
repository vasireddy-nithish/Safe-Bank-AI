"""Bounded validation-only tuning on the exact synthetic MLP v2 partitions.
Run from project root. Never exports to Android or overwrites MLP artifacts.
"""
import hashlib
import json
import runpy
import time
from pathlib import Path
import joblib
import numpy as np
import sklearn
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, precision_recall_fscore_support, roc_auc_score, confusion_matrix


def evaluate(model, X, y):
    start = time.perf_counter()
    probabilities = model.predict_proba(X)
    seconds = time.perf_counter() - start
    predicted = model.classes_[probabilities.argmax(axis=1)]
    precision, recall, f1, support = precision_recall_fscore_support(y, predicted, labels=[0,1,2], zero_division=0)
    return dict(rows=len(y), accuracy=float(accuracy_score(y,predicted)),
        macro_precision=float(precision.mean()), macro_recall=float(recall.mean()), macro_f1=float(f1.mean()),
        precision=precision.tolist(), recall=recall.tolist(), f1=f1.tolist(), support=support.tolist(),
        roc_auc_ovr=float(roc_auc_score(y,probabilities,multi_class="ovr")),
        confusion_matrix=confusion_matrix(y,predicted,labels=[0,1,2]).tolist(),
        batch_seconds_per_row=seconds/len(y))


def main():
    out=Path("ml/models/random_forest_v2")
    if (out/"random_forest.joblib").exists():
        raise SystemExit("Artifact already exists; choose a new version instead of overwriting a validated model.")
    v2=runpy.run_path("ml/scripts/train_v2.py",run_name="definitions")
    X,y,train,validation,test=v2["load_dataset"]()
    scaler=joblib.load("ml/models/v2/scaler.joblib")
    # The saved scaler is independent evidence that training rows match the original MLP run.
    np.testing.assert_allclose(X[train].mean(axis=0),scaler.mean_,rtol=1e-12,atol=1e-12)
    np.testing.assert_allclose(X[train].var(axis=0),scaler.var_,rtol=1e-12,atol=1e-12)
    mlp=joblib.load("ml/models/v2/behaviour_model.joblib")
    mlp_report=json.loads(Path("ml/models/v2/evaluation_report.json").read_text())
    for name,ids in [("train",train),("validation",validation),("test",test)]:
        actual=evaluate(mlp,scaler.transform(X[ids]),y[ids])
        assert actual["confusion_matrix"]==mlp_report[name]["confusion_matrix"], "MLP partition reproduction failed"
    # Eight small configurations; test rows are never used for selection.
    trials=[];best=None;best_score=-1;started=time.perf_counter()
    for depth in [6,None]:
        for leaf in [3,1]:
            for features in ["sqrt",0.7]:
                params=dict(n_estimators=150,max_depth=depth,min_samples_leaf=leaf,max_features=features,random_state=42,n_jobs=2)
                candidate=RandomForestClassifier(**params)
                start=time.perf_counter();candidate.fit(X[train],y[train]);fit_seconds=time.perf_counter()-start
                val=evaluate(candidate,X[validation],y[validation])
                trials.append(dict(parameters=params,validation=val,fit_seconds=fit_seconds))
                if val["macro_f1"]>best_score:
                    best=candidate;best_score=val["macro_f1"];best_fit_seconds=fit_seconds
    report=dict(contract_version=v2["VERSION"],source="Same 3000 seeded synthetic MLP v2 rows; no real participants",
        real_user_data=False,rows=3000,features=48,classes={"0":"genuine","1":"uncertain","2":"anomaly"},
        preprocessing="raw v2 features; Random Forest requires no scaling",
        sklearn_version=sklearn.__version__,selection="validation macro F1; ties retain earlier simpler candidate; no train+validation refit",
        tuning_seconds=time.perf_counter()-started,selected_fit_seconds=best_fit_seconds,
        parameters=best.get_params(),trials=trials,
        dataset_sha256=hashlib.sha256(X.astype("<f8").tobytes()+y.astype("<i8").tobytes()).hexdigest(),
        partition_sha256={name:hashlib.sha256(ids.astype("<i8").tobytes()).hexdigest() for name,ids in [("train",train),("validation",validation),("test",test)]},
        deployed_to_android=False,mlp_artifacts_replaced=False)
    for name,ids in [("train",train),("validation",validation),("test",test)]:report[name]=evaluate(best,X[ids],y[ids])
    report["train_validation_accuracy_gap"]=report["train"]["accuracy"]-report["validation"]["accuracy"]
    report["train_test_accuracy_gap"]=report["train"]["accuracy"]-report["test"]["accuracy"]
    report["mlp_comparison"]={name:{"accuracy":mlp_report[name]["accuracy"],"macro_f1":mlp_report[name]["macro_f1"],"roc_auc_ovr":mlp_report[name]["roc_auc_ovr"]} for name in ["train","validation","test"]}
    report["limitations"]=["Synthetic class separation is not evidence of real biometric performance", "No subject-disjoint or chronological real-world evaluation", "No CNN/LSTM sequences or real-user uncertainty labels", "Single seed/split; no statistical significance claim"]
    out.mkdir(parents=True,exist_ok=True)
    joblib.dump(best,out/"random_forest.joblib")
    np.savez_compressed(out/"partitions.npz",train=train,validation=validation,test=test)
    (out/"feature_contract.json").write_bytes(Path("ml/models/v2/feature_contract.json").read_bytes())
    (out/"evaluation_report.json").write_text(json.dumps(report,indent=2))
    # Reload round-trip proves the saved artifact supplies the evaluated probabilities.
    np.testing.assert_allclose(joblib.load(out/"random_forest.joblib").predict_proba(X[test]),best.predict_proba(X[test]),rtol=0,atol=1e-14)
    print(json.dumps({k:report[k] for k in ["parameters","tuning_seconds","train","validation","test","train_validation_accuracy_gap","train_test_accuracy_gap"]},indent=2))

if __name__=="__main__":main()
