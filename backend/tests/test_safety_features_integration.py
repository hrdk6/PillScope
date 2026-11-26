import json
import zipfile
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

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
from backend.app.db.models import DdiPair, Medication, RecallAlert, ScanLog
from backend.app.db.session import get_db_session
from backend.app.main import create_app
from backend.app.schemas.safety import ClinicalSummary
from backend.app.services.clinical_briefing import (
    BriefingContext,
    DeterministicGroundedBriefingGenerator,
    LlmBriefingGenerator,
)
from backend.app.services.embeddings import HashingEmbeddingProvider
from backend.app.services.monograph_ingestion import (
    parse_openfda_label_record,
    upsert_monograph_chunks,
)
from backend.app.services.reranker import LexicalCrossReranker
from backend.app.workers.tasks import (
    ingest_fda_recalls_from_file_job,
    ingest_monographs_from_file_job,
    sync_fda_recalls_job,
)
from backend.scripts.ingest_indian_brands import ingest_indian_brands_csv

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
INDIAN_BRANDS_CSV = FIXTURES_DIR / "indian_brands_sample.csv"
OPENFDA_SAMPLE_JSON = FIXTURES_DIR / "openfda_sample_labels.json"
OPENFDA_RECALLS_JSON = FIXTURES_DIR / "openfda_recalls_sample.json"


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


class _CountingBriefingGenerator:
    provider_name = "deterministic_counting"
    model_name = "counting-v1"

    def __init__(self) -> None:
        self.call_count = 0
        self._inner = DeterministicGroundedBriefingGenerator()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.call_count += 1
        return await self._inner.generate(context)


class _DowngradingAllergyLlmGenerator:
    provider_name = "adversarial"
    model_name = "downgrade-v1"

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        cited = [context.citations[0].chunk_id] if context.citations else []
        return ClinicalSummary(
            headline="Safe to take together with low risk.",
            patient_explanation="No safety concerns found for this medication.",
            actionable_next_steps=["Proceed as directed."],
            cited_chunk_ids=cited,
        )


def _build_test_client(
    *,
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    briefing_generator: LlmBriefingGenerator | None = None,
) -> AsyncClient:
    app = create_app(settings=test_settings)
    embedder = HashingEmbeddingProvider(dim=768)
    reranker = LexicalCrossReranker()
    generator = briefing_generator or DeterministicGroundedBriefingGenerator()

    async def _override_db_session() -> AsyncIterator[AsyncSession]:
        async with db_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _override_db_session
    app.dependency_overrides[get_settings] = lambda: test_settings
    app.dependency_overrides[get_scan_embedding_provider] = lambda: embedder
    app.dependency_overrides[get_scan_reranker] = lambda: reranker
    app.dependency_overrides[get_scan_briefing_generator] = lambda: generator
    app.dependency_overrides[get_optional_ml_service] = lambda: None

    return AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver")


