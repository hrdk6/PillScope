from __future__ import annotations

import json
from typing import Any
from uuid import uuid4

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from backend.app.core.config import Settings
from backend.app.db.models import Medication, Profile, RecallAlert, User
from backend.app.db.session import get_db_session
from backend.app.main import create_app
from backend.app.schemas.safety import ScanAnalyzeRequest
from backend.app.services.allergy_checker import detect_allergy_conflicts
from backend.app.services.clinical_briefing import DeterministicGroundedBriefingGenerator
from backend.app.services.deterministic_safety import detect_deterministic_ddi_alerts
from backend.app.services.dose_scheduler import (
    ScheduleMedicationInput,
    solve_daily_schedule,
)
from backend.app.services.monograph_ingestion import parse_openfda_label_record
from backend.app.services.recall_watchdog import sync_openfda_recalls
from backend.app.services.scan_analyzer import stream_scan_analysis


def test_production_secret_validation_rejects_placeholders() -> None:
    with pytest.raises(ValidationError, match="JWT_SECRET_KEY"):
        Settings(
            app_env="production",
            jwt_secret_key="replace-with-a-32-plus-byte-random-secret-key",
            internal_admin_api_key="prod-admin-key-very-strong-random-value-99",
            database_url="postgresql+asyncpg://user:StrongProdPass123!@db.internal:5432/pillscope",
            redis_url="redis://:StrongRedisPass123!@redis.internal:6379/0",
        )

    with pytest.raises(ValidationError, match="DATABASE_URL"):
        Settings(
            app_env="production",
            jwt_secret_key="0123456789abcdef0123456789abcdef01234567",
            internal_admin_api_key="prod-admin-key-very-strong-random-value-99",
            database_url="postgresql+asyncpg://pillscope:pillscope_dev_password@db.internal:5432/pillscope",
            redis_url="redis://:StrongRedisPass123!@redis.internal:6379/0",
        )

    with pytest.raises(ValidationError, match="REDIS_URL"):
        Settings(
            app_env="production",
            jwt_secret_key="0123456789abcdef0123456789abcdef01234567",
            internal_admin_api_key="prod-admin-key-very-strong-random-value-99",
            database_url="postgresql+asyncpg://user:StrongProdPass123!@db.internal:5432/pillscope",
            redis_url="redis://redis.internal:6379/0",
        )

    good = Settings(
        app_env="production",
        jwt_secret_key="0123456789abcdef0123456789abcdef01234567",
        internal_admin_api_key="prod-admin-key-very-strong-random-value-99",
        database_url="postgresql+asyncpg://user:StrongProdPass123!@db.internal:5432/pillscope",
        redis_url="redis://:StrongRedisPass123!@redis.internal:6379/0",
    )
    assert good.app_env == "production"


def test_typo_tolerant_allergy_checker() -> None:
    class_typo = detect_allergy_conflicts(
        candidate_salts=["amoxicillin"],
        allergies_json=["Penicilin"],
    )
    assert len(class_typo) == 1
    assert class_typo[0].canonical_salt == "amoxicillin"
    assert class_typo[0].matched_class == "penicillin"

    salt_typo = detect_allergy_conflicts(
        candidate_salts=["ibuprofen"],
        allergies_json=["ibupofen"],
    )
    assert len(salt_typo) == 1
    assert salt_typo[0].canonical_salt == "ibuprofen"


def test_combo_openfda_bulk_ingestion_indexes_all_active_salts() -> None:
    record = {
        "set_id": "combo-set-101",
        "openfda": {"generic_name": ["AMOXICILLIN AND CLAVULANATE POTASSIUM"]},
        "warnings": [
            "Serious and occasionally fatal hypersensitivity reactions have been reported in "
            "patients receiving beta-lactam therapy with amoxicillin and clavulanate potassium."
        ],
    }
    chunks = parse_openfda_label_record(record, fallback_drug_name=None)
    indexed_salts = {c.canonical_salt for c in chunks}
    assert indexed_salts == {"amoxicillin", "clavulanic acid"}


def test_dose_scheduler_qid_weekly_note_and_non_sedating_statin() -> None:
    statin_id = uuid4()
    abx_id = uuid4()
    alend_id = uuid4()
    result = solve_daily_schedule(
        profile_id=uuid4(),
        profile_name="Self",
        medications=[
            ScheduleMedicationInput(
                medication_id=statin_id,
                display_name="Simvotin 20",
                canonical_salt_keys=("simvastatin",),
                dosage_schedule="1 tab OD",
            ),
            ScheduleMedicationInput(
                medication_id=abx_id,
                display_name="Cephalexin 500",
                canonical_salt_keys=("cephalexin",),
                dosage_schedule="1 cap QID",
            ),
            ScheduleMedicationInput(
                medication_id=alend_id,
                display_name="Fosamax 70",
                canonical_salt_keys=("alendronate",),
                dosage_schedule="1 tablet once weekly",
            ),
        ],
    )
    all_items = [item for slot in result.slots for item in slot.medications]
    statin_items = [i for i in all_items if i.medication_id == statin_id]
    assert len(statin_items) == 1
    assert all("drowsiness" not in r.lower() for r in statin_items[0].rationale)
    assert any("evening-optimal" in r.lower() for r in statin_items[0].rationale)

    qid_items = [i for i in all_items if i.medication_id == abx_id]
    assert len(qid_items) == 4
    assert all(i.total_daily_doses == 4 for i in qid_items)

    weekly_items = [i for i in all_items if i.medication_id == alend_id]
    assert len(weekly_items) == 1
    assert weekly_items[0].frequency_note is not None
    assert "weekly" in weekly_items[0].frequency_note.lower()


