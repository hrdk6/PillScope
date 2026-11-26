from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib

from ml.ddi.models import LightGbmDdiModel, TorchMlpDdiModel
from ml.ddi.schemas import ArtifactManifest

DdiClassifier = LightGbmDdiModel | TorchMlpDdiModel


@dataclass(slots=True)
class LoadedDdiArtifact:
    manifest: ArtifactManifest
    model: DdiClassifier


def save_ddi_artifact(
    artifact_path: Path,
    manifest: ArtifactManifest,
    model: DdiClassifier,
) -> Path:
    artifact_path.parent.mkdir(parents=True, exist_ok=True)
    bundle: dict[str, Any] = {
        "schema_version": 1,
        "manifest": manifest.model_dump(mode="json"),
        "model_payload": model.to_payload(),
    }
    joblib.dump(bundle, artifact_path, compress=3)
    return artifact_path


def load_ddi_artifact(artifact_path: Path) -> LoadedDdiArtifact:
    if not artifact_path.exists():
        raise FileNotFoundError(f"DDI model artifact not found: {artifact_path}")
    bundle: dict[str, Any] = joblib.load(artifact_path)
    manifest = ArtifactManifest.model_validate(bundle["manifest"])
    payload: dict[str, Any] = bundle["model_payload"]

    model: DdiClassifier
    if manifest.model_name == "lightgbm":
        model = LightGbmDdiModel.from_payload(payload)
    elif manifest.model_name == "torch_mlp":
        model = TorchMlpDdiModel.from_payload(payload)
    else:
        raise ValueError(f"Unsupported model_name in artifact: {manifest.model_name}")

    return LoadedDdiArtifact(manifest=manifest, model=model)
