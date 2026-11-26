import re
from collections.abc import Sequence
from dataclasses import dataclass, field
from uuid import UUID

from sqlalchemy import select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import DdiPair, Medication, Profile
from backend.app.schemas.lifestyle import LifestyleTrigger
from backend.app.schemas.schedule import (
    AppliedSeparationConstraint,
    DoseSlotName,
    ProfileScheduleResponse,
    ScheduledDoseItem,
    ScheduleSlotGroup,
    UnresolvableScheduleClash,
)
from backend.app.services.drug_normalizer import canonical_salt_keys
from backend.app.services.lifestyle_triggers import extract_lifestyle_triggers_by_salt

SLOT_DEFINITIONS: tuple[tuple[DoseSlotName, str, int], ...] = (
    ("Morning (Empty Stomach)", "07:00", 420),
    ("Morning (With Food)", "08:00", 480),
    ("Afternoon", "13:00", 780),
    ("Evening", "18:30", 1110),
    ("Bedtime", "22:00", 1320),
)

SLOT_INDEX: dict[DoseSlotName, int] = {
    slot_name: idx for idx, (slot_name, _, _) in enumerate(SLOT_DEFINITIONS)
}

_THYROID_SALTS: frozenset[str] = frozenset({"levothyroxine", "liothyronine", "thyroid"})
_BISPHOSPHONATE_SALTS: frozenset[str] = frozenset(
    {"alendronate", "risedronate", "ibandronate", "etidronate", "tiludronate"}
)
_FLUOROQUINOLONE_SALTS: frozenset[str] = frozenset(
    {
        "ciprofloxacin",
        "levofloxacin",
        "ofloxacin",
        "moxifloxacin",
        "norfloxacin",
        "gatifloxacin",
        "gemifloxacin",
        "delafloxacin",
    }
)
_TETRACYCLINE_SALTS: frozenset[str] = frozenset(
    {
        "doxycycline",
        "minocycline",
        "tetracycline",
        "demeclocycline",
        "lymecycline",
        "oxytetracycline",
    }
)
_IRON_SALTS: frozenset[str] = frozenset(
    {
        "ferrous sulfate",
        "ferrous fumarate",
        "ferrous gluconate",
        "ferrous ascorbate",
        "iron",
        "carbonyl iron",
        "iron polymaltose",
        "ferric citrate",
        "ferric maltol",
    }
)
_CALCIUM_SALTS: frozenset[str] = frozenset(
    {
        "calcium",
        "calcium carbonate",
        "calcium citrate",
        "calcium gluconate",
        "calcium lactate",
        "calcium acetate",
    }
)
_ANTACID_CATION_SALTS: frozenset[str] = frozenset(
    {
        "aluminum hydroxide",
        "magnesium hydroxide",
        "magnesium oxide",
        "magnesium citrate",
        "magnesium sulfate",
        "magnesium",
        "sucralfate",
        "magaldrate",
        "sodium bicarbonate",
        "bismuth subsalicylate",
        "zinc",
        "zinc sulfate",
        "zinc gluconate",
        "zinc acetate",
    }
)
_BILE_SEQUESTRANT_SALTS: frozenset[str] = frozenset({"cholestyramine", "colestipol", "colesevelam"})
_PPI_SALTS: frozenset[str] = frozenset(
    {
        "omeprazole",
        "pantoprazole",
        "esomeprazole",
        "rabeprazole",
        "lansoprazole",
        "dexlansoprazole",
    }
)
_SEDATING_SALTS: frozenset[str] = frozenset(
    {
        "diphenhydramine",
        "hydroxyzine",
        "chlorpheniramine",
        "promethazine",
        "doxylamine",
        "cyproheptadine",
        "cetirizine",
        "levocetirizine",
        "zolpidem",
        "zopiclone",
        "eszopiclone",
        "melatonin",
        "trazodone",
        "mirtazapine",
        "amitriptyline",
        "nortriptyline",
        "doxepin",
        "imipramine",
        "gabapentin",
        "pregabalin",
        "quetiapine",
        "olanzapine",
        "cyclobenzaprine",
        "tizanidine",
        "baclofen",
        "methocarbamol",
        "chlorzoxazone",
        "alprazolam",
        "lorazepam",
        "diazepam",
        "clonazepam",
        "temazepam",
        "nitrazepam",
    }
)
_EVENING_OPTIMAL_SALTS: frozenset[str] = frozenset(
    {
        "montelukast",
        "simvastatin",
        "lovastatin",
        "pravastatin",
        "fluvastatin",
    }
)
_DIURETIC_SALTS: frozenset[str] = frozenset(
    {
        "furosemide",
        "torsemide",
        "bumetanide",
        "hydrochlorothiazide",
        "chlorthalidone",
        "indapamide",
        "metolazone",
        "spironolactone",
        "eplerenone",
        "amiloride",
        "acetazolamide",
    }
)
_STIMULANT_SALTS: frozenset[str] = frozenset(
    {
        "methylphenidate",
        "amphetamine",
        "dextroamphetamine",
        "lisdexamfetamine",
        "modafinil",
        "armodafinil",
        "atomoxetine",
        "bupropion",
        "caffeine",
        "pseudoephedrine",
        "phenylephrine",
        "fluoxetine",
        "prednisone",
        "prednisolone",
        "dexamethasone",
        "methylprednisolone",
    }
)
_WITH_FOOD_SALTS: frozenset[str] = frozenset(
    {
        "metformin",
        "ibuprofen",
        "diclofenac",
        "naproxen",
        "aspirin",
        "aceclofenac",
        "meloxicam",
        "celecoxib",
        "ketorolac",
        "indomethacin",
        "piroxicam",
        "etoricoxib",
        "mefenamic acid",
        "prednisone",
        "prednisolone",
        "dexamethasone",
        "methylprednisolone",
        "rivaroxaban",
        "nitrofurantoin",
        "itraconazole",
        "vitamin d3",
        "carvedilol",
        "spironolactone",
    }
)
_EMPTY_STOMACH_SALTS: frozenset[str] = frozenset(
    {
        "levothyroxine",
        "liothyronine",
        "alendronate",
        "risedronate",
        "ibandronate",
        "omeprazole",
        "pantoprazole",
        "esomeprazole",
        "rabeprazole",
        "lansoprazole",
        "sucralfate",
        "captopril",
        "rifampin",
        "isoniazid",
    }
)

