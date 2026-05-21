"""
ClearFlow Fraud Model Server
FastAPI server on port 8091 that serves a LightGBM model trained on PaySim data.
Falls back to a RandomForest on synthetic data if PaySim CSV is missing.
"""

import os
import sys
import pickle
import logging
import warnings
from pathlib import Path

import numpy as np
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

warnings.filterwarnings("ignore")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
PAYSIM_CSV = "/home/admin-/Desktop/EDI6/paysim dataset.csv"
MODEL_PKL = Path(__file__).parent / "fraud_model.pkl"
MODEL_VERSION = "lgbm-paysim-v1"
SAMPLE_SIZE = 500_000
FEATURE_NAMES = [
    "amountNormalized",
    "hourOfDay",
    "dayOfWeek",
    "isWeekend",
    "debtorCountryRisk",
    "creditorCountryRisk",
    "crossBorder",
    "highRiskCurrencyPair",
    "velocityLast1h",
    "velocityLast24h",
    "isNewCreditorPair",
]

# ---------------------------------------------------------------------------
# Pydantic schemas
# ---------------------------------------------------------------------------
class PredictRequest(BaseModel):
    features: List[float]
    metadata: Optional[Dict[str, Any]] = {}


class PredictResponse(BaseModel):
    score: float
    featureImportance: Dict[str, float]
    modelVersion: str


class HealthResponse(BaseModel):
    status: str
    model: str
    trainedOn: int


# ---------------------------------------------------------------------------
# Global model state
# ---------------------------------------------------------------------------
MODEL = None
TRAINED_ON = 0
FEATURE_IMPORTANCES: Dict[str, float] = {}


