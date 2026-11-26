import re
from collections.abc import Iterable, Sequence
from typing import Any

from backend.app.schemas.profiles import AllergyAlert
from backend.app.services.drug_normalizer import canonical_salt_keys, normalize_canonical_salt

_NON_ALNUM_RE = re.compile(r"[^a-z0-9]+")

DRUG_CLASS_MEMBERS: dict[str, frozenset[str]] = {
    "penicillin": frozenset(
        {
            "amoxicillin",
            "ampicillin",
            "penicillin",
            "penicillin g",
            "penicillin v",
            "benzylpenicillin",
            "phenoxymethylpenicillin",
            "piperacillin",
            "dicloxacillin",
            "flucloxacillin",
            "cloxacillin",
            "oxacillin",
            "nafcillin",
            "ticarcillin",
            "bacampicillin",
            "pivampicillin",
            "sultamicillin",
        }
    ),
    "cephalosporin": frozenset(
        {
            "cephalexin",
            "cefadroxil",
            "cefazolin",
            "cefuroxime",
            "cefaclor",
            "cefprozil",
            "cefixime",
            "ceftriaxone",
            "cefpodoxime",
            "cefdinir",
            "ceftazidime",
            "cefotaxime",
            "cefepime",
            "ceftaroline",
            "cefoperazone",
            "cefditorin",
            "cefditoren",
        }
    ),
    "carbapenem": frozenset(
        {
            "meropenem",
            "imipenem",
            "ertapenem",
            "doripenem",
            "faropenem",
        }
    ),
    "beta-lactam": frozenset(
        {
            "amoxicillin",
            "ampicillin",
            "penicillin",
            "penicillin g",
            "penicillin v",
            "benzylpenicillin",
            "phenoxymethylpenicillin",
            "piperacillin",
            "dicloxacillin",
            "flucloxacillin",
            "cloxacillin",
            "oxacillin",
            "nafcillin",
            "ticarcillin",
            "clavulanic acid",
            "sulbactam",
            "tazobactam",
            "cephalexin",
            "cefadroxil",
            "cefazolin",
            "cefuroxime",
            "cefaclor",
            "cefprozil",
            "cefixime",
            "ceftriaxone",
            "cefpodoxime",
            "cefdinir",
            "ceftazidime",
            "cefotaxime",
            "cefepime",
            "ceftaroline",
            "meropenem",
            "imipenem",
            "ertapenem",
            "doripenem",
            "faropenem",
            "aztreonam",
        }
    ),
    "sulfonamide": frozenset(
        {
            "sulfamethoxazole",
            "sulfadiazine",
            "sulfisoxazole",
            "sulfasalazine",
            "sulfacetamide",
            "sulfadoxine",
            "mafenide",
            "silver sulfadiazine",
            "co trimoxazole",
            "trimethoprim sulfamethoxazole",
            "dapsone",
            "celecoxib",
        }
    ),
    "nsaid": frozenset(
        {
            "aspirin",
            "ibuprofen",
            "diclofenac",
            "naproxen",
            "aceclofenac",
            "ketorolac",
            "indomethacin",
            "meloxicam",
            "piroxicam",
            "celecoxib",
            "etoricoxib",
            "mefenamic acid",
            "ketoprofen",
            "flurbiprofen",
            "etodolac",
            "nabumetone",
            "oxaprozin",
            "sulindac",
            "nimesulide",
            "dexketoprofen",
            "lornoxicam",
            "tenoxicam",
            "parecoxib",
        }
    ),
    "salicylate": frozenset(
        {
            "aspirin",
            "salsalate",
            "diflunisal",
            "bismuth subsalicylate",
            "mesalamine",
            "mesalazine",
            "sulfasalazine",
            "methyl salicylate",
        }
    ),
    "fluoroquinolone": frozenset(
        {
            "ciprofloxacin",
            "levofloxacin",
            "ofloxacin",
            "moxifloxacin",
            "norfloxacin",
            "gatifloxacin",
            "gemifloxacin",
            "delafloxacin",
            "lomefloxacin",
            "sparfloxacin",
            "prulifloxacin",
            "pazufloxacin",
        }
    ),
    "macrolide": frozenset(
        {
            "azithromycin",
            "clarithromycin",
            "erythromycin",
            "roxithromycin",
            "fidaxomicin",
            "spiramycin",
            "telithromycin",
        }
    ),
    "tetracycline": frozenset(
        {
            "doxycycline",
            "minocycline",
            "tetracycline",
            "demeclocycline",
            "tigecycline",
            "omadacycline",
            "sarecycline",
            "eravacycline",
            "lymecycline",
            "oxytetracycline",
        }
    ),
    "opioid": frozenset(
        {
            "morphine",
            "codeine",
            "tramadol",
            "oxycodone",
            "hydrocodone",
            "hydromorphone",
            "fentanyl",
            "meperidine",
            "methadone",
            "buprenorphine",
            "tapentadol",
            "oxymorphone",
            "pentazocine",
            "nalbuphine",
            "butorphanol",
        }
    ),
    "ace_inhibitor": frozenset(
        {
            "lisinopril",
            "enalapril",
            "ramipril",
            "captopril",
            "perindopril",
            "benazepril",
            "fosinopril",
            "quinapril",
            "trandolapril",
            "moexipril",
            "imidapril",
        }
    ),
    "arb": frozenset(
        {
            "losartan",
            "valsartan",
            "telmisartan",
            "olmesartan",
            "irbesartan",
            "candesartan",
            "azilsartan",
            "eprosartan",
        }
    ),
    "statin": frozenset(
        {
            "atorvastatin",
            "rosuvastatin",
            "simvastatin",
            "pravastatin",
            "lovastatin",
            "fluvastatin",
            "pitavastatin",
        }
    ),
    "ppi": frozenset(
        {
            "omeprazole",
            "pantoprazole",
            "esomeprazole",
            "rabeprazole",
            "lansoprazole",
            "dexlansoprazole",
            "ilaprazole",
        }
    ),
    "aminoglycoside": frozenset(
        {
            "gentamicin",
            "amikacin",
            "tobramycin",
            "streptomycin",
            "neomycin",
            "kanamycin",
            "plazomicin",
            "netilmicin",
        }
    ),
    "nitroimidazole": frozenset(
        {
            "metronidazole",
            "tinidazole",
            "ornidazole",
            "secnidazole",
            "satranidazole",
        }
    ),
    "aromatic_anticonvulsant": frozenset(
        {
            "phenytoin",
            "carbamazepine",
            "oxcarbazepine",
            "phenobarbital",
            "lamotrigine",
            "primidone",
        }
    ),
    "beta_blocker": frozenset(
        {
            "metoprolol",
            "atenolol",
            "propranolol",
            "bisoprolol",
            "carvedilol",
            "nebivolol",
            "labetalol",
            "nadolol",
            "sotalol",
            "timolol",
            "esmolol",
            "pindolol",
            "betaxolol",
        }
    ),
    "corticosteroid": frozenset(
        {
            "prednisone",
            "prednisolone",
            "methylprednisolone",
            "dexamethasone",
            "hydrocortisone",
            "betamethasone",
            "triamcinolone",
            "budesonide",
            "fluticasone",
            "mometasone",
            "beclomethasone",
            "deflazacort",
        }
    ),
    "benzodiazepine": frozenset(
        {
            "alprazolam",
            "lorazepam",
            "diazepam",
            "clonazepam",
            "midazolam",
            "temazepam",
            "oxazepam",
            "chlordiazepoxide",
            "nitrazepam",
            "clobazam",
            "etizolam",
        }
    ),
    "ssri": frozenset(
        {
            "fluoxetine",
            "sertraline",
            "escitalopram",
            "citalopram",
            "paroxetine",
            "fluvoxamine",
        }
    ),
    "antihistamine": frozenset(
        {
            "cetirizine",
            "levocetirizine",
            "loratadine",
            "desloratadine",
            "fexofenadine",
            "diphenhydramine",
            "chlorpheniramine",
            "hydroxyzine",
            "promethazine",
            "bilastine",
            "ebastine",
        }
    ),
    "local_anesthetic_amide": frozenset(
        {
            "lidocaine",
            "bupivacaine",
            "ropivacaine",
            "mepivacaine",
            "prilocaine",
            "articaine",
        }
    ),
    "local_anesthetic_ester": frozenset(
        {
            "procaine",
            "benzocaine",
            "tetracaine",
            "chloroprocaine",
        }
    ),
}

