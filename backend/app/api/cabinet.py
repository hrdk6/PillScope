from collections.abc import Sequence
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.api.deps import get_current_user
from backend.app.core.config import Settings, get_settings
from backend.app.db.models import BrandSaltMapping, Medication, User
from backend.app.db.session import get_db_session
from backend.app.schemas.cabinet import (
    DuplicateCheckRequest,
    DuplicateCheckResponse,
    MedicationCreateRequest,
    MedicationCreateResponse,
    MedicationDetailResponse,
    MedicationResponse,
    MedicationUpdateRequest,
)
from backend.app.schemas.drugs import DrugResolveRequest, SaltComponent
from backend.app.schemas.lifestyle import LifestyleTrigger
from backend.app.services.brand_resolver import BrandSaltResolver
from backend.app.services.deterministic_safety import evaluate_duplicate_safety
from backend.app.services.drug_normalizer import canonical_salt_keys, normalize_canonical_salt
from backend.app.services.lifestyle_triggers import extract_lifestyle_triggers_by_salt
from backend.app.services.profiles import resolve_user_profile
from backend.app.services.recall_watchdog import match_active_recalls_for_medications

router = APIRouter(prefix="/api/v1/cabinet", tags=["cabinet"])


def _normalize_salt_components(salts: list[SaltComponent]) -> list[SaltComponent]:
    normalized: list[SaltComponent] = []
    seen: set[str] = set()
    for item in salts:
        canonical = normalize_canonical_salt(item.canonical_salt)
        if not canonical or canonical in seen:
            continue
        seen.add(canonical)
        normalized.append(
            SaltComponent(
                canonical_salt=canonical,
                strength_mg=item.strength_mg,
                strength_text=item.strength_text,
                rxcui=item.rxcui,
            )
        )
    return normalized


def _merge_triggers_for_salts(
    salts: Sequence[str],
    triggers_by_salt: dict[str, list[LifestyleTrigger]],
) -> list[LifestyleTrigger]:
    severity_rank = {"INFO": 1, "MODERATE": 2, "HIGH": 3}
    best_by_type: dict[str, LifestyleTrigger] = {}
    for salt in salts:
        for trigger in triggers_by_salt.get(salt, []):
            existing = best_by_type.get(trigger.type)
            if (
                existing is None
                or severity_rank[trigger.severity] > severity_rank[existing.severity]
            ):
                best_by_type[trigger.type] = trigger
    return sorted(
        best_by_type.values(),
        key=lambda item: (-severity_rank[item.severity], item.type),
    )


async def _hydrate_medications(
    session: AsyncSession,
    medications: Sequence[Medication],
) -> list[MedicationResponse]:
    if not medications:
        return []

    all_salts = canonical_salt_keys(
        salt for med in medications for salt in med.canonical_salt_keys
    )
    triggers_by_salt = await extract_lifestyle_triggers_by_salt(session, all_salts)
    recalls_by_med, _ = await match_active_recalls_for_medications(session, medications)

    responses: list[MedicationResponse] = []
    for med in medications:
        base = MedicationResponse.model_validate(med)
        med_recalls = recalls_by_med.get(med.id, [])
        med_triggers = _merge_triggers_for_salts(med.canonical_salt_keys, triggers_by_salt)
        responses.append(
            base.model_copy(
                update={
                    "lifestyle_triggers": med_triggers,
                    "has_active_recall": len(med_recalls) > 0,
                    "active_recalls": med_recalls,
                }
            )
        )
    return responses


@router.get("/medications", response_model=list[MedicationResponse])
async def list_medications(
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    profile_id: UUID | None = Query(default=None),
    active_only: bool = Query(default=True),
) -> list[MedicationResponse]:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=profile_id,
    )
    stmt = select(Medication).where(
        Medication.user_id == current_user.id,
        Medication.profile_id == profile.id,
    )
    if active_only:
        stmt = stmt.where(Medication.is_active.is_(True))
    stmt = stmt.order_by(Medication.created_at.desc())

    rows = list((await session.execute(stmt)).scalars().all())
    return await _hydrate_medications(session, rows)


@router.get("/medications/{medication_id}", response_model=MedicationDetailResponse)
async def get_medication_detail(
    medication_id: UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    profile_id: UUID | None = Query(default=None),
) -> MedicationDetailResponse:
    stmt = select(Medication).where(
        Medication.id == medication_id,
        Medication.user_id == current_user.id,
    )
    if profile_id is not None:
        profile = await resolve_user_profile(
            session=session,
            user_id=current_user.id,
            profile_id=profile_id,
        )
        stmt = stmt.where(Medication.profile_id == profile.id)

    medication = await session.scalar(stmt)
    if medication is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Medication not found.",
        )

    hydrated = (await _hydrate_medications(session, [medication]))[0]
    return MedicationDetailResponse.model_validate(hydrated.model_dump())


