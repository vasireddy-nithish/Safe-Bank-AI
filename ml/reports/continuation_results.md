# SafeBank AI continuation results - 2026-11-13

MLP v2 integration was checked and repaired first, native Android inference passed, then Random Forest was trained and evaluated, followed by sequence dataset research. No MLP retraining occurred. Existing v1/v2 model artifacts and Android v2 assets were preserved. CNN/LSTM were not trained and no dataset archives were downloaded.

| Component | Training / evaluation | Execution and integration |
|---|---|---|
| MLP v2 | Existing synthetic model verified: 3,000 rows, 48 features (29 active, 19 reserved); train 98.8095%, validation 97.5556%, test 99.3333% accuracy; test macro F1 0.9933327 and ROC-AUC 1.0 | Android v2 assets/scaler/contract match saved artifacts. Actual native TFLite test passed on API 35 x86_64 emulator-5554; 3 reference vectors, probability tolerance 1e-5, fallback explicitly fails the test. |
| Random Forest v2 | Eight configurations evaluated on validation only; same 2,100/450/450 row partitions as MLP. Selected 150 trees, depth 6, min leaf 3, sqrt features, seed 42. Tuning 7.2651 seconds; selected fit 0.5585 seconds. Train/validation/test accuracy, macro precision/recall/F1 and ROC-AUC all 1.0 | Saved sklearn artifact reloaded and evaluated successfully. Not deployed in Android or backend scoring; MLP remains active. |
| CNN | No training; public raw sequence candidates investigated | No model artifact or runtime integration added. See sequence_dataset_investigation.md. |
| LSTM | No training; genuine chronological datasets investigated | No fabricated reshaping of the 48-column table. No model artifact or runtime integration added. |
| Risk engine | Rule-based policy using ML probabilities, baseline deviation and server-held restrictions | Existing Android-to-backend risk submission and dashboard integration retained; 139 backend tests passed, including missing/stale/failed/context-mismatched assessments, idempotency and duress cases. |

## Metrics and artifacts

Both models use seeded synthetic distributions, not real users. Random Forest has zero train-validation and train-test accuracy gaps on this split; this does not establish real-world generalization or absence of overfitting. The easy synthetic class distributions explain the perfect result. MLP test accuracy is 0.6667 percentage points lower; no statistical superiority claim is made.

Class order is genuine, uncertain, anomaly. MLP test precision: [0.9803922, 1, 1], recall: [1, 0.98, 1], F1: [0.9900990, 0.9898990, 1]. MLP test confusion matrix: [[150,0,0],[3,147,0],[0,0,150]]. RF test precision/recall/F1: [1,1,1]; confusion matrix: [[150,0,0],[0,150,0],[0,0,150]]. All classes have 150 held-out test rows.

MLP artifacts: `ml/models/v2/behaviour_model.joblib`, `behaviour_model.tflite`, `scaler.joblib`, `scaler_params.json`, `feature_contract.json`, `evaluation_report.json`, `golden_predictions.json`. Android counterparts are in `android/app/src/main/assets/v2/`.

RF artifacts: `ml/models/random_forest_v2/random_forest.joblib` (519,537 bytes), `evaluation_report.json`, `feature_contract.json`, `partitions.npz`. The report records all trial metrics, split hashes and dataset hash. `train_v2.load_dataset()` reconstructs the original seeded rows without training. `train_random_forest_v2.main()` validates the saved MLP scaler mean/variance and all three MLP confusion matrices before RF fitting. RF uses raw features, without MLP scaling, on the same sample partitions.

Flatbuffer weights evaluated against sklearn over 450 held-out rows retain the previously reported maximum error 1.4708742e-7. This is distinct from native verification on 3 golden vectors. The original MLP evaluation JSON intentionally retains its training-time `native_android_inference_verified=false`; later executed native evidence is in `native_inference_verification.json` and `native_inference_emulator.txt`. `mlp_v2_preservation_hashes.json` plus ML tests verify no MLP v2 artifact changes during RF development.

## Repairs and integration evidence

