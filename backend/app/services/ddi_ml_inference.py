from collections.abc import Sequence
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field

from backend.app.core.config import Settings, get_settings
from backend.app.services.drug_normalizer import normalize_canonical_salt
from ml.ddi.artifact import LoadedDdiArtifact, load_ddi_artifact
from ml.ddi.features import DdiPairFeatureExtractor
from ml.ddi.models import apply_safety_thresholds
from ml.ddi.schemas import (
    INDEX_TO_LABEL,
    SEVERITY_LABELS,
    SEVERITY_RANK,
    ArtifactManifest,
    DdiPrediction,
    DrugPairInput,
    SeverityLabel,
)


class ModelArtifactNotFoundError(FileNotFoundError):
    pass


class InvalidSmilesError(ValueError):
    pass


class DeterministicRuleAlert(BaseModel):
    rule_id: str
    salt_a: str
    salt_b: str
    severity: SeverityLabel
    mechanism: str
    management: str
    source: str = "ddinter_2"


class MergedInteraction(BaseModel):
    salt_a: str
    salt_b: str
    severity: SeverityLabel
    confidence: float = Field(ge=0.0, le=1.0)
    source: Literal["deterministic_db", "ml_model", "hybrid"]
    mechanism: str
    management: str
    ml_major_probability: float | None = Field(default=None, ge=0.0, le=1.0)


class SafetyMergeResult(BaseModel):
    overall_risk: SeverityLabel
    safety_banner_locked: bool
    interactions: list[MergedInteraction]


def _canonical_pair_key(salt_a: str, salt_b: str) -> tuple[str, str]:
    norm_a = normalize_canonical_salt(salt_a) or salt_a.strip().lower()
    norm_b = normalize_canonical_salt(salt_b) or salt_b.strip().lower()
    return (norm_a, norm_b) if norm_a <= norm_b else (norm_b, norm_a)


class DdiMlInferenceService:
    def __init__(
        self,
        loaded_artifact: LoadedDdiArtifact,
        *,
        major_threshold_override: float | None = None,
        moderate_threshold_override: float | None = None,
    ) -> None:
        self._manifest: ArtifactManifest = loaded_artifact.manifest
        self._model = loaded_artifact.model
        self._extractor = DdiPairFeatureExtractor(self._manifest.feature_config)
        self._major_threshold = (
            major_threshold_override
            if major_threshold_override is not None
            else self._manifest.major_threshold
        )
        self._moderate_threshold = (
            moderate_threshold_override
            if moderate_threshold_override is not None
            else self._manifest.moderate_threshold
        )

    @classmethod
    def from_artifact(
        cls,
        artifact_path: Path,
        *,
        major_threshold_override: float | None = None,
        moderate_threshold_override: float | None = None,
    ) -> "DdiMlInferenceService":
        try:
            loaded = load_ddi_artifact(artifact_path)
        except FileNotFoundError as exc:
            raise ModelArtifactNotFoundError(str(exc)) from exc
        return cls(
            loaded_artifact=loaded,
            major_threshold_override=major_threshold_override,
            moderate_threshold_override=moderate_threshold_override,
        )

    @property
    def manifest(self) -> ArtifactManifest:
        return self._manifest

    def score_pairs_batch(self, pairs: Sequence[DrugPairInput]) -> list[DdiPrediction]:
        if not pairs:
            return []

        canonical_names: list[tuple[str, str]] = []
        smiles_pairs: list[tuple[str, str]] = []
        for pair in pairs:
            salt_a, salt_b = _canonical_pair_key(pair.salt_a, pair.salt_b)
            canonical_names.append((salt_a, salt_b))
            smiles_pairs.append((pair.smiles_a, pair.smiles_b))

        try:
            features, tanimotos = self._extractor.extract_batch(smiles_pairs)
        except ValueError as exc:
            raise InvalidSmilesError(str(exc)) from exc

        probs = self._model.predict_proba(features)
        pred_indices = apply_safety_thresholds(
            probs=probs,
            major_threshold=self._major_threshold,
            moderate_threshold=self._moderate_threshold,
        )

        predictions: list[DdiPrediction] = []
        for idx, (salt_a, salt_b) in enumerate(canonical_names):
            row_probs = probs[idx]
            pred_idx = int(pred_indices[idx])
            pred_label = INDEX_TO_LABEL[pred_idx]
            prob_map: dict[SeverityLabel, float] = {
                label: round(float(row_probs[col]), 6) for col, label in enumerate(SEVERITY_LABELS)
            }
            predictions.append(
                DdiPrediction(
                    salt_a=salt_a,
                    salt_b=salt_b,
                    predicted_severity=pred_label,
                    probabilities=prob_map,
                    confidence=round(float(row_probs[pred_idx]), 6),
                    major_probability=round(float(row_probs[3]), 6),
                    tanimoto_similarity=round(float(tanimotos[idx]), 6),
                    model_name=self._manifest.model_name,
                    model_version=self._manifest.model_version,
                )
            )
        return predictions

    def score_pair(self, pair: DrugPairInput) -> DdiPrediction:
        return self.score_pairs_batch([pair])[0]


