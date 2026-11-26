from pathlib import Path

import httpx
import numpy as np
import pytest

from ml.ddi.artifact import load_ddi_artifact, save_ddi_artifact
from ml.ddi.data_prep import (
    PubChemSmilesResolver,
    SmilesCacheStore,
    canonicalize_smiles,
    compute_murcko_scaffold,
    prepare_ddinter_dataset,
)
from ml.ddi.features import DdiPairFeatureExtractor
from ml.ddi.models import LightGbmDdiModel, TorchMlpDdiModel
from ml.ddi.schemas import DdiFeatureConfig
from ml.ddi.splits import scaffold_drug_disjoint_split
from ml.ddi.train import train_and_evaluate

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
DDINTER_SAMPLE_CSV = FIXTURES_DIR / "ddinter_real_sample.csv"
SMILES_SEED_CSV = FIXTURES_DIR / "drug_smiles_seed.csv"


@pytest.mark.asyncio
async def test_pubchem_resolver_caches_and_handles_rate_limits(tmp_path: Path) -> None:
    cache = SmilesCacheStore(tmp_path / "pubchem_cache.sqlite3")
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if "aspirin" in str(request.url):
            if calls == 1:
                return httpx.Response(status_code=429, headers={"Retry-After": "0.01"})
            return httpx.Response(
                status_code=200,
                json={
                    "PropertyTable": {
                        "Properties": [{"CID": 2244, "CanonicalSMILES": "CC(=O)OC1=CC=CC=C1C(=O)O"}]
                    }
                },
            )
        return httpx.Response(status_code=404)

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        resolver = PubChemSmilesResolver(
            cache=cache,
            max_requests_per_second=100.0,
            base_backoff_seconds=0.01,
            http_client=client,
        )
        first = await resolver.resolve_one("Acetylsalicylic Acid")
        assert first.status == "ok"
        assert first.cid == 2244
        assert first.smiles == canonicalize_smiles("CC(=O)Oc1ccccc1C(=O)O")
        assert calls == 2

        cached = await resolver.resolve_one("Aspirin")
        assert cached.smiles == first.smiles
        assert calls == 2

        missing = await resolver.resolve_one("NonexistentCompoundXYZ")
        assert missing.status == "not_found"
        assert missing.smiles is None
        assert calls == 3

        missing_again = await resolver.resolve_one("NonexistentCompoundXYZ")
        assert missing_again.status == "not_found"
        assert calls == 3


def test_rdkit_pair_feature_extractor_is_symmetric_and_validates_smiles() -> None:
    cfg = DdiFeatureConfig(fp_radius=2, fp_bits=256)
    extractor = DdiPairFeatureExtractor(cfg)

    warfarin = "CC(=O)CC(c1ccccc1)c1c(O)c2ccccc2oc1=O"
    aspirin = "CC(=O)Oc1ccccc1C(=O)O"

    vec_ab, tan_ab = extractor.extract_pair(warfarin, aspirin)
    vec_ba, tan_ba = extractor.extract_pair(aspirin, warfarin)

    assert vec_ab.shape == (cfg.total_features,)
    assert len(extractor.feature_names()) == cfg.total_features
    assert np.allclose(vec_ab, vec_ba, atol=1e-6)
    assert tan_ab == pytest.approx(tan_ba, abs=1e-6)
    assert 0.0 <= tan_ab <= 1.0

    _, self_tan = extractor.extract_pair(aspirin, aspirin)
    assert self_tan == pytest.approx(1.0, abs=1e-6)

    with pytest.raises(ValueError, match="RDKit failed"):
        extractor.extract_pair("NOT_A_SMILES", aspirin)


