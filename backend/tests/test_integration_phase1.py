from pathlib import Path
from uuid import UUID

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import BrandSaltMapping, DdiPair, MonographChunk
from backend.scripts.ingest_ddinter import ingest_ddinter_csvs
from backend.scripts.ingest_indian_brands import ingest_indian_brands_csv

SAMPLE_CSV = Path(__file__).resolve().parent / "fixtures" / "indian_brands_sample.csv"
DDINTER_SAMPLE_CSV = (
    Path(__file__).resolve().parents[2] / "ml" / "ddi" / "fixtures" / "ddinter_real_sample.csv"
)


async def _register_and_auth_headers(
    client: AsyncClient,
    email: str,
    password: str = "StrongPass123!",
    display_name: str = "Test User",
) -> tuple[dict[str, str], dict[str, object]]:
    response = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": password, "display_name": display_name},
    )
    assert response.status_code == 201, response.text
    payload = response.json()
    access_token = payload["tokens"]["access_token"]
    return {"Authorization": f"Bearer {access_token}"}, payload


@pytest.mark.asyncio
async def test_health_and_readiness_endpoints(api_client: AsyncClient) -> None:
    health = await api_client.get("/healthz")
    assert health.status_code == 200
    assert health.json() == {"status": "ok"}

    ready = await api_client.get("/readyz")
    assert ready.status_code == 200, ready.text
    body = ready.json()
    assert body["status"] == "ready"
    assert body["checks"]["postgres"] is True
    assert {"pg_trgm", "pgcrypto", "vector"}.issubset(set(body["checks"]["extensions"]))
    assert body["checks"]["redis"] is True


@pytest.mark.asyncio
async def test_jwt_auth_lifecycle(api_client: AsyncClient) -> None:
    headers, registered = await _register_and_auth_headers(api_client, "alice@pillscope.in")
    refresh_token = registered["tokens"]["refresh_token"]

    duplicate = await api_client.post(
        "/api/v1/auth/register",
        json={"email": "ALICE@pillscope.in", "password": "StrongPass123!"},
    )
    assert duplicate.status_code == 409

    me_res = await api_client.get("/api/v1/auth/me", headers=headers)
    assert me_res.status_code == 200
    assert me_res.json()["email"] == "alice@pillscope.in"

    bad_login = await api_client.post(
        "/api/v1/auth/login",
        json={"email": "alice@pillscope.in", "password": "WrongPassword!"},
    )
    assert bad_login.status_code == 401

    login_res = await api_client.post(
        "/api/v1/auth/login",
        json={"email": "alice@pillscope.in", "password": "StrongPass123!"},
    )
    assert login_res.status_code == 200
    assert login_res.json()["tokens"]["access_token"]

    refresh_on_me = await api_client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {refresh_token}"},
    )
    assert refresh_on_me.status_code == 401

    rotated = await api_client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": refresh_token},
    )
    assert rotated.status_code == 200
    new_access = rotated.json()["access_token"]
    me_after_rotate = await api_client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {new_access}"},
    )
    assert me_after_rotate.status_code == 200


@pytest.mark.asyncio
async def test_indian_brand_ingestion_is_idempotent_and_resolves_exact_and_fuzzy(
    api_client: AsyncClient,
    db_session: AsyncSession,
) -> None:
    first_stats = await ingest_indian_brands_csv(db_session, SAMPLE_CSV)
    assert first_stats.rows_read == 22
    assert first_stats.rows_upserted == 20
    assert first_stats.rows_skipped_discontinued == 1
    assert first_stats.rows_skipped_invalid == 1

    second_stats = await ingest_indian_brands_csv(db_session, SAMPLE_CSV)
    assert second_stats.rows_upserted == 20

    total_rows = await db_session.scalar(select(func.count(BrandSaltMapping.id)))
    assert total_rows == 20

    headers, _ = await _register_and_auth_headers(api_client, "resolver@pillscope.in")

    exact_res = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=headers,
        json={"query": "Augmentin 625 Duo"},
    )
    assert exact_res.status_code == 200, exact_res.text
    exact_body = exact_res.json()
    assert len(exact_body["candidates"]) >= 1
    top_exact = exact_body["candidates"][0]
    assert top_exact["brand_name"] == "Augmentin 625 Duo Tablet"
    assert top_exact["match_strategy"] == "exact_brand"
    assert top_exact["canonical_salt_keys"] == ["amoxicillin", "clavulanic acid"]

    fuzzy_res = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=headers,
        json={"query": "Augmentln 625 Duo"},
    )
    assert fuzzy_res.status_code == 200, fuzzy_res.text
    fuzzy_body = fuzzy_res.json()
    assert len(fuzzy_body["candidates"]) >= 1
    top_fuzzy = fuzzy_body["candidates"][0]
    assert top_fuzzy["brand_name"] == "Augmentin 625 Duo Tablet"
    assert top_fuzzy["match_strategy"] == "trigram_brand"
    assert top_fuzzy["canonical_salt_keys"] == ["amoxicillin", "clavulanic acid"]

    search_res = await api_client.get(
        "/api/v1/drugs/search",
        headers=headers,
        params={"q": "Combflam", "country": "IN"},
    )
    assert search_res.status_code == 200, search_res.text
    search_items = search_res.json()
    assert search_items[0]["brand_name"] == "Combiflam Tablet"
    assert search_items[0]["canonical_salt_keys"] == ["acetaminophen", "ibuprofen"]

    generic_res = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=headers,
        json={"query": "Paracetamol"},
    )
    assert generic_res.status_code == 200
    generic_names = {item["brand_name"] for item in generic_res.json()["candidates"]}
    assert "Dolo 650 Tablet" in generic_names

    ocr_fallback_res = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=headers,
        json={
            "query": "Zyrnova Unlisted Local Brand 999",
            "raw_ocr_text": "ZYRNOVA 999 Amoxycillin 500mg Clavulanic Acid 125mg",
        },
    )
    assert ocr_fallback_res.status_code == 200
    ocr_body = ocr_fallback_res.json()
    assert ocr_body["candidates"][0]["match_strategy"] == "ocr_salt_parse"
    assert ocr_body["candidates"][0]["canonical_salt_keys"] == ["amoxicillin", "clavulanic acid"]


