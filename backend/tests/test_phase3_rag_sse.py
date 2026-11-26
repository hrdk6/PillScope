import json
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import httpx
import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from backend.app.api.scans import (
    get_optional_ml_service,
    get_scan_briefing_generator,
    get_scan_embedding_provider,
    get_scan_reranker,
)
from backend.app.core.config import Settings, get_settings
from backend.app.db.models import DdiPair, MonographChunk
from backend.app.db.session import get_db_session
from backend.app.main import create_app
from backend.app.schemas.safety import (
    ClinicalSummary,
    DeterministicDdiAlert,
    MonographCitation,
)
from backend.app.services.clinical_briefing import (
    BriefingContext,
    DeterministicGroundedBriefingGenerator,
    LlmBriefingGenerator,
    verify_clinical_briefing,
)
from backend.app.services.ddi_ml_inference import DdiMlInferenceService
from backend.app.services.embeddings import HashingEmbeddingProvider
from backend.app.services.hybrid_rag import HybridRagRetriever
from backend.app.services.monograph_ingestion import (
    parse_dailymed_spl_xml,
    parse_openfda_label_record,
)
from backend.app.services.reranker import LexicalCrossReranker
from backend.app.workers.tasks import (
    ingest_drug_monographs_job,
    ingest_monographs_from_file_job,
)
from backend.scripts.ingest_indian_brands import ingest_indian_brands_csv

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
INDIAN_BRANDS_CSV = FIXTURES_DIR / "indian_brands_sample.csv"
OPENFDA_SAMPLE_JSON = FIXTURES_DIR / "openfda_sample_labels.json"
TRAINED_ARTIFACT = Path(__file__).resolve().parents[2] / "ml" / "artifacts" / "ddi_ensemble.joblib"

WARFARIN_SMILES = "CC(=O)CC(c1ccccc1)c1c(O)c2ccccc2oc1=O"
ASPIRIN_SMILES = "CC(=O)Oc1ccccc1C(=O)O"


def _parse_sse_stream(raw_body: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    normalized = raw_body.replace("\r\n", "\n")
    for block in normalized.split("\n\n"):
        lines = [line for line in block.split("\n") if line.strip() and not line.startswith(":")]
        if not lines:
            continue
        event_id: str | None = None
        event_name: str | None = None
        data_lines: list[str] = []
        for line in lines:
            if line.startswith("id:"):
                event_id = line[3:].strip()
            elif line.startswith("event:"):
                event_name = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].strip())
        if event_name is not None and data_lines:
            events.append(
                {
                    "id": event_id,
                    "event": event_name,
                    "data": json.loads("\n".join(data_lines)),
                }
            )
    return events


class _AdversarialHallucinatingGenerator:
    """Test double simulating an LLM that fabricates citations and tries to downgrade Major DDI."""

    def __init__(self, fabricated_uuid: UUID) -> None:
        self._fabricated_uuid = fabricated_uuid

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        return ClinicalSummary(
            headline="Safe to take together with low risk.",
            patient_explanation=(
                f"No major interactions detected for {context.submitted_query}. "
                f"See fabricated monograph {self._fabricated_uuid}."
            ),
            actionable_next_steps=["Continue taking both without worry."],
            cited_chunk_ids=[self._fabricated_uuid],
            abstained=False,
        )


def _build_phase3_app(
    *,
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    briefing_generator: LlmBriefingGenerator | None = None,
    ml_service: DdiMlInferenceService | None = None,
) -> AsyncClient:
    app = create_app(settings=test_settings)
    embedder = HashingEmbeddingProvider(dim=768)
    reranker = LexicalCrossReranker()
    generator = briefing_generator or DeterministicGroundedBriefingGenerator()

    async def _override_db_session():  # type: ignore[no-untyped-def]
        async with db_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _override_db_session
    app.dependency_overrides[get_settings] = lambda: test_settings
    app.dependency_overrides[get_scan_embedding_provider] = lambda: embedder
    app.dependency_overrides[get_scan_reranker] = lambda: reranker
    app.dependency_overrides[get_scan_briefing_generator] = lambda: generator
    app.dependency_overrides[get_optional_ml_service] = lambda: ml_service

    return AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver")


