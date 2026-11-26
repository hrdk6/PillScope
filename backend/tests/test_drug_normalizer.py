from backend.app.services.drug_normalizer import (
    canonical_salt_keys,
    extract_dosage_form,
    normalize_brand_name,
    normalize_canonical_salt,
    parse_composition_cell,
    parse_ocr_salts,
    strip_dosage_form_suffix,
)


def test_canonical_salt_synonyms_and_counterion_stripping() -> None:
    assert normalize_canonical_salt("Amoxycillin (500mg)") == "amoxicillin"
    assert normalize_canonical_salt("Amoxicillin Trihydrate IP") == "amoxicillin"
    assert normalize_canonical_salt("Potassium Clavulanate 125mg") == "clavulanic acid"
    assert normalize_canonical_salt("Clavulanate Potassium") == "clavulanic acid"
    assert normalize_canonical_salt("Paracetamol (650mg)") == "acetaminophen"
    assert normalize_canonical_salt("Acetaminophen 500 mg") == "acetaminophen"
    assert normalize_canonical_salt("Diclofenac Sodium 100mg") == "diclofenac"
    assert normalize_canonical_salt("Diclofenac Potassium 50mg") == "diclofenac"
    assert normalize_canonical_salt("Metformin Hydrochloride 500mg SR") == "metformin"
    assert normalize_canonical_salt("Atorvastatin Calcium 10mg") == "atorvastatin"
    assert normalize_canonical_salt("Salbutamol 100mcg") == "albuterol"
    assert normalize_canonical_salt("Frusemide 40mg") == "furosemide"


def test_preserves_inorganic_salts() -> None:
    assert normalize_canonical_salt("Calcium Carbonate (500mg)") == "calcium carbonate"
    assert normalize_canonical_salt("Potassium Chloride 600mg") == "potassium chloride"
    assert normalize_canonical_salt("Ferrous Sulphate 200mg") == "ferrous sulfate"


def test_parse_composition_cell_single_and_combo() -> None:
    single = parse_composition_cell("Amoxycillin  (500mg)")
    assert len(single) == 1
    assert single[0].canonical_salt == "amoxicillin"
    assert single[0].strength_mg == 500.0
    assert single[0].strength_text == "500mg"

    combo = parse_composition_cell("Ibuprofen (400mg) + Paracetamol (325mg)")
    assert canonical_salt_keys(combo) == ["acetaminophen", "ibuprofen"]

    microgram = parse_composition_cell("Salbutamol (100mcg)")
    assert microgram[0].canonical_salt == "albuterol"
    assert microgram[0].strength_mg == 0.1

    wv_solution = parse_composition_cell("Sodium Chloride (0.9% w/v)")
    assert len(wv_solution) == 1
    assert wv_solution[0].canonical_salt == "sodium chloride"

    syrup = parse_composition_cell("Ambroxol (30mg/5ml)")
    assert len(syrup) == 1
    assert syrup[0].canonical_salt == "ambroxol"
    assert syrup[0].strength_mg == 30.0


def test_parse_ocr_salts_from_strip_text() -> None:
    ocr = "AUGMENTIN 625 DUO Amoxycillin 500mg Clavulanic Acid 125mg"
    parsed = parse_ocr_salts(ocr)
    assert canonical_salt_keys(parsed) == ["amoxicillin", "clavulanic acid"]
    strengths = {item.canonical_salt: item.strength_mg for item in parsed}
    assert strengths["amoxicillin"] == 500.0
    assert strengths["clavulanic acid"] == 125.0


def test_brand_normalization_and_dosage_form() -> None:
    norm = normalize_brand_name("Pan-D Capsule PR")
    assert norm == "pan d capsule pr"
    assert strip_dosage_form_suffix("augmentin 625 duo tablet") == "augmentin 625 duo"
    assert extract_dosage_form("Augmentin 625 Duo Tablet", "strip of 10 tablets") == "tablet"
    assert extract_dosage_form("Mox 500mg", "strip of 15 capsules") == "capsule"
