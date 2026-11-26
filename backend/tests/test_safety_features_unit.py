from dataclasses import dataclass, field
from uuid import UUID, uuid4

from backend.app.schemas.schedule import ProfileScheduleResponse
from backend.app.services.allergy_checker import detect_allergy_conflicts
from backend.app.services.dose_scheduler import (
    SLOT_DEFINITIONS,
    MajorDdiRecord,
    ScheduleMedicationInput,
    circular_gap_minutes,
    solve_daily_schedule,
)
from backend.app.services.lifestyle_triggers import extract_lifestyle_triggers_from_chunks
from backend.app.services.recall_watchdog import parse_openfda_enforcement_record
from backend.app.workers.settings import WorkerSettings


@dataclass
class _ChunkStub:
    canonical_salt: str
    loinc_section_code: str
    section_title: str
    content: str
    chunk_metadata: dict[str, object] = field(default_factory=dict)


def _slot_minute_for_med(schedule_resp: ProfileScheduleResponse, med_id: UUID) -> list[int]:
    minute_by_slot = {name: minute for name, _, minute in SLOT_DEFINITIONS}
    results: list[int] = []
    for group in schedule_resp.slots:
        for item in group.medications:
            if item.medication_id == med_id:
                results.append(minute_by_slot[group.slot])
    return results


def _slot_names_for_med(schedule_resp: ProfileScheduleResponse, med_id: UUID) -> list[str]:
    names: list[str] = []
    for group in schedule_resp.slots:
        for item in group.medications:
            if item.medication_id == med_id:
                names.append(group.slot)
    return names


def test_deterministic_allergy_matching_direct_salt_and_drug_classes() -> None:
    penicillin_alerts = detect_allergy_conflicts(
        candidate_salts=["amoxicillin", "clavulanic acid"],
        allergies_json=["Penicillin"],
    )
    assert len(penicillin_alerts) == 1
    assert penicillin_alerts[0].canonical_salt == "amoxicillin"
    assert penicillin_alerts[0].match_type == "DRUG_CLASS"
    assert penicillin_alerts[0].matched_class == "penicillin"
    assert penicillin_alerts[0].severity == "MAJOR"

    nsaid_alerts = detect_allergy_conflicts(
        candidate_salts=["acetaminophen", "ibuprofen"],
        allergies_json=[{"allergen": "NSAIDs", "reaction": "bronchospasm", "severity": "SEVERE"}],
    )
    assert len(nsaid_alerts) == 1
    assert nsaid_alerts[0].canonical_salt == "ibuprofen"
    assert nsaid_alerts[0].match_type == "DRUG_CLASS"
    assert "bronchospasm" in nsaid_alerts[0].reason

    direct_synonym_alerts = detect_allergy_conflicts(
        candidate_salts=["acetaminophen"],
        allergies_json=["Paracetamol"],
    )
    assert len(direct_synonym_alerts) == 1
    assert direct_synonym_alerts[0].match_type == "DIRECT_SALT"
    assert direct_synonym_alerts[0].canonical_salt == "acetaminophen"

    sulfa_alerts = detect_allergy_conflicts(
        candidate_salts=["sulfamethoxazole", "trimethoprim"],
        allergies_json=["Sulfa"],
    )
    assert len(sulfa_alerts) == 1
    assert sulfa_alerts[0].canonical_salt == "sulfamethoxazole"
    assert sulfa_alerts[0].matched_class == "sulfonamide"

    safe_alerts = detect_allergy_conflicts(
        candidate_salts=["metformin"],
        allergies_json=["Penicillin", "Sulfa", "NSAIDs"],
    )
    assert safe_alerts == []