@pytest.mark.asyncio
async def test_cabinet_crud_and_deterministic_duplicate_salt_detection(
    api_client: AsyncClient,
    db_session: AsyncSession,
) -> None:
    await ingest_indian_brands_csv(db_session, SAMPLE_CSV)
    user_a_headers, _ = await _register_and_auth_headers(api_client, "patient_a@pillscope.in")
    user_b_headers, _ = await _register_and_auth_headers(api_client, "patient_b@pillscope.in")

    mox_lookup = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=user_a_headers,
        json={"query": "Mox 500mg Capsule"},
    )
    mox_mapping_id = mox_lookup.json()["candidates"][0]["brand_mapping_id"]

    add_mox = await api_client.post(
        "/api/v1/cabinet/medications",
        headers=user_a_headers,
        json={"brand_mapping_id": mox_mapping_id, "dosage_schedule": "1 capsule TID"},
    )
    assert add_mox.status_code == 201, add_mox.text
    add_mox_body = add_mox.json()
    assert add_mox_body["overall_risk"] == "NONE"
    assert add_mox_body["safety_banner_locked"] is False
    assert add_mox_body["duplicate_salts"] == []
    mox_med_id = add_mox_body["medication"]["id"]
    assert add_mox_body["medication"]["canonical_salt_keys"] == ["amoxicillin"]

    dup_augmentin = await api_client.post(
        "/api/v1/cabinet/check-duplicates",
        headers=user_a_headers,
        json={"brand_name": "Augmentin 625 Duo"},
    )
    assert dup_augmentin.status_code == 200, dup_augmentin.text
    dup_body = dup_augmentin.json()
    assert dup_body["overall_risk"] == "MAJOR"
    assert dup_body["safety_banner_locked"] is True
    assert dup_body["resolved_salts"] == ["amoxicillin", "clavulanic acid"]
    assert len(dup_body["duplicate_salts"]) == 1
    assert dup_body["duplicate_salts"][0]["canonical_salt"] == "amoxicillin"
    assert dup_body["duplicate_salts"][0]["existing_medication_id"] == mox_med_id
    assert dup_body["duplicate_salts"][0]["existing_display_name"] == "Mox 500mg Capsule"

    user_b_check = await api_client.post(
        "/api/v1/cabinet/check-duplicates",
        headers=user_b_headers,
        json={"brand_name": "Augmentin 625 Duo"},
    )
    assert user_b_check.status_code == 200
    assert user_b_check.json()["safety_banner_locked"] is False

    dolo_lookup = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=user_a_headers,
        json={"query": "Dolo 650"},
    )
    combiflam_lookup = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=user_a_headers,
        json={"query": "Combiflam"},
    )
    add_dolo = await api_client.post(
        "/api/v1/cabinet/medications",
        headers=user_a_headers,
        json={"brand_mapping_id": dolo_lookup.json()["candidates"][0]["brand_mapping_id"]},
    )
    assert add_dolo.status_code == 201
    assert add_dolo.json()["safety_banner_locked"] is False

    add_combiflam = await api_client.post(
        "/api/v1/cabinet/medications",
        headers=user_a_headers,
        json={"brand_mapping_id": combiflam_lookup.json()["candidates"][0]["brand_mapping_id"]},
    )
    assert add_combiflam.status_code == 201
    combiflam_body = add_combiflam.json()
    assert combiflam_body["overall_risk"] == "MAJOR"
    assert combiflam_body["safety_banner_locked"] is True
    assert combiflam_body["duplicate_salts"][0]["canonical_salt"] == "acetaminophen"

    voveran_sr = await api_client.post(
        "/api/v1/drugs/resolve",
        headers=user_a_headers,
        json={"query": "Voveran SR 100"},
    )
    await api_client.post(
        "/api/v1/cabinet/medications",
        headers=user_a_headers,
        json={"brand_mapping_id": voveran_sr.json()["candidates"][0]["brand_mapping_id"]},
    )
    cross_counterion = await api_client.post(
        "/api/v1/cabinet/check-duplicates",
        headers=user_a_headers,
        json={"brand_name": "Voveran 50 GE"},
    )
    assert cross_counterion.status_code == 200
    assert cross_counterion.json()["safety_banner_locked"] is True
    assert cross_counterion.json()["duplicate_salts"][0]["canonical_salt"] == "diclofenac"

    cross_user_patch = await api_client.patch(
        f"/api/v1/cabinet/medications/{mox_med_id}",
        headers=user_b_headers,
        json={"is_active": False},
    )
    assert cross_user_patch.status_code == 404

    deactivate_mox = await api_client.patch(
        f"/api/v1/cabinet/medications/{mox_med_id}",
        headers=user_a_headers,
        json={"display_name": None, "is_active": False},
    )
    assert deactivate_mox.status_code == 200
    assert deactivate_mox.json()["is_active"] is False
    assert deactivate_mox.json()["display_name"] == "Mox 500mg Capsule"

    post_deactivate = await api_client.post(
        "/api/v1/cabinet/check-duplicates",
        headers=user_a_headers,
        json={"brand_name": "Augmentin 625 Duo"},
    )
    assert post_deactivate.status_code == 200
    assert post_deactivate.json()["safety_banner_locked"] is False
    assert post_deactivate.json()["duplicate_salts"] == []

    active_list = await api_client.get(
        "/api/v1/cabinet/medications",
        headers=user_a_headers,
        params={"active_only": True},
    )
    assert active_list.status_code == 200
    assert mox_med_id not in {item["id"] for item in active_list.json()}

    delete_mox = await api_client.delete(
        f"/api/v1/cabinet/medications/{mox_med_id}",
        headers=user_a_headers,
    )
    assert delete_mox.status_code == 204


