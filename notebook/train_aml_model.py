# ============================================================================
# Shield AML Classifier Training — Run in Google Colab
# ============================================================================
# HOW TO USE:
#   1. Open https://colab.research.google.com
#   2. Upload this file (or paste into a new notebook)
#   3. Upload your kaggle.json (Kaggle → Account → Create New API Token)
#   4. Run all cells top to bottom
#   5. Download the output: aml_classifier.onnx + feature_schema.json
#   6. Put both in: src/main/resources/models/
# ============================================================================

# ── Cell 1: Install dependencies ────────────────────────────────────────────
!pip install kagglehub skl2onnx onnxruntime pandas scikit-learn numpy

# ── Cell 2: Download the IBM AML dataset ──────────────────────────────────────
import kagglehub
import os

path = kagglehub.dataset_download("ealtman2019/ibm-transactions-for-anti-money-laundering-aml")
print("Dataset downloaded to:", path)
print("Files:", os.listdir(path))

# Find the HI-Small file (higher-intensity = more laundering examples)
csv_file = None
for f in os.listdir(path):
    if "HI-Small" in f and f.endswith(".csv"):
        csv_file = os.path.join(path, f)
        break
if csv_file is None:
    # Fallback: take any CSV
    for f in os.listdir(path):
        if f.endswith(".csv"):
            csv_file = os.path.join(path, f)
            break
print("Using:", csv_file)

# ── Cell 3: Load and inspect ─────────────────────────────────────────────────
import pandas as pd
import numpy as np

# The IBM dataset has no header row — columns are positional:
# 0: Time (int timestep), 1: From Bank, 2: From Account, 3: To Bank,
# 4: To Account, 5: Amount Received, 6: Receiving Currency,
# 7: Amount Paid, 8: Payment Currency, 9: Payment Format, 10: Is Laundering
columns = [
    "time_step", "from_bank", "from_account", "to_bank", "to_account",
    "amount_received", "receiving_currency", "amount_paid",
    "payment_currency", "payment_format", "is_laundering"
]

df = pd.read_csv(csv_file, names=columns, dtype=str)
df["amount_paid"] = pd.to_numeric(df["amount_paid"], errors="coerce")
df["amount_received"] = pd.to_numeric(df["amount_received"], errors="coerce")
df["time_step"] = pd.to_numeric(df["time_step"], errors="coerce")
df["is_laundering"] = pd.to_numeric(df["is_laundering"], errors="coerce").astype(int)

print(f"Total transactions: {len(df):,}")
print(f"Laundering cases: {df['is_laundering'].sum():,}")
print(f"Laundering rate: {df['is_laundering'].mean()*100:.3f}%")
print(f"Payment formats: {df['payment_format'].unique()}")
print(df.head())

# ── Cell 4: Feature engineering (transaction → account-level features) ──────
# We engineer per-ACCOUNT features because Shield's AmlAgent assesses
# the RISK of a beneficiary account, not a single transaction.

print("Engineering account-level features...")

# Group by from_account (sender) — what does this account look like?
sender_stats = df.groupby("from_account").agg(
    txn_count=("amount_paid", "count"),
    total_sent=("amount_paid", "sum"),
    avg_sent=("amount_paid", "mean"),
    std_sent=("amount_paid", "std"),
    min_sent=("amount_paid", "min"),
    max_sent=("amount_paid", "max"),
    unique_beneficiaries=("to_account", "nunique"),
    active_days=("time_step", "nunique"),
    payment_formats_used=("payment_format", "nunique"),
).reset_index()
sender_stats["avg_daily_txns"] = sender_stats["txn_count"] / sender_stats["active_days"].clip(lower=1)
sender_stats["fan_out_ratio"] = sender_stats["unique_beneficiaries"] / sender_stats["txn_count"].clip(lower=1)
sender_stats["std_sent"] = sender_stats["std_sent"].fillna(0)

# Group by to_account (receiver) — what does receiving look like?
receiver_stats = df.groupby("to_account").agg(
    recv_count=("amount_received", "count"),
    total_received=("amount_received", "sum"),
    avg_received=("amount_received", "mean"),
    std_received=("amount_received", "std"),
    unique_senders=("from_account", "nunique"),
).reset_index()
receiver_stats["fan_in_ratio"] = receiver_stats["unique_senders"] / receiver_stats["recv_count"].clip(lower=1)
receiver_stats["std_received"] = receiver_stats["std_received"].fillna(0)

# Per-transaction features (the actual prediction target is per transaction)
# Merge sender + receiver stats onto each transaction
feat_df = df.merge(sender_stats, on="from_account", how="left", suffixes=("", "_s"))
feat_df = feat_df.merge(receiver_stats, on="to_account", how="left", suffixes=("", "_r"))

# Structuring signal: is this transaction below a typical reporting threshold?
SAR_THRESHOLD = 10000  # common SAR threshold
feat_df["below_threshold"] = (feat_df["amount_paid"] < SAR_THRESHOLD).astype(int)
feat_df["amount_to_avg_ratio"] = feat_df["amount_paid"] / feat_df["avg_sent"].clip(lower=0.01)

# Rapid transit signal: does the receiver also send a lot? (pass-through)
receiver_sender_overlap = set(df["from_account"]) & set(df["to_account"])
feat_df["receiver_also_sends"] = feat_df["to_account"].isin(receiver_sender_overlap).astype(int)

