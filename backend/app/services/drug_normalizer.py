import re
import unicodedata
from collections.abc import Iterable

from backend.app.schemas.drugs import SaltComponent

DOSAGE_FORM_KEYWORDS: tuple[tuple[str, str], ...] = (
    ("oral suspension", "suspension"),
    ("suspension", "suspension"),
    ("tablets", "tablet"),
    ("tablet", "tablet"),
    ("tab", "tablet"),
    ("capsules", "capsule"),
    ("capsule", "capsule"),
    ("cap", "capsule"),
    ("syrup", "syrup"),
    ("injection", "injection"),
    ("infusion", "injection"),
    ("inhaler", "inhaler"),
    ("rotacap", "inhaler"),
    ("respule", "inhaler"),
    ("eye drop", "drops"),
    ("ear drop", "drops"),
    ("drops", "drops"),
    ("drop", "drops"),
    ("ointment", "ointment"),
    ("cream", "cream"),
    ("gel", "gel"),
    ("spray", "spray"),
    ("lotion", "lotion"),
    ("solution", "solution"),
    ("powder", "powder"),
    ("sachet", "powder"),
)

TRAILING_DOSAGE_FORM_TOKENS: frozenset[str] = frozenset(
    {
        "tablet",
        "tablets",
        "tab",
        "capsule",
        "capsules",
        "cap",
        "syrup",
        "suspension",
        "injection",
        "infusion",
        "inhaler",
        "drops",
        "drop",
        "ointment",
        "cream",
        "gel",
        "spray",
        "lotion",
        "solution",
        "powder",
        "sachet",
    }
)

SALT_SYNONYMS: dict[str, str] = {
    "amoxycillin": "amoxicillin",
    "paracetamol": "acetaminophen",
    "clavulanate": "clavulanic acid",
    "clavulanate potassium": "clavulanic acid",
    "potassium clavulanate": "clavulanic acid",
    "salbutamol": "albuterol",
    "adrenaline": "epinephrine",
    "noradrenaline": "norepinephrine",
    "lignocaine": "lidocaine",
    "frusemide": "furosemide",
    "phenobarbitone": "phenobarbital",
    "glibenclamide": "glyburide",
    "pethidine": "meperidine",
    "cefalexin": "cephalexin",
    "acetylsalicylic acid": "aspirin",
    "cholecalciferol": "vitamin d3",
    "colecalciferol": "vitamin d3",
    "cyanocobalamin": "vitamin b12",
    "methylcobalamin": "mecobalamin",
    "folic acid": "folate",
    "esomperazole": "esomeprazole",
}

PRESERVED_INORGANIC_COMPOUNDS: frozenset[str] = frozenset(
    {
        "calcium carbonate",
        "calcium citrate",
        "calcium gluconate",
        "calcium lactate",
        "potassium chloride",
        "potassium citrate",
        "sodium chloride",
        "sodium bicarbonate",
        "sodium phosphate",
        "magnesium hydroxide",
        "magnesium oxide",
        "magnesium sulfate",
        "magnesium sulphate",
        "ferrous sulfate",
        "ferrous sulphate",
        "ferrous fumarate",
        "ferrous ascorbate",
        "zinc sulfate",
        "zinc sulphate",
        "zinc gluconate",
        "aluminium hydroxide",
        "aluminum hydroxide",
        "lithium carbonate",
        "barium sulfate",
    }
)

COUNTERION_SUFFIXES: tuple[str, ...] = (
    "dihydrochloride",
    "monohydrochloride",
    "hydrochloride",
    "hydrobromide",
    "hyclate",
    "hcl",
    "sodium",
    "potassium",
    "calcium",
    "magnesium",
    "maleate",
    "tartrate",
    "bitartrate",
    "succinate",
    "fumarate",
    "hemifumarate",
    "besylate",
    "besilate",
    "mesylate",
    "mesilate",
    "tosylate",
    "esylate",
    "camsylate",
    "bisulfate",
    "bisulphate",
    "sulfate",
    "sulphate",
    "phosphate",
    "citrate",
    "acetate",
    "nitrate",
    "bromide",
    "chloride",
    "gluconate",
    "lactate",
    "benzoate",
    "propionate",
    "dipropionate",
    "valerate",
    "furoate",
    "disodium",
    "dipotassium",
    "tromethamine",
    "olamine",
    "meglumine",
    "oxalate",
    "stearate",
    "palmitate",
    "trihydrate",
    "dihydrate",
    "monohydrate",
    "hemihydrate",
    "anhydrous",
    "hydrate",
    "ip",
    "bp",
    "usp",
)

