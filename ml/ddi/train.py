import argparse
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal

import mlflow
import numpy as np
import pandas as pd
import structlog
from numpy.typing import NDArray
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)

from ml.ddi.artifact import DdiClassifier, save_ddi_artifact
from ml.ddi.features import DdiPairFeatureExtractor
from ml.ddi.models import (
    MAJOR_CLASS_INDEX,
    LightGbmDdiModel,
    TorchMlpDdiModel,
    apply_safety_thresholds,
)
from ml.ddi.schemas import (
    LABEL_TO_INDEX,
    ArtifactManifest,
    DdiFeatureConfig,
    ModelMetrics,
    SplitSummary,
)
from ml.ddi.splits import scaffold_drug_disjoint_split

logger = structlog.get_logger(__name__)


@dataclass(slots=True)
class TrainingComparisonResult:
    winner_name: Literal["lightgbm", "torch_mlp"]
    artifact_path: Path
    manifest: ArtifactManifest
    all_val_metrics: dict[str, ModelMetrics]
    all_test_metrics: dict[str, ModelMetrics]


def tune_major_threshold(
    y_true: NDArray[np.int64],
    probs: NDArray[np.float32],
    min_recall_target: float = 0.90,
) -> float:
    y_major = (y_true == MAJOR_CLASS_INDEX).astype(np.int64)
    if int(np.sum(y_major)) == 0:
        return 0.50

    p_major = probs[:, MAJOR_CLASS_INDEX]
    best_threshold = 0.50
    best_score = -1.0

    for threshold in np.linspace(0.15, 0.75, 25):
        pred_major = (p_major >= threshold).astype(np.int64)
        rec = float(recall_score(y_major, pred_major, zero_division=0))
        prec = float(precision_score(y_major, pred_major, zero_division=0))
        f2 = (5.0 * prec * rec) / max((4.0 * prec) + rec, 1e-9)
        score = f2 + (0.25 if rec >= min_recall_target else 0.0)
        if score > best_score:
            best_score = score
            best_threshold = float(threshold)

    return round(best_threshold, 4)


def evaluate_predictions(
    *,
    model_name: str,
    split: Literal["val", "test"],
    y_true: NDArray[np.int64],
    probs: NDArray[np.float32],
    major_threshold: float,
    moderate_threshold: float = 0.35,
) -> ModelMetrics:
    y_pred = apply_safety_thresholds(
        probs=probs,
        major_threshold=major_threshold,
        moderate_threshold=moderate_threshold,
    )
    y_major = (y_true == MAJOR_CLASS_INDEX).astype(np.int64)
    p_major = probs[:, MAJOR_CLASS_INDEX]
    pred_major = (y_pred == MAJOR_CLASS_INDEX).astype(np.int64)

    macro_f1 = float(f1_score(y_true, y_pred, average="macro", zero_division=0))
    weighted_f1 = float(f1_score(y_true, y_pred, average="weighted", zero_division=0))
    major_recall = float(recall_score(y_major, pred_major, zero_division=0))
    major_precision = float(precision_score(y_major, pred_major, zero_division=0))

    if len(np.unique(y_major)) > 1:
        major_pr_auc = float(average_precision_score(y_major, p_major))
    else:
        major_pr_auc = 1.0 if int(np.sum(y_major)) > 0 else 0.0

    brier_major = float(brier_score_loss(y_major, np.clip(p_major, 0.0, 1.0)))

    roc_auc_ovr: float | None = None
    unique_classes = np.unique(y_true)
    if len(unique_classes) >= 2:
        try:
            if len(unique_classes) == 2:
                pos_cls = int(unique_classes[1])
                roc_auc_ovr = float(
                    roc_auc_score((y_true == pos_cls).astype(np.int64), probs[:, pos_cls])
                )
            else:
                sliced = probs[:, unique_classes]
                sliced = sliced / np.clip(sliced.sum(axis=1, keepdims=True), 1e-9, None)
                roc_auc_ovr = float(
                    roc_auc_score(
                        y_true,
                        sliced,
                        multi_class="ovr",
                        average="macro",
                        labels=unique_classes,
                    )
                )
        except ValueError:
            roc_auc_ovr = None

    return ModelMetrics(
        model_name=model_name,
        split=split,
        macro_f1=round(macro_f1, 4),
        weighted_f1=round(weighted_f1, 4),
        major_recall=round(major_recall, 4),
        major_precision=round(major_precision, 4),
        major_pr_auc=round(major_pr_auc, 4),
        roc_auc_ovr=round(roc_auc_ovr, 4) if roc_auc_ovr is not None else None,
        brier_major=round(brier_major, 4),
        major_threshold=major_threshold,
        moderate_threshold=moderate_threshold,
        sample_count=len(y_true),
    )


