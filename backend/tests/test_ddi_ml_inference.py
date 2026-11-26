from pathlib import Path

import pytest

from backend.app.services.ddi_ml_inference import (
    DdiMlInferenceService,
    DeterministicRuleAlert,
    InvalidSmilesError,
    ModelArtifactNotFoundError,
    merge_deterministic_and_ml_interactions,
)
from ml.ddi.data_prep import prepare_ddinter_dataset
from ml.ddi.schemas import DdiFeatureConfig, DdiPrediction, DrugPairInput
from ml.ddi.train import train_and_evaluate

ML_FIXTURES = Path(__file__).resolve().parents[2] / "ml" / "ddi" / "fixtures"
DDINTER_SAMPLE_CSV = ML_FIXTURES / "ddinter_real_sample.csv"
SMILES_SEED_CSV = ML_FIXTURES / "drug_smiles_seed.csv"

WARFARIN_SMILES = "CC(=O)CC(c1ccccc1)c1c(O)c2ccccc2oc1=O"
ASPIRIN_SMILES = "CC(=O)Oc1ccccc1C(=O)O"
METFORMIN_SMILES = "CN(C)C(=N)NC(=N)N"


@pytest.mark.asyncio
async def test_inference_wrapper_single_batch_order_invariance_and_errors(tmp_path: Path) -> None:
    missing_path = tmp_path / "does_not_exist.joblib"
    with pytest.raises(ModelArtifactNotFoundError):
        DdiMlInferenceService.from_artifact(missing_path)

    prepared_path = tmp_path / "prepared.parquet"
    artifact_path = tmp_path / "ddi_model.joblib"
    await prepare_ddinter_dataset(
        input_paths=[DDINTER_SAMPLE_CSV],
        output_path=prepared_path,
        cache_db_path=tmp_path / "cache.sqlite3",
        smiles_seed_csv=SMILES_SEED_CSV,
        offline=True,
    )
    train_and_evaluate(
        dataset_path=prepared_path,
        artifact_output_path=artifact_path,
        mlflow_tracking_uri=f"sqlite:///{(tmp_path / 'mlflow.db').as_posix()}",
        experiment_name="pillscope-inference-unit-test",
        feature_config=DdiFeatureConfig(fp_radius=2, fp_bits=256),
        seed=42,
        torch_epochs=8,
    )

    service = DdiMlInferenceService.from_artifact(artifact_path)

    pred_ab = service.score_pair(
        DrugPairInput(
            salt_a="Warfarin Sodium",
            smiles_a=WARFARIN_SMILES,
            salt_b="Acetylsalicylic Acid",
            smiles_b=ASPIRIN_SMILES,
        )
    )
    pred_ba = service.score_pair(
        DrugPairInput(
            salt_a="Aspirin",
            smiles_a=ASPIRIN_SMILES,
            salt_b="Warfarin",
            smiles_b=WARFARIN_SMILES,
        )
    )

    assert pred_ab.salt_a == "aspirin"
    assert pred_ab.salt_b == "warfarin"
    assert pred_ba.salt_a == "aspirin"
    assert pred_ba.salt_b == "warfarin"
    assert pred_ab.predicted_severity == pred_ba.predicted_severity
    assert pred_ab.major_probability == pytest.approx(pred_ba.major_probability, abs=1e-5)
    assert pred_ab.tanimoto_similarity == pytest.approx(pred_ba.tanimoto_similarity, abs=1e-5)
    assert sum(pred_ab.probabilities.values()) == pytest.approx(1.0, abs=1e-3)

    batch_preds = service.score_pairs_batch(
        [
            DrugPairInput(
                salt_a="Warfarin",
                smiles_a=WARFARIN_SMILES,
                salt_b="Aspirin",
                smiles_b=ASPIRIN_SMILES,
            ),
            DrugPairInput(
                salt_a="Warfarin",
                smiles_a=WARFARIN_SMILES,
                salt_b="Metformin",
                smiles_b=METFORMIN_SMILES,
            ),
        ]
    )
    assert len(batch_preds) == 2
    assert batch_preds[0].major_probability == pytest.approx(pred_ab.major_probability, abs=1e-5)
    assert service.score_pairs_batch([]) == []

    with pytest.raises(InvalidSmilesError):
        service.score_pair(
            DrugPairInput(
                salt_a="Warfarin",
                smiles_a="INVALID_SMILES_@@@",
                salt_b="Aspirin",
                smiles_b=ASPIRIN_SMILES,
            )
        )