async def _register(client: AsyncClient, email: str, name: str = "User") -> dict[str, str]:
    res = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "StrongPassword123!", "display_name": name},
    )
    assert res.status_code == 201, res.text
    token = res.json()["tokens"]["access_token"]
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_multi_profile_crud_isolation_and_allergy_overrides(
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

    async with _build_test_client(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
        briefing_generator=_DowngradingAllergyLlmGenerator(),
    ) as client:
        owner_headers = await _register(client, "family_owner@pillscope.in", "Aarav")
        other_headers = await _register(client, "other_user@pillscope.in", "Priya")

        profiles_res = await client.get("/api/v1/profiles", headers=owner_headers)
        assert profiles_res.status_code == 200
        initial_profiles = profiles_res.json()
        assert len(initial_profiles) == 1
        default_profile_id = initial_profiles[0]["id"]
        assert initial_profiles[0]["is_default"] is True
        assert initial_profiles[0]["relationship"] == "self"

        create_mom = await client.post(
            "/api/v1/profiles",
            headers=owner_headers,
            json={
                "name": "Mom",
                "relationship": "parent",
                "allergies_json": ["Penicillin", "NSAIDs"],
                "notes": "Severe rash to penicillin antibiotics",
            },
        )
        assert create_mom.status_code == 201, create_mom.text
        mom_profile = create_mom.json()
        mom_profile_id = mom_profile["id"]
        assert mom_profile["is_default"] is False
        assert mom_profile["allergies_json"] == ["Penicillin", "NSAIDs"]

        cross_user_get = await client.get(
            f"/api/v1/profiles/{mom_profile_id}",
            headers=other_headers,
        )
        assert cross_user_get.status_code == 404

        cross_user_scan = await client.post(
            "/api/v1/scans/analyze",
            headers=other_headers,
            json={
                "profile_id": mom_profile_id,
                "submitted_query": "Augmentin 625 Duo",
            },
        )
        assert cross_user_scan.status_code == 404

        mox_lookup = await client.post(
            "/api/v1/drugs/resolve",
            headers=owner_headers,
            json={"query": "Mox 500mg Capsule"},
        )
        mox_mapping_id = mox_lookup.json()["candidates"][0]["brand_mapping_id"]

        add_self_mox = await client.post(
            "/api/v1/cabinet/medications",
            headers=owner_headers,
            json={"profile_id": default_profile_id, "brand_mapping_id": mox_mapping_id},
        )
        assert add_self_mox.status_code == 201
        assert add_self_mox.json()["safety_banner_locked"] is False
        assert add_self_mox.json()["medication"]["profile_id"] == default_profile_id

        mom_meds = await client.get(
            "/api/v1/cabinet/medications",
            headers=owner_headers,
            params={"profile_id": mom_profile_id},
        )
        assert mom_meds.status_code == 200
        assert mom_meds.json() == []

        self_dup = await client.post(
            "/api/v1/cabinet/check-duplicates",
            headers=owner_headers,
            json={"profile_id": default_profile_id, "brand_name": "Augmentin 625 Duo"},
        )
        assert self_dup.status_code == 200
        assert self_dup.json()["safety_banner_locked"] is True
        assert len(self_dup.json()["duplicate_salts"]) == 1
        assert self_dup.json()["allergy_alerts"] == []

        mom_check = await client.post(
            "/api/v1/cabinet/check-duplicates",
            headers=owner_headers,
            json={"profile_id": mom_profile_id, "brand_name": "Augmentin 625 Duo"},
        )
        assert mom_check.status_code == 200
        mom_check_body = mom_check.json()
        assert mom_check_body["duplicate_salts"] == []
        assert mom_check_body["safety_banner_locked"] is True
        assert mom_check_body["overall_risk"] == "MAJOR"
        assert len(mom_check_body["allergy_alerts"]) == 1
        assert mom_check_body["allergy_alerts"][0]["canonical_salt"] == "amoxicillin"
        assert mom_check_body["allergy_alerts"][0]["matched_class"] == "penicillin"

        mom_scan = await client.post(
            "/api/v1/scans/analyze",
            headers=owner_headers,
            json={
                "profile_id": mom_profile_id,
                "input_mode": "MANUAL_TEXT",
                "submitted_query": "Augmentin 625 Duo",
            },
        )
        assert mom_scan.status_code == 200
        events = _parse_sse_stream(mom_scan.text)
        assert [e["event"] for e in events] == [
            "deterministic_safety",
            "interactions",
            "citations",
            "clinical_summary",
            "complete",
        ]
        ev_det, ev_int, _, ev_sum, ev_done = [e["data"] for e in events]
        assert ev_det["profile_id"] == mom_profile_id
        assert ev_det["overall_risk"] == "MAJOR"
        assert ev_det["safety_banner_locked"] is True
        assert len(ev_det["allergy_alerts"]) == 1
        assert ev_det["allergy_alerts"][0]["allergen"] == "Penicillin"
        assert ev_int["overall_risk"] == "MAJOR"
        assert ev_int["safety_banner_locked"] is True
        assert "safe to take together" not in ev_sum["clinical_summary"]["headline"].lower()
        assert ev_done["overall_risk"] == "MAJOR"
        assert ev_done["safety_banner_locked"] is True
        assert len(ev_done["allergy_alerts"]) == 1

        cannot_delete_default = await client.delete(
            f"/api/v1/profiles/{default_profile_id}",
            headers=owner_headers,
        )
        assert cannot_delete_default.status_code == 400

        patch_mom = await client.patch(
            f"/api/v1/profiles/{mom_profile_id}",
            headers=owner_headers,
            json={"name": "Mother", "allergies_json": ["Penicillin"]},
        )
        assert patch_mom.status_code == 200
        assert patch_mom.json()["name"] == "Mother"
        assert patch_mom.json()["allergies_json"] == ["Penicillin"]

        delete_mom = await client.delete(
            f"/api/v1/profiles/{mom_profile_id}",
            headers=owner_headers,
        )
        assert delete_mom.status_code == 204


@pytest.mark.asyncio
async def test_check_before_buying_sandbox_persist_false_does_not_mutate_db(
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
            severity="MAJOR",
            mechanism="Additive antiplatelet and vitamin K antagonist anticoagulation.",
            clinical_effect="High risk of major gastrointestinal bleeding.",
            management="Avoid concurrent use unless supervised with frequent INR checks.",
            sources=["ddinter_2"],
        )
    )
    await db_session.commit()

    async with _build_test_client(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
    ) as client:
        headers = await _register(client, "sandbox_buyer@pillscope.in", "Buyer")
        profiles = (await client.get("/api/v1/profiles", headers=headers)).json()
        profile_id = profiles[0]["id"]

        warf = await client.post(
            "/api/v1/drugs/resolve", headers=headers, json={"query": "Warf 5 Tablet"}
        )
        add_warf = await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={
                "profile_id": profile_id,
                "brand_mapping_id": warf.json()["candidates"][0]["brand_mapping_id"],
            },
        )
        assert add_warf.status_code == 201

        sandbox_res = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={
                "profile_id": profile_id,
                "persist": False,
                "input_mode": "MANUAL_TEXT",
                "submitted_query": "Ecosprin 75 Tablet",
            },
        )
        assert sandbox_res.status_code == 200
        events = _parse_sse_stream(sandbox_res.text)
        assert [e["event"] for e in events] == [
            "deterministic_safety",
            "interactions",
            "citations",
            "clinical_summary",
            "complete",
        ]
        ev_det = events[0]["data"]
        ev_done = events[-1]["data"]
        assert ev_det["persisted"] is False
        assert ev_det["overall_risk"] == "MAJOR"
        assert ev_det["safety_banner_locked"] is True
        assert len(ev_det["deterministic_alerts"]) == 1
        assert ev_done["persisted"] is False
        assert ev_done["profile_id"] == profile_id
        assert ev_done["citation_verified"] is True
        assert len(ev_done["citations"]) >= 1

        not_persisted = await client.get(
            f"/api/v1/scans/{ev_done['scan_id']}",
            headers=headers,
        )
        assert not_persisted.status_code == 404

        med_count = await db_session.scalar(select(func.count(Medication.id)))
        scan_count = await db_session.scalar(select(func.count(ScanLog.id)))
        assert med_count == 1
        assert scan_count == 0