- `FeatureExtractor.extract`: corrected tilt to angle of mean acceleration, matching v2 training (previous Android code averaged individual angles). The complete 48-slot fixture and specific counterexample test pass. Full slot meanings and units are in `feature_contract_v2.md`.
- `BehaviourClassifier.loadScalerParams`: validates contract version, 48-element dimensions, finite means and positive finite scales; uses structured JSON parsing instead of silently retaining default parameters. `loadModel` uses v2 TFLite and checks tensor shapes. `classify` marks failures unavailable; backend-connected transfers reject fallback results.
- `MainScaffold`: attaches the previously unused capture Modifier, scopes persisted consent by authenticated user, requires an active session and restores an Enable/Pause control. Existing `continuousCollection` captures foreground touch/motion on home/transfers/history/security and clears buffers on disposal. `TransfersScreen` forwards only timing/backspace metadata for the amount field. No password/PIN/text contents are supplied to the collector.
- `ContinuousCollectionTest.consentEnablesTouchCaptureAndRevocationClearsEvents`: confirms no touch capture without consent, capture when enabled and buffer clearing when revoked. Collection is continuous while eligible screens are foreground; inference remains on demand for assessments/transfers, not a new background polling service.
- `risk_policy.permits_transfer`: malformed persisted data-point counts now fail closed rather than raising a type error. Five new cases cover null, string, list, object and boolean counts.
- `backend/tests/test_banking_api.py`: whitespace-only cleanup during final review; existing banking assertions remain intact.

## Risk decision logic and trust boundary

The Android classifier computes floor(35 * uncertain_probability + 100 * anomaly_probability). `BehaviourSessionManager.classifyWithBaseline` adds a bounded 0-35 drift penalty: average capped normalized feature difference times 25, clamped to 35. Backend `risk.submit_risk` validates bounded probabilities and their sum (current accepted interval 0.9-1.1), recomputes the weighted score and takes the larger of that and the submitted score, capped to 0-100. Low is 0-29 (allow), medium 30-69 (step_up), high 70-100 (restrict). Missing live status, wrong contract or fewer than five points forces at least medium risk. Server duress restrictions take priority.

A sensitive non-duress transfer additionally requires the latest allowing receipt to match user, session, exact normalized source/destination/amount/currency/description, be no older than 120 seconds and not be consumed by another idempotency key. Failed/invalid new assessments leave a pending/failed denial record. Existing idempotent replay and restricted honeypot behavior are preserved. A client biometric-success boolean cannot authorize a transfer: cryptographic step-up is not configured and the endpoint returns 501.

Device integrity remains explicitly unverified and does not lower risk. Probability/live-model/data-count assertions are still client telemetry, not remote attestation. This is a demonstration policy, not proof of trustworthy execution on a compromised phone. Baseline deviation is also computed client-side. Cryptographic challenge binding/attestation or trusted server inference remains future work.

## Executed checks

Commands ran in the existing project, using the bundled Python interpreter because legacy venv launchers reference a missing base installation. No global Python/Java installation or Windows security setting was changed.