_NON_ALNUM_RE = re.compile(r"[^a-z0-9]+")
_PAREN_CONTENT_RE = re.compile(r"\(([^)]*)\)")
_STRENGTH_RE = re.compile(
    r"(?P<value>\d+(?:\.\d+)?)\s*(?P<unit>mg|mcg|ug|µg|g|gm|ml|iu|%|w/v|w/w)\b",
    re.IGNORECASE,
)
_TRAILING_RELEASE_RE = re.compile(
    r"\b(?:ip|bp|usp|sr|xr|er|pr|dr|cr|ec|mr|tr|dt|md)\b",
    re.IGNORECASE,
)
_COMPOSITION_SPLIT_RE = re.compile(
    r"\s*(?:\+|/|&|,|;|\band\b|\n)\s*(?![^()]*\))",
    re.IGNORECASE,
)
_OCR_SALT_SEGMENT_RE = re.compile(
    r"(?P<name>[A-Za-z][A-Za-z0-9\-\s]{2,55}?)\s*"
    r"(?:\(\s*(?P<p_val>\d+(?:\.\d+)?)\s*(?P<p_unit>mg|mcg|ug|µg|g|gm|ml|iu|%)\s*\)"
    r"|(?P<val>\d+(?:\.\d+)?)\s*(?P<unit>mg|mcg|ug|µg|g|gm|ml|iu|%)\b)",
    re.IGNORECASE,
)


def normalize_brand_name(raw: str) -> str:
    text = unicodedata.normalize("NFKC", raw).lower()
    text = _NON_ALNUM_RE.sub(" ", text)
    return " ".join(text.split())


def strip_dosage_form_suffix(normalized_brand: str) -> str:
    tokens = normalized_brand.split()
    while len(tokens) > 1 and tokens[-1] in TRAILING_DOSAGE_FORM_TOKENS:
        tokens.pop()
    return " ".join(tokens)


def extract_dosage_form(name: str, pack_size_label: str | None = None) -> str | None:
    combined = f"{name} {pack_size_label or ''}".lower()
    combined = _NON_ALNUM_RE.sub(" ", combined)
    padded = f" {combined} "
    for keyword, canonical_form in DOSAGE_FORM_KEYWORDS:
        if f" {keyword} " in padded:
            return canonical_form
    return None


def _convert_strength_to_mg(value: float, unit: str) -> float | None:
    normalized_unit = unit.lower()
    if normalized_unit == "mg":
        return round(value, 4)
    if normalized_unit in {"mcg", "ug", "µg"}:
        return round(value / 1000.0, 6)
    if normalized_unit in {"g", "gm"}:
        return round(value * 1000.0, 4)
    return None


