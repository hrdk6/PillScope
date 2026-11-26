from collections.abc import Iterable
from uuid import UUID

from sqlalchemy import select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import DdiPair, Medication
from backend.app.schemas.cabinet import DuplicateCheckResponse, DuplicateSaltAlert, RiskLevel
from backend.app.schemas.safety import DeterministicDdiAlert
from backend.app.services.drug_normalizer import canonical_salt_keys

_RISK_ORDER: dict[RiskLevel, int] = {"NONE": 0, "MINOR": 1, "MODERATE": 2, "MAJOR": 3}
_RISK_MAP: dict[str, RiskLevel] = {
    "NONE": "NONE",
    "MINOR": "MINOR",
    "MODERATE": "MODERATE",
    "MAJOR": "MAJOR",
}


def max_risk(a: RiskLevel, b: RiskLevel) -> RiskLevel:
    return a if _RISK_ORDER[a] >= _RISK_ORDER[b] else b


async def detect_duplicate_salts(
    session: AsyncSession,
    user_id: UUID,
    candidate_salts: Iterable[str],
    exclude_medication_id: UUID | None = None,
) -> list[DuplicateSaltAlert]:
    normalized_keys = canonical_salt_keys(candidate_salts)
    if not normalized_keys:
        return []

    stmt = select(Medication).where(
        Medication.user_id == user_id,
        Medication.is_active.is_(True),
        Medication.canonical_salt_keys.overlap(normalized_keys),
    )
    if exclude_medication_id is not None:
        stmt = stmt.where(Medication.id != exclude_medication_id)

    existing_meds = list((await session.execute(stmt)).scalars().all())
    candidate_set = set(normalized_keys)

    alerts: list[DuplicateSaltAlert] = []
    for med in existing_meds:
        overlapping = sorted(candidate_set.intersection(med.canonical_salt_keys))
        for salt in overlapping:
            alerts.append(
                DuplicateSaltAlert(
                    canonical_salt=salt,
                    existing_medication_id=med.id,
                    existing_display_name=med.display_name,
                )
            )

    alerts.sort(key=lambda item: (item.canonical_salt, item.existing_display_name))
    return alerts


async def detect_deterministic_ddi_alerts(
    session: AsyncSession,
    candidate_salts: Iterable[str],
    cabinet_salts: Iterable[str],
) -> list[DeterministicDdiAlert]:
    cand_keys = canonical_salt_keys(candidate_salts)
    cab_keys = canonical_salt_keys(cabinet_salts)
    if not cand_keys or not cab_keys:
        return []

    ordered_pairs: set[tuple[str, str]] = set()
    for c_salt in cand_keys:
        for m_salt in cab_keys:
            if c_salt == m_salt:
                continue
            pair = (c_salt, m_salt) if c_salt < m_salt else (m_salt, c_salt)
            ordered_pairs.add(pair)

    if not ordered_pairs:
        return []

    stmt = select(DdiPair).where(
        tuple_(DdiPair.salt_a, DdiPair.salt_b).in_(sorted(ordered_pairs)),
        DdiPair.severity != "UNKNOWN",
    )
    rows = list((await session.execute(stmt)).scalars().all())

    alerts: list[DeterministicDdiAlert] = []
    for row in rows:
        sev = _RISK_MAP.get(row.severity)
        if sev is None:
            continue
        alerts.append(
            DeterministicDdiAlert(
                rule_id=f"ddi_pairs:{row.id}",
                salt_a=row.salt_a,
                salt_b=row.salt_b,
                severity=sev,
                mechanism=row.mechanism,
                clinical_effect=row.clinical_effect,
                management=row.management,
                sources=list(row.sources),
            )
        )

    alerts.sort(key=lambda item: (-_RISK_ORDER[item.severity], item.salt_a, item.salt_b))
    return alerts


async def evaluate_duplicate_safety(
    session: AsyncSession,
    user_id: UUID,
    candidate_salts: Iterable[str],
    exclude_medication_id: UUID | None = None,
) -> DuplicateCheckResponse:
    resolved_keys = canonical_salt_keys(candidate_salts)
    duplicates = await detect_duplicate_salts(
        session=session,
        user_id=user_id,
        candidate_salts=resolved_keys,
        exclude_medication_id=exclude_medication_id,
    )
    locked = len(duplicates) > 0
    overall_risk: RiskLevel = "MAJOR" if locked else "NONE"
    return DuplicateCheckResponse(
        resolved_salts=resolved_keys,
        overall_risk=overall_risk,
        safety_banner_locked=locked,
        duplicate_salts=duplicates,
    )
