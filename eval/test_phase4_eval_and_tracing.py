from pathlib import Path
from uuid import uuid4

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from backend.app.api.scans import (
    get_optional_ml_service,
    get_scan_briefing_generator,
    get_scan_embedding_provider,
    get_scan_reranker,
)
from backend.app.core.config import Settings, get_settings
from backend.app.db.models import DdiPair, ScanLog
from backend.app.db.session import get_db_session
from backend.app.main import create_app
from backend.app.schemas.safety import ClinicalSummary, MonographCitation
from backend.app.services.clinical_briefing import (
    BriefingContext,
    LlmTokenUsage,
    build_extractive_grounded_summary,
)
from backend.app.services.ddi_ml_inference import DdiMlInferenceService
from backend.app.services.embeddings import HashingEmbeddingProvider
from backend.app.services.reranker import LexicalCrossReranker
from backend.app.workers.tasks import ingest_monographs_from_file_job
from backend.scripts.ingest_indian_brands import ingest_indian_brands_csv
from eval.benchmarks.ml_eval_gate import run_ml_eval_gate
from eval.rag_eval.citation_verifier import compute_citation_span_support
from eval.rag_eval.rag_eval_gate import run_rag_eval_gate
from eval.tracing.otel import InMemorySpanCollector, configure_telemetry
from ml.ddi.data_prep import prepare_ddinter_dataset
from ml.ddi.schemas import DdiFeatureConfig
from ml.ddi.train import train_and_evaluate

REPO_ROOT = Path(__file__).resolve().parents[1]
HOLDOUT_JSON = REPO_ROOT / "eval" / "datasets" / "major_ddi_holdout.json"
RAG_SUITE_JSON = REPO_ROOT / "eval" / "datasets" / "rag_safety_cases.json"
INDIAN_BRANDS_CSV = REPO_ROOT / "backend" / "tests" / "fixtures" / "indian_brands_sample.csv"
OPENFDA_SAMPLE_JSON = REPO_ROOT / "backend" / "tests" / "fixtures" / "openfda_sample_labels.json"
DDINTER_SAMPLE_CSV = REPO_ROOT / "ml" / "ddi" / "fixtures" / "ddinter_real_sample.csv"
SMILES_SEED_CSV = REPO_ROOT / "ml" / "ddi" / "fixtures" / "drug_smiles_seed.csv"


class _TokenMeteredGenerator:
    provider_name = "gemini"
    model_name = "gemini-2.5-flash"

    def __init__(self) -> None:
        self.last_usage = LlmTokenUsage()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.last_usage = LlmTokenUsage(input_tokens=412, output_tokens=96, total_tokens=508)
        return build_extractive_grounded_summary(context)


@pytest.mark.asyncio
async def test_ml_eval_gate_enforces_major_recall_and_macro_f1(tmp_path: Path) -> None:
    artifact_path = REPO_ROOT / "ml" / "artifacts" / "ddi_ensemble.joblib"
    using_sample_fixture = not artifact_path.exists()
    if using_sample_fixture:
        prepared_path = tmp_path / "prepared.parquet"
        artifact_path = tmp_path / "ddi_ensemble.joblib"
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
            experiment_name="pillscope-eval-gate-test",
            feature_config=DdiFeatureConfig(fp_radius=2, fp_bits=256),
            seed=42,
            torch_epochs=20,
        )

    min_macro_f1 = 0.65 if using_sample_fixture else 0.75
    report = run_ml_eval_gate(
        artifact_path=artifact_path,
        holdout_path=HOLDOUT_JSON,
        min_macro_f1=min_macro_f1,
        min_major_recall=1.0,
        min_ml_major_recall=0.70,
    )
    assert report.passed is True, report.failure_reasons
    assert report.zero_leakage_verified is True
    assert report.combined_safety_metrics.major_recall == 1.0
    assert report.combined_safety_metrics.missed_major_pairs == []
    assert report.combined_safety_metrics.macro_f1 >= min_macro_f1

    impossible_gate = run_ml_eval_gate(
        artifact_path=artifact_path,
        holdout_path=HOLDOUT_JSON,
        min_macro_f1=0.9999,
        min_major_recall=1.0,
        min_ml_major_recall=0.70,
    )
    assert impossible_gate.passed is False
    assert any("macro-F1" in reason for reason in impossible_gate.failure_reasons)


def test_rag_eval_gate_and_citation_span_grounding() -> None:
    report = run_rag_eval_gate(
        dataset_path=RAG_SUITE_JSON,
        judge_backend="deterministic",
        min_faithfulness=0.70,
        min_span_support=0.75,
    )
    assert report.passed is True, report.failure_reasons
    assert report.total_fabricated_citations_leaked == 0
    assert report.adversarial_fabrication_block_rate == 1.0
    assert report.major_override_block_rate == 1.0
    assert report.missing_monograph_abstention_rate == 1.0
    assert report.mean_span_support >= 0.75
    assert report.mean_faithfulness >= 0.70

    cite_id = uuid4()
    citation = MonographCitation(
        chunk_id=cite_id,
        set_id="spl-1",
        canonical_salt="warfarin",
        section_key="drug_interactions",
        section_title="DRUG INTERACTIONS",
        loinc_section_code="34073-7",
        excerpt="Concomitant aspirin increases gastrointestinal bleeding risk and elevates INR.",
        source_url="https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=spl-1",
        rerank_score=0.95,
    )
    ctx = BriefingContext(
        submitted_query="Aspirin",
        resolved_salts=["aspirin"],
        overall_risk="MAJOR",
        safety_banner_locked=True,
        duplicate_salts=[],
        deterministic_alerts=[],
        interactions=[],
        citations=[citation],
    )
    ungrounded = ClinicalSummary(
        headline="Unrelated claim",
        patient_explanation=(
            "Quantum solar flares reverse hepatic cytochrome oxidation causing emerald hair."
        ),
        actionable_next_steps=["Consult clinician."],
        cited_chunk_ids=[cite_id],
    )
    span_score, token_faith = compute_citation_span_support(ungrounded, [citation], ctx)
    assert span_score == 0.0
    assert token_faith < 0.2