_QID_RE = re.compile(
    r"\b(?:qid|q\.i\.d\.|four\s+times\s+daily|4\s*x\s*daily|qds|every\s+6\s*(?:h|hr|hrs|hours)|q6h)\b",
    re.IGNORECASE,
)
_TID_RE = re.compile(
    r"\b(?:tid|t\.i\.d\.|three\s+times\s+daily|3\s*x\s*daily|tds|every\s+8\s*(?:h|hr|hrs|hours)|q8h)\b",
    re.IGNORECASE,
)
_BID_RE = re.compile(
    r"\b(?:bid|b\.i\.d\.|twice\s+daily|2\s*x\s*daily|bd|every\s+12\s*(?:h|hr|hrs|hours)|q12h)\b",
    re.IGNORECASE,
)
_WEEKLY_RE = re.compile(
    r"\b(?:weekly|once\s+(?:a\s+)?week|every\s+week|qwk|q7d)\b",
    re.IGNORECASE,
)
_ALTERNATE_DAY_RE = re.compile(
    r"\b(?:every\s+other\s+day|alternate\s+days?|qod)\b",
    re.IGNORECASE,
)
_BEDTIME_RE = re.compile(r"\b(?:hs|qhs|bedtime|at\s+night|nocte)\b", re.IGNORECASE)
_MORNING_RE = re.compile(r"\b(?:qam|morning|in\s+the\s+morning|mane)\b", re.IGNORECASE)
_EMPTY_RE = re.compile(r"\b(?:empty\s+stomach|before\s+(?:breakfast|food|meals)|ac)\b", re.I)
_FOOD_RE = re.compile(r"\b(?:with\s+(?:food|meals)|after\s+(?:food|meals)|pc)\b", re.IGNORECASE)


@dataclass(frozen=True, slots=True)
class ScheduleMedicationInput:
    medication_id: UUID
    display_name: str
    canonical_salt_keys: tuple[str, ...]
    dosage_schedule: str | None = None
    lifestyle_triggers: tuple[LifestyleTrigger, ...] = ()


@dataclass(frozen=True, slots=True)
class MajorDdiRecord:
    salt_a: str
    salt_b: str
    mechanism: str
    clinical_effect: str
    management: str


@dataclass(slots=True)
class _DoseNode:
    node_idx: int
    med: ScheduleMedicationInput
    dose_number: int
    total_daily_doses: int
    tags: frozenset[str]
    slot_costs: tuple[int, int, int, int, int]
    frequency_note: str | None = None
    rationale: list[str] = field(default_factory=list)


@dataclass(frozen=True, slots=True)
class _PairwiseConstraint:
    node_a: int
    node_b: int
    min_minutes: int
    salt_a: str
    salt_b: str
    reason: str
    same_medication: bool = False


def circular_gap_minutes(minute_a: int, minute_b: int) -> int:
    diff = abs(minute_a - minute_b)
    return min(diff, 1440 - diff)


def _classify_tags(salts: Sequence[str]) -> frozenset[str]:
    tags: set[str] = set()
    for salt in salts:
        if salt in _THYROID_SALTS:
            tags.add("THYROID")
        if salt in _BISPHOSPHONATE_SALTS or salt.endswith("dronate"):
            tags.add("BISPHOSPHONATE")
        if salt in _FLUOROQUINOLONE_SALTS or salt.endswith("floxacin"):
            tags.add("FLUOROQUINOLONE")
        if salt in _TETRACYCLINE_SALTS:
            tags.add("TETRACYCLINE")
        if salt in _IRON_SALTS or salt.startswith("ferrous "):
            tags.add("IRON")
        if salt in _CALCIUM_SALTS or salt.startswith("calcium "):
            tags.add("CALCIUM")
        if salt in _ANTACID_CATION_SALTS or salt.startswith(("magnesium ", "aluminum ", "zinc ")):
            tags.add("ANTACID_CATION")
        if salt in _BILE_SEQUESTRANT_SALTS:
            tags.add("BILE_SEQUESTRANT")
        if salt in _PPI_SALTS:
            tags.add("PPI")
        if salt in _SEDATING_SALTS:
            tags.add("SEDATING")
        if salt in _EVENING_OPTIMAL_SALTS:
            tags.add("EVENING_OPTIMAL")
        if salt in _DIURETIC_SALTS:
            tags.add("DIURETIC")
        if salt in _STIMULANT_SALTS:
            tags.add("STIMULANT")
        if salt in _WITH_FOOD_SALTS:
            tags.add("WITH_FOOD")
        if salt in _EMPTY_STOMACH_SALTS:
            tags.add("EMPTY_STOMACH")
    return frozenset(tags)


