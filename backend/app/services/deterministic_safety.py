from collections.abc import Iterable, Sequence
from typing import Any
from uuid import UUID

from sqlalchemy import select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import DdiPair, Medication
from backend.app.schemas.cabinet import DuplicateCheckResponse, DuplicateSaltAlert, RiskLevel
from backend.app.schemas.safety import DeterministicDdiAlert
from backend.app.services.allergy_checker import DRUG_CLASS_MEMBERS, detect_allergy_conflicts
from backend.app.services.drug_normalizer import canonical_salt_keys

_THERAPEUTIC_DUPLICATION_CLASSES: dict[str, tuple[str, str, str]] = {
    "nsaid": (
        "NSAID",
        "Additive COX inhibition increases risk of GI bleeding, ulceration, and renal injury.",
        "Avoid taking multiple systemic NSAIDs concurrently unless explicitly directed.",
    ),
    "ppi": (
        "Proton Pump Inhibitor (PPI)",
        "Duplicate gastric acid suppression offers no added benefit and raises adverse risk.",
        "Review whether two proton pump inhibitors are intentionally prescribed together.",
    ),
    "statin": (
        "Statin",
        "Combining two statins significantly raises myopathy and hepatotoxicity risk.",
        "Verify with prescriber; typically only one statin should be taken at a time.",
    ),
    "ace_inhibitor": (
        "ACE Inhibitor",
        "Duplicate RAAS blockade raises risk of hypotension, hyperkalemia, and renal injury.",
        "Consult prescriber immediately before combining two ACE inhibitors.",
    ),
    "arb": (
        "ARB",
        "Duplicate angiotensin receptor blockade increases hypotension and renal injury risk.",
        "Consult prescriber immediately before combining two ARBs.",
    ),
    "ssri": (
        "SSRI",
        "Combining multiple SSRIs increases risk of serotonin syndrome and QT prolongation.",
        "Verify therapy with prescribing clinician before concurrent use.",
    ),
    "benzodiazepine": (
        "Benzodiazepine",
        "Additive CNS and respiratory depression, sedation, and fall risk.",
        "Avoid duplicating benzodiazepines unless closely supervised by a clinician.",
    ),
    "opioid": (
        "Opioid",
        "Additive CNS and respiratory depression increases risk of overdose.",
        "Consult prescriber or pharmacist immediately before combining opioid analgesics.",
    ),
    "beta_blocker": (
        "Beta-Blocker",
        "Additive bradycardia, AV block, and hypotension risk.",
        "Confirm with prescriber whether duplicate beta-blocker therapy is intended.",
    ),
}

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
    profile_id: UUID | None = None,
) -> list[DuplicateSaltAlert]:
    normalized_keys = canonical_salt_keys(candidate_salts)
    if not normalized_keys:
        return []

    stmt = select(Medication).where(
        Medication.user_id == user_id,
        Medication.is_active.is_(True),
        Medication.canonical_salt_keys.overlap(normalized_keys),
    )
    if profile_id is not None:
        stmt = stmt.where(Medication.profile_id == profile_id)
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
    covered_pairs: set[tuple[str, str]] = set()
    for row in rows:
        sev = _RISK_MAP.get(row.severity)
        if sev is None:
            continue
        covered_pairs.add((row.salt_a, row.salt_b))
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

    for pair in sorted(ordered_pairs):
        if pair in covered_pairs:
            continue
        salt_a, salt_b = pair
        for class_key, (label, effect, management) in _THERAPEUTIC_DUPLICATION_CLASSES.items():
            members = DRUG_CLASS_MEMBERS.get(class_key, frozenset())
            if salt_a in members and salt_b in members:
                alerts.append(
                    DeterministicDdiAlert(
                        rule_id=f"therapeutic_class:{class_key}",
                        salt_a=salt_a,
                        salt_b=salt_b,
                        severity="MODERATE",
                        mechanism=(
                            f"Same-class therapeutic duplication ({label}): {salt_a} and {salt_b}."
                        ),
                        clinical_effect=effect,
                        management=management,
                        sources=["pillscope_therapeutic_class"],
                    )
                )
                break

    alerts.sort(key=lambda item: (-_RISK_ORDER[item.severity], item.salt_a, item.salt_b))
    return alerts


async def evaluate_duplicate_safety(
    session: AsyncSession,
    user_id: UUID,
    candidate_salts: Iterable[str],
    exclude_medication_id: UUID | None = None,
    profile_id: UUID | None = None,
    allergies_json: Sequence[Any] | None = None,
) -> DuplicateCheckResponse:
    resolved_keys = canonical_salt_keys(candidate_salts)
    duplicates = await detect_duplicate_salts(
        session=session,
        user_id=user_id,
        candidate_salts=resolved_keys,
        exclude_medication_id=exclude_medication_id,
        profile_id=profile_id,
    )
    allergy_alerts = detect_allergy_conflicts(
        candidate_salts=resolved_keys,
        allergies_json=allergies_json,
    )
    locked = len(duplicates) > 0 or len(allergy_alerts) > 0
    overall_risk: RiskLevel = "MAJOR" if locked else "NONE"
    return DuplicateCheckResponse(
        profile_id=profile_id,
        resolved_salts=resolved_keys,
        overall_risk=overall_risk,
        safety_banner_locked=locked,
        duplicate_salts=duplicates,
        allergy_alerts=allergy_alerts,
    )