# ---------------------------------------------------------------------------
# Feature engineering helpers
# ---------------------------------------------------------------------------
def engineer_features(df):
    """Map PaySim columns to ClearFlow's 11-feature vector."""
    import numpy as _np

    step = df["step"].values.astype(float)
    amount = df["amount"].values.astype(float)
    tx_type = df["type"].values
    old_bal_dest = df["oldbalanceDest"].values.astype(float)
    new_bal_dest = df["newbalanceDest"].values.astype(float)

    f0 = _np.log10(amount + 1) / 9.0                              # amountNormalized
    f1 = (step % 24) / 23.0                                        # hourOfDay
    f2 = ((step // 24) % 7) / 7.0                                  # dayOfWeek
    f3 = (((step // 24).astype(int) % 7) >= 5).astype(float)       # isWeekend
    f4 = _np.full(len(df), 0.5)                                    # debtorCountryRisk (neutral)
    f5 = _np.full(len(df), 0.5)                                    # creditorCountryRisk (neutral)
    f6 = _np.zeros(len(df))                                        # crossBorder (PaySim domestic)
    f7 = _np.where(_np.isin(tx_type, ["CASH_OUT", "TRANSFER"]), 1.0, 0.2)  # highRiskCurrencyPair
    f8 = _np.zeros(len(df))                                        # velocityLast1h (not in PaySim)
    f9 = _np.zeros(len(df))                                        # velocityLast24h (not in PaySim)
    f10 = ((old_bal_dest == 0) & (new_bal_dest > 0)).astype(float) # isNewCreditorPair

    X = _np.column_stack([f0, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10])
    return X


# ---------------------------------------------------------------------------
# Training logic
# ---------------------------------------------------------------------------
def train_lgbm_on_paysim():
    """Train LightGBM on PaySim CSV and return (model, n_train_samples, importances)."""
    try:
        import lightgbm as lgb
    except ImportError:
        log.error("LightGBM is not installed.")
        log.error("Install it with:  pip install lightgbm")
        sys.exit(1)

    import pandas as pd
    from sklearn.model_selection import train_test_split
    from sklearn.metrics import roc_auc_score

    log.info("Loading PaySim dataset (sample=%d rows)…", SAMPLE_SIZE)
    df = pd.read_csv(PAYSIM_CSV, nrows=SAMPLE_SIZE)
    log.info("Loaded %d rows. Fraud rate: %.4f%%", len(df), df["isFraud"].mean() * 100)

    X = engineer_features(df)
    y = df["isFraud"].values.astype(int)

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    neg = (y_train == 0).sum()
    pos = (y_train == 1).sum()
    spw = neg / max(pos, 1)
    log.info("Train set: %d samples, pos=%d, neg=%d, scale_pos_weight=%.1f", len(y_train), pos, neg, spw)

    model = lgb.LGBMClassifier(
        n_estimators=300,
        learning_rate=0.05,
        max_depth=7,
        num_leaves=63,
        scale_pos_weight=spw,
        subsample=0.8,
        colsample_bytree=0.8,
        random_state=42,
        n_jobs=-1,
        verbose=-1,
    )
    model.fit(X_train, y_train)

    y_prob = model.predict_proba(X_test)[:, 1]
    auc = roc_auc_score(y_test, y_prob)
    log.info("Test ROC-AUC: %.4f", auc)

    raw_imp = model.feature_importances_
    total = raw_imp.sum() or 1.0
    importances = {name: float(raw_imp[i] / total) for i, name in enumerate(FEATURE_NAMES)}

    return model, len(X_train), importances


def train_fallback_rf():
    """Train a RandomForest on 10K synthetic samples when PaySim CSV is missing."""
    log.warning("PaySim CSV not found at %s", PAYSIM_CSV)
    log.warning("Falling back to RandomForest trained on synthetic data.")

    from sklearn.ensemble import RandomForestClassifier
    from sklearn.model_selection import train_test_split
    from sklearn.metrics import roc_auc_score

    rng = np.random.default_rng(42)
    n = 10_000
    X = rng.random((n, 11)).astype(np.float32)
    y = (rng.random(n) < 0.01).astype(int)  # ~1% fraud

    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    model = RandomForestClassifier(n_estimators=100, random_state=42, n_jobs=-1, class_weight="balanced")
    model.fit(X_train, y_train)

    y_prob = model.predict_proba(X_test)[:, 1]
    auc = roc_auc_score(y_test, y_prob)
    log.info("Fallback RF Test ROC-AUC: %.4f", auc)

    raw_imp = model.feature_importances_
    total = raw_imp.sum() or 1.0
    importances = {name: float(raw_imp[i] / total) for i, name in enumerate(FEATURE_NAMES)}

    return model, len(X_train), importances


def load_or_train_model():
    """Load model from disk if available; otherwise train and save."""
    global MODEL, TRAINED_ON, FEATURE_IMPORTANCES

    if MODEL_PKL.exists():
        log.info("Loading existing model from %s", MODEL_PKL)
        with open(MODEL_PKL, "rb") as fh:
            bundle = pickle.load(fh)
        MODEL = bundle["model"]
        TRAINED_ON = bundle["trained_on"]
        FEATURE_IMPORTANCES = bundle["importances"]
        log.info("Model loaded. Trained on %d samples.", TRAINED_ON)
        return

    # Train fresh
    paysim_exists = Path(PAYSIM_CSV).exists()
    if paysim_exists:
        model, n, importances = train_lgbm_on_paysim()
    else:
        model, n, importances = train_fallback_rf()

    bundle = {"model": model, "trained_on": n, "importances": importances}
    with open(MODEL_PKL, "wb") as fh:
        pickle.dump(bundle, fh)
    log.info("Model saved to %s", MODEL_PKL)

    MODEL = model
    TRAINED_ON = n
    FEATURE_IMPORTANCES = importances


# ---------------------------------------------------------------------------
# FastAPI application
# ---------------------------------------------------------------------------
app = FastAPI(title="ClearFlow Fraud Model Server", version="1.0.0")


@app.on_event("startup")
def startup_event():
    load_or_train_model()


@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest):
    if MODEL is None:
        raise HTTPException(status_code=503, detail="Model not ready")

    features = request.features
    if len(features) != 11:
        raise HTTPException(
            status_code=400,
            detail=f"Expected 11 features, got {len(features)}"
        )

    X = np.array(features, dtype=np.float32).reshape(1, -1)
    score = float(MODEL.predict_proba(X)[0, 1])

    return PredictResponse(
        score=score,
        featureImportance=FEATURE_IMPORTANCES,
        modelVersion=MODEL_VERSION,
    )


@app.get("/health", response_model=HealthResponse)
def health():
    return HealthResponse(
        status="UP" if MODEL is not None else "STARTING",
        model=MODEL_VERSION,
        trainedOn=TRAINED_ON,
    )


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    uvicorn.run("fraud_model_server:app", host="0.0.0.0", port=8091, reload=False)
