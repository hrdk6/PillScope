from typing import Any

import lightgbm as lgb
import numpy as np
import torch
from numpy.typing import NDArray
from sklearn.preprocessing import StandardScaler
from torch import nn
from torch.utils.data import DataLoader, TensorDataset

from ml.ddi.schemas import LABEL_TO_INDEX, SEVERITY_LABELS

FloatArray = NDArray[np.float32]
IntArray = NDArray[np.int64]

NUM_CLASSES = len(SEVERITY_LABELS)
MAJOR_CLASS_INDEX = LABEL_TO_INDEX["MAJOR"]
MODERATE_CLASS_INDEX = LABEL_TO_INDEX["MODERATE"]


def apply_safety_thresholds(
    probs: NDArray[np.float32],
    major_threshold: float,
    moderate_threshold: float,
) -> NDArray[np.int64]:
    preds = np.argmax(probs, axis=1).astype(np.int64)
    moderate_mask = (probs[:, MODERATE_CLASS_INDEX] >= moderate_threshold) & (
        preds < MODERATE_CLASS_INDEX
    )
    preds[moderate_mask] = MODERATE_CLASS_INDEX
    major_mask = probs[:, MAJOR_CLASS_INDEX] >= major_threshold
    preds[major_mask] = MAJOR_CLASS_INDEX
    return preds


def _compute_class_weights(y: IntArray, major_boost: float = 1.5) -> dict[int, float]:
    counts = np.bincount(y, minlength=NUM_CLASSES).astype(np.float64)
    present = counts > 0
    total = float(len(y))
    n_present = int(np.sum(present))
    weights: dict[int, float] = {}
    for cls_idx in range(NUM_CLASSES):
        if counts[cls_idx] > 0:
            w = total / (max(n_present, 1) * counts[cls_idx])
            if cls_idx == NUM_CLASSES - 1:
                w *= major_boost
            weights[cls_idx] = float(w)
        else:
            weights[cls_idx] = 1.0
    return weights


def _expand_probabilities(raw_probs: NDArray[np.float64], classes: NDArray[np.int64]) -> FloatArray:
    expanded = np.zeros((raw_probs.shape[0], NUM_CLASSES), dtype=np.float32)
    for col_idx, cls_id in enumerate(classes):
        expanded[:, int(cls_id)] = raw_probs[:, col_idx].astype(np.float32)
    row_sums = expanded.sum(axis=1, keepdims=True)
    row_sums = np.where(row_sums > 0, row_sums, 1.0)
    return (expanded / row_sums).astype(np.float32)