def normalize_canonical_salt(raw_salt: str) -> str:
    text = unicodedata.normalize("NFKC", raw_salt).lower()
    text = _PAREN_CONTENT_RE.sub(" ", text)
    text = _STRENGTH_RE.sub(" ", text)
    text = re.sub(
        r"\b(?:equivalent to|eq\.?\s*to|elemental|contains|each|film coated)\b",
        " ",
        text,
    )
    text = _TRAILING_RELEASE_RE.sub(" ", text)
    text = _NON_ALNUM_RE.sub(" ", text)
    text = " ".join(text.split())
    if not text:
        return ""

    if text in SALT_SYNONYMS:
        text = SALT_SYNONYMS[text]

    if text in PRESERVED_INORGANIC_COMPOUNDS:
        return text.replace("sulphate", "sulfate").replace("aluminium", "aluminum")

    tokens = text.split()
    while len(tokens) > 1 and tokens[-1] in COUNTERION_SUFFIXES:
        candidate = " ".join(tokens[:-1])
        if candidate in SALT_SYNONYMS:
            return SALT_SYNONYMS[candidate]
        tokens.pop()

    while len(tokens) > 1 and tokens[0] in {"sodium", "potassium", "calcium", "magnesium"}:
        remainder = " ".join(tokens[1:])
        if remainder in SALT_SYNONYMS:
            return SALT_SYNONYMS[remainder]
        if f"{tokens[0]} {remainder}" in PRESERVED_INORGANIC_COMPOUNDS:
            break
        tokens.pop(0)

    cleaned = " ".join(tokens)
    return SALT_SYNONYMS.get(cleaned, cleaned)


def parse_composition_cell(cell: str | None) -> list[SaltComponent]:
    if not cell or not cell.strip():
        return []

    components: list[SaltComponent] = []
    seen: set[str] = set()

    for segment in _COMPOSITION_SPLIT_RE.split(cell.strip()):
        segment = segment.strip()
        if not segment:
            continue

        strength_mg: float | None = None
        strength_text: str | None = None
        match = _STRENGTH_RE.search(segment)
        if match:
            raw_val = float(match.group("value"))
            raw_unit = match.group("unit")
            strength_mg = _convert_strength_to_mg(raw_val, raw_unit)
            strength_text = f"{match.group('value')}{raw_unit.lower()}"

        canonical = normalize_canonical_salt(segment)[:160]
        if not canonical or canonical in seen:
            continue
        seen.add(canonical)
        components.append(
            SaltComponent(
                canonical_salt=canonical,
                strength_mg=strength_mg,
                strength_text=strength_text[:64] if strength_text else None,
            )
        )

    return components


def parse_ocr_salts(raw_ocr_text: str | None) -> list[SaltComponent]:
    if not raw_ocr_text or not raw_ocr_text.strip():
        return []

    components: list[SaltComponent] = []
    seen: set[str] = set()

    for match in _OCR_SALT_SEGMENT_RE.finditer(raw_ocr_text):
        raw_name = match.group("name").strip()
        val_str = match.group("p_val") or match.group("val")
        unit_str = match.group("p_unit") or match.group("unit")
        if not val_str or not unit_str:
            continue

        name_tokens = [
            tok for tok in _NON_ALNUM_RE.sub(" ", raw_name.lower()).split() if not tok.isdigit()
        ]
        if not name_tokens:
            continue

        canonical = _extract_salt_from_ocr_tokens(name_tokens)
        if not canonical or canonical in seen or len(canonical) < 3:
            continue

        seen.add(canonical)
        val = float(val_str)
        components.append(
            SaltComponent(
                canonical_salt=canonical,
                strength_mg=_convert_strength_to_mg(val, unit_str),
                strength_text=f"{val_str}{unit_str.lower()}",
            )
        )

    return components


def _extract_salt_from_ocr_tokens(tokens: list[str]) -> str:
    if len(tokens) >= 2:
        last_two = " ".join(tokens[-2:])
        if (
            last_two in SALT_SYNONYMS
            or last_two in PRESERVED_INORGANIC_COMPOUNDS
            or tokens[-1] in {"acid", *COUNTERION_SUFFIXES}
            or tokens[-2] in {"vitamin", "sodium", "potassium", "calcium", "magnesium", "ferrous"}
        ):
            return normalize_canonical_salt(last_two)
    return normalize_canonical_salt(tokens[-1])


def canonical_salt_keys(salts: Iterable[SaltComponent | str]) -> list[str]:
    keys: set[str] = set()
    for item in salts:
        raw = item.canonical_salt if isinstance(item, SaltComponent) else item
        normalized = normalize_canonical_salt(raw)
        if normalized:
            keys.add(normalized)
    return sorted(keys)