- From `backend`: bundled Python `-B -m pytest -q --tb=short`, with PYTHONPATH=`backend/.venv_local/Lib/site-packages`, DEBUG=false, ENABLE_DEV_SESSION_ENDPOINT=false and PYTEST_DEBUG_TEMPROOT=`backend/.pytest_cache`: **139 passed in 13.16 seconds** (earlier 134-pass run also executed).
- From project root: bundled Python `-B -m unittest discover -s ml/tests -v`, PYTHONPATH=`.venv312/Lib/site-packages`: **7 passed in 1.349 seconds**. Includes held-out parity, artifact preservation, identical/disjoint partitions, saved RF evaluation and serialization parity.
- From `android`: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`: successful after resolving an uncached Kotlin test dependency online. Subsequent offline build also succeeded. Final `gradlew.bat :app:testDebugUnitTest --offline --no-daemon`: **BUILD SUCCESSFUL; 84 tests, 0 failures, 0 errors, 0 skipped**.
- `adb -s emulator-5554 install -r` for debug app and test APK: both successful. `adb -s emulator-5554 shell am instrument -w -e class com.safebank.ai.ml.NativeInferenceTest com.safebank.ai.debug.test/androidx.test.runner.AndroidJUnitRunner`: **OK (1 test), 0.192 seconds**. Native library logged successful loading of the 22,424-byte model.
- `python -B ml/scripts/train_random_forest_v2.py`: all training/evaluation and artifact saves completed; initial final byte-exact probability assertion failed on a 2.22e-16 floating-point summation difference with parallel trees. Changed that check to absolute tolerance 1e-14. The saved model was not retrained; executed serialization and metric-reproduction tests subsequently passed.
- Earlier offline build was blocked by missing cached coroutines-test 1.9.0. Earlier Gradle connected-test attempt was blocked by uncached UTP; direct ADB instrumentation provides the executed native result. Locked/stalled emulator attempts are not counted as passes.

Bundled interpreter: `C:/Users/hp/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`.

## Remaining limits and demonstration preparation

Physical-device testing is **unverified**. No real-human accuracy, FAR/FRR/EER, battery impact or complete physical-device banking journey is claimed. Sensor-only/missing-modality windows differ from synthetic training distributions and may produce poor real-world calibration. Source synthetic features are not all physically coupled. The current baseline migration copied legacy samples into the v2 table; legacy baseline provenance/semantic compatibility needs review before claiming all migrated profiles are calibrated v2 profiles. Existing SQLite data was not reset or modified in this continuation.

The backend database was inspected read-only: revision c842bd510002 was already applied; legacy and v2 enrolment tables both exist. This continuation did not run migrations. Existing working state was retained, including the OkHttp connection-pool configuration in BankingApiClient.kt.

Before the hackathon, install the rebuilt debug APK on the intended demonstration device, restart FastAPI to load the risk-policy update, sign in and explicitly enable collection, then rehearse real sensor collection and allow/step-up/restrict outcomes with test accounts. Step-up currently blocks rather than providing a cryptographic completion flow. Confirm real-data performance and licensing before claiming production biometric protection or starting CNN/LSTM training. RF remains an offline comparison artifact.

## Files changed or created in this continuation

Paths below are absolute. Many other files were already dirty from prior enrolment/Phase 1 work; they were preserved rather than attributed as new changes here.

- `C:/Users/hp/Downloads/zup/android/app/src/main/java/com/safebank/ai/ml/FeatureExtractor.kt`
- `C:/Users/hp/Downloads/zup/android/app/src/main/java/com/safebank/ai/ml/BehaviourClassifier.kt`
- `C:/Users/hp/Downloads/zup/android/app/src/main/java/com/safebank/ai/ui/scaffold/MainScaffold.kt`
- `C:/Users/hp/Downloads/zup/android/app/src/test/java/com/safebank/ai/BehaviourContractV2Test.kt`
- `C:/Users/hp/Downloads/zup/android/app/src/test/java/com/safebank/ai/ContinuousCollectionTest.kt`
- `C:/Users/hp/Downloads/zup/backend/app/services/risk_policy.py`
- `C:/Users/hp/Downloads/zup/backend/tests/test_risk_v2.py`
- `C:/Users/hp/Downloads/zup/backend/tests/test_banking_api.py`
- `C:/Users/hp/Downloads/zup/ml/scripts/train_v2.py`
- `C:/Users/hp/Downloads/zup/ml/scripts/train_random_forest_v2.py`
- `C:/Users/hp/Downloads/zup/ml/tests/test_random_forest_v2.py`
- `C:/Users/hp/Downloads/zup/ml/models/random_forest_v2/evaluation_report.json`
- `C:/Users/hp/Downloads/zup/ml/models/random_forest_v2/feature_contract.json`
- `C:/Users/hp/Downloads/zup/ml/models/random_forest_v2/partitions.npz`
- `C:/Users/hp/Downloads/zup/ml/models/random_forest_v2/random_forest.joblib`
- `C:/Users/hp/Downloads/zup/ml/reports/feature_contract_v2.md`
- `C:/Users/hp/Downloads/zup/ml/reports/mlp_v2_preservation_hashes.json`
- `C:/Users/hp/Downloads/zup/ml/reports/native_inference_emulator.txt`
- `C:/Users/hp/Downloads/zup/ml/reports/native_inference_verification.json`
- `C:/Users/hp/Downloads/zup/ml/reports/sequence_dataset_investigation.md`
- `C:/Users/hp/Downloads/zup/ml/reports/continuation_results.md`

Local test infrastructure: `.git/info/exclude` gained `/.local-emulator/`; a separate SafeBank_ML_Verification AVD was created using the already installed API 35 image, then stopped. Its ignored files remain in `.local-emulator/`. The user emulator at port 5554 was left running.