@pytest.mark.asyncio
async def test_lifestyle_triggers_in_medication_detail_and_sse_without_extra_llm_calls(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
) -> None:
    embedder = HashingEmbeddingProvider(dim=768)
    rich_label = {
        "set_id": "spl-doxycycline-lifestyle-001",
        "version": "1",
        "openfda": {
            "generic_name": ["DOXYCYCLINE HYCLATE"],
            "brand_name": ["VIBRAMYCIN"],
        },
        "warnings_and_precautions": [
            "Avoid alcohol consumption as ethanol may decrease serum levels and worsen "
            "hepatotoxicity. Photosensitivity manifested by an exaggerated sunburn reaction "
            "occurs; avoid direct sunlight and wear sunscreen."
        ],
        "drug_interactions": [
            "Dairy products including milk and yogurt reduce doxycycline absorption via "
            "chelation; separate by at least 2 hours."
        ],
        "dosage_and_administration": [
            "Take with food or meals to minimize gastrointestinal irritation and stay upright."
        ],
        "precautions": [
            "May cause drowsiness or dizziness; use caution when driving or operating "
            "heavy machinery."
        ],
    }
    chunks = parse_openfda_label_record(rich_label)
    assert len(chunks) == 4
    await upsert_monograph_chunks(db_session, chunks, embedder)

    counting_llm = _CountingBriefingGenerator()
    async with _build_test_client(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
        briefing_generator=counting_llm,
    ) as client:
        headers = await _register(client, "lifestyle_tester@pillscope.in", "Lifestyle")

        create_med = await client.post(
            "/api/v1/cabinet/medications",
            headers=headers,
            json={
                "display_name": "Doxy 100 Capsule",
                "generic_salts": [{"canonical_salt": "doxycycline", "strength_mg": 100.0}],
                "dosage_schedule": "1 capsule BID",
            },
        )
        assert create_med.status_code == 201, create_med.text
        med_id = create_med.json()["medication"]["id"]

        detail_res = await client.get(
            f"/api/v1/cabinet/medications/{med_id}",
            headers=headers,
        )
        assert detail_res.status_code == 200, detail_res.text
        detail_triggers = detail_res.json()["lifestyle_triggers"]
        detail_types = {t["type"] for t in detail_triggers}
        assert {"ALCOHOL", "SUN_SENSITIVITY", "DAIRY", "WITH_FOOD", "DROWSINESS"}.issubset(
            detail_types
        )
        assert counting_llm.call_count == 0

        scan_res = await client.post(
            "/api/v1/scans/analyze",
            headers=headers,
            json={
                "persist": False,
                "submitted_query": "Doxycycline 100",
                "confirmed_salts": ["doxycycline"],
            },
        )
        assert scan_res.status_code == 200
        events = _parse_sse_stream(scan_res.text)
        ev_cit = next(e["data"] for e in events if e["event"] == "citations")
        ev_done = next(e["data"] for e in events if e["event"] == "complete")
        assert {"ALCOHOL", "SUN_SENSITIVITY", "DAIRY", "WITH_FOOD", "DROWSINESS"}.issubset(
            {t["type"] for t in ev_cit["lifestyle_triggers"]}
        )
        assert ev_done["lifestyle_triggers"] == ev_cit["lifestyle_triggers"]
        assert counting_llm.call_count == 1