def _parse_daily_doses(dosage_schedule: str | None) -> int:
    if not dosage_schedule:
        return 1
    if _WEEKLY_RE.search(dosage_schedule) or _ALTERNATE_DAY_RE.search(dosage_schedule):
        return 1
    if _QID_RE.search(dosage_schedule):
        return 4
    if _TID_RE.search(dosage_schedule):
        return 3
    if _BID_RE.search(dosage_schedule):
        return 2
    return 1


def _detect_frequency_note(dosage_schedule: str | None) -> str | None:
    if not dosage_schedule:
        return None
    if _WEEKLY_RE.search(dosage_schedule):
        return (
            f"Weekly regimen ({dosage_schedule.strip()}): take once per week on your chosen "
            "dosing day, not daily."
        )
    if _ALTERNATE_DAY_RE.search(dosage_schedule):
        return (
            f"Alternate-day regimen ({dosage_schedule.strip()}): take every other day, not daily."
        )
    return None


def _compute_slot_costs_and_rationale(
    med: ScheduleMedicationInput,
    tags: frozenset[str],
    dose_number: int,
    total_doses: int,
) -> tuple[tuple[int, int, int, int, int], list[str]]:
    costs = [30, 15, 20, 20, 35]
    rationale: list[str] = []

    if "BISPHOSPHONATE" in tags:
        costs = [0, 120, 120, 120, 140]
        rationale.append(
            "Bisphosphonates require an empty stomach with plain water 30-60 minutes "
            "before food or other medications."
        )
    elif "THYROID" in tags:
        costs = [0, 75, 70, 70, 22]
        rationale.append(
            "Levothyroxine/thyroid hormone is best absorbed on an empty stomach "
            "30-60 minutes before breakfast."
        )
    elif "EMPTY_STOMACH" in tags:
        costs = [4, 45, 45, 24, 50]
        rationale.append("Optimized for empty-stomach administration before meals.")

    if "SEDATING" in tags:
        costs[0] += 80
        costs[1] += 70
        costs[2] += 45
        costs[3] += 10
        costs[4] = max(costs[4] - 32, 0)
        rationale.append(
            "Sedating medication biased toward Bedtime to minimize daytime drowsiness."
        )
    elif "EVENING_OPTIMAL" in tags:
        costs[0] += 55
        costs[1] += 45
        costs[2] += 30
        costs[3] += 8
        costs[4] = max(costs[4] - 28, 0)
        rationale.append(
            "Evening-optimal medication (aligned with nighttime pharmacodynamics) "
            "biased toward Bedtime."
        )

    if "DIURETIC" in tags:
        costs[0] += 8
        costs[1] = max(costs[1] - 12, 0)
        costs[2] += 18
        costs[3] += 75
        costs[4] += 110
        rationale.append("Diuretic biased toward Morning to reduce nighttime urination (nocturia).")

    if "STIMULANT" in tags:
        costs[0] += 8
        costs[1] = max(costs[1] - 12, 0)
        costs[2] += 20
        costs[3] += 80
        costs[4] += 115
        rationale.append("Activating/stimulant medication biased toward Morning to avoid insomnia.")

    if "WITH_FOOD" in tags or "CALCIUM" in tags:
        costs[0] += 45
        costs[1] = max(costs[1] - 8, 0)
        costs[2] = max(costs[2] - 6, 0)
        costs[3] = max(costs[3] - 6, 0)
        costs[4] += 25
        if "CALCIUM" in tags:
            rationale.append("Calcium supplements absorb better and are gentler with meals.")
        else:
            rationale.append("Take with food to enhance absorption or minimize stomach upset.")

    if "IRON" in tags:
        costs[1] += 6
        costs[2] = max(costs[2] - 8, 0)
        costs[3] = max(costs[3] - 4, 0)
        rationale.append("Iron scheduled away from thyroid hormones, antibiotics, and calcium.")

    if "FLUOROQUINOLONE" in tags:
        costs[4] += 25
        rationale.append(
            "Fluoroquinolone antibiotic: avoid dairy products and mineral supplements "
            "within 2-4 hours."
        )

    if "TETRACYCLINE" in tags:
        costs[4] += 60
        rationale.append(
            "Tetracycline antibiotic: avoid dairy/cations within 2-4 hours and avoid "
            "lying down immediately after dosing."
        )

    trigger_types = {t.type for t in med.lifestyle_triggers}
    if "EMPTY_STOMACH" in trigger_types and "THYROID" not in tags and "BISPHOSPHONATE" not in tags:
        costs[0] = max(costs[0] - 20, 0)
        costs[1] += 20
        rationale.append("Monograph indicates empty-stomach administration.")
    if "WITH_FOOD" in trigger_types and "WITH_FOOD" not in tags:
        costs[0] += 25
        costs[1] = max(costs[1] - 8, 0)
        costs[2] = max(costs[2] - 6, 0)
        costs[3] = max(costs[3] - 6, 0)
        rationale.append("Monograph recommends taking with food.")
    if "DROWSINESS" in trigger_types and "SEDATING" not in tags:
        costs[4] = max(costs[4] - 20, 0)
        costs[1] += 15
        rationale.append("Monograph warns of drowsiness; biased toward Bedtime.")

    schedule_text = med.dosage_schedule or ""
    if schedule_text:
        if _BEDTIME_RE.search(schedule_text):
            costs[4] = max(costs[4] - 40, 0)
            costs[1] += 30
            rationale.append(f"Aligned with prescribed schedule ({schedule_text}).")
        elif _MORNING_RE.search(schedule_text):
            costs[1] = max(costs[1] - 25, 0)
            costs[4] += 35
            rationale.append(f"Aligned with prescribed schedule ({schedule_text}).")
        if _EMPTY_RE.search(schedule_text):
            costs[0] = max(costs[0] - 30, 0)
        if _FOOD_RE.search(schedule_text):
            costs[0] += 30
            costs[1] = max(costs[1] - 10, 0)

    if total_doses == 2:
        if dose_number == 1:
            costs[1] = max(costs[1] - 12, 0)
            costs[3] += 20
            costs[4] += 30
        else:
            costs[3] = max(costs[3] - 12, 0)
            costs[0] += 30
            costs[1] += 25
    elif total_doses == 3:
        preferred = (1, 2, 3)[dose_number - 1]
        for idx in range(5):
            if idx != preferred:
                costs[idx] += 18
    elif total_doses == 4:
        preferred = (1, 2, 3, 4)[dose_number - 1]
        for idx in range(5):
            if idx != preferred:
                costs[idx] += 18

    freq_note = _detect_frequency_note(med.dosage_schedule)
    if freq_note:
        rationale.insert(0, freq_note)

    if not rationale:
        rationale.append("Standard daily schedule slot selected with no absorption conflicts.")

    return (costs[0], costs[1], costs[2], costs[3], costs[4]), list(dict.fromkeys(rationale))


