from dataclasses import dataclass
from functools import lru_cache

import numpy as np
from numpy.typing import NDArray
from rdkit import Chem, DataStructs
from rdkit.Chem import QED, Descriptors, Lipinski, rdFingerprintGenerator

from ml.ddi.schemas import DESCRIPTOR_NAMES, DdiFeatureConfig

FloatArray = NDArray[np.float32]


@dataclass(frozen=True, slots=True)
class MoleculeFeatures:
    canonical_smiles: str
    fp_vector: FloatArray
    fp_bitvect: DataStructs.ExplicitBitVect
    descriptors: FloatArray


def _compute_descriptors(mol: Chem.Mol) -> FloatArray:
    values = [
        float(Descriptors.MolWt(mol)),
        float(Descriptors.MolLogP(mol)),
        float(Descriptors.TPSA(mol)),
        float(Lipinski.NumHDonors(mol)),
        float(Lipinski.NumHAcceptors(mol)),
        float(Lipinski.NumRotatableBonds(mol)),
        float(Lipinski.NumAromaticRings(mol)),
        float(Lipinski.NumAliphaticRings(mol)),
        float(Lipinski.FractionCSP3(mol)),
        float(mol.GetNumHeavyAtoms()),
        float(Lipinski.RingCount(mol)),
        float(QED.qed(mol)),
    ]
    return np.asarray(values, dtype=np.float32)


class DdiPairFeatureExtractor:
    def __init__(self, config: DdiFeatureConfig | None = None) -> None:
        self._config = config or DdiFeatureConfig()
        self._fp_gen = rdFingerprintGenerator.GetMorganGenerator(
            radius=self._config.fp_radius,
            fpSize=self._config.fp_bits,
            includeChirality=self._config.use_chirality,
        )
        self._featurize_cached = lru_cache(maxsize=8192)(self._featurize_smiles)

    @property
    def config(self) -> DdiFeatureConfig:
        return self._config

    @property
    def feature_dim(self) -> int:
        return self._config.total_features

    @property
    def continuous_feature_start(self) -> int:
        return 2 * self._config.fp_bits

    def _featurize_smiles(self, raw_smiles: str) -> MoleculeFeatures:
        mol = Chem.MolFromSmiles(raw_smiles.strip())
        if mol is None:
            raise ValueError(f"RDKit failed to parse SMILES: {raw_smiles!r}")

        canonical = str(Chem.MolToSmiles(mol, canonical=True))
        bitvect = self._fp_gen.GetFingerprint(mol)
        fp_arr = np.zeros((self._config.fp_bits,), dtype=np.float32)
        DataStructs.ConvertToNumpyArray(bitvect, fp_arr)
        descriptors = _compute_descriptors(mol)
        return MoleculeFeatures(
            canonical_smiles=canonical,
            fp_vector=fp_arr,
            fp_bitvect=bitvect,
            descriptors=descriptors,
        )

    def featurize_molecule(self, smiles: str) -> MoleculeFeatures:
        return self._featurize_cached(smiles)

    def extract_pair(self, smiles_a: str, smiles_b: str) -> tuple[FloatArray, float]:
        feat_a = self.featurize_molecule(smiles_a)
        feat_b = self.featurize_molecule(smiles_b)

        fp_sum = feat_a.fp_vector + feat_b.fp_vector
        fp_intersect = feat_a.fp_vector * feat_b.fp_vector

        desc_mean = 0.5 * (feat_a.descriptors + feat_b.descriptors)
        desc_abs_diff = np.abs(feat_a.descriptors - feat_b.descriptors)
        desc_min = np.minimum(feat_a.descriptors, feat_b.descriptors)
        desc_max = np.maximum(feat_a.descriptors, feat_b.descriptors)

        tanimoto = float(DataStructs.TanimotoSimilarity(feat_a.fp_bitvect, feat_b.fp_bitvect))
        dice = float(DataStructs.DiceSimilarity(feat_a.fp_bitvect, feat_b.fp_bitvect))
        sim_vec = np.asarray([tanimoto, dice], dtype=np.float32)

        pair_vector = np.concatenate(
            [fp_sum, fp_intersect, desc_mean, desc_abs_diff, desc_min, desc_max, sim_vec]
        ).astype(np.float32, copy=False)
        return pair_vector, tanimoto

    def extract_batch(self, smiles_pairs: list[tuple[str, str]]) -> tuple[FloatArray, list[float]]:
        if not smiles_pairs:
            return np.empty((0, self.feature_dim), dtype=np.float32), []

        matrix = np.empty((len(smiles_pairs), self.feature_dim), dtype=np.float32)
        tanimotos: list[float] = []
        for idx, (smiles_a, smiles_b) in enumerate(smiles_pairs):
            vec, tanimoto = self.extract_pair(smiles_a, smiles_b)
            matrix[idx] = vec
            tanimotos.append(tanimoto)
        return matrix, tanimotos

    def feature_names(self) -> list[str]:
        bits = self._config.fp_bits
        names = [f"fp_sum_{i}" for i in range(bits)]
        names.extend(f"fp_and_{i}" for i in range(bits))
        for prefix in ("desc_mean", "desc_diff", "desc_min", "desc_max"):
            names.extend(f"{prefix}_{desc}" for desc in DESCRIPTOR_NAMES)
        names.extend(["tanimoto_similarity", "dice_similarity"])
        return names