@pytest.mark.asyncio
async def test_profile_schedule_endpoint_resolves_separations_and_flags_major_ddi(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
) -> None:
    db_session.add(
        DdiPair(
            salt_a="aspirin",
            salt_b="warfarin",
            severity="MAJOR",
            mechanism="Additive anticoagulant and antiplatelet bleeding risk.",
            clinical_effect="Major gastrointestinal bleeding.",
            management="Avoid concurrent use unless supervised with frequent INR checks.",
            sources=["ddinter_2"],
        )
    )
    await db_session.commit()

    async with _build_test_client(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
    ) as client:
        headers = await _register(client, "scheduler@pillscope.in", "Schedule User")
        profile_id = (await client.get("/api/v1/profiles", headers=headers)).json()[0]["id"]

        for payload in (
            {
                "profile_id": profile_id,
                "display_name": "Thyronorm 50mcg",
                "generic_salts": [{"canonical_salt": "levothyroxine", "strength_mg": 0.05}],
            },
            {
                "profile_id": profile_id,
                "display_name": "Ferrous Sulfate 325mg",
                "generic_salts": [{"canonical_salt": "ferrous sulfate", "strength_mg": 325.0}],
            },
            {
                "profile_id": profile_id,
                "display_name": "Warf 5",
                "generic_salts": [{"canonical_salt": "warfarin", "strength_mg": 5.0}],
            },
            {
                "profile_id": profile_id,
                "display_name": "Ecosprin 75",
                "generic_salts": [{"canonical_salt": "aspirin", "strength_mg": 75.0}],
            },
        ):
            res = await client.post("/api/v1/cabinet/medications", headers=headers, json=payload)
            assert res.status_code == 201, res.text

        sched_res = await client.get(f"/api/v1/profiles/{profile_id}/schedule", headers=headers)
        assert sched_res.status_code == 200, sched_res.text
        sched = sched_res.json()
        assert sched["safety_banner_locked"] is True
        assert [s["slot"] for s in sched["slots"]] == [
            "Morning (Empty Stomach)",
            "Morning (With Food)",
            "Afternoon",
            "Evening",
            "Bedtime",
        ]
        empty_slot = sched["slots"][0]
        assert any("levothyroxine" in m["canonical_salt_keys"] for m in empty_slot["medications"])
        assert any(
            sep["satisfied"] is True and sep["min_separation_minutes"] >= 240
            for sep in sched["applied_separations"]
        )
        assert any(
            clash["clash_type"] == "MAJOR_DDI"
            and {clash["salt_a"], clash["salt_b"]} == {"aspirin", "warfarin"}
            for clash in sched["unresolvable_clashes"]
        )


