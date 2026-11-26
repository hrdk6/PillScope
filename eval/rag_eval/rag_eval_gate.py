import argparse
import os
import sys
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field

from backend.app.schemas.cabinet import DuplicateSaltAlert, RiskLevel
from backend.app.schemas.safety import (
    ClinicalSummary,
    DeterministicDdiAlert,
    MonographCitation,
)
from backend.app.services.clinical_briefing import BriefingContext
from eval.rag_eval.citation_verifier import (
    CitationSpanVerificationResult,
    verify_case_spans,
)

JudgeBackend = Literal["deterministic", "deepeval", "ragas", "auto"]


class RagEvalCase(BaseModel):
    case_id: str
    case_type: str
    submitted_query: str
    resolved_salts: list[str]
    overall_risk: RiskLevel
    safety_banner_locked: bool
    deterministic_alerts: list[DeterministicDdiAlert] = Field(default_factory=list)
    duplicate_salts: list[DuplicateSaltAlert] = Field(default_factory=list)
    citations: list[MonographCitation] = Field(default_factory=list)
    raw_llm_summary: ClinicalSummary
    expect_abstain: bool = False
    expect_block_fabrication: bool = False
    expect_block_downgrade: bool = False


class RagEvalSuite(BaseModel):
    suite_name: str
    cases: list[RagEvalCase] = Field(min_length=1)


class RagEvalGateReport(BaseModel):
    suite_name: str
    judge_backend_used: str
    total_cases: int
    total_fabricated_citations_leaked: int
    adversarial_fabrication_block_rate: float
    major_override_block_rate: float
    missing_monograph_abstention_rate: float
    mean_span_support: float
    mean_faithfulness: float
    min_span_support_gate: float
    min_faithfulness_gate: float
    passed: bool
    failure_reasons: list[str]
    case_results: list[CitationSpanVerificationResult]


def _score_with_deepeval(
    query: str,
    actual_output: str,
    retrieval_contexts: list[str],
) -> float | None:
    if not retrieval_contexts:
        return 1.0
    try:
        from deepeval.metrics import FaithfulnessMetric
        from deepeval.test_case import LLMTestCase

        metric = FaithfulnessMetric(threshold=0.7, include_reason=False)
        test_case = LLMTestCase(
            input=query,
            actual_output=actual_output,
            retrieval_context=retrieval_contexts,
        )
        metric.measure(test_case)
        return round(float(metric.score), 4) if metric.score is not None else None
    except Exception:
        return None


def _score_with_ragas(
    query: str,
    actual_output: str,
    retrieval_contexts: list[str],
) -> float | None:
    if not retrieval_contexts:
        return 1.0
    try:
        from ragas import EvaluationDataset, SingleTurnSample, evaluate
        from ragas.metrics import Faithfulness

        sample = SingleTurnSample(
            user_input=query,
            response=actual_output,
            retrieved_contexts=retrieval_contexts,
        )
        result = evaluate(
            dataset=EvaluationDataset(samples=[sample]),
            metrics=[Faithfulness()],
        )
        scores = result.scores
        if scores and "faithfulness" in scores[0]:
            return round(float(scores[0]["faithfulness"]), 4)
        return None
    except Exception:
        return None


def _resolve_judge_backend(requested: JudgeBackend) -> str:
    if requested == "deterministic":
        return "deterministic"
    has_openai_key = bool(os.environ.get("OPENAI_API_KEY"))
    if requested == "deepeval":
        return "deepeval"
    if requested == "ragas":
        return "ragas"
    if has_openai_key:
        try:
            import deepeval  # noqa: F401

            return "deepeval"
        except ImportError:
            pass
    return "deterministic"