def test_dose_scheduler_separates_levothyroxine_from_iron_calcium_and_antacids() -> None:
    profile_id = uuid4()
    levo_id = uuid4()
    iron_id = uuid4()
    calc_id = uuid4()
    antacid_id = uuid4()

    schedule = solve_daily_schedule(
        profile_id=profile_id,
        profile_name="Thyroid Care",
        medications=[
            ScheduleMedicationInput(
                medication_id=levo_id,
                display_name="Thyronorm 50mcg",
                canonical_salt_keys=("levothyroxine",),
                dosage_schedule="1 tablet OD",
            ),
            ScheduleMedicationInput(
                medication_id=iron_id,
                display_name="Ferrous Sulfate 325mg",
                canonical_salt_keys=("ferrous sulfate",),
                dosage_schedule="1 tablet OD",
            ),
            ScheduleMedicationInput(
                medication_id=calc_id,
                display_name="Shelcal 500 Calcium",
                canonical_salt_keys=("calcium carbonate",),
                dosage_schedule="1 tablet OD",
            ),
            ScheduleMedicationInput(
                medication_id=antacid_id,
                display_name="Gelusil Antacid",
                canonical_salt_keys=("aluminum hydroxide", "magnesium hydroxide"),
                dosage_schedule="1 tablet OD",
            ),
        ],
    )

    assert [group.slot for group in schedule.slots] == [
        "Morning (Empty Stomach)",
        "Morning (With Food)",
        "Afternoon",
        "Evening",
        "Bedtime",
    ]
    assert _slot_names_for_med(schedule, levo_id) == ["Morning (Empty Stomach)"]

    levo_min = _slot_minute_for_med(schedule, levo_id)[0]
    for other_id in (iron_id, calc_id, antacid_id):
        other_min = _slot_minute_for_med(schedule, other_id)[0]
        assert circular_gap_minutes(levo_min, other_min) >= 240

    assert len(schedule.applied_separations) >= 3
    assert all(sep.satisfied for sep in schedule.applied_separations)
    assert schedule.unresolvable_clashes == []
    assert schedule.safety_banner_locked is False


def test_dose_scheduler_fluoroquinolone_bisphosphonate_sedating_and_diuretic_biases() -> None:
    profile_id = uuid4()
    cipro_id = uuid4()
    zinc_id = uuid4()
    alendronate_id = uuid4()
    furosemide_id = uuid4()
    diphenhydramine_id = uuid4()

    schedule = solve_daily_schedule(
        profile_id=profile_id,
        profile_name="Complex Regimen",
        medications=[
            ScheduleMedicationInput(
                medication_id=alendronate_id,
                display_name="Fosamax 70mg",
                canonical_salt_keys=("alendronate",),
                dosage_schedule="1 tablet weekly",
            ),
            ScheduleMedicationInput(
                medication_id=cipro_id,
                display_name="Ciplox 500mg",
                canonical_salt_keys=("ciprofloxacin",),
                dosage_schedule="1 tablet BID",
            ),
            ScheduleMedicationInput(
                medication_id=zinc_id,
                display_name="Zincovit",
                canonical_salt_keys=("zinc sulfate",),
                dosage_schedule="1 tablet OD",
            ),
            ScheduleMedicationInput(
                medication_id=furosemide_id,
                display_name="Lasix 40mg",
                canonical_salt_keys=("furosemide",),
                dosage_schedule="1 tablet OD",
            ),
            ScheduleMedicationInput(
                medication_id=diphenhydramine_id,
                display_name="Benadryl 25mg",
                canonical_salt_keys=("diphenhydramine",),
                dosage_schedule="1 capsule OD",
            ),
        ],
    )

    assert _slot_names_for_med(schedule, alendronate_id) == ["Morning (Empty Stomach)"]
    assert _slot_names_for_med(schedule, furosemide_id) == ["Morning (With Food)"]
    assert _slot_names_for_med(schedule, diphenhydramine_id) == ["Bedtime"]

    alendronate_min = _slot_minute_for_med(schedule, alendronate_id)[0]
    furosemide_min = _slot_minute_for_med(schedule, furosemide_id)[0]
    assert circular_gap_minutes(alendronate_min, furosemide_min) >= 60

    cipro_mins = _slot_minute_for_med(schedule, cipro_id)
    zinc_min = _slot_minute_for_med(schedule, zinc_id)[0]
    assert len(cipro_mins) == 2
    for cm in cipro_mins:
        assert circular_gap_minutes(cm, zinc_min) >= 240
    assert all(sep.satisfied for sep in schedule.applied_separations)