class LightGbmDdiModel:
    model_name = "lightgbm"

    def __init__(
        self,
        *,
        n_estimators: int = 300,
        learning_rate: float = 0.05,
        num_leaves: int = 31,
        min_child_samples: int = 5,
        subsample: float = 0.85,
        colsample_bytree: float = 0.7,
        seed: int = 42,
    ) -> None:
        self._params: dict[str, Any] = {
            "n_estimators": n_estimators,
            "learning_rate": learning_rate,
            "num_leaves": num_leaves,
            "min_child_samples": min_child_samples,
            "subsample": subsample,
            "subsample_freq": 1,
            "colsample_bytree": colsample_bytree,
            "reg_alpha": 1.0,
            "reg_lambda": 5.0,
            "random_state": seed,
            "n_jobs": -1,
            "verbose": -1,
        }
        self._booster: lgb.Booster | None = None
        self._classes: NDArray[np.int64] = np.arange(NUM_CLASSES, dtype=np.int64)

    def fit(
        self,
        x_train: FloatArray,
        y_train: IntArray,
        x_val: FloatArray,
        y_val: IntArray,
    ) -> dict[str, Any]:
        present_classes = np.unique(y_train).astype(np.int64)
        self._classes = present_classes
        remap = {int(c): idx for idx, c in enumerate(present_classes)}
        y_train_mapped = np.asarray([remap[int(v)] for v in y_train], dtype=np.int64)

        class_weights = _compute_class_weights(y_train)
        sample_weight = np.asarray([class_weights[int(v)] for v in y_train], dtype=np.float32)

        num_present = len(present_classes)
        objective = "binary" if num_present == 2 else "multiclass"
        clf_params: dict[str, Any] = {
            **self._params,
            "objective": objective,
            "min_child_samples": min(self._params["min_child_samples"], max(2, len(y_train) // 8)),
        }
        if num_present > 2:
            clf_params["num_class"] = num_present

        classifier = lgb.LGBMClassifier(**clf_params)
        val_mask = np.isin(y_val, present_classes)
        if np.any(val_mask):
            y_val_mapped = np.asarray([remap[int(v)] for v in y_val[val_mask]], dtype=np.int64)
            classifier.fit(
                x_train,
                y_train_mapped,
                sample_weight=sample_weight,
                eval_X=x_val[val_mask],
                eval_y=y_val_mapped,
                callbacks=[lgb.early_stopping(stopping_rounds=30, verbose=False)],
            )
        else:
            classifier.fit(x_train, y_train_mapped, sample_weight=sample_weight)

        self._booster = classifier.booster_
        return {
            **self._params,
            "best_iteration": int(classifier.best_iteration_ or self._params["n_estimators"]),
        }

    def predict_proba(self, x: FloatArray) -> FloatArray:
        if self._booster is None:
            raise RuntimeError("LightGbmDdiModel has not been fitted or loaded.")
        raw = np.asarray(self._booster.predict(x), dtype=np.float64)
        if raw.ndim == 1:
            raw = np.stack([1.0 - raw, raw], axis=1)
        return _expand_probabilities(raw, self._classes)

    def to_payload(self) -> dict[str, Any]:
        if self._booster is None:
            raise RuntimeError("Cannot serialize unfitted LightGbmDdiModel.")
        return {
            "booster_str": self._booster.model_to_string(),
            "classes": self._classes.tolist(),
            "params": self._params,
        }

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "LightGbmDdiModel":
        instance = cls(
            n_estimators=int(payload["params"]["n_estimators"]),
            learning_rate=float(payload["params"]["learning_rate"]),
            num_leaves=int(payload["params"]["num_leaves"]),
            min_child_samples=int(payload["params"]["min_child_samples"]),
            subsample=float(payload["params"]["subsample"]),
            colsample_bytree=float(payload["params"]["colsample_bytree"]),
            seed=int(payload["params"]["random_state"]),
        )
        instance._booster = lgb.Booster(model_str=str(payload["booster_str"]))
        instance._classes = np.asarray(payload["classes"], dtype=np.int64)
        return instance


class _DdiMlpNet(nn.Module):
    def __init__(
        self,
        input_dim: int,
        hidden_dims: tuple[int, int] = (256, 128),
        dropout: float = 0.25,
        num_classes: int = NUM_CLASSES,
    ) -> None:
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(input_dim, hidden_dims[0]),
            nn.LayerNorm(hidden_dims[0]),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(hidden_dims[0], hidden_dims[1]),
            nn.LayerNorm(hidden_dims[1]),
            nn.GELU(),
            nn.Dropout(dropout * 0.8),
            nn.Linear(hidden_dims[1], num_classes),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        out: torch.Tensor = self.net(x)
        return out


class TorchMlpDdiModel:
    model_name = "torch_mlp"

    def __init__(
        self,
        *,
        continuous_start: int,
        hidden_dims: tuple[int, int] = (256, 128),
        dropout: float = 0.40,
        learning_rate: float = 1e-3,
        weight_decay: float = 2e-2,
        epochs: int = 45,
        batch_size: int = 32,
        patience: int = 10,
        seed: int = 42,
    ) -> None:
        self._continuous_start = continuous_start
        self._hidden_dims = hidden_dims
        self._dropout = dropout
        self._learning_rate = learning_rate
        self._weight_decay = weight_decay
        self._epochs = epochs
        self._batch_size = batch_size
        self._patience = patience
        self._seed = seed
        self._device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self._scaler = StandardScaler()
        self._input_dim: int | None = None
        self._net: _DdiMlpNet | None = None

    def _transform_features(self, x: FloatArray, *, fit_scaler: bool) -> FloatArray:
        out = x.astype(np.float32, copy=True)
        cont = out[:, self._continuous_start :]
        scaled = self._scaler.fit_transform(cont) if fit_scaler else self._scaler.transform(cont)
        out[:, self._continuous_start :] = np.nan_to_num(
            scaled.astype(np.float32, copy=False), nan=0.0, posinf=0.0, neginf=0.0
        )
        return out

    def fit(
        self,
        x_train: FloatArray,
        y_train: IntArray,
        x_val: FloatArray,
        y_val: IntArray,
    ) -> dict[str, Any]:
        torch.manual_seed(self._seed)
        np.random.seed(self._seed)

        x_train_scaled = self._transform_features(x_train, fit_scaler=True)
        x_val_scaled = self._transform_features(x_val, fit_scaler=False)

        self._input_dim = int(x_train_scaled.shape[1])
        net = _DdiMlpNet(
            input_dim=self._input_dim,
            hidden_dims=self._hidden_dims,
            dropout=self._dropout,
            num_classes=NUM_CLASSES,
        ).to(self._device)

        weights_dict = _compute_class_weights(y_train)
        weight_tensor = torch.tensor(
            [weights_dict[i] for i in range(NUM_CLASSES)],
            dtype=torch.float32,
            device=self._device,
        )
        criterion = nn.CrossEntropyLoss(weight=weight_tensor, label_smoothing=0.05)
        optimizer = torch.optim.AdamW(
            net.parameters(),
            lr=self._learning_rate,
            weight_decay=self._weight_decay,
        )
        scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=self._epochs)

        dataset = TensorDataset(
            torch.from_numpy(x_train_scaled),
            torch.from_numpy(y_train.astype(np.int64)),
        )
        loader = DataLoader(dataset, batch_size=min(self._batch_size, len(dataset)), shuffle=True)

        x_val_t = torch.from_numpy(x_val_scaled).to(self._device)
        y_val_t = torch.from_numpy(y_val.astype(np.int64)).to(self._device)

        best_val_loss = float("inf")
        best_state: dict[str, torch.Tensor] | None = None
        best_epoch = 0
        no_improve = 0

        for epoch in range(1, self._epochs + 1):
            net.train()
            for xb, yb in loader:
                xb = xb.to(self._device)
                yb = yb.to(self._device)
                optimizer.zero_grad()
                logits = net(xb)
                loss = criterion(logits, yb)
                loss.backward()
                nn.utils.clip_grad_norm_(net.parameters(), max_norm=2.0)
                optimizer.step()
            scheduler.step()

            net.eval()
            with torch.no_grad():
                val_logits = net(x_val_t)
                val_loss = float(criterion(val_logits, y_val_t).item())

            if val_loss < best_val_loss - 1e-4:
                best_val_loss = val_loss
                best_epoch = epoch
                best_state = {k: v.detach().cpu().clone() for k, v in net.state_dict().items()}
                no_improve = 0
            else:
                no_improve += 1
                if no_improve >= self._patience:
                    break

        if best_state is not None:
            net.load_state_dict(best_state)
        net.eval()
        self._net = net
        return {
            "hidden_dims": list(self._hidden_dims),
            "dropout": self._dropout,
            "learning_rate": self._learning_rate,
            "weight_decay": self._weight_decay,
            "epochs_configured": self._epochs,
            "best_epoch": best_epoch,
            "best_val_loss": round(best_val_loss, 6),
        }

    def predict_proba(self, x: FloatArray) -> FloatArray:
        if self._net is None:
            raise RuntimeError("TorchMlpDdiModel has not been fitted or loaded.")
        x_scaled = self._transform_features(x, fit_scaler=False)
        self._net.eval()
        with torch.no_grad():
            tensor_x = torch.from_numpy(x_scaled).to(self._device)
            logits = self._net(tensor_x)
            probs = torch.softmax(logits, dim=-1).cpu().numpy()
        return np.asarray(probs, dtype=np.float32)

    def to_payload(self) -> dict[str, Any]:
        if self._net is None or self._input_dim is None:
            raise RuntimeError("Cannot serialize unfitted TorchMlpDdiModel.")
        state_numpy = {k: v.detach().cpu().numpy() for k, v in self._net.state_dict().items()}
        return {
            "input_dim": self._input_dim,
            "continuous_start": self._continuous_start,
            "hidden_dims": list(self._hidden_dims),
            "dropout": self._dropout,
            "scaler_mean": self._scaler.mean_.tolist(),
            "scaler_scale": self._scaler.scale_.tolist(),
            "scaler_var": self._scaler.var_.tolist(),
            "state_dict_numpy": state_numpy,
        }

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "TorchMlpDdiModel":
        hidden = payload["hidden_dims"]
        instance = cls(
            continuous_start=int(payload["continuous_start"]),
            hidden_dims=(int(hidden[0]), int(hidden[1])),
            dropout=float(payload["dropout"]),
        )
        instance._input_dim = int(payload["input_dim"])
        instance._scaler.mean_ = np.asarray(payload["scaler_mean"], dtype=np.float64)
        instance._scaler.scale_ = np.asarray(payload["scaler_scale"], dtype=np.float64)
        instance._scaler.var_ = np.asarray(payload["scaler_var"], dtype=np.float64)
        instance._scaler.n_features_in_ = len(instance._scaler.mean_)

        net = _DdiMlpNet(
            input_dim=instance._input_dim,
            hidden_dims=instance._hidden_dims,
            dropout=instance._dropout,
            num_classes=NUM_CLASSES,
        ).to(instance._device)
        state_tensors = {
            k: torch.from_numpy(np.asarray(v)) for k, v in payload["state_dict_numpy"].items()
        }
        net.load_state_dict(state_tensors)
        net.eval()
        instance._net = net
        return instance