async def _register_user(client: AsyncClient, email: str) -> dict[str, str]:
    res = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "StrongPassword123!", "display_name": "Phase 3 Tester"},
    )
    assert res.status_code == 201, res.text
    token = res.json()["tokens"]["access_token"]
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_arq_monograph_ingestion_spl_parser_and_hybrid_rag(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
) -> None:
    embedder = HashingEmbeddingProvider(dim=768)
    reranker = LexicalCrossReranker()

    ctx: dict[str, Any] = {
        "settings": test_settings,
        "session_factory": db_session_factory,
        "embedding_provider": embedder,
    }
    first = await ingest_monographs_from_file_job(ctx, str(OPENFDA_SAMPLE_JSON))
    assert first["labels_processed"] == 3
    assert first["chunks_upserted"] == 9

    second = await ingest_monographs_from_file_job(ctx, str(OPENFDA_SAMPLE_JSON))
    assert second["chunks_upserted"] == 9

    total_chunks = await db_session.scalar(select(func.count(MonographChunk.id)))
    assert total_chunks == 9

    spl_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
    <document xmlns="urn:hl7-org:v3">
      <setId root="spl-clopidogrel-999"/>
      <versionNumber value="3"/>
      <component>
        <structuredBody>
          <component>
            <section>
              <code code="34073-7" displayName="DRUG INTERACTIONS SECTION"/>
              <text>Omeprazole inhibits CYP2C19 and reduces active metabolite of clopidogrel.</text>
            </section>
          </component>
          <component>
            <section>
              <code code="43685-7" displayName="WARNINGS AND PRECAUTIONS"/>
              <text>Clopidogrel increases risk of bleeding.</text>
            </section>
          </component>
          <component>
            <section>
              <code code="34068-7" displayName="DOSAGE AND ADMINISTRATION"/>
              <text>Administer 75 mg orally once daily.</text>
            </section>
          </component>
        </structuredBody>
      </component>
    </document>
    """
    spl_chunks = parse_dailymed_spl_xml(spl_xml, canonical_salt="Clopidogrel Bisulfate")
    assert len(spl_chunks) == 3
    assert {c.section_key for c in spl_chunks} == {
        "drug_interactions",
        "warnings",
        "dosage_and_administration",
    }
    assert all(c.canonical_salt == "clopidogrel" for c in spl_chunks)

    def _mock_openfda_handler(request: httpx.Request) -> httpx.Response:
        if "spls.json" in str(request.url):
            return httpx.Response(200, json={"data": [{"setid": "spl-clopidogrel-999"}]})
        if "spl-clopidogrel-999.xml" in str(request.url):
            return httpx.Response(200, content=spl_xml)
        return httpx.Response(404)

    async with httpx.AsyncClient(transport=httpx.MockTransport(_mock_openfda_handler)) as mock_http:
        ctx_http = {**ctx, "http_client": mock_http}
        job_res = await ingest_drug_monographs_job(ctx_http, ["clopidogrel"], source="auto")
        assert job_res["labels_processed"] == 1
        assert job_res["chunks_upserted"] == 3

    retriever = HybridRagRetriever(
        embedding_provider=embedder,
        reranker=reranker,
        min_dense_similarity=0.05,
    )
    hits = await retriever.retrieve(
        session=db_session,
        query="warfarin aspirin drug interactions gastrointestinal bleeding",
        involved_salts=["warfarin", "aspirin"],
        top_k=4,
    )
    assert len(hits) >= 2
    assert hits[0].section_key == "drug_interactions"
    assert hits[0].loinc_section_code == "34073-7"
    assert hits[0].lexical_score is not None and hits[0].lexical_score > 0.0
    assert hits[0].dense_score is not None and hits[0].dense_score > 0.0
    assert hits[0].rrf_score is not None and hits[0].rrf_score > 0.0
    assert hits[0].rerank_score > 0.0

    missing_hits = await retriever.retrieve(
        session=db_session,
        query="metformin telmisartan interactions",
        involved_salts=["metformin", "telmisartan"],
        top_k=4,
    )
    assert missing_hits == []

    async with _build_phase3_app(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
    ) as client:
        denied = await client.post(
            "/api/v1/internal/jobs/ingest",
            headers={"X-Admin-API-Key": "wrong-key"},
            json={"drug_names": ["warfarin"], "source": "auto"},
        )
        assert denied.status_code == 403

        queued = await client.post(
            "/api/v1/internal/jobs/ingest",
            headers={"X-Admin-API-Key": test_settings.internal_admin_api_key},
            json={"drug_names": ["warfarin"], "source": "openfda"},
        )
        assert queued.status_code == 202, queued.text
        assert queued.json()["status"] == "queued"
        assert queued.json()["job_id"]


@pytest.mark.asyncio
async def test_sse_event_order_deterministic_major_override_and_citation_blocking(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
) -> None:
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
            salt_a_smiles=ASPIRIN_SMILES,
            salt_b_smiles=WARFARIN_SMILES,
            severity="MAJOR",
            mechanism="Additive antiplatelet and vitamin K antagonist anticoagulation.",
            clinical_effect="High risk of severe gastrointestinal and intracranial hemorrhage.",
            management=(
                "Avoid concurrent use unless specifically supervised with frequent INR checks."
            ),
            sources=["ddinter_2"],
        )
    )
    await db_session.commit()

    ml_service = (
        DdiMlInferenceService.from_artifact(TRAINED_ARTIFACT) if TRAINED_ARTIFACT.exists() else None
    )
    fabricated_uuid = UUID("00000000-0000-0000-0000-deadbeefcafe")
    adversarial_llm = _AdversarialHallucinatingGenerator(fabricated_uuid=fabricated_uuid)

    async with _build_phase3_app(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
        briefing_generator=adversarial_llm,
        ml_service=ml_service,
    ) as client:
        headers = await _register_user(client, "sse_major@pillscope.in")

        warf_lookup = await client.post(
            "/api/v1/drugs/resolve",
            headers=headers,
            json={"query": "Warf 5 Tablet"},
        )
        warf_id = warf_lookup.json()["candidates"][0]["brand_mapping_id"]
        add_res = await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={"brand_mapping_id": warf_id, "dosage_schedule": "1 tablet OD"},
        )
        assert add_res.status_code == 201

        sse_res = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={"input_mode": "MANUAL_TEXT", "submitted_query": "Ecosprin 75 Tablet"},
        )
        assert sse_res.status_code == 200, sse_res.text
        assert "text/event-stream" in sse_res.headers["content-type"]

        events = _parse_sse_stream(sse_res.text)
        event_names = [e["event"] for e in events]
        event_ids = [e["id"] for e in events]
        assert event_names == [
            "deterministic_safety",
            "interactions",
            "citations",
            "clinical_summary",
            "complete",
        ]
        assert event_ids == ["1", "2", "3", "4", "5"]

        ev_det, ev_int, ev_cit, ev_sum, ev_done = [e["data"] for e in events]

        assert ev_det["resolved_salts"] == ["aspirin"]
        assert ev_det["overall_risk"] == "MAJOR"
        assert ev_det["safety_banner_locked"] is True
        assert len(ev_det["deterministic_alerts"]) == 1
        assert ev_det["deterministic_alerts"][0]["severity"] == "MAJOR"

        assert ev_int["overall_risk"] == "MAJOR"
        assert ev_int["safety_banner_locked"] is True
        assert len(ev_int["interactions"]) == 1
        if ml_service is not None:
            assert ev_int["ml_scored_pair_count"] == 1
            assert ev_int["interactions"][0]["source"] == "hybrid"
            assert ev_int["interactions"][0]["ml_major_probability"] is not None

        assert len(ev_cit["citations"]) >= 1
        valid_db_chunk_ids = {c["chunk_id"] for c in ev_cit["citations"]}

        assert ev_sum["overall_risk"] == "MAJOR"
        assert ev_sum["safety_banner_locked"] is True
        assert ev_sum["citation_verified"] is True
        assert str(fabricated_uuid) in ev_sum["blocked_citation_ids"]
        assert ev_sum["clinical_summary"]["abstained"] is False
        assert "safe to take together" not in ev_sum["clinical_summary"]["headline"].lower()
        assert len(ev_sum["clinical_summary"]["cited_chunk_ids"]) >= 1
        assert set(ev_sum["clinical_summary"]["cited_chunk_ids"]).issubset(valid_db_chunk_ids)

        assert ev_done["overall_risk"] == "MAJOR"
        assert ev_done["safety_banner_locked"] is True
        assert ev_done["citation_verified"] is True
        assert ev_done["latency_ms"] >= 1

        persisted = await client.get(f"/api/v1/scans/{ev_done['scan_id']}", headers=headers)
        assert persisted.status_code == 200
        assert persisted.json()["safety_banner_locked"] is True
        assert persisted.json()["overall_risk"] == "MAJOR"
        assert persisted.json()["citation_verified"] is True


@pytest.mark.asyncio
async def test_sse_duplicate_salt_lock_and_missing_monograph_abstention(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
) -> None:
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

    async with _build_phase3_app(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
    ) as client:
        headers = await _register_user(client, "abstain_and_dup@pillscope.in")

        glycomet_lookup = await client.post(
            "/api/v1/drugs/resolve",
            headers=headers,
            json={"query": "Glycomet 500 SR Tablet"},
        )
        glycomet_id = glycomet_lookup.json()["candidates"][0]["brand_mapping_id"]
        await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={"brand_mapping_id": glycomet_id},
        )

        abstain_sse = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={"input_mode": "MANUAL_TEXT", "submitted_query": "Telma 40 Tablet"},
        )
        assert abstain_sse.status_code == 200
        abstain_events = _parse_sse_stream(abstain_sse.text)
        assert [e["event"] for e in abstain_events] == [
            "deterministic_safety",
            "interactions",
            "citations",
            "clinical_summary",
            "complete",
        ]
        assert abstain_events[2]["data"]["citations"] == []
        summary_data = abstain_events[3]["data"]
        assert summary_data["clinical_summary"]["abstained"] is True
        assert summary_data["clinical_summary"]["abstention_reason"] == "NO_INDEXED_MONOGRAPHS"
        assert summary_data["clinical_summary"]["cited_chunk_ids"] == []
        assert summary_data["citations"] == []
        assert summary_data["citation_verified"] is True

        mox_lookup = await client.post(
            "/api/v1/drugs/resolve",
            headers=headers,
            json={"query": "Mox 500mg Capsule"},
        )
        await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={"brand_mapping_id": mox_lookup.json()["candidates"][0]["brand_mapping_id"]},
        )

        dup_sse = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={"input_mode": "CAMERA_OCR", "submitted_query": "Augmentin 625 Duo"},
        )
        assert dup_sse.status_code == 200
        dup_events = _parse_sse_stream(dup_sse.text)
        assert dup_events[0]["event"] == "deterministic_safety"
        assert dup_events[0]["data"]["safety_banner_locked"] is True
        assert dup_events[0]["data"]["overall_risk"] == "MAJOR"
        assert dup_events[0]["data"]["duplicate_salts"][0]["canonical_salt"] == "amoxicillin"
        assert dup_events[-1]["data"]["safety_banner_locked"] is True
        assert dup_events[-1]["data"]["overall_risk"] == "MAJOR"


def test_post_generation_verifier_preserves_valid_and_blocks_inline_fabrication() -> None:
    real_chunk_id = uuid4()
    fake_chunk_id = uuid4()
    citation = MonographCitation(
        chunk_id=real_chunk_id,
        set_id="set-warfarin-1",
        canonical_salt="warfarin",
        section_key="drug_interactions",
        section_title="DRUG INTERACTIONS",
        loinc_section_code="34073-7",
        excerpt="Concomitant aspirin increases risk of severe gastrointestinal bleeding.",
        source_url="https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=set-warfarin-1",
        rerank_score=0.95,
    )
    context = BriefingContext(
        submitted_query="Aspirin 75",
        resolved_salts=["aspirin"],
        overall_risk="MAJOR",
        safety_banner_locked=True,
        duplicate_salts=[],
        deterministic_alerts=[
            DeterministicDdiAlert(
                rule_id="ddi_pairs:1",
                salt_a="aspirin",
                salt_b="warfarin",
                severity="MAJOR",
                mechanism="Additive bleeding.",
                clinical_effect="Major bleeding risk.",
                management="Consult physician immediately.",
                sources=["ddinter_2"],
            )
        ],
        interactions=[],
        citations=[citation],
    )

    valid_raw = ClinicalSummary(
        headline="Major bleeding risk with Aspirin and Warfarin.",
        patient_explanation="Combining aspirin with warfarin markedly raises bleeding risk.",
        actionable_next_steps=["Contact your physician before taking aspirin."],
        cited_chunk_ids=[real_chunk_id],
    )
    verified_ok = verify_clinical_briefing(valid_raw, context)
    assert verified_ok.citation_verified is True
    assert verified_ok.blocked_citation_ids == []
    assert verified_ok.summary.headline == valid_raw.headline
    assert verified_ok.summary.cited_chunk_ids == [real_chunk_id]

    inline_fake = ClinicalSummary(
        headline="Major bleeding risk with Aspirin and Warfarin.",
        patient_explanation=f"Supported by unindexed citation {fake_chunk_id}.",
        actionable_next_steps=["Consult clinician."],
        cited_chunk_ids=[real_chunk_id],
    )
    verified_blocked = verify_clinical_briefing(inline_fake, context)
    assert verified_blocked.citation_verified is True
    assert str(fake_chunk_id) in verified_blocked.blocked_citation_ids
    assert str(fake_chunk_id) not in verified_blocked.summary.patient_explanation
    assert verified_blocked.summary.cited_chunk_ids == [real_chunk_id]


class _InvalidApiKeyLlmGenerator:
    provider_name = "gemini"
    model_name = "gemini-2.5-flash"

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        req = httpx.Request("POST", "https://generativelanguage.googleapis.com/v1beta/models/x")
        res = httpx.Response(403, request=req, json={"error": {"message": "API key not valid"}})
        raise httpx.HTTPStatusError("403 Forbidden: API key not valid", request=req, response=res)


class _TimeoutLlmGenerator:
    provider_name = "gemini"
    model_name = "gemini-2.5-flash"

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        raise httpx.ReadTimeout("Upstream LLM timed out after 20s")


@pytest.mark.asyncio
async def test_safety_decoupling_streams_locked_red_banner_on_invalid_llm_key_and_timeout(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
) -> None:
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
            salt_a_smiles=ASPIRIN_SMILES,
            salt_b_smiles=WARFARIN_SMILES,
            severity="MAJOR",
            mechanism="Additive antiplatelet and anticoagulant bleeding risk.",
            clinical_effect="Severe gastrointestinal hemorrhage.",
            management="Avoid concurrent use unless supervised with frequent INR checks.",
            sources=["ddinter_2"],
        )
    )
    await db_session.commit()

    # 1. Invalid LLM API key (HTTP 403) -> Major DDI still locks red deterministic banner
    async with _build_phase3_app(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
        briefing_generator=_InvalidApiKeyLlmGenerator(),
    ) as client:
        headers = await _register_user(client, "invalid_key_safety@pillscope.in")
        warf = await client.post(
            "/api/v1/drugs/resolve", headers=headers, json={"query": "Warf 5 Tablet"}
        )
        await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={"brand_mapping_id": warf.json()["candidates"][0]["brand_mapping_id"]},
        )

        res = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={"input_mode": "MANUAL_TEXT", "submitted_query": "Ecosprin 75 Tablet"},
        )
        assert res.status_code == 200
        events = _parse_sse_stream(res.text)
        assert [e["event"] for e in events] == [
            "deterministic_safety",
            "interactions",
            "citations",
            "clinical_summary",
            "complete",
        ]
        assert events[0]["data"]["safety_banner_locked"] is True
        assert events[0]["data"]["overall_risk"] == "MAJOR"
        assert events[0]["data"]["deterministic_alerts"][0]["severity"] == "MAJOR"
        assert events[-1]["data"]["safety_banner_locked"] is True
        assert events[-1]["data"]["overall_risk"] == "MAJOR"
        assert events[-1]["data"]["citation_verified"] is True

    # 2. LLM timeout (httpx.ReadTimeout) -> Duplicate-salt alert still locks red banner
    async with _build_phase3_app(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
        briefing_generator=_TimeoutLlmGenerator(),
    ) as client:
        headers = await _register_user(client, "timeout_safety@pillscope.in")
        mox = await client.post(
            "/api/v1/drugs/resolve", headers=headers, json={"query": "Mox 500mg Capsule"}
        )
        await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={"brand_mapping_id": mox.json()["candidates"][0]["brand_mapping_id"]},
        )

        res = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={"input_mode": "CAMERA_OCR", "submitted_query": "Augmentin 625 Duo"},
        )
        assert res.status_code == 200
        events = _parse_sse_stream(res.text)
        assert events[0]["event"] == "deterministic_safety"
        assert events[0]["data"]["safety_banner_locked"] is True
        assert events[0]["data"]["overall_risk"] == "MAJOR"
        assert events[0]["data"]["duplicate_salts"][0]["canonical_salt"] == "amoxicillin"
        assert events[-1]["event"] == "complete"
        assert events[-1]["data"]["safety_banner_locked"] is True
        assert events[-1]["data"]["overall_risk"] == "MAJOR"


def test_real_openfda_combo_generic_name_and_nested_dailymed_spl_parsing() -> None:
    combo_openfda = {
        "set_id": "00b86913-50c8-443f-8467-f4f499d358af",
        "version": "4",
        "openfda": {"generic_name": ["AMOXICILLIN AND CLAVULANATE POTASSIUM"]},
        "drug_interactions": ["Probenecid decreases renal tubular secretion of amoxicillin."],
    }
    chunks = parse_openfda_label_record(combo_openfda, fallback_drug_name="amoxicillin")
    assert len(chunks) == 1
    assert chunks[0].canonical_salt == "amoxicillin"

    nested_spl_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
    <document xmlns="urn:hl7-org:v3">
      <setId root="nested-warfarin-spl"/>
      <versionNumber value="2"/>
      <component>
        <structuredBody>
          <component>
            <section>
              <code code="34073-7" displayName="DRUG INTERACTIONS"/>
              <component>
                <section>
                  <title>7.1 CYP2C9 Inhibitors</title>
                  <text>Fluconazole and amiodarone increase warfarin INR and bleeding risk.</text>
                </section>
              </component>
            </section>
          </component>
        </structuredBody>
      </component>
    </document>
    """
    spl_chunks = parse_dailymed_spl_xml(nested_spl_xml, canonical_salt="warfarin")
    assert len(spl_chunks) == 1
    assert spl_chunks[0].section_key == "drug_interactions"
    assert "Fluconazole" in spl_chunks[0].content