def test_ml_cannot_downgrade_deterministic_major_safety_banner() -> None:
    deterministic_major = DeterministicRuleAlert(
        rule_id="ddi_pairs:001",
        salt_a="warfarin",
        salt_b="aspirin",
        severity="MAJOR",
        mechanism="Additive bleeding risk.",
        management="Avoid concurrent use unless supervised.",
    )
    ml_predicts_none = DdiPrediction(
        salt_a="aspirin",
        salt_b="warfarin",
        predicted_severity="NONE",
        probabilities={"NONE": 0.94, "MINOR": 0.03, "MODERATE": 0.02, "MAJOR": 0.01},
        confidence=0.94,
        major_probability=0.01,
        tanimoto_similarity=0.18,
        model_name="lightgbm",
        model_version="lightgbm-test",
    )
    ml_escalates_novel_pair = DdiPrediction(
        salt_a="amiodarone",
        salt_b="clarithromycin",
        predicted_severity="MODERATE",
        probabilities={"NONE": 0.10, "MINOR": 0.15, "MODERATE": 0.65, "MAJOR": 0.10},
        confidence=0.65,
        major_probability=0.10,
        tanimoto_similarity=0.12,
        model_name="lightgbm",
        model_version="lightgbm-test",
    )

    merged = merge_deterministic_and_ml_interactions(
        deterministic_alerts=[deterministic_major],
        ml_predictions=[ml_predicts_none, ml_escalates_novel_pair],
        has_duplicate_salts=False,
    )

    assert merged.overall_risk == "MAJOR"
    assert merged.safety_banner_locked is True
    assert len(merged.interactions) == 2

    warfarin_aspirin = merged.interactions[0]
    assert (warfarin_aspirin.salt_a, warfarin_aspirin.salt_b) == ("aspirin", "warfarin")
    assert warfarin_aspirin.severity == "MAJOR"
    assert warfarin_aspirin.confidence == 1.0
    assert warfarin_aspirin.source == "hybrid"

    novel_pair = merged.interactions[1]
    assert (novel_pair.salt_a, novel_pair.salt_b) == ("amiodarone", "clarithromycin")
    assert novel_pair.severity == "MODERATE"
    assert novel_pair.source == "ml_model"


def test_ml_can_escalate_unknown_pair_without_breaking_duplicate_lock() -> None:
    ml_major = DdiPrediction(
        salt_a="ketoconazole",
        salt_b="simvastatin",
        predicted_severity="MAJOR",
        probabilities={"NONE": 0.05, "MINOR": 0.05, "MODERATE": 0.15, "MAJOR": 0.75},
        confidence=0.75,
        major_probability=0.75,
        tanimoto_similarity=0.22,
        model_name="torch_mlp",
        model_version="torch_mlp-test",
    )

    merged_no_dup = merge_deterministic_and_ml_interactions(
        deterministic_alerts=[],
        ml_predictions=[ml_major],
        has_duplicate_salts=False,
    )
    assert merged_no_dup.overall_risk == "MAJOR"
    assert merged_no_dup.safety_banner_locked is False
    assert merged_no_dup.interactions[0].source == "ml_model"

    merged_with_dup = merge_deterministic_and_ml_interactions(
        deterministic_alerts=[],
        ml_predictions=[],
        has_duplicate_salts=True,
    )
    assert merged_with_dup.overall_risk == "MAJOR"
    assert merged_with_dup.safety_banner_locked is True