def _first_matching_salt(salts: Sequence[str], target_set: frozenset[str], suffix: str = "") -> str:
    for salt in salts:
        if salt in target_set or (suffix and salt.endswith(suffix)):
            return salt
    return salts[0] if salts else "unknown"


def _evaluate_absorption_rule(
    med_a: ScheduleMedicationInput,
    tags_a: frozenset[str],
    med_b: ScheduleMedicationInput,
    tags_b: frozenset[str],
) -> tuple[int, str, str, str] | None:
    best: tuple[int, str, str, str] | None = None

    def _consider(minutes: int, salt_a: str, salt_b: str, reason: str) -> None:
        nonlocal best
        if best is None or minutes > best[0]:
            best = (minutes, salt_a, salt_b, reason)

    def _check_directional(
        m1: ScheduleMedicationInput,
        t1: frozenset[str],
        m2: ScheduleMedicationInput,
        t2: frozenset[str],
        swapped: bool,
    ) -> None:
        def _pair(s1: str, s2: str) -> tuple[str, str]:
            return (s2, s1) if swapped else (s1, s2)

        if "THYROID" in t1 and "IRON" in t2:
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _THYROID_SALTS),
                _first_matching_salt(m2.canonical_salt_keys, _IRON_SALTS),
            )
            _consider(
                240,
                sa,
                sb,
                "Separate levothyroxine from iron by at least 4 hours to prevent absorption loss.",
            )
        if "THYROID" in t1 and "CALCIUM" in t2:
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _THYROID_SALTS),
                _first_matching_salt(m2.canonical_salt_keys, _CALCIUM_SALTS),
            )
            _consider(
                240,
                sa,
                sb,
                "Separate levothyroxine from calcium by at least 4 hours to prevent "
                "binding in the gut.",
            )
        if "THYROID" in t1 and "ANTACID_CATION" in t2:
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _THYROID_SALTS),
                _first_matching_salt(m2.canonical_salt_keys, _ANTACID_CATION_SALTS),
            )
            _consider(
                240,
                sa,
                sb,
                "Separate levothyroxine from antacids/divalent cations by at least 4 hours.",
            )
        if "THYROID" in t1 and "BILE_SEQUESTRANT" in t2:
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _THYROID_SALTS),
                _first_matching_salt(m2.canonical_salt_keys, _BILE_SEQUESTRANT_SALTS),
            )
            _consider(
                240,
                sa,
                sb,
                "Separate levothyroxine from bile acid sequestrants by at least 4 hours.",
            )
        if "FLUOROQUINOLONE" in t1 and (t2 & {"CALCIUM", "IRON", "ANTACID_CATION"}):
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _FLUOROQUINOLONE_SALTS, "floxacin"),
                _first_matching_salt(
                    m2.canonical_salt_keys,
                    _CALCIUM_SALTS | _IRON_SALTS | _ANTACID_CATION_SALTS,
                ),
            )
            _consider(
                240,
                sa,
                sb,
                "Separate fluoroquinolone antibiotic from calcium, iron, zinc, magnesium, "
                "antacids, and dairy by 2-4 hours to avoid chelation.",
            )
        if "TETRACYCLINE" in t1 and (t2 & {"CALCIUM", "IRON", "ANTACID_CATION"}):
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _TETRACYCLINE_SALTS),
                _first_matching_salt(
                    m2.canonical_salt_keys,
                    _CALCIUM_SALTS | _IRON_SALTS | _ANTACID_CATION_SALTS,
                ),
            )
            _consider(
                180,
                sa,
                sb,
                "Separate tetracycline antibiotic from calcium, iron, zinc, magnesium, "
                "antacids, and dairy by 2-4 hours to avoid chelation.",
            )
        if "BISPHOSPHONATE" in t1:
            if t2 & {"CALCIUM", "IRON", "ANTACID_CATION"}:
                sa, sb = _pair(
                    _first_matching_salt(m1.canonical_salt_keys, _BISPHOSPHONATE_SALTS, "dronate"),
                    _first_matching_salt(
                        m2.canonical_salt_keys,
                        _CALCIUM_SALTS | _IRON_SALTS | _ANTACID_CATION_SALTS,
                    ),
                )
                _consider(
                    120,
                    sa,
                    sb,
                    "Separate oral bisphosphonate on an empty stomach from calcium, iron, "
                    "and antacids by at least 2 hours.",
                )
            else:
                sa, sb = _pair(
                    _first_matching_salt(m1.canonical_salt_keys, _BISPHOSPHONATE_SALTS, "dronate"),
                    m2.canonical_salt_keys[0] if m2.canonical_salt_keys else "other",
                )
                _consider(
                    60,
                    sa,
                    sb,
                    "Separate oral bisphosphonate on an empty stomach by at least 30-60 "
                    "minutes before food or any other oral medication.",
                )
        if "IRON" in t1 and "CALCIUM" in t2:
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _IRON_SALTS),
                _first_matching_salt(m2.canonical_salt_keys, _CALCIUM_SALTS),
            )
            _consider(
                120,
                sa,
                sb,
                "Separate iron and calcium supplements by at least 2 hours to prevent "
                "competitive absorption.",
            )
        if "IRON" in t1 and "ANTACID_CATION" in t2:
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _IRON_SALTS),
                _first_matching_salt(m2.canonical_salt_keys, _ANTACID_CATION_SALTS),
            )
            _consider(
                120,
                sa,
                sb,
                "Separate iron from antacids, zinc, or magnesium by at least 2 hours.",
            )
        if "THYROID" in t1 and "PPI" in t2:
            sa, sb = _pair(
                _first_matching_salt(m1.canonical_salt_keys, _THYROID_SALTS),
                _first_matching_salt(m2.canonical_salt_keys, _PPI_SALTS),
            )
            _consider(
                60,
                sa,
                sb,
                "Separate levothyroxine from proton pump inhibitors by at least 60 minutes "
                "for consistent absorption.",
            )

    _check_directional(med_a, tags_a, med_b, tags_b, swapped=False)
    _check_directional(med_b, tags_b, med_a, tags_a, swapped=True)
    return best