@pytest.mark.asyncio
async def test_data_prep_and_zero_leakage_scaffold_drug_split(tmp_path: Path) -> None:
    prepared_path = tmp_path / "ddi_prepared.parquet"
    cache_db = tmp_path / "smiles_cache.sqlite3"

    df = await prepare_ddinter_dataset(
        input_paths=[DDINTER_SAMPLE_CSV],
        output_path=prepared_path,
        cache_db_path=cache_db,
        smiles_seed_csv=SMILES_SEED_CSV,
        offline=True,
    )
    assert prepared_path.exists()
    assert len(df) >= 180
    assert (df["salt_a"] < df["salt_b"]).all()

    for smiles in df["smiles_a"].unique():
        assert compute_murcko_scaffold(smiles)

    train_df, val_df, test_df, summary = scaffold_drug_disjoint_split(df, seed=42)
    assert summary.train_pairs > 0
    assert summary.val_pairs > 0
    assert summary.test_pairs > 0
    assert summary.drug_overlap_count == 0
    assert summary.scaffold_overlap_count == 0

    train_drugs = set(train_df["salt_a"]) | set(train_df["salt_b"])
    val_drugs = set(val_df["salt_a"]) | set(val_df["salt_b"])
    test_drugs = set(test_df["salt_a"]) | set(test_df["salt_b"])
    assert train_drugs.isdisjoint(val_drugs)
    assert train_drugs.isdisjoint(test_drugs)
    assert val_drugs.isdisjoint(test_drugs)

    train_scaffolds = set(train_df["scaffold_a"]) | set(train_df["scaffold_b"])
    val_scaffolds = set(val_df["scaffold_a"]) | set(val_df["scaffold_b"])
    test_scaffolds = set(test_df["scaffold_a"]) | set(test_df["scaffold_b"])
    assert train_scaffolds.isdisjoint(val_scaffolds)
    assert train_scaffolds.isdisjoint(test_scaffolds)
    assert val_scaffolds.isdisjoint(test_scaffolds)


@pytest.mark.asyncio
async def test_train_mlflow_comparison_and_model_serialization_parity(tmp_path: Path) -> None:
    prepared_path = tmp_path / "ddi_prepared.parquet"
    artifact_path = tmp_path / "ddi_best.joblib"
    mlruns_uri = f"sqlite:///{(tmp_path / 'mlflow.db').as_posix()}"

    df = await prepare_ddinter_dataset(
        input_paths=[DDINTER_SAMPLE_CSV],
        output_path=prepared_path,
        cache_db_path=tmp_path / "smiles_cache.sqlite3",
        smiles_seed_csv=SMILES_SEED_CSV,
        offline=True,
    )

    cfg = DdiFeatureConfig(fp_radius=2, fp_bits=256)
    result = train_and_evaluate(
        dataset_path=prepared_path,
        artifact_output_path=artifact_path,
        mlflow_tracking_uri=mlruns_uri,
        experiment_name="pillscope-ddi-unit-test",
        feature_config=cfg,
        seed=42,
        torch_epochs=10,
    )

    assert artifact_path.exists()
    assert result.winner_name in {"lightgbm", "torch_mlp"}
    assert set(result.all_val_metrics.keys()) == {"lightgbm", "torch_mlp"}
    assert set(result.all_test_metrics.keys()) == {"lightgbm", "torch_mlp"}
    assert result.manifest.split_summary.drug_overlap_count == 0
    assert result.manifest.split_summary.scaffold_overlap_count == 0

    loaded = load_ddi_artifact(artifact_path)
    assert loaded.manifest.model_name == result.winner_name
    assert loaded.manifest.feature_config.fp_bits == 256

    extractor = DdiPairFeatureExtractor(cfg)
    x_sample, _ = extractor.extract_batch(
        list(zip(df["smiles_a"].head(16), df["smiles_b"].head(16), strict=True))
    )
    y_sample = np.asarray([0, 1, 2, 3] * 4, dtype=np.int64)

    models_to_verify: list[LightGbmDdiModel | TorchMlpDdiModel] = [
        LightGbmDdiModel(n_estimators=20, seed=7),
        TorchMlpDdiModel(
            continuous_start=extractor.continuous_feature_start,
            epochs=5,
            seed=7,
        ),
    ]
    for model in models_to_verify:
        model.fit(x_sample, y_sample, x_sample, y_sample)
        probs_before = model.predict_proba(x_sample)
        assert np.allclose(probs_before.sum(axis=1), 1.0, atol=1e-4)

        reloaded_model: LightGbmDdiModel | TorchMlpDdiModel = (
            LightGbmDdiModel.from_payload(model.to_payload())
            if isinstance(model, LightGbmDdiModel)
            else TorchMlpDdiModel.from_payload(model.to_payload())
        )
        probs_after = reloaded_model.predict_proba(x_sample)
        assert np.allclose(probs_before, probs_after, atol=1e-5)

        saved_path = tmp_path / f"{model.model_name}.joblib"
        manifest = result.manifest.model_copy(update={"model_name": model.model_name})
        save_ddi_artifact(saved_path, manifest, model)
        from_disk = load_ddi_artifact(saved_path)
        assert np.allclose(probs_before, from_disk.model.predict_proba(x_sample), atol=1e-5)