def test_dose_scheduler_flags_unresolvable_major_ddi_and_duplicate_salts() -> None:
    profile_id = uuid4()
    warfarin_id = uuid4()
    aspirin_id = uuid4()
    mox_id = uuid4()
    augmentin_id = uuid4()

    schedule = solve_daily_schedule(
        profile_id=profile_id,
        profile_name="High Risk",
        medications=[
            ScheduleMedicationInput(
                medication_id=warfarin_id,
                display_name="Warf 5",
                canonical_salt_keys=("warfarin",),
            ),
            ScheduleMedicationInput(
                medication_id=aspirin_id,
                display_name="Ecosprin 75",
                canonical_salt_keys=("aspirin",),
            ),
            ScheduleMedicationInput(
                medication_id=mox_id,
                display_name="Mox 500",
                canonical_salt_keys=("amoxicillin",),
            ),
            ScheduleMedicationInput(
                medication_id=augmentin_id,
                display_name="Augmentin 625 Duo",
                canonical_salt_keys=("amoxicillin", "clavulanic acid"),
            ),
        ],
        major_ddis=[
            MajorDdiRecord(
                salt_a="aspirin",
                salt_b="warfarin",
                mechanism="Additive antiplatelet and anticoagulant bleeding risk.",
                clinical_effect="High risk of major gastrointestinal bleeding.",
                management="Avoid concurrent use unless supervised with frequent INR monitoring.",
            )
        ],
    )

    assert schedule.safety_banner_locked is True
    clash_types = {c.clash_type for c in schedule.unresolvable_clashes}
    assert clash_types == {"MAJOR_DDI", "DUPLICATE_ACTIVE_INGREDIENT"}
    ddi_clash = next(c for c in schedule.unresolvable_clashes if c.clash_type == "MAJOR_DDI")
    assert {ddi_clash.salt_a, ddi_clash.salt_b} == {"aspirin", "warfarin"}
    assert "timing separation alone cannot resolve" in ddi_clash.reason.lower()


