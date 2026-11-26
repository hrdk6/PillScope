import random
from collections import defaultdict

import pandas as pd

from ml.ddi.schemas import SplitSummary


def scaffold_drug_disjoint_split(
    df: pd.DataFrame,
    *,
    train_frac: float = 0.60,
    val_frac: float = 0.20,
    test_frac: float = 0.20,
    seed: int = 42,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, SplitSummary]:
    if abs((train_frac + val_frac + test_frac) - 1.0) > 1e-6:
        raise ValueError("train_frac + val_frac + test_frac must equal 1.0.")
    if df.empty:
        raise ValueError("Cannot split an empty DDI dataset.")

    drug_to_scaffold: dict[str, str] = {}
    for row in df.itertuples(index=False):
        drug_to_scaffold[str(row.salt_a)] = str(row.scaffold_a)
        drug_to_scaffold[str(row.salt_b)] = str(row.scaffold_b)

    scaffold_to_drugs: dict[str, set[str]] = defaultdict(set)
    for drug, scaffold in drug_to_scaffold.items():
        scaffold_to_drugs[scaffold].add(drug)

    scaffolds = sorted(scaffold_to_drugs.keys())
    if len(scaffolds) < 3:
        raise ValueError(
            "At least 3 distinct Murcko scaffolds are required for disjoint splits; "
            f"found {len(scaffolds)}."
        )

    scaffold_adj: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    scaffold_major_adj: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    scaffold_degree: dict[str, int] = defaultdict(int)
    scaffold_major_degree: dict[str, int] = defaultdict(int)

    for row in df.itertuples(index=False):
        sc_a = str(row.scaffold_a)
        sc_b = str(row.scaffold_b)
        is_major = str(row.severity) == "MAJOR"
        scaffold_adj[sc_a][sc_b] += 1
        scaffold_adj[sc_b][sc_a] += 1
        scaffold_degree[sc_a] += 1
        scaffold_degree[sc_b] += 1
        if is_major:
            scaffold_major_adj[sc_a][sc_b] += 1
            scaffold_major_adj[sc_b][sc_a] += 1
            scaffold_major_degree[sc_a] += 1
            scaffold_major_degree[sc_b] += 1

    rng = random.Random(seed)
    tie_break = {sc: rng.random() for sc in scaffolds}
    ordered_scaffolds = sorted(
        scaffolds,
        key=lambda sc: (
            -scaffold_major_degree[sc],
            -scaffold_degree[sc],
            -len(scaffold_to_drugs[sc]),
            tie_break[sc],
        ),
    )

    total_drugs = len(drug_to_scaffold)
    targets = {
        "train": max(2.0, total_drugs * train_frac),
        "val": max(2.0, total_drugs * val_frac),
        "test": max(2.0, total_drugs * test_frac),
    }

    split_scaffolds: dict[str, set[str]] = {"train": set(), "val": set(), "test": set()}
    split_drug_counts: dict[str, int] = {"train": 0, "val": 0, "test": 0}
    split_major_edges: dict[str, int] = {"train": 0, "val": 0, "test": 0}

    for split_name, seed_scaffold in zip(
        ("train", "val", "test"), ordered_scaffolds[:3], strict=True
    ):
        split_scaffolds[split_name].add(seed_scaffold)
        split_drug_counts[split_name] += len(scaffold_to_drugs[seed_scaffold])
        split_major_edges[split_name] += scaffold_major_adj[seed_scaffold].get(seed_scaffold, 0)

    for sc in ordered_scaffolds[3:]:
        sc_size = len(scaffold_to_drugs[sc])
        eligible = [s for s in ("train", "val", "test") if split_drug_counts[s] < targets[s]]
        if not eligible:
            eligible = [
                min(("train", "val", "test"), key=lambda s: split_drug_counts[s] / targets[s])
            ]

        best_split = eligible[0]
        best_score = float("-inf")

        for split_name in eligible:
            members = split_scaffolds[split_name]
            member_count = max(len(members), 1)
            internal_density = sum(scaffold_adj[sc][other] for other in members) / member_count
            major_density = sum(scaffold_major_adj[sc][other] for other in members) / member_count
            remaining_ratio = (targets[split_name] - split_drug_counts[split_name]) / targets[
                split_name
            ]
            overshoot_penalty = (
                -5.0
                if (split_drug_counts[split_name] + sc_size) > (targets[split_name] * 1.25)
                else 0.0
            )
            major_need_bonus = (
                3.0 * major_density if split_major_edges[split_name] == 0 else 1.25 * major_density
            )
            score = (
                (internal_density + major_need_bonus + 0.1) * (1.0 + max(remaining_ratio, 0.0))
                + (2.0 * remaining_ratio)
                + overshoot_penalty
            )
            if score > best_score:
                best_score = score
                best_split = split_name

        split_scaffolds[best_split].add(sc)
        split_drug_counts[best_split] += sc_size
        split_major_edges[best_split] += sum(
            scaffold_major_adj[sc][other] for other in split_scaffolds[best_split]
        )

    split_drugs: dict[str, set[str]] = {
        name: {d for sc in sc_set for d in scaffold_to_drugs[sc]}
        for name, sc_set in split_scaffolds.items()
    }

    train_mask = df["salt_a"].isin(split_drugs["train"]) & df["salt_b"].isin(split_drugs["train"])
    val_mask = df["salt_a"].isin(split_drugs["val"]) & df["salt_b"].isin(split_drugs["val"])
    test_mask = df["salt_a"].isin(split_drugs["test"]) & df["salt_b"].isin(split_drugs["test"])

    train_df = df.loc[train_mask].reset_index(drop=True)
    val_df = df.loc[val_mask].reset_index(drop=True)
    test_df = df.loc[test_mask].reset_index(drop=True)

    train_used_drugs = set(train_df["salt_a"]).union(set(train_df["salt_b"]))
    val_used_drugs = set(val_df["salt_a"]).union(set(val_df["salt_b"]))
    test_used_drugs = set(test_df["salt_a"]).union(set(test_df["salt_b"]))

    train_used_scaffolds = set(train_df["scaffold_a"]).union(set(train_df["scaffold_b"]))
    val_used_scaffolds = set(val_df["scaffold_a"]).union(set(val_df["scaffold_b"]))
    test_used_scaffolds = set(test_df["scaffold_a"]).union(set(test_df["scaffold_b"]))

    drug_overlap = (
        len(train_used_drugs & val_used_drugs)
        + len(train_used_drugs & test_used_drugs)
        + len(val_used_drugs & test_used_drugs)
    )
    scaffold_overlap = (
        len(train_used_scaffolds & val_used_scaffolds)
        + len(train_used_scaffolds & test_used_scaffolds)
        + len(val_used_scaffolds & test_used_scaffolds)
    )
    if drug_overlap != 0 or scaffold_overlap != 0:
        raise RuntimeError(
            f"Leakage detected: drug_overlap={drug_overlap}, scaffold_overlap={scaffold_overlap}"
        )

    dropped = len(df) - (len(train_df) + len(val_df) + len(test_df))
    summary = SplitSummary(
        train_pairs=len(train_df),
        val_pairs=len(val_df),
        test_pairs=len(test_df),
        dropped_cross_boundary_pairs=dropped,
        train_drugs=len(train_used_drugs),
        val_drugs=len(val_used_drugs),
        test_drugs=len(test_used_drugs),
        train_scaffolds=len(train_used_scaffolds),
        val_scaffolds=len(val_used_scaffolds),
        test_scaffolds=len(test_used_scaffolds),
        drug_overlap_count=drug_overlap,
        scaffold_overlap_count=scaffold_overlap,
    )
    return train_df, val_df, test_df, summary