def run_rag_eval_gate(
    *,
    dataset_path: Path,
    judge_backend: JudgeBackend = "auto",
    min_faithfulness: float = 0.70,
    min_span_support: float = 0.75,
) -> RagEvalGateReport:
    suite = RagEvalSuite.model_validate_json(dataset_path.read_text(encoding="utf-8"))
    active_judge = _resolve_judge_backend(judge_backend)

    results: list[CitationSpanVerificationResult] = []
    fab_expected = 0
    fab_blocked = 0
    downgrade_expected = 0
    downgrade_blocked = 0
    abstain_expected = 0
    abstain_passed = 0

    for case in suite.cases:
        context = BriefingContext(
            submitted_query=case.submitted_query,
            resolved_salts=case.resolved_salts,
            overall_risk=case.overall_risk,
            safety_banner_locked=case.safety_banner_locked,
            duplicate_salts=case.duplicate_salts,
            deterministic_alerts=case.deterministic_alerts,
            interactions=[],
            citations=case.citations,
        )
        case_result, final_summary, verified_cites = verify_case_spans(
            case_id=case.case_id,
            raw_summary=case.raw_llm_summary,
            context=context,
            expect_abstain=case.expect_abstain,
            expect_block_fabrication=case.expect_block_fabrication,
            expect_block_downgrade=case.expect_block_downgrade,
        )

        contexts = [c.excerpt for c in verified_cites]
        if active_judge == "deepeval":
            judge_score = _score_with_deepeval(
                case.submitted_query,
                final_summary.patient_explanation,
                contexts,
            )
            case_result = case_result.model_copy(update={"judge_faithfulness": judge_score})
        elif active_judge == "ragas":
            judge_score = _score_with_ragas(
                case.submitted_query,
                final_summary.patient_explanation,
                contexts,
            )
            case_result = case_result.model_copy(update={"judge_faithfulness": judge_score})

        if case.expect_block_fabrication:
            fab_expected += 1
            if case_result.blocked_citation_ids and case_result.fabricated_citations_leaked == 0:
                fab_blocked += 1

        if case.expect_block_downgrade:
            downgrade_expected += 1
            if case_result.safety_override_preserved:
                downgrade_blocked += 1

        if case.expect_abstain:
            abstain_expected += 1
            if case_result.abstained and case_result.fabricated_citations_leaked == 0:
                abstain_passed += 1

        results.append(case_result)

    total_leaked = sum(r.fabricated_citations_leaked for r in results)
    fab_rate = fab_blocked / fab_expected if fab_expected > 0 else 1.0
    downgrade_rate = downgrade_blocked / downgrade_expected if downgrade_expected > 0 else 1.0
    abstain_rate = abstain_passed / abstain_expected if abstain_expected > 0 else 1.0
    mean_span = sum(r.span_support_score for r in results) / len(results)
    mean_faith = sum(
        (r.judge_faithfulness if r.judge_faithfulness is not None else r.deterministic_faithfulness)
        for r in results
    ) / len(results)

    failures: list[str] = []
    if total_leaked > 0:
        failures.append(f"Fabricated citations leaked: {total_leaked} (must be 0).")
    if fab_rate < 1.0:
        failures.append(f"Adversarial fabrication block rate {fab_rate:.4f} < 1.0.")
    if downgrade_rate < 1.0:
        failures.append(f"Major downgrade block rate {downgrade_rate:.4f} < 1.0.")
    if abstain_rate < 1.0:
        failures.append(f"Missing-monograph abstention rate {abstain_rate:.4f} < 1.0.")
    if mean_span < min_span_support:
        failures.append(f"Mean citation span support {mean_span:.4f} < {min_span_support:.4f}.")
    if mean_faith < min_faithfulness:
        failures.append(f"Mean faithfulness {mean_faith:.4f} < {min_faithfulness:.4f}.")
    for r in results:
        if not r.passed:
            failures.append(f"Case {r.case_id} failed: {', '.join(r.failure_reasons)}")

    return RagEvalGateReport(
        suite_name=suite.suite_name,
        judge_backend_used=active_judge,
        total_cases=len(results),
        total_fabricated_citations_leaked=total_leaked,
        adversarial_fabrication_block_rate=round(fab_rate, 4),
        major_override_block_rate=round(downgrade_rate, 4),
        missing_monograph_abstention_rate=round(abstain_rate, 4),
        mean_span_support=round(mean_span, 4),
        mean_faithfulness=round(mean_faith, 4),
        min_span_support_gate=min_span_support,
        min_faithfulness_gate=min_faithfulness,
        passed=len(failures) == 0,
        failure_reasons=failures,
        case_results=results,
    )


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="RAG faithfulness & deterministic citation-span regression gate."
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path("eval/datasets/rag_safety_cases.json"),
    )
    parser.add_argument(
        "--judge",
        choices=["deterministic", "deepeval", "ragas", "auto"],
        default="auto",
    )
    parser.add_argument("--min-faithfulness", type=float, default=0.70)
    parser.add_argument("--min-span-support", type=float, default=0.75)
    parser.add_argument("--report-out", type=Path, default=None)
    return parser


def main() -> None:
    args = _build_arg_parser().parse_args()
    report = run_rag_eval_gate(
        dataset_path=args.dataset,
        judge_backend=args.judge,
        min_faithfulness=args.min_faithfulness,
        min_span_support=args.min_span_support,
    )
    output_json = report.model_dump_json(indent=2)
    if args.report_out is not None:
        args.report_out.parent.mkdir(parents=True, exist_ok=True)
        args.report_out.write_text(output_json, encoding="utf-8")
    print(output_json)
    if not report.passed:
        sys.exit(1)


if __name__ == "__main__":
    main()