def test_lifestyle_trigger_parser_covers_all_eight_badges_and_guards_negations() -> None:
    chunks = [
        _ChunkStub(
            canonical_salt="metronidazole",
            loinc_section_code="43685-7",
            section_title="WARNINGS AND PRECAUTIONS",
            content=(
                "Avoid alcoholic beverages during therapy and for at least 3 days afterward "
                "due to disulfiram-like reactions. May cause drowsiness and sedation; do not "
                "drive or operate heavy machinery."
            ),
            chunk_metadata={"section_key": "warnings"},
        ),
        _ChunkStub(
            canonical_salt="simvastatin",
            loinc_section_code="34073-7",
            section_title="DRUG INTERACTIONS",
            content=(
                "Avoid grapefruit juice while taking simvastatin because CYP3A4 inhibition "
                "markedly increases risk of myopathy and rhabdomyolysis."
            ),
            chunk_metadata={"section_key": "drug_interactions"},
        ),
        _ChunkStub(
            canonical_salt="ciprofloxacin",
            loinc_section_code="34068-7",
            section_title="DOSAGE AND ADMINISTRATION",
            content=(
                "Do not take with dairy products such as milk or yogurt alone because calcium "
                "reduces absorption. Limit caffeine intake because ciprofloxacin inhibits "
                "caffeine clearance and may cause nervousness or insomnia."
            ),
            chunk_metadata={"section_key": "dosage_and_administration"},
        ),
        _ChunkStub(
            canonical_salt="levothyroxine",
            loinc_section_code="34068-7",
            section_title="DOSAGE AND ADMINISTRATION",
            content=(
                "Administer as a single daily dose on an empty stomach at least "
                "30 to 60 minutes before breakfast."
            ),
            chunk_metadata={"section_key": "dosage_and_administration"},
        ),
        _ChunkStub(
            canonical_salt="metformin",
            loinc_section_code="34068-7",
            section_title="DOSAGE AND ADMINISTRATION",
            content="Take with food or meals to reduce gastrointestinal upset.",
            chunk_metadata={"section_key": "dosage_and_administration"},
        ),
        _ChunkStub(
            canonical_salt="doxycycline",
            loinc_section_code="42232-9",
            section_title="PRECAUTIONS",
            content=(
                "Photosensitivity manifested by an exaggerated sunburn reaction has been observed; "
                "avoid prolonged sunlight exposure and use sunscreen."
            ),
            chunk_metadata={"section_key": "precautions"},
        ),
    ]

    triggers = extract_lifestyle_triggers_from_chunks(chunks)
    by_type = {t.type: t for t in triggers}
    assert set(by_type.keys()) == {
        "ALCOHOL",
        "GRAPEFRUIT",
        "DAIRY",
        "CAFFEINE",
        "EMPTY_STOMACH",
        "WITH_FOOD",
        "DROWSINESS",
        "SUN_SENSITIVITY",
    }
    assert by_type["ALCOHOL"].severity == "HIGH"
    assert by_type["ALCOHOL"].source_section == "warnings"
    assert by_type["GRAPEFRUIT"].severity == "HIGH"
    assert by_type["GRAPEFRUIT"].source_section == "drug_interactions"
    assert by_type["DAIRY"].source_section == "dosage_and_administration"
    assert by_type["CAFFEINE"].source_section == "dosage_and_administration"
    assert by_type["EMPTY_STOMACH"].severity == "HIGH"
    assert by_type["WITH_FOOD"].source_section == "dosage_and_administration"
    assert by_type["DROWSINESS"].severity == "HIGH"
    assert by_type["SUN_SENSITIVITY"].severity == "HIGH"
    assert by_type["SUN_SENSITIVITY"].source_section == "precautions"

    negated_chunks = [
        _ChunkStub(
            canonical_salt="amoxicillin",
            loinc_section_code="34068-7",
            section_title="DOSAGE AND ADMINISTRATION",
            content=(
                "May be taken with or without food. Food does not significantly affect absorption. "
                "Alcohol-free oral suspension contains no benzyl alcohol preservative. "
                "No clinically significant interaction with grapefruit or caffeine. "
                "Excreted in human breast milk; non-drowsy profile with no evidence "
                "of photosensitivity."
            ),
            chunk_metadata={"section_key": "dosage_and_administration"},
        )
    ]
    assert extract_lifestyle_triggers_from_chunks(negated_chunks) == []


def test_recall_parser_fallback_and_worker_cron_registration() -> None:
    sparse_record = {
        "recall_number": "D-9999-2026",
        "event_id": "99999",
        "status": "Ongoing",
        "classification": "Class I",
        "recalling_firm": "Fallback Labs",
        "product_description": "Cozaar (losartan potassium) Tablets, USP, 50 mg, 90-count bottle",
        "reason_for_recall": "Azido impurity exceeding acceptable limit.",
        "report_date": "20260401",
    }
    parsed = parse_openfda_enforcement_record(sparse_record)
    assert parsed is not None
    assert "losartan" in parsed.canonical_salt_keys
    assert "cozaar" in parsed.brand_names

    cron_names = [job.coroutine.__name__ for job in WorkerSettings.cron_jobs]
    assert "sync_fda_recalls_job" in cron_names
    fn_names = [fn.__name__ for fn in WorkerSettings.functions]
    assert "sync_fda_recalls_job" in fn_names
    assert "ingest_fda_recalls_from_file_job" in fn_names