@pytest.mark.asyncio
async def test_opentelemetry_records_api_rag_ml_and_llm_token_spans(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
) -> None:
    collector = InMemorySpanCollector()
    configure_telemetry(service_name="pillscope-test", extra_exporter=collector)

    await ingest_indian_brands_csv(db_session, INDIAN_BRANDS_CSV)
    embedder = HashingEmbeddingProvider(dim=768)
    await ingest_monographs_from_file_job(
        {
            "settings": test_settings,
            "session_factory": db_session_factory,
            "embedding_provider": embedder,
        },
        str(OPENFDA_SAMPLE_JSON),
    )
    db_session.add(
        DdiPair(
            salt_a="aspirin",
            salt_b="warfarin",
            salt_a_smiles="CC(=O)Oc1ccccc1C(=O)O",
            salt_b_smiles="CC(=O)CC(c1ccccc1)c1c(O)c2ccccc2oc1=O",
            severity="MAJOR",
            mechanism="Additive bleeding risk.",
            clinical_effect="Severe gastrointestinal hemorrhage.",
            management="Avoid concurrent therapy.",
            sources=["ddinter_2"],
        )
    )
    await db_session.commit()

    artifact_path = REPO_ROOT / "ml" / "artifacts" / "ddi_ensemble.joblib"
    ml_service = (
        DdiMlInferenceService.from_artifact(artifact_path) if artifact_path.exists() else None
    )
    metered_llm = _TokenMeteredGenerator()

    app = create_app(settings=test_settings)

    async def _override_db():  # type: ignore[no-untyped-def]
        async with db_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _override_db
    app.dependency_overrides[get_settings] = lambda: test_settings
    app.dependency_overrides[get_scan_embedding_provider] = lambda: embedder
    app.dependency_overrides[get_scan_reranker] = lambda: LexicalCrossReranker()
    app.dependency_overrides[get_scan_briefing_generator] = lambda: metered_llm
    app.dependency_overrides[get_optional_ml_service] = lambda: ml_service

    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://testserver"
    ) as client:
        reg = await client.post(
            "/api/v1/auth/register",
            json={"email": "otel@pillscope.in", "password": "StrongPassword123!"},
        )
        headers = {"Authorization": f"Bearer {reg.json()['tokens']['access_token']}"}
        warf = await client.post(
            "/api/v1/drugs/resolve", headers=headers, json={"query": "Warf 5 Tablet"}
        )
        await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={"brand_mapping_id": warf.json()["candidates"][0]["brand_mapping_id"]},
        )

        collector.clear()
        sse_res = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={"input_mode": "MANUAL_TEXT", "submitted_query": "Ecosprin 75 Tablet"},
        )
        assert sse_res.status_code == 200
        header_trace_id = sse_res.headers.get("x-trace-id")
        assert header_trace_id is not None and len(header_trace_id) == 32

    scan_row = await db_session.scalar(select(ScanLog).order_by(ScanLog.created_at.desc()).limit(1))
    assert scan_row is not None
    assert scan_row.trace_id == header_trace_id

    span_by_name = {span.name: span for span in collector.spans}
    expected_spans = {
        "HTTP POST /api/v1/scans/analyze",
        "pillscope.scan.analyze",
        "pillscope.scan.deterministic_safety",
        "pillscope.scan.ml_ddi",
        "pillscope.rag.retrieve",
        "pillscope.llm.synthesize",
    }
    assert expected_spans.issubset(set(span_by_name.keys()))

    http_attrs = dict(span_by_name["HTTP POST /api/v1/scans/analyze"].attributes or {})
    assert http_attrs["http.response.status_code"] == 200
    http_dur = http_attrs["pillscope.http.duration_ms"]
    assert isinstance(http_dur, int | float) and http_dur >= 0.0

    rag_attrs = dict(span_by_name["pillscope.rag.retrieve"].attributes or {})
    rag_hits = rag_attrs["pillscope.rag.reranked_hits"]
    rag_dur = rag_attrs["pillscope.rag.duration_ms"]
    assert isinstance(rag_hits, int) and rag_hits >= 1
    assert isinstance(rag_dur, int | float) and rag_dur >= 0.0

    llm_attrs = dict(span_by_name["pillscope.llm.synthesize"].attributes or {})
    assert llm_attrs["gen_ai.system"] == "gemini"
    assert llm_attrs["gen_ai.request.model"] == "gemini-2.5-flash"
    assert llm_attrs["gen_ai.usage.input_tokens"] == 412
    assert llm_attrs["gen_ai.usage.output_tokens"] == 96
    assert llm_attrs["gen_ai.usage.total_tokens"] == 508
    llm_lat = llm_attrs["pillscope.llm.latency_ms"]
    assert isinstance(llm_lat, int | float) and llm_lat >= 0.0