def _selection_score(metrics: ModelMetrics) -> float:
    return (0.50 * metrics.major_pr_auc) + (0.30 * metrics.major_recall) + (0.20 * metrics.macro_f1)


def _load_prepared_dataframe(dataset_path: Path) -> pd.DataFrame:
    if dataset_path.suffix.lower() == ".parquet":
        return pd.read_parquet(dataset_path)
    return pd.read_csv(dataset_path)


_STR_TO_INDEX: dict[str, int] = {str(k): v for k, v in LABEL_TO_INDEX.items()}


def _encode_labels(df: pd.DataFrame) -> NDArray[np.int64]:
    return np.asarray([_STR_TO_INDEX[str(sev).upper()] for sev in df["severity"]], dtype=np.int64)


def _log_model_run_to_mlflow(
    *,
    model_name: str,
    feature_config: DdiFeatureConfig,
    split_summary: SplitSummary,
    train_meta: dict[str, Any],
    val_metrics: ModelMetrics,
    test_metrics: ModelMetrics,
) -> None:
    with mlflow.start_run(run_name=f"ddi-{model_name}", nested=True):
        mlflow.log_params(
            {
                "model_name": model_name,
                "fp_radius": feature_config.fp_radius,
                "fp_bits": feature_config.fp_bits,
                "use_chirality": feature_config.use_chirality,
                "total_features": feature_config.total_features,
                "split_strategy": "murcko_scaffold_and_drug_disjoint",
                "train_pairs": split_summary.train_pairs,
                "val_pairs": split_summary.val_pairs,
                "test_pairs": split_summary.test_pairs,
                "drug_overlap_count": split_summary.drug_overlap_count,
                "scaffold_overlap_count": split_summary.scaffold_overlap_count,
                **{f"hp_{k}": str(v) for k, v in train_meta.items()},
            }
        )
        metrics_payload: dict[str, float] = {
            "val_macro_f1": val_metrics.macro_f1,
            "val_weighted_f1": val_metrics.weighted_f1,
            "val_major_recall": val_metrics.major_recall,
            "val_major_precision": val_metrics.major_precision,
            "val_major_pr_auc": val_metrics.major_pr_auc,
            "val_brier_major": val_metrics.brier_major,
            "test_macro_f1": test_metrics.macro_f1,
            "test_weighted_f1": test_metrics.weighted_f1,
            "test_major_recall": test_metrics.major_recall,
            "test_major_precision": test_metrics.major_precision,
            "test_major_pr_auc": test_metrics.major_pr_auc,
            "test_brier_major": test_metrics.brier_major,
            "major_threshold": val_metrics.major_threshold,
            "selection_score": _selection_score(val_metrics),
        }
        if val_metrics.roc_auc_ovr is not None:
            metrics_payload["val_roc_auc_ovr"] = val_metrics.roc_auc_ovr
        if test_metrics.roc_auc_ovr is not None:
            metrics_payload["test_roc_auc_ovr"] = test_metrics.roc_auc_ovr
        mlflow.log_metrics(metrics_payload)