DRUG_CLASS_ALIASES: dict[str, str] = {
    "penicillin": "penicillin",
    "penicillins": "penicillin",
    "penicillin antibiotics": "penicillin",
    "penicillin allergy": "penicillin",
    "cephalosporin": "cephalosporin",
    "cephalosporins": "cephalosporin",
    "cefalosporin": "cephalosporin",
    "cefalosporins": "cephalosporin",
    "carbapenem": "carbapenem",
    "carbapenems": "carbapenem",
    "beta lactam": "beta-lactam",
    "beta lactams": "beta-lactam",
    "beta lactam antibiotics": "beta-lactam",
    "betalactam": "beta-lactam",
    "sulfa": "sulfonamide",
    "sulfa drug": "sulfonamide",
    "sulfa drugs": "sulfonamide",
    "sulfa antibiotics": "sulfonamide",
    "sulfonamide": "sulfonamide",
    "sulfonamides": "sulfonamide",
    "sulphonamide": "sulfonamide",
    "sulphonamides": "sulfonamide",
    "cotrimoxazole": "sulfonamide",
    "nsaid": "nsaid",
    "nsaids": "nsaid",
    "nonsteroidal anti inflammatory": "nsaid",
    "nonsteroidal anti inflammatory drugs": "nsaid",
    "non steroidal anti inflammatory": "nsaid",
    "salicylate": "salicylate",
    "salicylates": "salicylate",
    "fluoroquinolone": "fluoroquinolone",
    "fluoroquinolones": "fluoroquinolone",
    "quinolone": "fluoroquinolone",
    "quinolones": "fluoroquinolone",
    "macrolide": "macrolide",
    "macrolides": "macrolide",
    "tetracycline": "tetracycline",
    "tetracyclines": "tetracycline",
    "opioid": "opioid",
    "opioids": "opioid",
    "opiate": "opioid",
    "opiates": "opioid",
    "narcotic": "opioid",
    "narcotics": "opioid",
    "ace inhibitor": "ace_inhibitor",
    "ace inhibitors": "ace_inhibitor",
    "acei": "ace_inhibitor",
    "arb": "arb",
    "arbs": "arb",
    "angiotensin receptor blocker": "arb",
    "angiotensin receptor blockers": "arb",
    "statin": "statin",
    "statins": "statin",
    "hmg coa reductase inhibitor": "statin",
    "ppi": "ppi",
    "ppis": "ppi",
    "proton pump inhibitor": "ppi",
    "proton pump inhibitors": "ppi",
    "aminoglycoside": "aminoglycoside",
    "aminoglycosides": "aminoglycoside",
    "nitroimidazole": "nitroimidazole",
    "nitroimidazoles": "nitroimidazole",
    "aromatic anticonvulsant": "aromatic_anticonvulsant",
    "aromatic anticonvulsants": "aromatic_anticonvulsant",
    "beta blocker": "beta_blocker",
    "beta blockers": "beta_blocker",
    "corticosteroid": "corticosteroid",
    "corticosteroids": "corticosteroid",
    "steroid": "corticosteroid",
    "steroids": "corticosteroid",
    "glucocorticoid": "corticosteroid",
    "benzodiazepine": "benzodiazepine",
    "benzodiazepines": "benzodiazepine",
    "ssri": "ssri",
    "ssris": "ssri",
    "selective serotonin reuptake inhibitor": "ssri",
    "antihistamine": "antihistamine",
    "antihistamines": "antihistamine",
    "amide anesthetic": "local_anesthetic_amide",
    "amide local anesthetic": "local_anesthetic_amide",
    "ester anesthetic": "local_anesthetic_ester",
    "ester local anesthetic": "local_anesthetic_ester",
}