def _solve_assignment(
    nodes: list[_DoseNode],
    constraints: list[_PairwiseConstraint],
) -> list[int]:
    n = len(nodes)
    if n == 0:
        return []

    adj: list[list[tuple[int, int]]] = [[] for _ in range(n)]
    for c in constraints:
        adj[c.node_a].append((c.node_b, c.min_minutes))
        adj[c.node_b].append((c.node_a, c.min_minutes))

    slot_minutes = [defn[2] for defn in SLOT_DEFINITIONS]
    order = sorted(
        range(n),
        key=lambda idx: (
            -len(adj[idx]),
            min(nodes[idx].slot_costs),
            -max(nodes[idx].slot_costs),
            idx,
        ),
    )

    if n <= 12:
        best_assignment = [0] * n
        best_score: tuple[int, int] = (10**9, 10**9)
        current = [-1] * n
        min_remaining_cost = [0] * (n + 1)
        for pos in range(n - 1, -1, -1):
            node = nodes[order[pos]]
            min_remaining_cost[pos] = min_remaining_cost[pos + 1] + min(node.slot_costs)

        def _backtrack(pos: int, cur_violations: int, cur_cost: int) -> None:
            nonlocal best_score
            if cur_violations > best_score[0]:
                return
            if (
                cur_violations == best_score[0]
                and (cur_cost + min_remaining_cost[pos]) >= best_score[1]
            ):
                return
            if pos == n:
                best_score = (cur_violations, cur_cost)
                for i in range(n):
                    best_assignment[i] = current[i]
                return

            node_idx = order[pos]
            node = nodes[node_idx]
            slot_order = sorted(range(5), key=lambda s: (node.slot_costs[s], s))
            for slot_idx in slot_order:
                added_violations = 0
                slot_min = slot_minutes[slot_idx]
                for neighbor_idx, min_sep in adj[node_idx]:
                    neighbor_slot = current[neighbor_idx]
                    if (
                        neighbor_slot != -1
                        and circular_gap_minutes(slot_min, slot_minutes[neighbor_slot]) < min_sep
                    ):
                        added_violations += 1
                next_violations = cur_violations + added_violations
                next_cost = cur_cost + node.slot_costs[slot_idx]
                if next_violations > best_score[0]:
                    continue
                if (
                    next_violations == best_score[0]
                    and (next_cost + min_remaining_cost[pos + 1]) >= best_score[1]
                ):
                    continue
                current[node_idx] = slot_idx
                _backtrack(pos + 1, next_violations, next_cost)
                current[node_idx] = -1

        _backtrack(0, 0, 0)
        return best_assignment

    assignment = [-1] * n
    for node_idx in order:
        node = nodes[node_idx]
        best_slot = 0
        best_local: tuple[int, int, int] = (10**9, 10**9, 10**9)
        for slot_idx in range(5):
            violations = 0
            slot_min = slot_minutes[slot_idx]
            for neighbor_idx, min_sep in adj[node_idx]:
                neighbor_slot = assignment[neighbor_idx]
                if (
                    neighbor_slot != -1
                    and circular_gap_minutes(slot_min, slot_minutes[neighbor_slot]) < min_sep
                ):
                    violations += 1
            score = (violations, node.slot_costs[slot_idx], slot_idx)
            if score < best_local:
                best_local = score
                best_slot = slot_idx
        assignment[node_idx] = best_slot

    for _ in range(4):
        improved = False
        for node_idx in order:
            node = nodes[node_idx]
            cur_slot = assignment[node_idx]
            cur_v = sum(
                1
                for nb, min_sep in adj[node_idx]
                if circular_gap_minutes(slot_minutes[cur_slot], slot_minutes[assignment[nb]])
                < min_sep
            )
            cur_pair = (cur_v, node.slot_costs[cur_slot], cur_slot)
            best_slot = cur_slot
            best_pair = cur_pair
            for candidate_slot in range(5):
                cand_v = sum(
                    1
                    for nb, min_sep in adj[node_idx]
                    if circular_gap_minutes(
                        slot_minutes[candidate_slot], slot_minutes[assignment[nb]]
                    )
                    < min_sep
                )
                cand_pair = (cand_v, node.slot_costs[candidate_slot], candidate_slot)
                if cand_pair < best_pair:
                    best_pair = cand_pair
                    best_slot = candidate_slot
            if best_slot != cur_slot:
                assignment[node_idx] = best_slot
                improved = True
        if not improved:
            break

    return assignment