@pytest.mark.asyncio
async def test_auth_logout_refresh_rotation_delete_account_and_legal_pages(
    api_client: AsyncClient,
    db_session: AsyncSession,
) -> None:
    privacy_resp = await api_client.get("/privacy")
    assert privacy_resp.status_code == 200
    assert "PillScope" in privacy_resp.text

    deletion_resp = await api_client.get("/account-deletion")
    assert deletion_resp.status_code == 200
    assert "DELETE /api/v1/auth/me" in deletion_resp.text

    reg = await api_client.post(
        "/api/v1/auth/register",
        json={
            "email": "hardening@example.com",
            "password": "StrongPassword123!",
            "display_name": "Harden",
        },
    )
    assert reg.status_code == 201
    tokens = reg.json()["tokens"]
    access_1 = tokens["access_token"]
    refresh_1 = tokens["refresh_token"]

    # Rotate refresh token; replaying refresh_1 must fail with 401
    refreshed = await api_client.post("/api/v1/auth/refresh", json={"refresh_token": refresh_1})
    assert refreshed.status_code == 200
    refresh_2 = refreshed.json()["refresh_token"]
    access_2 = refreshed.json()["access_token"]

    replay = await api_client.post("/api/v1/auth/refresh", json={"refresh_token": refresh_1})
    assert replay.status_code == 401

    # Logout revokes refresh_2
    logout_resp = await api_client.post(
        "/api/v1/auth/logout",
        headers={"Authorization": f"Bearer {access_2}"},
        json={"refresh_token": refresh_2},
    )
    assert logout_resp.status_code == 204
    after_logout = await api_client.post("/api/v1/auth/refresh", json={"refresh_token": refresh_2})
    assert after_logout.status_code == 401

    # Add a medication then delete the entire account
    headers = {"Authorization": f"Bearer {access_1}"}
    med_resp = await api_client.post(
        "/api/v1/cabinet/medications",
        headers=headers,
        json={
            "display_name": "Dolo 650",
            "generic_salts": [{"canonical_salt": "acetaminophen", "strength_mg": 650}],
        },
    )
    assert med_resp.status_code == 201

    del_resp = await api_client.delete("/api/v1/auth/me", headers=headers)
    assert del_resp.status_code == 204

    me_after = await api_client.get("/api/v1/auth/me", headers=headers)
    assert me_after.status_code == 401

    users_left = (await db_session.execute(select(User))).scalars().all()
    meds_left = (await db_session.execute(select(Medication))).scalars().all()
    assert users_left == []
    assert meds_left == []


@pytest.mark.asyncio
async def test_cabinet_reactivation_safety_and_therapeutic_duplication(
    api_client: AsyncClient,
    db_session: AsyncSession,
) -> None:
    reg = await api_client.post(
        "/api/v1/auth/register",
        json={"email": "reactivate@example.com", "password": "StrongPassword123!"},
    )
    assert reg.status_code == 201
    headers = {"Authorization": f"Bearer {reg.json()['tokens']['access_token']}"}

    profiles = (await api_client.get("/api/v1/profiles", headers=headers)).json()
    default_profile_id = profiles[0]["id"]
    await api_client.patch(
        f"/api/v1/profiles/{default_profile_id}",
        headers=headers,
        json={"allergies_json": ["Penicillin"]},
    )

    m1 = await api_client.post(
        "/api/v1/cabinet/medications",
        headers=headers,
        json={
            "display_name": "Mox 500",
            "generic_salts": [{"canonical_salt": "amoxicillin", "strength_mg": 500}],
            "is_active": False,
        },
    )
    assert m1.status_code == 201
    m1_id = m1.json()["medication"]["id"]

    m2 = await api_client.post(
        "/api/v1/cabinet/medications",
        headers=headers,
        json={
            "display_name": "Augmentin 625",
            "generic_salts": [{"canonical_salt": "amoxicillin", "strength_mg": 500}],
            "is_active": True,
        },
    )
    assert m2.status_code == 201

    reactivated = await api_client.patch(
        f"/api/v1/cabinet/medications/{m1_id}",
        headers=headers,
        json={"is_active": True},
    )
    assert reactivated.status_code == 200
    body = reactivated.json()
    assert body["safety_banner_locked"] is True
    assert any(d["canonical_salt"] == "amoxicillin" for d in body["duplicate_salts"])
    assert any(a["canonical_salt"] == "amoxicillin" for a in body["allergy_alerts"])

    # Same-class NSAID therapeutic duplication
    alerts = await detect_deterministic_ddi_alerts(
        session=db_session,
        candidate_salts=["naproxen"],
        cabinet_salts=["ibuprofen"],
    )
    assert any(a.rule_id == "therapeutic_class:nsaid" and a.severity == "MODERATE" for a in alerts)