_STEM_EXCLUDED: dict[str, frozenset[str]] = {
    "statin": frozenset({"nystatin", "cilastatin", "somatostatin"}),
    "ppi": frozenset({"aripiprazole", "brexpiprazole"}),
}


def _normalize_allergen_phrase(raw: str) -> str:
    cleaned = _NON_ALNUM_RE.sub(" ", raw.lower()).strip()
    return " ".join(cleaned.split())


def _extract_allergen_labels(allergies_json: Sequence[Any]) -> list[tuple[str, str | None]]:
    extracted: list[tuple[str, str | None]] = []
    for entry in allergies_json:
        if isinstance(entry, str):
            label = entry.strip()
            if label:
                extracted.append((label, None))
        elif isinstance(entry, dict):
            raw_label = entry.get("allergen") or entry.get("substance") or entry.get("name") or ""
            label = str(raw_label).strip()
            reaction_raw = entry.get("reaction")
            reaction = str(reaction_raw).strip() if reaction_raw else None
            if label:
                extracted.append((label, reaction))
    return extracted


def _salt_belongs_to_class(canonical_salt: str, class_key: str) -> bool:
    if canonical_salt in DRUG_CLASS_MEMBERS.get(class_key, frozenset()):
        return True
    if class_key == "fluoroquinolone" and canonical_salt.endswith("floxacin"):
        return True
    if class_key == "cephalosporin" and canonical_salt.startswith(("cef", "ceph")):
        return True
    if class_key == "carbapenem" and canonical_salt.endswith("penem"):
        return True
    if class_key == "ace_inhibitor" and canonical_salt.endswith("pril"):
        return True
    if class_key == "arb" and canonical_salt.endswith("sartan"):
        return True
    if (
        class_key == "statin"
        and canonical_salt.endswith("statin")
        and canonical_salt not in _STEM_EXCLUDED["statin"]
    ):
        return True
    if (
        class_key == "ppi"
        and canonical_salt.endswith("prazole")
        and canonical_salt not in _STEM_EXCLUDED["ppi"]
    ):
        return True
    return class_key == "penicillin" and canonical_salt.endswith("cillin")