def _prepare_mlflow_tracking_uri(tracking_uri: str) -> str:
    if tracking_uri.startswith("sqlite:///"):
        db_file = Path(tracking_uri.removeprefix("sqlite:///"))
        db_file.parent.mkdir(parents=True, exist_ok=True)
    return tracking_uri


def train_and_evaluate(
    *,
    dataset_path: Path,
    artifact_output_path: Path,
    mlflow_tracking_uri: str = "sqlite:///mlruns/mlflow.db",
    experiment_name: str = "pillscope-ddi-severity",
    feature_config: DdiFeatureConfig | None = None,
    seed: int = 42,
    torch_epochs: int = 45,
) -> TrainingComparisonResult:
    df = _load_prepared_dataframe(dataset_path)
    train_df, val_df, test_df, split_summary = scaffold_drug_disjoint_split(df, seed=seed)

    if train_df.empty or val_df.empty or test_df.empty:
        raise ValueError(
            f"Disjoint split produced an empty partition: {split_summary.model_dump()}"
        )

    cfg = feature_config or DdiFeatureConfig()
    extractor = DdiPairFeatureExtractor(cfg)

    x_train, _ = extractor.extract_batch(
        list(zip(train_df["smiles_a"], train_df["smiles_b"], strict=True))
    )
    x_val, _ = extractor.extract_batch(
        list(zip(val_df["smiles_a"], val_df["smiles_b"], strict=True))
    )
    x_test, _ = extractor.extract_batch(
        list(zip(test_df["smiles_a"], test_df["smiles_b"], strict=True))
    )
    y_train = _encode_labels(train_df)
    y_val = _encode_labels(val_df)
    y_test = _encode_labels(test_df)

    mlflow.set_tracking_uri(_prepare_mlflow_tracking_uri(mlflow_tracking_uri))
    mlflow.set_experiment(experiment_name)

    candidates: list[tuple[Literal["lightgbm", "torch_mlp"], DdiClassifier]] = [
        ("lightgbm", LightGbmDdiModel(seed=seed)),
        (
            "torch_mlp",
            TorchMlpDdiModel(
                continuous_start=extractor.continuous_feature_start,
                epochs=torch_epochs,
                seed=seed,
            ),
        ),
    ]

    trained_models: dict[Literal["lightgbm", "torch_mlp"], DdiClassifier] = {}
    val_metrics_map: dict[str, ModelMetrics] = {}
    test_metrics_map: dict[str, ModelMetrics] = {}
    thresholds_map: dict[str, float] = {}

    with mlflow.start_run(run_name=f"ddi-comparison-{datetime.now(UTC).strftime('%Y%m%d-%H%M%S')}"):
        mlflow.log_params(
            {
                "dataset_path": str(dataset_path),
                "total_rows": len(df),
                "seed": seed,
            }
        )

        for name, model in candidates:
            train_meta = model.fit(x_train, y_train, x_val, y_val)
            val_probs = model.predict_proba(x_val)
            test_probs = model.predict_proba(x_test)

            major_threshold = tune_major_threshold(y_val, val_probs)
            val_metrics = evaluate_predictions(
                model_name=name,
                split="val",
                y_true=y_val,
                probs=val_probs,
                major_threshold=major_threshold,
            )
            test_metrics = evaluate_predictions(
                model_name=name,
                split="test",
                y_true=y_test,
                probs=test_probs,
                major_threshold=major_threshold,
            )

            _log_model_run_to_mlflow(
                model_name=name,
                feature_config=cfg,
                split_summary=split_summary,
                train_meta=train_meta,
                val_metrics=val_metrics,
                test_metrics=test_metrics,
            )

            trained_models[name] = model
            val_metrics_map[name] = val_metrics
            test_metrics_map[name] = test_metrics
            thresholds_map[name] = major_threshold

        winner_name: Literal["lightgbm", "torch_mlp"] = max(
            trained_models.keys(),
            key=lambda m: _selection_score(val_metrics_map[m]),
        )
        winner_model = trained_models[winner_name]
        train_drugs = sorted(set(train_df["salt_a"]).union(set(train_df["salt_b"])))
        train_scaffolds = sorted(set(train_df["scaffold_a"]).union(set(train_df["scaffold_b"])))
        now_utc = datetime.now(UTC)
        manifest = ArtifactManifest(
            model_name=winner_name,
            model_version=f"{winner_name}-{now_utc.strftime('%Y%m%dT%H%M%SZ')}",
            created_at_utc=now_utc.isoformat(),
            feature_config=cfg,
            major_threshold=thresholds_map[winner_name],
            moderate_threshold=0.35,
            val_metrics=val_metrics_map[winner_name],
            test_metrics=test_metrics_map[winner_name],
            split_summary=split_summary,
            train_drugs=train_drugs,
            train_scaffolds=train_scaffolds,
        )

        save_ddi_artifact(artifact_output_path, manifest, winner_model)

        report_path = artifact_output_path.with_suffix(".report.json")
        report_payload = {
            "winner": winner_name,
            "artifact_path": str(artifact_output_path),
            "split_summary": split_summary.model_dump(mode="json"),
            "val_metrics": {k: v.model_dump(mode="json") for k, v in val_metrics_map.items()},
            "test_metrics": {k: v.model_dump(mode="json") for k, v in test_metrics_map.items()},
        }
        report_path.write_text(json.dumps(report_payload, indent=2), encoding="utf-8")

        mlflow.log_param("winner_model", winner_name)
        mlflow.log_artifact(str(report_path), artifact_path="evaluation")
        mlflow.log_artifact(str(artifact_output_path), artifact_path="model_bundle")

    logger.info(
        "ddi_training_complete",
        winner=winner_name,
        artifact_path=str(artifact_output_path),
        val_major_pr_auc=val_metrics_map[winner_name].major_pr_auc,
        test_major_pr_auc=test_metrics_map[winner_name].major_pr_auc,
        test_macro_f1=test_metrics_map[winner_name].macro_f1,
        drug_overlap=split_summary.drug_overlap_count,
        scaffold_overlap=split_summary.scaffold_overlap_count,
    )

    return TrainingComparisonResult(
        winner_name=winner_name,
        artifact_path=artifact_output_path,
        manifest=manifest,
        all_val_metrics=val_metrics_map,
        all_test_metrics=test_metrics_map,
    )


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Train & compare LightGBM vs PyTorch MLP for pairwise DDI severity with MLflow."
    )
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument(
        "--artifact-output",
        type=Path,
        default=Path("ml/artifacts/ddi_ensemble.joblib"),
    )
    parser.add_argument("--mlflow-uri", type=str, default="sqlite:///mlruns/mlflow.db")
    parser.add_argument("--experiment", type=str, default="pillscope-ddi-severity")
    parser.add_argument("--fp-bits", type=int, default=1024)
    parser.add_argument("--fp-radius", type=int, default=2)
    parser.add_argument("--torch-epochs", type=int, default=45)
    parser.add_argument("--seed", type=int, default=42)
    return parser


def main() -> None:
    args = _build_arg_parser().parse_args()
    result = train_and_evaluate(
        dataset_path=args.dataset,
        artifact_output_path=args.artifact_output,
        mlflow_tracking_uri=args.mlflow_uri,
        experiment_name=args.experiment,
        feature_config=DdiFeatureConfig(fp_radius=args.fp_radius, fp_bits=args.fp_bits),
        seed=args.seed,
        torch_epochs=args.torch_epochs,
    )
    print(
        json.dumps(
            {
                "winner": result.winner_name,
                "artifact_path": str(result.artifact_path),
                "split_summary": result.manifest.split_summary.model_dump(mode="json"),
                "val_metrics": {
                    k: v.model_dump(mode="json") for k, v in result.all_val_metrics.items()
                },
                "test_metrics": {
                    k: v.model_dump(mode="json") for k, v in result.all_test_metrics.items()
                },
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