@pytest.mark.asyncio
async def test_pgvector_fts_and_ddi_schema_constraints(db_session: AsyncSession) -> None:
    major_pair = DdiPair(
        salt_a="aspirin",
        salt_b="warfarin",
        severity="MAJOR",
        mechanism="Additive antiplatelet and anticoagulant effects.",
        clinical_effect="Significantly elevated risk of major gastrointestinal bleeding.",
        management="Avoid concurrent use unless explicitly supervised with close INR monitoring.",
        sources=["ddinter_2"],
    )
    moderate_pair = DdiPair(
        salt_a="amoxicillin",
        salt_b="warfarin",
        severity="MODERATE",
        mechanism="Gut flora alteration reducing vitamin K synthesis.",
        clinical_effect="Potential INR elevation.",
        management="Monitor INR closely during and after antibiotic course.",
        sources=["ddinter_2"],
    )
    db_session.add_all([major_pair, moderate_pair])
    await db_session.commit()
    await db_session.refresh(major_pair)
    await db_session.refresh(moderate_pair)

    assert major_pair.is_hard_safety_override is True
    assert moderate_pair.is_hard_safety_override is False

    bad_order = DdiPair(
        salt_a="warfarin",
        salt_b="aspirin",
        severity="MAJOR",
        mechanism="invalid order",
        clinical_effect="invalid order",
        management="invalid order",
        sources=["ddinter_2"],
    )
    db_session.add(bad_order)
    with pytest.raises(IntegrityError):
        await db_session.commit()
    await db_session.rollback()

    chunk = MonographChunk(
        set_id="test-spl-warfarin-001",
        document_version="1",
        canonical_salt="warfarin",
        loinc_section_code="34073-7",
        section_title="DRUG INTERACTIONS",
        chunk_index=0,
        content="Concomitant use of warfarin with aspirin or NSAIDs increases bleeding risk.",
        content_sha256="a" * 64,
        source_url="https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=test-spl-warfarin-001",
        embedding=[0.01] * 768,
    )
    db_session.add(chunk)
    await db_session.commit()
    await db_session.refresh(chunk)

    assert isinstance(chunk.id, UUID)
    fts_hit = await db_session.scalar(
        select(MonographChunk.id).where(
            MonographChunk.fts_vector.op("@@")(func.plainto_tsquery("english", "warfarin bleeding"))
        )
    )
    assert fts_hit == chunk.id


@pytest.mark.asyncio
async def test_ddinter_csv_ingestion_into_postgres_is_idempotent(
    db_session: AsyncSession,
) -> None:
    first = await ingest_ddinter_csvs(db_session, [DDINTER_SAMPLE_CSV])
    assert first.pairs_upserted >= 180
    assert first.major_pairs >= 80

    second = await ingest_ddinter_csvs(db_session, [DDINTER_SAMPLE_CSV])
    assert second.pairs_upserted == first.pairs_upserted

    total = await db_session.scalar(select(func.count(DdiPair.id)))
    assert total == first.pairs_upserted

    warfarin_aspirin = await db_session.scalar(
        select(DdiPair).where(DdiPair.salt_a == "aspirin", DdiPair.salt_b == "warfarin")
    )
    assert warfarin_aspirin is not None
    assert warfarin_aspirin.severity == "MAJOR"
    assert warfarin_aspirin.is_hard_safety_override is True