def detect_allergy_conflicts(
    *,
    candidate_salts: Iterable[str],
    allergies_json: Sequence[Any] | None,
) -> list[AllergyAlert]:
    if not allergies_json:
        return []

    resolved_salts = canonical_salt_keys(candidate_salts)
    if not resolved_salts:
        return []

    allergens = _extract_allergen_labels(allergies_json)
    if not allergens:
        return []

    alerts: list[AllergyAlert] = []
    seen_pairs: set[tuple[str, str]] = set()

    for display_allergen, reaction in allergens:
        phrase = _normalize_allergen_phrase(display_allergen)
        if not phrase:
            continue
        canonical_allergen = normalize_canonical_salt(display_allergen)
        class_key = DRUG_CLASS_ALIASES.get(phrase) or DRUG_CLASS_ALIASES.get(canonical_allergen)
        reaction_suffix = f" (documented reaction: {reaction})" if reaction else ""

        for salt in resolved_salts:
            dedupe_key = (salt, phrase)
            if dedupe_key in seen_pairs:
                continue

            if canonical_allergen and salt == canonical_allergen:
                seen_pairs.add(dedupe_key)
                alerts.append(
                    AllergyAlert(
                        allergen=display_allergen,
                        canonical_salt=salt,
                        match_type="DIRECT_SALT",
                        matched_class=None,
                        severity="MAJOR",
                        reason=(
                            f"Active salt '{salt}' directly matches documented profile allergy "
                            f"'{display_allergen}'{reaction_suffix}."
                        ),
                    )
                )
                continue

            if class_key is not None and _salt_belongs_to_class(salt, class_key):
                seen_pairs.add(dedupe_key)
                alerts.append(
                    AllergyAlert(
                        allergen=display_allergen,
                        canonical_salt=salt,
                        match_type="DRUG_CLASS",
                        matched_class=class_key,
                        severity="MAJOR",
                        reason=(
                            f"Active salt '{salt}' belongs to the '{class_key}' drug class "
                            f"matching profile allergy '{display_allergen}'{reaction_suffix}."
                        ),
                    )
                )

    alerts.sort(key=lambda item: (item.canonical_salt, item.match_type, item.allergen.lower()))
    return alerts
