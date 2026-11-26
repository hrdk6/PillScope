import argparse
import sys
from pathlib import Path

import numpy as np
from pydantic import BaseModel, Field
from sklearn.metrics import average_precision_score, f1_score, precision_score, recall_score

from backend.app.services.ddi_ml_inference import (
    DdiMlInferenceService,
    DeterministicRuleAlert,
    merge_deterministic_and_ml_interactions,
)
from backend.app.services.drug_normalizer import normalize_canonical_salt
from ml.ddi.data_prep import canonicalize_smiles, compute_murcko_scaffold
from ml.ddi.schemas import LABEL_TO_INDEX, DrugPairInput, SeverityLabel

MAJOR_INDEX = LABEL_TO_INDEX["MAJOR"]


class HoldoutPairCase(BaseModel):
    salt_a: str
    smiles_a: str
    salt_b: str
    smiles_b: str
    severity: SeverityLabel
    in_deterministic_db: bool = False
    mechanism: str = ""
    management: str = ""


class HoldoutDataset(BaseModel):
    dataset_name: str
    description: str = ""
    pairs: list[HoldoutPairCase] = Field(min_length=1)


class EvalGateMetrics(BaseModel):
    sample_count: int
    major_count: int
    macro_f1: float
    weighted_f1: float
    major_recall: float
    major_precision: float
    major_pr_auc: float
    missed_major_pairs: list[str]


class MlEvalGateReport(BaseModel):
    dataset_name: str
    artifact_path: str
    model_name: str
    model_version: str
    zero_leakage_verified: bool
    holdout_train_drug_overlap: int = 0
    holdout_train_scaffold_overlap: int = 0
    ml_only_metrics: EvalGateMetrics
    combined_safety_metrics: EvalGateMetrics
    min_macro_f1_gate: float
    min_major_recall_gate: float
    min_ml_major_recall_gate: float
    passed: bool
    failure_reasons: list[str]


def _compute_metrics(
    cases: list[HoldoutPairCase],
    y_pred: list[int],
    major_scores: list[float],
) -> EvalGateMetrics:
    y_true = np.asarray([LABEL_TO_INDEX[c.severity] for c in cases], dtype=np.int64)
    pred_arr = np.asarray(y_pred, dtype=np.int64)
    score_arr = np.asarray(major_scores, dtype=np.float64)

    y_major = (y_true == MAJOR_INDEX).astype(np.int64)
    pred_major = (pred_arr == MAJOR_INDEX).astype(np.int64)

    missed = [
        f"{c.salt_a}+{c.salt_b}"
        for c, pred_idx in zip(cases, y_pred, strict=True)
        if c.severity == "MAJOR" and pred_idx != MAJOR_INDEX
    ]
    pr_auc = (
        float(average_precision_score(y_major, score_arr)) if len(np.unique(y_major)) > 1 else 1.0
    )

    return EvalGateMetrics(
        sample_count=len(cases),
        major_count=int(np.sum(y_major)),
        macro_f1=round(float(f1_score(y_true, pred_arr, average="macro", zero_division=0)), 4),
        weighted_f1=round(
            float(f1_score(y_true, pred_arr, average="weighted", zero_division=0)), 4
        ),
        major_recall=round(float(recall_score(y_major, pred_major, zero_division=0)), 4),
        major_precision=round(float(precision_score(y_major, pred_major, zero_division=0)), 4),
        major_pr_auc=round(pr_auc, 4),
        missed_major_pairs=missed,
    )