def merge_deterministic_and_ml_interactions(
    *,
    deterministic_alerts: Sequence[DeterministicRuleAlert],
    ml_predictions: Sequence[DdiPrediction],
    has_duplicate_salts: bool = False,
) -> SafetyMergeResult:
    det_by_key: dict[tuple[str, str], DeterministicRuleAlert] = {}
    for alert in deterministic_alerts:
        key = _canonical_pair_key(alert.salt_a, alert.salt_b)
        existing = det_by_key.get(key)
        if existing is None or SEVERITY_RANK[alert.severity] > SEVERITY_RANK[existing.severity]:
            det_by_key[key] = alert

    ml_by_key: dict[tuple[str, str], DdiPrediction] = {
        _canonical_pair_key(pred.salt_a, pred.salt_b): pred for pred in ml_predictions
    }

    all_keys = sorted(set(det_by_key.keys()) | set(ml_by_key.keys()))
    merged: list[MergedInteraction] = []

    banner_locked = has_duplicate_salts
    highest_rank = SEVERITY_RANK["MAJOR"] if has_duplicate_salts else SEVERITY_RANK["NONE"]

    for key in all_keys:
        det = det_by_key.get(key)
        ml_pred = ml_by_key.get(key)

        if det is not None:
            if det.severity == "MAJOR":
                banner_locked = True
            final_severity: SeverityLabel = det.severity
            if (
                ml_pred is not None
                and SEVERITY_RANK[ml_pred.predicted_severity] > SEVERITY_RANK[det.severity]
            ):
                final_severity = ml_pred.predicted_severity
            highest_rank = max(highest_rank, SEVERITY_RANK[final_severity])
            merged.append(
                MergedInteraction(
                    salt_a=key[0],
                    salt_b=key[1],
                    severity=final_severity,
                    confidence=1.0 if det.severity == final_severity else ml_pred.confidence,  # type: ignore[union-attr]
                    source="hybrid" if ml_pred is not None else "deterministic_db",
                    mechanism=det.mechanism,
                    management=det.management,
                    ml_major_probability=(
                        ml_pred.major_probability if ml_pred is not None else None
                    ),
                )
            )
            continue

        if ml_pred is not None and ml_pred.predicted_severity != "NONE":
            highest_rank = max(highest_rank, SEVERITY_RANK[ml_pred.predicted_severity])
            merged.append(
                MergedInteraction(
                    salt_a=key[0],
                    salt_b=key[1],
                    severity=ml_pred.predicted_severity,
                    confidence=ml_pred.confidence,
                    source="ml_model",
                    mechanism=(
                        f"Predicted molecular interaction risk by {ml_pred.model_name} "
                        f"({ml_pred.model_version}; P(Major)={ml_pred.major_probability:.2f})."
                    ),
                    management=(
                        "Verify concurrent use with a pharmacist or prescribing clinician."
                    ),
                    ml_major_probability=ml_pred.major_probability,
                )
            )

    merged.sort(key=lambda item: (-SEVERITY_RANK[item.severity], item.salt_a, item.salt_b))
    overall_risk = INDEX_TO_LABEL[highest_rank]
    return SafetyMergeResult(
        overall_risk=overall_risk,
        safety_banner_locked=banner_locked,
        interactions=merged,
    )


@lru_cache(maxsize=4)
def _cached_inference_service(
    artifact_path_str: str,
    major_override: float | None,
    moderate_override: float | None,
) -> DdiMlInferenceService:
    return DdiMlInferenceService.from_artifact(
        Path(artifact_path_str),
        major_threshold_override=major_override,
        moderate_threshold_override=moderate_override,
    )


def get_ddi_inference_service(settings: Settings | None = None) -> DdiMlInferenceService:
    resolved = settings or get_settings()
    return _cached_inference_service(
        resolved.ddi_model_artifact_path,
        resolved.ddi_ml_major_threshold,
        resolved.ddi_ml_moderate_threshold,
    )