def solve_daily_schedule(
    *,
    profile_id: UUID,
    profile_name: str,
    medications: Sequence[ScheduleMedicationInput],
    major_ddis: Sequence[MajorDdiRecord] = (),
) -> ProfileScheduleResponse:
    ordered_meds = sorted(
        medications,
        key=lambda m: (m.canonical_salt_keys, m.display_name.lower(), str(m.medication_id)),
    )

    nodes: list[_DoseNode] = []
    med_to_node_indices: dict[UUID, list[int]] = {}
    tags_by_med: dict[UUID, frozenset[str]] = {}

    for med in ordered_meds:
        tags = _classify_tags(med.canonical_salt_keys)
        tags_by_med[med.medication_id] = tags
        total_doses = _parse_daily_doses(med.dosage_schedule)
        freq_note = _detect_frequency_note(med.dosage_schedule)
        for dose_num in range(1, total_doses + 1):
            costs, rationale = _compute_slot_costs_and_rationale(med, tags, dose_num, total_doses)
            idx = len(nodes)
            nodes.append(
                _DoseNode(
                    node_idx=idx,
                    med=med,
                    dose_number=dose_num,
                    total_daily_doses=total_doses,
                    tags=tags,
                    slot_costs=costs,
                    frequency_note=freq_note,
                    rationale=rationale,
                )
            )
            med_to_node_indices.setdefault(med.medication_id, []).append(idx)

    constraints: list[_PairwiseConstraint] = []
    for med in ordered_meds:
        node_indices = med_to_node_indices.get(med.medication_id, [])
        if len(node_indices) == 2:
            constraints.append(
                _PairwiseConstraint(
                    node_a=node_indices[0],
                    node_b=node_indices[1],
                    min_minutes=480,
                    salt_a=med.canonical_salt_keys[0] if med.canonical_salt_keys else "",
                    salt_b=med.canonical_salt_keys[0] if med.canonical_salt_keys else "",
                    reason="Twice-daily doses separated across morning and evening.",
                    same_medication=True,
                )
            )
        elif len(node_indices) >= 3:
            min_gap = 180 if len(node_indices) >= 4 else 240
            spacing_reason = (
                "Four-times-daily doses spaced across the day."
                if len(node_indices) >= 4
                else "Three-times-daily doses spaced across the day."
            )
            for i in range(len(node_indices)):
                for j in range(i + 1, len(node_indices)):
                    constraints.append(
                        _PairwiseConstraint(
                            node_a=node_indices[i],
                            node_b=node_indices[j],
                            min_minutes=min_gap,
                            salt_a=med.canonical_salt_keys[0] if med.canonical_salt_keys else "",
                            salt_b=med.canonical_salt_keys[0] if med.canonical_salt_keys else "",
                            reason=spacing_reason,
                            same_medication=True,
                        )
                    )

    for i in range(len(ordered_meds)):
        for j in range(i + 1, len(ordered_meds)):
            med_a = ordered_meds[i]
            med_b = ordered_meds[j]
            rule = _evaluate_absorption_rule(
                med_a,
                tags_by_med[med_a.medication_id],
                med_b,
                tags_by_med[med_b.medication_id],
            )
            if rule is None:
                continue
            min_minutes, salt_a, salt_b, reason = rule
            for na in med_to_node_indices[med_a.medication_id]:
                for nb in med_to_node_indices[med_b.medication_id]:
                    constraints.append(
                        _PairwiseConstraint(
                            node_a=na,
                            node_b=nb,
                            min_minutes=min_minutes,
                            salt_a=salt_a,
                            salt_b=salt_b,
                            reason=reason,
                            same_medication=False,
                        )
                    )

    assignment = _solve_assignment(nodes, constraints)
    slot_minutes = [defn[2] for defn in SLOT_DEFINITIONS]

    separation_notes_by_node: dict[int, list[str]] = {i: [] for i in range(len(nodes))}
    applied_by_med_pair: dict[tuple[UUID, UUID], AppliedSeparationConstraint] = {}
    unresolvable_clashes: list[UnresolvableScheduleClash] = []

    for c in constraints:
        if c.same_medication:
            continue
        node_a = nodes[c.node_a]
        node_b = nodes[c.node_b]
        gap = circular_gap_minutes(
            slot_minutes[assignment[c.node_a]],
            slot_minutes[assignment[c.node_b]],
        )
        satisfied = gap >= c.min_minutes
        gap_hours = round(gap / 60.0, 1)
        min_hours = round(c.min_minutes / 60.0, 1)
        note_a = (
            f"Spaced {gap_hours}h from {node_b.med.display_name} "
            f"(requires >={min_hours}h): {c.reason}"
        )
        note_b = (
            f"Spaced {gap_hours}h from {node_a.med.display_name} "
            f"(requires >={min_hours}h): {c.reason}"
        )
        if note_a not in separation_notes_by_node[c.node_a]:
            separation_notes_by_node[c.node_a].append(note_a)
        if note_b not in separation_notes_by_node[c.node_b]:
            separation_notes_by_node[c.node_b].append(note_b)

        pair_key = (node_a.med.medication_id, node_b.med.medication_id)
        record = AppliedSeparationConstraint(
            medication_a_id=node_a.med.medication_id,
            medication_a_name=node_a.med.display_name,
            salt_a=c.salt_a,
            medication_b_id=node_b.med.medication_id,
            medication_b_name=node_b.med.display_name,
            salt_b=c.salt_b,
            min_separation_minutes=c.min_minutes,
            actual_separation_minutes=gap,
            satisfied=satisfied,
            reason=c.reason,
        )
        existing = applied_by_med_pair.get(pair_key)
        if existing is None or gap < existing.actual_separation_minutes:
            applied_by_med_pair[pair_key] = record

    for sep in applied_by_med_pair.values():
        if not sep.satisfied:
            unresolvable_clashes.append(
                UnresolvableScheduleClash(
                    medication_a_id=sep.medication_a_id,
                    medication_a_name=sep.medication_a_name,
                    salt_a=sep.salt_a,
                    medication_b_id=sep.medication_b_id,
                    medication_b_name=sep.medication_b_name,
                    salt_b=sep.salt_b,
                    severity="MAJOR",
                    clash_type="TIMING_UNSATISFIABLE",
                    mechanism="Conflicting dosing frequency or absorption spacing constraints.",
                    management=(
                        "Consult a pharmacist to adjust dosing frequency or alternative therapy."
                    ),
                    reason=(
                        f"Could not guarantee >={sep.min_separation_minutes} min separation "
                        f"between {sep.medication_a_name} and {sep.medication_b_name} "
                        f"(closest gap {sep.actual_separation_minutes} min)."
                    ),
                )
            )

    seen_dup_pairs: set[tuple[UUID, UUID, str]] = set()
    for i in range(len(ordered_meds)):
        for j in range(i + 1, len(ordered_meds)):
            med_a = ordered_meds[i]
            med_b = ordered_meds[j]
            overlap = sorted(set(med_a.canonical_salt_keys) & set(med_b.canonical_salt_keys))
            for shared_salt in overlap:
                dup_key = (med_a.medication_id, med_b.medication_id, shared_salt)
                if dup_key in seen_dup_pairs:
                    continue
                seen_dup_pairs.add(dup_key)
                unresolvable_clashes.append(
                    UnresolvableScheduleClash(
                        medication_a_id=med_a.medication_id,
                        medication_a_name=med_a.display_name,
                        salt_a=shared_salt,
                        medication_b_id=med_b.medication_id,
                        medication_b_name=med_b.display_name,
                        salt_b=shared_salt,
                        severity="MAJOR",
                        clash_type="DUPLICATE_ACTIVE_INGREDIENT",
                        mechanism="Duplicate active pharmaceutical ingredient in active cabinet.",
                        management="Do not combine duplicate active salts; review with clinician.",
                        reason=(
                            f"Both {med_a.display_name} and {med_b.display_name} contain "
                            f"'{shared_salt}'; dose timing cannot resolve duplicate therapy."
                        ),
                    )
                )

    ddi_by_pair: dict[tuple[str, str], MajorDdiRecord] = {}
    for ddi in major_ddis:
        norm_pair = (
            (ddi.salt_a, ddi.salt_b) if ddi.salt_a < ddi.salt_b else (ddi.salt_b, ddi.salt_a)
        )
        ddi_by_pair[norm_pair] = ddi

    seen_ddi_med_pairs: set[tuple[UUID, UUID, str, str]] = set()
    for i in range(len(ordered_meds)):
        for j in range(i + 1, len(ordered_meds)):
            med_a = ordered_meds[i]
            med_b = ordered_meds[j]
            for sa in med_a.canonical_salt_keys:
                for sb in med_b.canonical_salt_keys:
                    if sa == sb:
                        continue
                    norm_pair = (sa, sb) if sa < sb else (sb, sa)
                    ddi_rec = ddi_by_pair.get(norm_pair)
                    if ddi_rec is None:
                        continue
                    dedupe = (med_a.medication_id, med_b.medication_id, norm_pair[0], norm_pair[1])
                    if dedupe in seen_ddi_med_pairs:
                        continue
                    seen_ddi_med_pairs.add(dedupe)
                    unresolvable_clashes.append(
                        UnresolvableScheduleClash(
                            medication_a_id=med_a.medication_id,
                            medication_a_name=med_a.display_name,
                            salt_a=norm_pair[0],
                            medication_b_id=med_b.medication_id,
                            medication_b_name=med_b.display_name,
                            salt_b=norm_pair[1],
                            severity="MAJOR",
                            clash_type="MAJOR_DDI",
                            mechanism=ddi_rec.mechanism,
                            management=ddi_rec.management,
                            reason=(
                                f"MAJOR systemic drug-drug interaction ({norm_pair[0]} + "
                                f"{norm_pair[1]}): {ddi_rec.clinical_effect} "
                                "Timing separation alone cannot resolve this clash."
                            ),
                        )
                    )

    groups: list[ScheduleSlotGroup] = [
        ScheduleSlotGroup(slot=slot_name, recommended_time=rec_time, medications=[])
        for slot_name, rec_time, _ in SLOT_DEFINITIONS
    ]

    for idx, node in enumerate(nodes):
        slot_idx = assignment[idx]
        slot_name, rec_time, _ = SLOT_DEFINITIONS[slot_idx]
        groups[slot_idx].medications.append(
            ScheduledDoseItem(
                medication_id=node.med.medication_id,
                display_name=node.med.display_name,
                canonical_salt_keys=list(node.med.canonical_salt_keys),
                dosage_schedule=node.med.dosage_schedule,
                slot=slot_name,
                recommended_time=rec_time,
                dose_number=node.dose_number,
                total_daily_doses=node.total_daily_doses,
                frequency_note=node.frequency_note,
                rationale=node.rationale,
                separation_notes=separation_notes_by_node[idx],
            )
        )

    for group in groups:
        group.medications.sort(
            key=lambda item: (item.display_name.lower(), item.dose_number, str(item.medication_id))
        )

    applied_list = sorted(
        applied_by_med_pair.values(),
        key=lambda item: (item.medication_a_name.lower(), item.medication_b_name.lower()),
    )
    unresolvable_clashes.sort(
        key=lambda item: (
            item.clash_type,
            item.salt_a,
            item.salt_b,
            item.medication_a_name.lower(),
        )
    )

    return ProfileScheduleResponse(
        profile_id=profile_id,
        profile_name=profile_name,
        safety_banner_locked=len(unresolvable_clashes) > 0,
        slots=groups,
        applied_separations=applied_list,
        unresolvable_clashes=unresolvable_clashes,
    )