def run_ml_eval_gate(
    *,
    artifact_path: Path,
    holdout_path: Path,
    min_macro_f1: float = 0.75,
    min_major_recall: float = 1.0,
    min_ml_major_recall: float = 0.70,
    major_threshold_override: float | None = None,
) -> MlEvalGateReport:
    dataset = HoldoutDataset.model_validate_json(holdout_path.read_text(encoding="utf-8"))
    service = DdiMlInferenceService.from_artifact(
        artifact_path,
        major_threshold_override=major_threshold_override,
    )
    manifest = service.manifest

    holdout_drugs: set[str] = set()
    holdout_scaffolds: set[str] = set()
    for c in dataset.pairs:
        norm_a = normalize_canonical_salt(c.salt_a) or c.salt_a.strip().lower()
        norm_b = normalize_canonical_salt(c.salt_b) or c.salt_b.strip().lower()
        holdout_drugs.update((norm_a, norm_b))
        smi_a = canonicalize_smiles(c.smiles_a) or c.smiles_a
        smi_b = canonicalize_smiles(c.smiles_b) or c.smiles_b
        holdout_scaffolds.add(compute_murcko_scaffold(smi_a))
        holdout_scaffolds.add(compute_murcko_scaffold(smi_b))

    train_drug_set = set(manifest.train_drugs)
    train_scaffold_set = set(manifest.train_scaffolds)
    holdout_drug_overlap = len(holdout_drugs & train_drug_set)
    holdout_scaffold_overlap = len(holdout_scaffolds & train_scaffold_set)

    zero_leakage_verified = (
        manifest.split_summary.drug_overlap_count == 0
        and manifest.split_summary.scaffold_overlap_count == 0
        and holdout_drug_overlap == 0
        and holdout_scaffold_overlap == 0
    )

    pair_inputs = [
        DrugPairInput(
            salt_a=c.salt_a,
            smiles_a=c.smiles_a,
            salt_b=c.salt_b,
            smiles_b=c.smiles_b,
        )
        for c in dataset.pairs
    ]
    ml_predictions = service.score_pairs_batch(pair_inputs)

    ml_only_preds = [LABEL_TO_INDEX[p.predicted_severity] for p in ml_predictions]
    ml_only_scores = [p.major_probability for p in ml_predictions]
    ml_only_metrics = _compute_metrics(dataset.pairs, ml_only_preds, ml_only_scores)

    combined_preds: list[int] = []
    combined_scores: list[float] = []
    for idx, (case, ml_pred) in enumerate(zip(dataset.pairs, ml_predictions, strict=True)):
        det_alerts: list[DeterministicRuleAlert] = []
        if case.in_deterministic_db and case.severity != "NONE":
            det_alerts.append(
                DeterministicRuleAlert(
                    rule_id=f"holdout:{idx}",
                    salt_a=case.salt_a,
                    salt_b=case.salt_b,
                    severity=case.severity,
                    mechanism=case.mechanism or "Curated deterministic rule.",
                    management=case.management or "Follow clinical protocol.",
                )
            )
        merged = merge_deterministic_and_ml_interactions(
            deterministic_alerts=det_alerts,
            ml_predictions=[ml_pred],
            has_duplicate_salts=False,
        )
        combined_preds.append(LABEL_TO_INDEX[merged.overall_risk])
        score = (
            1.0
            if (case.in_deterministic_db and case.severity == "MAJOR")
            else ml_pred.major_probability
        )
        combined_scores.append(score)

    combined_metrics = _compute_metrics(dataset.pairs, combined_preds, combined_scores)

    failures: list[str] = []
    if not zero_leakage_verified:
        failures.append(
            "Zero-leakage invariant failed "
            f"(split_drug_overlap={manifest.split_summary.drug_overlap_count}, "
            f"split_scaffold_overlap={manifest.split_summary.scaffold_overlap_count}, "
            f"holdout_train_drug_overlap={holdout_drug_overlap}, "
            f"holdout_train_scaffold_overlap={holdout_scaffold_overlap})."
        )
    if combined_metrics.major_recall < min_major_recall:
        failures.append(
            f"Combined safety Major recall {combined_metrics.major_recall:.4f} < "
            f"{min_major_recall:.4f} (missed={combined_metrics.missed_major_pairs})."
        )
    if combined_metrics.macro_f1 < min_macro_f1:
        failures.append(
            f"Combined safety macro-F1 {combined_metrics.macro_f1:.4f} < {min_macro_f1:.4f}."
        )
    if ml_only_metrics.major_recall < min_ml_major_recall:
        failures.append(
            f"Raw ML Major recall {ml_only_metrics.major_recall:.4f} < "
            f"{min_ml_major_recall:.4f} (missed={ml_only_metrics.missed_major_pairs})."
        )

    return MlEvalGateReport(
        dataset_name=dataset.dataset_name,
        artifact_path=str(artifact_path),
        model_name=manifest.model_name,
        model_version=manifest.model_version,
        zero_leakage_verified=zero_leakage_verified,
        holdout_train_drug_overlap=holdout_drug_overlap,
        holdout_train_scaffold_overlap=holdout_scaffold_overlap,
        ml_only_metrics=ml_only_metrics,
        combined_safety_metrics=combined_metrics,
        min_macro_f1_gate=min_macro_f1,
        min_major_recall_gate=min_major_recall,
        min_ml_major_recall_gate=min_ml_major_recall,
        passed=len(failures) == 0,
        failure_reasons=failures,
    )


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Offline ML DDI evaluation gate enforcing macro-F1 and 100% Major recall."
    )
    parser.add_argument(
        "--artifact",
        type=Path,
        default=Path("ml/artifacts/ddi_ensemble.joblib"),
    )
    parser.add_argument(
        "--holdout",
        type=Path,
        default=Path("eval/datasets/major_ddi_holdout.json"),
    )
    parser.add_argument("--min-macro-f1", type=float, default=0.75)
    parser.add_argument("--min-major-recall", type=float, default=1.0)
    parser.add_argument("--min-ml-major-recall", type=float, default=0.70)
    parser.add_argument("--major-threshold", type=float, default=None)
    parser.add_argument("--report-out", type=Path, default=None)
    return parser


def main() -> None:
    args = _build_arg_parser().parse_args()
    report = run_ml_eval_gate(
        artifact_path=args.artifact,
        holdout_path=args.holdout,
        min_macro_f1=args.min_macro_f1,
        min_major_recall=args.min_major_recall,
        min_ml_major_recall=args.min_ml_major_recall,
        major_threshold_override=args.major_threshold,
    )
    output_json = report.model_dump_json(indent=2)
    if args.report_out is not None:
        args.report_out.parent.mkdir(parents=True, exist_ok=True)
        args.report_out.write_text(output_json, encoding="utf-8")
    print(output_json)
    if not report.passed:
        sys.exit(1)


if __name__ == "__main__":
    main()
