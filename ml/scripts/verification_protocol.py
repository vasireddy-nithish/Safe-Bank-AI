"""
SafeBank AI - Behavioural Verification Evaluation Protocol
Implements enrollment template matching, cosine similarity computation,
ROC analysis, and Equal Error Rate (EER) calculation for unseen test users.
"""

import numpy as np
from sklearn.metrics import roc_curve, roc_auc_score, confusion_matrix, precision_recall_fscore_support

def compute_subject_embeddings(encoder, X_data, batch_size=64):
    """
    Computes L2-normalized 64-d embeddings using the trained encoder backbone.
    """
    embeddings = encoder.predict(X_data, batch_size=batch_size, verbose=0)
    # Ensure L2 normalization
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    norms = np.where(norms < 1e-12, 1.0, norms)
    return embeddings / norms

def evaluate_unseen_verification(encoder, X_data, y_subjects, split_name="val"):
    """
    Evaluates unseen-subject verification using chronological enrollment and query split.
    
    Protocol:
    For each subject:
      - First 50% samples = Enrollment Template (mean vector, L2 normalized)
      - Second 50% samples = Genuine Verification Queries
      - Impostor Queries = Second 50% samples from ALL other subjects
    """
    unique_subs = sorted(list(set(y_subjects)))
    num_subs = len(unique_subs)
    
    embeddings = compute_subject_embeddings(encoder, X_data)
    
    enrollment_templates = {}
    query_samples = {}
    
    # Partition each subject's samples chronologically
    for sub in unique_subs:
        indices = np.where(y_subjects == sub)[0]
        half = len(indices) // 2
        enroll_idx = indices[:half]
        query_idx = indices[half:]
        
        # Mean embedding template, L2 normalized
        template = np.mean(embeddings[enroll_idx], axis=0)
        norm = np.linalg.norm(template)
        template = template / (norm if norm > 1e-12 else 1.0)
        
        enrollment_templates[sub] = template
        query_samples[sub] = embeddings[query_idx]

    genuine_scores = []
    impostor_scores = []
    
    # Calculate genuine scores: query_i against template_i
    for sub in unique_subs:
        template = enrollment_templates[sub]
        queries = query_samples[sub]
        scores = np.dot(queries, template) # cosine similarities in [-1, 1]
        genuine_scores.extend(scores.tolist())
        
    # Calculate impostor scores: query_j against template_i (j != i)
    # Balanced sampling across subjects to prevent skew
    for i, target_sub in enumerate(unique_subs):
        template = enrollment_templates[target_sub]
        for other_sub in unique_subs:
            if other_sub == target_sub:
                continue
            # Sample queries from other_sub
            other_queries = query_samples[other_sub]
            # Take a balanced subset of impostor queries
            step = max(1, len(other_queries) // max(1, (len(query_samples[target_sub]) // (num_subs - 1))))
            sampled_other = other_queries[::step][:len(query_samples[target_sub]) // (num_subs - 1) + 1]
            scores = np.dot(sampled_other, template)
            impostor_scores.extend(scores.tolist())

    genuine_scores = np.array(genuine_scores, dtype=np.float32)
    impostor_scores = np.array(impostor_scores, dtype=np.float32)
    
    # Construct binary targets: 1 = genuine, 0 = impostor
    y_true = np.concatenate([np.ones_like(genuine_scores), np.zeros_like(impostor_scores)])
    scores = np.concatenate([genuine_scores, impostor_scores])
    
    # Calculate ROC curve & AUC
    fpr, tpr, thresholds = roc_curve(y_true, scores)
    roc_auc = float(roc_auc_score(y_true, scores))
    
    # Calculate EER (where FAR == FRR)
    # FAR = fpr; FRR = 1 - tpr
    fnr = 1.0 - tpr
    eer_idx = np.nanargmin(np.abs(fpr - fnr))
    eer = float((fpr[eer_idx] + fnr[eer_idx]) / 2.0)
    eer_threshold = float(thresholds[eer_idx])
    
    # Classification at EER threshold
    y_pred = (scores >= eer_threshold).astype(int)
    cm = confusion_matrix(y_true, y_pred).tolist()
    
    # FAR & FRR at selected threshold
    tn, fp, fn, tp = confusion_matrix(y_true, y_pred).ravel()
    far = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0
    frr = float(fn / (fn + tp)) if (fn + tp) > 0 else 0.0
    acc = float((tp + tn) / len(y_true))
    
    prec, rec, f1, _ = precision_recall_fscore_support(y_true, y_pred, average="binary", zero_division=0)
    
    results = {
        "split": split_name,
        "subjects_count": num_subs,
        "genuine_queries_count": len(genuine_scores),
        "impostor_queries_count": len(impostor_scores),
        "total_comparisons": len(scores),
        "eer": eer,
        "eer_threshold": eer_threshold,
        "roc_auc": roc_auc,
        "far": far,
        "frr": frr,
        "accuracy": acc,
        "precision": float(prec),
        "recall": float(rec),
        "f1": float(f1),
        "confusion_matrix": cm,
        "genuine_distribution": {
            "mean": float(np.mean(genuine_scores)),
            "std": float(np.std(genuine_scores)),
            "min": float(np.min(genuine_scores)),
            "max": float(np.max(genuine_scores))
        },
        "impostor_distribution": {
            "mean": float(np.mean(impostor_scores)),
            "std": float(np.std(impostor_scores)),
            "min": float(np.min(impostor_scores)),
            "max": float(np.max(impostor_scores))
        }
    }
    
    return results, eer_threshold

def evaluate_with_frozen_threshold(encoder, X_data, y_subjects, frozen_threshold, split_name="test"):
    """
    Evaluates unseen test subjects strictly using the frozen threshold selected on validation.
    """
    results, _ = evaluate_unseen_verification(encoder, X_data, y_subjects, split_name=split_name)
    
    # Re-evaluate decision metrics at the FROZEN threshold
    embeddings = compute_subject_embeddings(encoder, X_data)
    unique_subs = sorted(list(set(y_subjects)))
    num_subs = len(unique_subs)
    
    enrollment_templates = {}
    query_samples = {}
    for sub in unique_subs:
        indices = np.where(y_subjects == sub)[0]
        half = len(indices) // 2
        template = np.mean(embeddings[indices[:half]], axis=0)
        norm = np.linalg.norm(template)
        enrollment_templates[sub] = template / (norm if norm > 1e-12 else 1.0)
        query_samples[sub] = embeddings[indices[half:]]

    genuine_scores = []
    impostor_scores = []
    for sub in unique_subs:
        genuine_scores.extend(np.dot(query_samples[sub], enrollment_templates[sub]).tolist())
    for target_sub in unique_subs:
        template = enrollment_templates[target_sub]
        for other_sub in unique_subs:
            if other_sub == target_sub:
                continue
            other_queries = query_samples[other_sub]
            step = max(1, len(other_queries) // max(1, (len(query_samples[target_sub]) // (num_subs - 1))))
            sampled_other = other_queries[::step][:len(query_samples[target_sub]) // (num_subs - 1) + 1]
            impostor_scores.extend(np.dot(sampled_other, template).tolist())

    genuine_scores = np.array(genuine_scores, dtype=np.float32)
    impostor_scores = np.array(impostor_scores, dtype=np.float32)
    y_true = np.concatenate([np.ones_like(genuine_scores), np.zeros_like(impostor_scores)])
    scores = np.concatenate([genuine_scores, impostor_scores])
    
    # Decisions using the FROZEN threshold
    y_pred = (scores >= frozen_threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, y_pred).ravel()
    
    results["frozen_threshold"] = frozen_threshold
    results["far_at_frozen_threshold"] = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0
    results["frr_at_frozen_threshold"] = float(fn / (fn + tp)) if (fn + tp) > 0 else 0.0
    results["accuracy_at_frozen_threshold"] = float((tp + tn) / len(y_true))
    prec, rec, f1, _ = precision_recall_fscore_support(y_true, y_pred, average="binary", zero_division=0)
    results["precision_at_frozen_threshold"] = float(prec)
    results["recall_at_frozen_threshold"] = float(rec)
    results["f1_at_frozen_threshold"] = float(f1)
    results["confusion_matrix_at_frozen_threshold"] = [[int(tn), int(fp)], [int(fn), int(tp)]]
    
    return results
