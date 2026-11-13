"""Train a separately versioned synthetic prototype; never overwrites v1 artifacts."""
import json, time, runpy, shutil
from pathlib import Path
import numpy as np
import joblib
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.neural_network import MLPClassifier
from sklearn.metrics import accuracy_score, precision_recall_fscore_support, f1_score, confusion_matrix, roc_auc_score

VERSION = "safebank.behaviour.v2"
# Reserved slots are explicitly unavailable/proxies without a reliable matching measurement.
RESERVED = [6,7,8,9,10,11,12,13,14,17,18,20,21,22,28,29,30,31,47]

def load_dataset():
    """Reconstruct the exact original v2 rows and seeded partition indices."""
    source=runpy.run_path("ml/scripts/generate_sample_data.py",run_name="definitions")
    X=np.vstack([source["generate_class"](c,1000) for c in range(3)])
    y=np.repeat(np.arange(3),1000)
    X[:,RESERVED]=0
    X[:,19]=X[:,0] # measured gesture duration; no invented key dwell
    X[:,27]=60000/np.maximum(X[:,23],1) # cadence from inter-key intervals
    X[:,46]=np.degrees(np.arctan2(np.hypot(X[:,32],X[:,34]),np.abs(X[:,36])))
    X[:,25]=X[:,23] # explicitly defined interval proxy
    ids=np.arange(len(X)); train,tmp=train_test_split(ids,test_size=.3,stratify=y,random_state=42)
    val,test=train_test_split(tmp,test_size=.5,stratify=y[tmp],random_state=42)
    return X,y,train,val,test

def main():
    out=Path("ml/models/v2"); out.mkdir(parents=True,exist_ok=True)
    X,y,train,val,test=load_dataset()
    source=runpy.run_path("ml/scripts/generate_sample_data.py",run_name="definitions")
    scaler=StandardScaler().fit(X[train]); Z=scaler.transform(X)
    model=MLPClassifier(hidden_layer_sizes=(64,32),random_state=42,early_stopping=True,
        validation_fraction=.15,max_iter=200,batch_size=64)
    start=time.perf_counter();model.fit(Z[train],y[train]);duration=time.perf_counter()-start
    report={"contract_version":VERSION,"source":"seeded synthetic distributions, corrected v2 definitions", "real_user_data":False,
            "rows":3000,"features":48,"reserved_zero_indices":RESERVED,"training_seconds":duration,"iterations":model.n_iter_}
    for name,indices in [("train",train),("validation",val),("test",test)]:
        start=time.perf_counter();probs=model.predict_proba(Z[indices]);latency=(time.perf_counter()-start)/len(indices)
        pred=probs.argmax(1);precision,recall,f1,support=precision_recall_fscore_support(y[indices],pred,zero_division=0)
        report[name]={"rows":len(indices),"accuracy":accuracy_score(y[indices],pred),"macro_f1":f1_score(y[indices],pred,average="macro"),
            "precision":precision.tolist(),"recall":recall.tolist(),"f1":f1.tolist(),"support":support.tolist(),
            "confusion_matrix":confusion_matrix(y[indices],pred).tolist(),"roc_auc_ovr":roc_auc_score(y[indices],probs,multi_class="ovr"),"seconds_per_row":latency}
    converter=runpy.run_path("ml/scripts/convert_tflite.py",run_name="definitions")
    data=converter["build_tflite_mlp"](model);schema=converter["tflite"];parsed=schema.Model.GetRootAs(data,0);graph=parsed.Subgraphs(0)
    a=Z[test].astype(np.float32)
    for i in range(3):
        op=graph.Operators(i);w=graph.Tensors(op.Inputs(1));b=graph.Tensors(op.Inputs(2))
        weights=np.frombuffer(parsed.Buffers(w.Buffer()).DataAsNumpy().tobytes(),dtype=np.float32).reshape(w.ShapeAsNumpy())
        bias=np.frombuffer(parsed.Buffers(b.Buffer()).DataAsNumpy().tobytes(),dtype=np.float32)
        a=a@weights.T+bias
        if i<2:a=np.maximum(a,0)
    a=np.exp(a-a.max(1,keepdims=True));a/=a.sum(1,keepdims=True)
    difference=float(np.abs(a-model.predict_proba(Z[test])).max());assert difference<1e-5
    report["export_max_probability_error"]=difference
    report["native_android_inference_verified"]=False
    joblib.dump(model,out/"behaviour_model.joblib");joblib.dump(scaler,out/"scaler.joblib")
    (out/"behaviour_model.tflite").write_bytes(data)
    names=source["FEATURE_NAMES"].copy()
    for i in RESERVED:names[i]="reserved_zero_"+str(i)
    names[19]="touch_contact_duration_mean_ms";names[27]="typing_interval_cpm";names[46]="motion_tilt_from_vertical_degrees"
    contract={"version":VERSION,"feature_count":48,"feature_names":names,"reserved_zero_indices":RESERVED,
        "missing_modality":"zero with standard-deviation floor 0.001 where specified by extractor", "raw_telemetry_uploaded":False}
    (out/"feature_contract.json").write_text(json.dumps(contract,indent=2))
    (out/"scaler_params.json").write_text(json.dumps({"contract_version":VERSION,"means":scaler.mean_.tolist(),"stds":scaler.scale_.tolist()},indent=2))
    (out/"evaluation_report.json").write_text(json.dumps(report,indent=2))
    assets=Path("android/app/src/main/assets/v2");assets.mkdir(parents=True,exist_ok=True)
    for name in ["behaviour_model.tflite","scaler_params.json","feature_contract.json"]:shutil.copy2(out/name,assets/name)
    print(json.dumps(report,indent=2))
if __name__=="__main__":main()