@pytest.mark.asyncio
async def test_scan_analyzer_rollback_resilience_and_scan_time_recalls(
    db_session: AsyncSession,
    test_settings: Settings,
) -> None:
    user = User(email="scanres@example.com", password_hash="not-used")
    db_session.add(user)
    await db_session.flush()
    profile = Profile(
        user_id=user.id,
        name="Self",
        relationship="self",
        allergies_json=["Penicillin"],
        is_default=True,
    )
    db_session.add(profile)
    db_session.add(
        RecallAlert(
            recall_number="D-9999-2026",
            status="Ongoing",
            classification="Class I",
            recalling_firm="Test Pharma",
            product_description="Amoxicillin Capsules 500mg",
            reason_for_recall="Contamination detected.",
            canonical_salt_keys=["amoxicillin"],
            brand_names=["amoxil"],
            generic_names=["amoxicillin"],
            report_date="20260601",
            source_url="https://api.fda.gov/drug/enforcement.json?search=recall_number:D-9999-2026",
        )
    )
    await db_session.commit()

    class _BoomRetriever:
        async def retrieve(self, *args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("Simulated pgvector failure")

    request = ScanAnalyzeRequest(
        profile_id=profile.id,
        persist=False,
        input_mode="MANUAL_TEXT",
        submitted_query="Amoxil 500",
        confirmed_salts=["amoxicillin"],
    )
    events: dict[str, dict[str, Any]] = {}
    async for ev in stream_scan_analysis(
        session=db_session,
        user_id=user.id,
        request=request,
        settings=test_settings,
        rag_retriever=_BoomRetriever(),  # type: ignore[arg-type]
        briefing_generator=DeterministicGroundedBriefingGenerator(),
        profile=profile,
    ):
        events[ev["event"]] = json.loads(ev["data"])

    assert "deterministic_safety" in events
    assert "complete" in events
    det_payload = events["deterministic_safety"]
    assert len(det_payload["active_recalls"]) == 1
    assert det_payload["active_recalls"][0]["recall_number"] == "D-9999-2026"
    assert len(det_payload["allergy_alerts"]) == 1


@pytest.mark.asyncio
async def test_sync_terminated_recalls_updates_ongoing_status(
    db_session: AsyncSession,
    test_settings: Settings,
) -> None:
    db_session.add(
        RecallAlert(
            recall_number="D-TERM-1",
            status="Ongoing",
            classification="Class II",
            recalling_firm="Acme",
            product_description="Metformin 500mg",
            reason_for_recall="NDMA impurity",
            canonical_salt_keys=["metformin"],
            brand_names=["glycomet"],
            generic_names=["metformin"],
            source_url="https://api.fda.gov/drug/enforcement.json",
        )
    )
    await db_session.commit()

    class _FakeResp:
        status_code = 200

        def __init__(self, payload: dict[str, Any]) -> None:
            self._payload = payload
            self.headers: dict[str, str] = {}

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, Any]:
            return self._payload

    class _FakeClient:
        async def get(self, url: str, params: dict[str, Any]) -> _FakeResp:
            return _FakeResp(
                {
                    "results": [
                        {
                            "recall_number": "D-TERM-1",
                            "status": "Terminated",
                            "classification": "Class II",
                            "recalling_firm": "Acme",
                            "product_description": "Metformin 500mg",
                            "reason_for_recall": "NDMA impurity",
                            "openfda": {"generic_name": ["METFORMIN HYDROCHLORIDE"]},
                        }
                    ]
                }
            )

    summary = await sync_openfda_recalls(
        session=db_session,
        settings=test_settings,
        max_pages=1,
        status_filter="Terminated",
        http_client=_FakeClient(),  # type: ignore[arg-type]
    )
    assert summary.records_upserted == 1
    updated = (
        await db_session.execute(select(RecallAlert).where(RecallAlert.recall_number == "D-TERM-1"))
    ).scalar_one()
    assert updated.status == "Terminated"


@pytest.mark.asyncio
async def test_rate_limit_middleware_returns_429(
    test_settings: Settings,
    db_session_factory: async_sessionmaker[AsyncSession],
) -> None:
    rl_settings = test_settings.model_copy(
        update={
            "rate_limit_enabled": True,
            "rate_limit_auth_per_minute": 2,
        }
    )
    app = create_app(settings=rl_settings)

    async def _override_db() -> Any:
        async with db_session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = _override_db
    client_headers = {"X-Forwarded-For": f"198.51.100.{uuid4().int % 250}"}
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        for _ in range(2):
            r = await client.post(
                "/api/v1/auth/login",
                headers=client_headers,
                json={"email": "missing@example.com", "password": "wrongpassword"},
            )
            assert r.status_code == 401
        third = await client.post(
            "/api/v1/auth/login",
            headers=client_headers,
            json={"email": "missing@example.com", "password": "wrongpassword"},
        )
        assert third.status_code == 429
        assert "Retry-After" in third.headers