@router.post(
    "/medications",
    response_model=MedicationCreateResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_medication(
    body: MedicationCreateRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> MedicationCreateResponse:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=body.profile_id,
    )

    mapping: BrandSaltMapping | None = None
    if body.brand_mapping_id is not None:
        mapping = await session.scalar(
            select(BrandSaltMapping).where(BrandSaltMapping.id == body.brand_mapping_id)
        )
        if mapping is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Referenced brand_mapping_id not found.",
            )

    display_name = (
        body.display_name.strip()
        if body.display_name
        else (mapping.brand_name if mapping is not None else "")
    )
    raw_salts = (
        body.generic_salts
        if body.generic_salts is not None
        else [SaltComponent.model_validate(s) for s in (mapping.generic_salts if mapping else [])]
    )
    normalized_salts = _normalize_salt_components(raw_salts)
    salt_keys = canonical_salt_keys(normalized_salts)
    if not salt_keys:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="At least one valid canonical generic salt is required.",
        )

    safety_check = await evaluate_duplicate_safety(
        session=session,
        user_id=current_user.id,
        profile_id=profile.id,
        candidate_salts=salt_keys,
        allergies_json=profile.allergies_json,
    )

    medication = Medication(
        user_id=current_user.id,
        profile_id=profile.id,
        brand_mapping_id=mapping.id if mapping is not None else None,
        display_name=display_name,
        generic_salts=[salt.model_dump() for salt in normalized_salts],
        canonical_salt_keys=salt_keys,
        dosage_schedule=body.dosage_schedule,
        is_active=body.is_active,
        started_at=body.started_at,
        ended_at=body.ended_at,
    )
    session.add(medication)
    await session.commit()
    await session.refresh(medication)

    hydrated = (await _hydrate_medications(session, [medication]))[0]
    return MedicationCreateResponse(
        medication=hydrated,
        overall_risk=safety_check.overall_risk,
        safety_banner_locked=safety_check.safety_banner_locked,
        duplicate_salts=safety_check.duplicate_salts,
        allergy_alerts=safety_check.allergy_alerts,
    )


@router.patch("/medications/{medication_id}", response_model=MedicationResponse)
async def update_medication(
    medication_id: UUID,
    body: MedicationUpdateRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    profile_id: UUID | None = Query(default=None),
) -> MedicationResponse:
    stmt = select(Medication).where(
        Medication.id == medication_id,
        Medication.user_id == current_user.id,
    )
    if profile_id is not None:
        profile = await resolve_user_profile(
            session=session,
            user_id=current_user.id,
            profile_id=profile_id,
        )
        stmt = stmt.where(Medication.profile_id == profile.id)

    medication = await session.scalar(stmt)
    if medication is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Medication not found.",
        )

    updates = body.model_dump(exclude_unset=True)
    for field, value in updates.items():
        if value is None and field in {"display_name", "is_active"}:
            continue
        if field == "display_name" and isinstance(value, str):
            value = value.strip()
        setattr(medication, field, value)

    await session.commit()
    await session.refresh(medication)
    return (await _hydrate_medications(session, [medication]))[0]


@router.delete("/medications/{medication_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_medication(
    medication_id: UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    profile_id: UUID | None = Query(default=None),
) -> Response:
    stmt = select(Medication).where(
        Medication.id == medication_id,
        Medication.user_id == current_user.id,
    )
    if profile_id is not None:
        profile = await resolve_user_profile(
            session=session,
            user_id=current_user.id,
            profile_id=profile_id,
        )
        stmt = stmt.where(Medication.profile_id == profile.id)

    medication = await session.scalar(stmt)
    if medication is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Medication not found.",
        )

    await session.delete(medication)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/check-duplicates", response_model=DuplicateCheckResponse)
async def check_duplicates(
    body: DuplicateCheckRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> DuplicateCheckResponse:
    profile = await resolve_user_profile(
        session=session,
        user_id=current_user.id,
        profile_id=body.profile_id,
    )
    candidate_salts: list[str] = list(body.confirmed_salts or [])
    if not candidate_salts and (body.brand_name or body.raw_ocr_text):
        resolver = BrandSaltResolver(trigram_threshold=settings.trigram_similarity_threshold)
        resolution = await resolver.resolve(
            session=session,
            request=DrugResolveRequest(
                query=body.brand_name or (body.raw_ocr_text or ""),
                raw_ocr_text=body.raw_ocr_text,
                limit=1,
            ),
        )
        if resolution.candidates:
            candidate_salts = resolution.candidates[0].canonical_salt_keys
        elif resolution.extracted_salts:
            candidate_salts = [salt.canonical_salt for salt in resolution.extracted_salts]

    return await evaluate_duplicate_safety(
        session=session,
        user_id=current_user.id,
        profile_id=profile.id,
        candidate_salts=candidate_salts,
        allergies_json=profile.allergies_json,
    )