async def build_profile_schedule(
    session: AsyncSession,
    profile: Profile,
) -> ProfileScheduleResponse:
    med_rows = list(
        (
            await session.execute(
                select(Medication)
                .where(
                    Medication.profile_id == profile.id,
                    Medication.is_active.is_(True),
                )
                .order_by(Medication.created_at.asc())
            )
        )
        .scalars()
        .all()
    )

    all_salts = canonical_salt_keys(salt for med in med_rows for salt in med.canonical_salt_keys)
    triggers_by_salt = await extract_lifestyle_triggers_by_salt(session, all_salts)

    major_pairs: list[MajorDdiRecord] = []
    if len(all_salts) >= 2:
        ordered_pairs: set[tuple[str, str]] = set()
        for i in range(len(all_salts)):
            for j in range(i + 1, len(all_salts)):
                a, b = all_salts[i], all_salts[j]
                ordered_pairs.add((a, b) if a < b else (b, a))
        if ordered_pairs:
            ddi_rows = list(
                (
                    await session.execute(
                        select(DdiPair).where(
                            tuple_(DdiPair.salt_a, DdiPair.salt_b).in_(sorted(ordered_pairs)),
                            DdiPair.severity == "MAJOR",
                        )
                    )
                )
                .scalars()
                .all()
            )
            major_pairs = [
                MajorDdiRecord(
                    salt_a=row.salt_a,
                    salt_b=row.salt_b,
                    mechanism=row.mechanism,
                    clinical_effect=row.clinical_effect,
                    management=row.management,
                )
                for row in ddi_rows
            ]

    med_inputs: list[ScheduleMedicationInput] = []
    for med in med_rows:
        med_triggers: list[LifestyleTrigger] = []
        seen_types: set[str] = set()
        for salt in med.canonical_salt_keys:
            for trigger in triggers_by_salt.get(salt, []):
                if trigger.type not in seen_types:
                    seen_types.add(trigger.type)
                    med_triggers.append(trigger)
        med_inputs.append(
            ScheduleMedicationInput(
                medication_id=med.id,
                display_name=med.display_name,
                canonical_salt_keys=tuple(med.canonical_salt_keys),
                dosage_schedule=med.dosage_schedule,
                lifestyle_triggers=tuple(med_triggers),
            )
        )

    return solve_daily_schedule(
        profile_id=profile.id,
        profile_name=profile.name,
        medications=med_inputs,
        major_ddis=major_pairs,
    )