# Currency mismatch
feat_df["currency_mismatch"] = (feat_df["payment_currency"] != feat_df["receiving_currency"]).astype(int)

# Final feature columns (must match Java AmlFeatures exactly)
FEATURE_COLS = [
    "amount_paid",
    "below_threshold",
    "amount_to_avg_ratio",
    "txn_count", "total_sent", "avg_sent", "std_sent", "min_sent", "max_sent",
    "unique_beneficiaries", "active_days", "avg_daily_txns", "fan_out_ratio",
    "payment_formats_used",
    "recv_count", "total_received", "avg_received", "std_received",
    "unique_senders", "fan_in_ratio",
    "receiver_also_sends",
    "currency_mismatch",
]

# Handle any NaN
for c in FEATURE_COLS:
    feat_df[c] = pd.to_numeric(feat_df[c], errors="coerce").fillna(0)

X = feat_df[FEATURE_COLS].values.astype(np.float32)
y = feat_df["is_laundering"].values.astype(int)

print(f"Feature matrix shape: {X.shape}")
print(f"Features: {FEATURE_COLS}")
print(f"Positive class: {y.sum()} ({y.mean()*100:.2f}%)")

# ── Cell 5: Train / test split (stratified, no leakage) ───────────────────────
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score

# Split by TIME to avoid leakage (train on earlier, test on later)
time_median = feat_df["time_step"].median()
train_mask = feat_df["time_step"] <= time_median
test_mask = feat_df["time_step"] > time_median

X_train, y_train = X[train_mask], y[train_mask]
X_test, y_test = X[test_mask], y[test_mask]

print(f"Train: {len(X_train):,} ({y_train.sum()} laundering)")
print(f"Test:  {len(X_test):,} ({y_test.sum()} laundering)")

# ── Cell 6: Train the model ──────────────────────────────────────────────────
# RandomForest: robust, handles imbalance, no GPU needed, exports cleanly to ONNX
# Class weight balanced because laundering is <1% of data
print("Training RandomForest...")
model = RandomForestClassifier(
    n_estimators=100,
    max_depth=12,
    min_samples_leaf=10,
    class_weight="balanced",
    n_jobs=-1,
    random_state=42,
)
model.fit(X_train, y_train)

# ── Cell 7: Evaluate ──────────────────────────────────────────────────────────
y_pred = model.predict(X_test)
y_proba = model.predict_proba(X_test)[:, 1]

print("\n=== Classification Report ===")
print(classification_report(y_test, y_pred, target_names=["Clean", "Laundering"]))
print("\n=== Confusion Matrix ===")
cm = confusion_matrix(y_test, y_pred)
print(cm)
print(f"\nTrue Positives: {cm[1,1]}")
print(f"False Positives: {cm[0,1]}")
print(f"False Negatives: {cm[1,0]}")
try:
    auc = roc_auc_score(y_test, y_proba)
    print(f"ROC-AUC: {auc:.4f}")
except:
    pass

# Feature importance
importances = model.feature_importances_
for name, imp in sorted(zip(FEATURE_COLS, importances), key=lambda x: -x[1]):
    print(f"  {name}: {imp:.4f}")

# ── Cell 8: Export to ONNX ───────────────────────────────────────────────────
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
import json

print("Exporting to ONNX...")
initial_type = [("features", FloatTensorType([None, len(FEATURE_COLS)]))]
onnx_model = convert_sklearn(model, initial_types=initial_type, target_opset=15)

onnx_path = "aml_classifier.onnx"
with open(onnx_path, "wb") as f:
    f.write(onnx_model.SerializeToString())
print(f"ONNX model saved: {onnx_path} ({os.path.getsize(onnx_path)/1024:.1f} KB)")

# Verify the ONNX model runs
import onnxruntime as ort
sess = ort.InferenceSession(onnx_path)
input_name = sess.get_inputs()[0].name
sample = X_test[:5].astype(np.float32)
pred = sess.run(None, {input_name: sample})
print("ONNX verification OK. Predictions:", pred[0].flatten())

# ── Cell 9: Save feature schema (Java needs this to know the feature order) ─
schema = {
    "features": FEATURE_COLS,
    "feature_count": len(FEATURE_COLS),
    "model_type": "RandomForestClassifier",
    "sar_threshold": SAR_THRESHOLD,
    "training_rows": int(len(X_train)),
    "laundering_rate_train": float(y_train.mean()),
    "metrics": {
        "roc_auc": float(roc_auc_score(y_test, y_proba)) if len(set(y_test)) > 1 else None,
        "true_positives": int(cm[1, 1]),
        "false_positives": int(cm[0, 1]),
        "false_negatives": int(cm[1, 0]),
    },
}
schema_path = "feature_schema.json"
with open(schema_path, "w") as f:
    json.dump(schema, f, indent=2)
print(f"Feature schema saved: {schema_path}")
print(json.dumps(schema, indent=2))

# ── Cell 10: Download both files to your machine ─────────────────────────────
from google.colab import files
files.download(onnx_path)
files.download(schema_path)

# ============================================================================
# After downloading, place both files in:
#   mer9ad-backend-master/src/main/resources/models/
#
# Then build and run the backend. The AmlAgent will auto-load the ONNX model.
# ============================================================================