@pytest.mark.asyncio
async def test_fda_recall_watchdog_rate_limit_bulk_zip_and_profile_matching(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
    tmp_path: Path,
) -> None:
    sample_payload = json.loads(OPENFDA_RECALLS_JSON.read_text(encoding="utf-8"))
    attempts = {"count": 0}
    sleeps: list[float] = []

    async def _record_sleep(delay: float) -> None:
        sleeps.append(delay)

    def _rate_limited_handler(request: httpx.Request) -> httpx.Response:
        attempts["count"] += 1
        if attempts["count"] == 1:
            return httpx.Response(429, headers={"Retry-After": "2"}, json={"error": "rate limit"})
        return httpx.Response(200, json=sample_payload)

    async with httpx.AsyncClient(transport=httpx.MockTransport(_rate_limited_handler)) as mock_http:
        sync_res = await sync_fda_recalls_job(
            {
                "settings": test_settings,
                "session_factory": db_session_factory,
                "http_client": mock_http,
                "sleep_fn": _record_sleep,
            },
            max_pages=2,
            page_size=10,
        )

    assert sync_res["pages_fetched"] == 1
    assert sync_res["records_read"] == 4
    assert sync_res["records_upserted"] == 4
    assert sync_res["active_class_1_or_2_upserted"] == 2
    assert sync_res["rate_limited"] is False
    assert sleeps == [2.0]

    zip_path = tmp_path / "openfda_enforcement_bulk.json.zip"
    with zipfile.ZipFile(zip_path, "w") as archive:
        archive.writestr("drug-enforcement-0001.json", OPENFDA_RECALLS_JSON.read_text("utf-8"))

    bulk_res = await ingest_fda_recalls_from_file_job(
        {
            "settings": test_settings,
            "session_factory": db_session_factory,
        },
        str(zip_path),
    )
    assert bulk_res["records_upserted"] == 4
    total_recalls = await db_session.scalar(select(func.count(RecallAlert.id)))
    assert total_recalls == 4

    def _always_429(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, headers={"Retry-After": "1"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(_always_429)) as mock_429:
        exhausted = await sync_fda_recalls_job(
            {
                "settings": test_settings,
                "session_factory": db_session_factory,
                "http_client": mock_429,
                "sleep_fn": _record_sleep,
            },
            max_pages=1,
            page_size=10,
        )
    assert exhausted["rate_limited"] is True
    assert len(exhausted["warnings"]) == 1

    async with _build_test_client(
        test_settings=test_settings,
        db_session_factory=db_session_factory,
    ) as client:
        headers = await _register(client, "recall_patient@pillscope.in", "Recall User")
        primary_id = (await client.get("/api/v1/profiles", headers=headers)).json()[0]["id"]
        second_profile_id = (
            await client.post(
                "/api/v1/profiles",
                headers=headers,
                json={"name": "Child", "relationship": "child"},
            )
        ).json()["id"]

        for med_payload in (
            {
                "profile_id": primary_id,
                "display_name": "Diovan 80 Tablet",
                "generic_salts": [{"canonical_salt": "valsartan", "strength_mg": 80.0}],
            },
            {
                "profile_id": primary_id,
                "display_name": "Glycomet 500 SR",
                "generic_salts": [{"canonical_salt": "metformin", "strength_mg": 500.0}],
            },
            {
                "profile_id": primary_id,
                "display_name": "Ecosprin 81",
                "generic_salts": [{"canonical_salt": "aspirin", "strength_mg": 81.0}],
            },
            {
                "profile_id": primary_id,
                "display_name": "Warf 5",
                "generic_salts": [{"canonical_salt": "warfarin", "strength_mg": 5.0}],
            },
        ):
            res = await client.post(
                "/api/v1/cabinet/medications", headers=headers, json=med_payload
            )
            assert res.status_code == 201, res.text

        cabinet_items = (
            await client.get(
                "/api/v1/cabinet/medications",
                headers=headers,
                params={"profile_id": primary_id},
            )
        ).json()
        by_name = {item["display_name"]: item for item in cabinet_items}
        assert by_name["Diovan 80 Tablet"]["has_active_recall"] is True
        diovan_recall = by_name["Diovan 80 Tablet"]["active_recalls"][0]
        assert diovan_recall["alert_label"] == "US FDA Recall Alert"
        assert diovan_recall["classification"] == "Class I"
        assert diovan_recall["match_basis"] == "BRAND_AND_SALT"
        assert by_name["Glycomet 500 SR"]["has_active_recall"] is True
        assert by_name["Glycomet 500 SR"]["active_recalls"][0]["classification"] == "Class II"
        assert by_name["Glycomet 500 SR"]["active_recalls"][0]["match_basis"] == "GENERIC_SALT"
        assert by_name["Ecosprin 81"]["has_active_recall"] is False
        assert by_name["Warf 5"]["has_active_recall"] is False

        recalls_res = await client.get(
            f"/api/v1/profiles/{primary_id}/recalls",
            headers=headers,
        )
        assert recalls_res.status_code == 200, recalls_res.text
        recalls_body = recalls_res.json()
        assert recalls_body["alert_label"] == "US FDA Recall Alert"
        assert recalls_body["has_active_recalls"] is True
        assert recalls_body["total_matches"] == 2
        assert {r["recall_number"] for r in recalls_body["recalls"]} == {
            "D-0101-2026",
            "D-0202-2026",
        }
        assert all(r["alert_label"] == "US FDA Recall Alert" for r in recalls_body["recalls"])

        child_recalls = (
            await client.get(f"/api/v1/profiles/{second_profile_id}/recalls", headers=headers)
        ).json()
        assert child_recalls["has_active_recalls"] is False
        assert child_recalls["recalls"] == []

        denied_job = await client.post(
            "/api/v1/internal/jobs/sync-recalls",
            headers={"X-Admin-API-Key": "invalid"},
            json={"max_pages": 1},
        )
        assert denied_job.status_code == 403

        queued_job = await client.post(
            "/api/v1/internal/jobs/sync-recalls",
            headers={"X-Admin-API-Key": test_settings.internal_admin_api_key},
            json={"max_pages": 1, "page_size": 50},
        )
        assert queued_job.status_code == 202, queued_job.text
        assert queued_job.json()["status"] == "queued"

        openapi_schema = (await client.get("/openapi.json")).json()
        paths = openapi_schema["paths"]
        assert "/api/v1/profiles" in paths
        assert "/api/v1/profiles/{profile_id}/schedule" in paths
        assert "/api/v1/profiles/{profile_id}/recalls" in paths
        assert "/api/v1/cabinet/medications/{medication_id}" in paths
        assert "/api/v1/internal/jobs/sync-recalls" in paths
