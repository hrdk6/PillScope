from typing import Literal

from pydantic import BaseModel, Field

SeverityLabel = Literal["NONE", "MINOR", "MODERATE", "MAJOR"]

SEVERITY_LABELS: tuple[SeverityLabel, ...] = ("NONE", "MINOR", "MODERATE", "MAJOR")
LABEL_TO_INDEX: dict[SeverityLabel, int] = {label: idx for idx, label in enumerate(SEVERITY_LABELS)}
INDEX_TO_LABEL: dict[int, SeverityLabel] = {idx: label for idx, label in enumerate(SEVERITY_LABELS)}
SEVERITY_RANK: dict[SeverityLabel, int] = {
    "NONE": 0,
    "MINOR": 1,
    "MODERATE": 2,
    "MAJOR": 3,
}

DESCRIPTOR_NAMES: tuple[str, ...] = (
    "MolWt",
    "MolLogP",
    "TPSA",
    "NumHDonors",
    "NumHAcceptors",
    "NumRotatableBonds",
    "NumAromaticRings",
    "NumAliphaticRings",
    "FractionCSP3",
    "HeavyAtomCount",
    "RingCount",
    "QED",
)


class DdiFeatureConfig(BaseModel):
    fp_radius: int = Field(default=2, ge=1, le=4)
    fp_bits: int = Field(default=1024, ge=64, le=4096)
    use_chirality: bool = True
    descriptor_names: tuple[str, ...] = DESCRIPTOR_NAMES

    @property
    def total_features(self) -> int:
        return (2 * self.fp_bits) + (4 * len(self.descriptor_names)) + 2


class PreparedDdiRecord(BaseModel):
    salt_a: str = Field(min_length=1)
    salt_b: str = Field(min_length=1)
    smiles_a: str = Field(min_length=1)
    smiles_b: str = Field(min_length=1)
    scaffold_a: str = Field(min_length=1)
    scaffold_b: str = Field(min_length=1)
    severity: SeverityLabel
    ddinter_id_a: str | None = None
    ddinter_id_b: str | None = None
    source: str = "ddinter_2"


class SplitSummary(BaseModel):
    train_pairs: int
    val_pairs: int
    test_pairs: int
    dropped_cross_boundary_pairs: int
    train_drugs: int
    val_drugs: int
    test_drugs: int
    train_scaffolds: int
    val_scaffolds: int
    test_scaffolds: int
    drug_overlap_count: int
    scaffold_overlap_count: int


class ModelMetrics(BaseModel):
    model_name: str
    split: Literal["val", "test"]
    macro_f1: float
    weighted_f1: float
    major_recall: float
    major_precision: float
    major_pr_auc: float
    roc_auc_ovr: float | None = None
    brier_major: float
    major_threshold: float
    moderate_threshold: float
    sample_count: int


class ArtifactManifest(BaseModel):
    model_name: Literal["lightgbm", "torch_mlp"]
    model_version: str
    created_at_utc: str
    severity_labels: tuple[SeverityLabel, ...] = SEVERITY_LABELS
    feature_config: DdiFeatureConfig
    major_threshold: float = Field(ge=0.05, le=0.95)
    moderate_threshold: float = Field(ge=0.05, le=0.95)
    val_metrics: ModelMetrics
    test_metrics: ModelMetrics
    split_summary: SplitSummary
    train_drugs: list[str] = Field(default_factory=list)
    train_scaffolds: list[str] = Field(default_factory=list)


class DrugPairInput(BaseModel):
    salt_a: str = Field(min_length=1)
    smiles_a: str = Field(min_length=1)
    salt_b: str = Field(min_length=1)
    smiles_b: str = Field(min_length=1)


class DdiPrediction(BaseModel):
    salt_a: str
    salt_b: str
    predicted_severity: SeverityLabel
    probabilities: dict[SeverityLabel, float]
    confidence: float = Field(ge=0.0, le=1.0)
    major_probability: float = Field(ge=0.0, le=1.0)
    tanimoto_similarity: float = Field(ge=0.0, le=1.0)
    model_name: str
    model_version: str
