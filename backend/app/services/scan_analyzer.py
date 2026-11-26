import asyncio
import time
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import structlog
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.config import Settings
from backend.app.db.models import BrandSaltMapping, DdiPair, Medication, Profile, ScanLog
from backend.app.schemas.cabinet import RiskLevel
from backend.app.schemas.drugs import DrugResolveRequest
from backend.app.schemas.lifestyle import LifestyleTrigger
from backend.app.schemas.safety import (
    CitationsEvent,
    ClinicalSummaryEvent,
    DeterministicDdiAlert,
    DeterministicSafetyEvent,
    InteractionItem,
    InteractionsEvent,
    MonographCitation,
    ScanAnalyzeRequest,
    ScanCompleteEvent,
)
from backend.app.services.allergy_checker import detect_allergy_conflicts
from backend.app.services.brand_resolver import BrandSaltResolver
from backend.app.services.clinical_briefing import (
    BriefingContext,
    LlmBriefingGenerator,
    LlmTokenUsage,
    build_abstention_summary,
    build_extractive_grounded_summary,
    verify_clinical_briefing,
)
from backend.app.services.ddi_ml_inference import (
    DdiMlInferenceService,
    DeterministicRuleAlert,
    merge_deterministic_and_ml_interactions,
)
from backend.app.services.deterministic_safety import (
    detect_deterministic_ddi_alerts,
    detect_duplicate_salts,
    max_risk,
)
from backend.app.services.drug_normalizer import canonical_salt_keys, parse_ocr_salts
from backend.app.services.hybrid_rag import HybridRagRetriever
from backend.app.services.lifestyle_triggers import extract_lifestyle_triggers_for_salts
from backend.app.services.profiles import resolve_user_profile
from eval.tracing.otel import current_trace_id_hex, traced_span
from ml.ddi.data_prep import SmilesCacheStore, load_smiles_seed_csv
from ml.ddi.schemas import DdiPrediction, DrugPairInput

logger = structlog.get_logger(__name__)
_DEFAULT_SMILES_SEED_CSV = (
    Path(__file__).resolve().parents[3] / "ml" / "ddi" / "fixtures" / "drug_smiles_seed.csv"
)


async def _resolve_smiles_for_salts(
    session: AsyncSession,
    salts: list[str],
    cache_db_path: Path,
) -> dict[str, str]:
    if not salts:
        return {}

    smiles_map: dict[str, str] = {}
    salt_set = set(salts)

    ddi_stmt = select(
        DdiPair.salt_a,
        DdiPair.salt_a_smiles,
        DdiPair.salt_b,
        DdiPair.salt_b_smiles,
    ).where(or_(DdiPair.salt_a.in_(salts), DdiPair.salt_b.in_(salts)))
    for salt_a, smi_a, salt_b, smi_b in (await session.execute(ddi_stmt)).all():
        if salt_a in salt_set and smi_a and salt_a not in smiles_map:
            smiles_map[salt_a] = smi_a
        if salt_b in salt_set and smi_b and salt_b not in smiles_map:
            smiles_map[salt_b] = smi_b

    missing = [s for s in salts if s not in smiles_map]
    if missing:
        brand_stmt = select(BrandSaltMapping.smiles_map).where(
            BrandSaltMapping.canonical_salt_keys.overlap(missing)
        )
        for row_map in (await session.execute(brand_stmt)).scalars().all():
            if not isinstance(row_map, dict):
                continue
            for salt in missing:
                if salt in row_map and row_map[salt] and salt not in smiles_map:
                    smiles_map[salt] = str(row_map[salt])

    missing = [s for s in salts if s not in smiles_map]
    if missing:

        def _lookup_from_cache(names: list[str]) -> dict[str, str]:
            found: dict[str, str] = {}
            if cache_db_path.exists():
                store = SmilesCacheStore(cache_db_path)
                for name in names:
                    item = store.get(name)
                    if item is not None and item.smiles:
                        found[name] = item.smiles
            remaining = [name for name in names if name not in found]
            if remaining and _DEFAULT_SMILES_SEED_CSV.exists():
                seeded = load_smiles_seed_csv(_DEFAULT_SMILES_SEED_CSV)
                for name in remaining:
                    if name in seeded:
                        found[name] = seeded[name]
            return found

        cached = await asyncio.to_thread(_lookup_from_cache, missing)
        smiles_map.update(cached)

    return smiles_map


def _build_rag_query(
    resolved_salts: list[str],
    cabinet_salts: list[str],
    interactions: list[InteractionItem],
) -> str:
    terms: list[str] = [*resolved_salts]
    if interactions:
        for item in interactions[:3]:
            terms.extend([item.salt_a, item.salt_b])
        terms.extend(["drug interactions", "warnings", "bleeding", "dosage"])
    else:
        terms.extend(cabinet_salts[:4])
        terms.extend(["drug interactions", "warnings", "dosage and administration"])
    return " ".join(dict.fromkeys(terms))


def _format_sse_event(event_id: int, event_name: str, data_json: str) -> dict[str, str]:
    return {"id": str(event_id), "event": event_name, "data": data_json}


async def stream_scan_analysis(
    *,
    session: AsyncSession,
    user_id: UUID,
    request: ScanAnalyzeRequest,
    settings: Settings,
    rag_retriever: HybridRagRetriever | None,
    briefing_generator: LlmBriefingGenerator,
    ml_service: DdiMlInferenceService | None = None,
    profile: Profile | None = None,
) -> AsyncIterator[dict[str, str]]:
    started_at = time.perf_counter()
    scan_id = uuid4()
    event_seq = 0
    active_profile = profile or await resolve_user_profile(
        session=session,
        user_id=user_id,
        profile_id=request.profile_id,
    )

    with traced_span(
        "pillscope.scan.analyze",
        attributes={
            "pillscope.scan_id": str(scan_id),
            "pillscope.profile_id": str(active_profile.id),
            "pillscope.persist": request.persist,
            "pillscope.input_mode": request.input_mode,
        },
        instrumentation_name="pillscope.scan",
    ) as root_span:
        trace_id = current_trace_id_hex()

        tier1_start = time.perf_counter()
        with traced_span(
            "pillscope.scan.deterministic_safety",
            instrumentation_name="pillscope.scan",
        ) as det_span:
            if request.confirmed_salts:
                resolved_salts = canonical_salt_keys(request.confirmed_salts)
            else:
                resolver = BrandSaltResolver(
                    trigram_threshold=settings.trigram_similarity_threshold
                )
                resolution = await resolver.resolve(
                    session=session,
                    request=DrugResolveRequest(
                        query=request.submitted_query,
                        raw_ocr_text=request.raw_ocr_text,
                        limit=1,
                    ),
                )
                if resolution.candidates:
                    resolved_salts = canonical_salt_keys(
                        resolution.candidates[0].canonical_salt_keys
                    )
                elif resolution.extracted_salts:
                    resolved_salts = canonical_salt_keys(resolution.extracted_salts)
                else:
                    ocr_salts = parse_ocr_salts(request.raw_ocr_text)
                    resolved_salts = canonical_salt_keys(ocr_salts)

            active_meds = list(
                (
                    await session.execute(
                        select(Medication).where(
                            Medication.user_id == user_id,
                            Medication.profile_id == active_profile.id,
                            Medication.is_active.is_(True),
                        )
                    )
                )
                .scalars()
                .all()
            )
            cabinet_snapshot: list[dict[str, Any]] = [
                {
                    "medication_id": str(med.id),
                    "display_name": med.display_name,
                    "canonical_salt_keys": list(med.canonical_salt_keys),
                }
                for med in active_meds
            ]
            cabinet_salts = canonical_salt_keys(
                salt for med in active_meds for salt in med.canonical_salt_keys
            )

            duplicate_salts = await detect_duplicate_salts(
                session=session,
                user_id=user_id,
                profile_id=active_profile.id,
                candidate_salts=resolved_salts,
            )
            allergy_alerts = detect_allergy_conflicts(
                candidate_salts=resolved_salts,
                allergies_json=active_profile.allergies_json,
            )
            deterministic_alerts: list[
                DeterministicDdiAlert
            ] = await detect_deterministic_ddi_alerts(
                session=session,
                candidate_salts=resolved_salts,
                cabinet_salts=cabinet_salts,
            )

            has_major_ddi = any(alert.severity == "MAJOR" for alert in deterministic_alerts)
            safety_banner_locked = bool(duplicate_salts) or bool(allergy_alerts) or has_major_ddi
            overall_risk: RiskLevel = "MAJOR" if safety_banner_locked else "NONE"
            for alert in deterministic_alerts:
                overall_risk = max_risk(overall_risk, alert.severity)

            det_span.set_attribute("pillscope.resolved_salt_count", len(resolved_salts))
            det_span.set_attribute("pillscope.duplicate_count", len(duplicate_salts))
            det_span.set_attribute("pillscope.allergy_alert_count", len(allergy_alerts))
            det_span.set_attribute("pillscope.deterministic_alert_count", len(deterministic_alerts))
            det_span.set_attribute("pillscope.safety_banner_locked", safety_banner_locked)
            det_span.set_attribute(
                "pillscope.duration_ms",
                round((time.perf_counter() - tier1_start) * 1000.0, 2),
            )

        event_seq += 1
        det_event = DeterministicSafetyEvent(
            scan_id=scan_id,
            profile_id=active_profile.id,
            persisted=request.persist,
            submitted_query=request.submitted_query,
            resolved_salts=resolved_salts,
            overall_risk=overall_risk,
            safety_banner_locked=safety_banner_locked,
            duplicate_salts=duplicate_salts,
            allergy_alerts=allergy_alerts,
            deterministic_alerts=deterministic_alerts,
        )
        yield _format_sse_event(event_seq, "deterministic_safety", det_event.model_dump_json())

        tier2_start = time.perf_counter()
        ml_predictions: list[DdiPrediction] = []
        with traced_span(
            "pillscope.scan.ml_ddi",
            instrumentation_name="pillscope.ml",
        ) as ml_span:
            if ml_service is not None and resolved_salts and cabinet_salts:
                ml_span.set_attribute("pillscope.ml.model_name", ml_service.manifest.model_name)
                all_salts = sorted(set(resolved_salts) | set(cabinet_salts))
                smiles_map = await _resolve_smiles_for_salts(
                    session=session,
                    salts=all_salts,
                    cache_db_path=Path(settings.smiles_cache_db_path),
                )
                pair_inputs: list[DrugPairInput] = []
                seen_pairs: set[tuple[str, str]] = set()
                for c_salt in resolved_salts:
                    for m_salt in cabinet_salts:
                        if c_salt == m_salt:
                            continue
                        ordered = (c_salt, m_salt) if c_salt < m_salt else (m_salt, c_salt)
                        if ordered in seen_pairs:
                            continue
                        smi_a = smiles_map.get(ordered[0])
                        smi_b = smiles_map.get(ordered[1])
                        if smi_a and smi_b:
                            seen_pairs.add(ordered)
                            pair_inputs.append(
                                DrugPairInput(
                                    salt_a=ordered[0],
                                    smiles_a=smi_a,
                                    salt_b=ordered[1],
                                    smiles_b=smi_b,
                                )
                            )
                if pair_inputs:
                    try:
                        ml_predictions = await asyncio.to_thread(
                            ml_service.score_pairs_batch, pair_inputs
                        )
                    except Exception as exc:
                        logger.warning("ml_ddi_inference_failed", error=str(exc))

            ml_span.set_attribute("pillscope.ml.scored_pairs", len(ml_predictions))
            ml_span.set_attribute(
                "pillscope.duration_ms",
                round((time.perf_counter() - tier2_start) * 1000.0, 2),
            )

        det_rule_alerts = [
            DeterministicRuleAlert(
                rule_id=alert.rule_id,
                salt_a=alert.salt_a,
                salt_b=alert.salt_b,
                severity=alert.severity,
                mechanism=alert.mechanism,
                management=alert.management,
                source=alert.sources[0] if alert.sources else "ddinter_2",
            )
            for alert in deterministic_alerts
        ]
        merged = merge_deterministic_and_ml_interactions(
            deterministic_alerts=det_rule_alerts,
            ml_predictions=ml_predictions,
            has_duplicate_salts=bool(duplicate_salts),
            has_allergy_alerts=bool(allergy_alerts),
        )
        overall_risk = max_risk(overall_risk, merged.overall_risk)
        safety_banner_locked = safety_banner_locked or merged.safety_banner_locked
        interactions = [
            InteractionItem(
                salt_a=item.salt_a,
                salt_b=item.salt_b,
                severity=item.severity,
                confidence=item.confidence,
                source=item.source,
                mechanism=item.mechanism,
                management=item.management,
                ml_major_probability=item.ml_major_probability,
            )
            for item in merged.interactions
        ]

        event_seq += 1
        interactions_event = InteractionsEvent(
            scan_id=scan_id,
            overall_risk=overall_risk,
            safety_banner_locked=safety_banner_locked,
            interactions=interactions,
            ml_scored_pair_count=len(ml_predictions),
        )
        yield _format_sse_event(event_seq, "interactions", interactions_event.model_dump_json())

        involved_salts = sorted(set(resolved_salts) | set(cabinet_salts))
        rag_query = _build_rag_query(resolved_salts, cabinet_salts, interactions)
        retrieved_citations: list[MonographCitation] = []
        if involved_salts and rag_retriever is not None:
            try:
                retrieved_citations = await rag_retriever.retrieve(
                    session=session,
                    query=rag_query,
                    involved_salts=involved_salts,
                )
            except Exception as exc:
                logger.warning("hybrid_rag_retrieval_failed", error=str(exc))
                await session.rollback()

        lifestyle_triggers: list[LifestyleTrigger] = []
        if resolved_salts:
            try:
                lifestyle_triggers = await extract_lifestyle_triggers_for_salts(
                    session=session,
                    canonical_salts=resolved_salts,
                )
            except Exception as exc:
                logger.warning("lifestyle_trigger_extraction_failed", error=str(exc))

        event_seq += 1
        citations_event = CitationsEvent(
            scan_id=scan_id,
            citations=retrieved_citations,
            lifestyle_triggers=lifestyle_triggers,
        )
        yield _format_sse_event(event_seq, "citations", citations_event.model_dump_json())

        briefing_context = BriefingContext(
            submitted_query=request.submitted_query,
            resolved_salts=resolved_salts,
            overall_risk=overall_risk,
            safety_banner_locked=safety_banner_locked,
            duplicate_salts=duplicate_salts,
            deterministic_alerts=deterministic_alerts,
            interactions=interactions,
            citations=retrieved_citations,
            allergy_alerts=allergy_alerts,
        )

        llm_start = time.perf_counter()
        with traced_span(
            "pillscope.llm.synthesize",
            attributes={
                "gen_ai.system": str(getattr(briefing_generator, "provider_name", "custom")),
                "gen_ai.request.model": str(getattr(briefing_generator, "model_name", "unknown")),
            },
            instrumentation_name="pillscope.llm",
        ) as llm_span:
            if not retrieved_citations:
                raw_summary = build_abstention_summary(briefing_context)
            else:
                try:
                    raw_summary = await briefing_generator.generate(briefing_context)
                except Exception as exc:
                    logger.warning("llm_briefing_generation_failed", error=str(exc))
                    raw_summary = build_extractive_grounded_summary(briefing_context)

            verified = verify_clinical_briefing(raw_summary, briefing_context)
            usage = getattr(briefing_generator, "last_usage", None)
            if isinstance(usage, LlmTokenUsage):
                llm_span.set_attribute("gen_ai.usage.input_tokens", usage.input_tokens)
                llm_span.set_attribute("gen_ai.usage.output_tokens", usage.output_tokens)
                llm_span.set_attribute("gen_ai.usage.total_tokens", usage.total_tokens)
            else:
                llm_span.set_attribute("gen_ai.usage.input_tokens", 0)
                llm_span.set_attribute("gen_ai.usage.output_tokens", 0)
                llm_span.set_attribute("gen_ai.usage.total_tokens", 0)
            llm_span.set_attribute("pillscope.llm.abstained", verified.summary.abstained)
            llm_span.set_attribute(
                "pillscope.llm.blocked_citation_count", len(verified.blocked_citation_ids)
            )
            llm_span.set_attribute(
                "pillscope.llm.latency_ms",
                round((time.perf_counter() - llm_start) * 1000.0, 2),
            )

        event_seq += 1
        summary_event = ClinicalSummaryEvent(
            scan_id=scan_id,
            overall_risk=overall_risk,
            safety_banner_locked=safety_banner_locked,
            citation_verified=verified.citation_verified,
            blocked_citation_ids=verified.blocked_citation_ids,
            clinical_summary=verified.summary,
            citations=verified.verified_citations,
        )
        yield _format_sse_event(event_seq, "clinical_summary", summary_event.model_dump_json())

        latency_ms = max(int((time.perf_counter() - started_at) * 1000), 1)
        root_span.set_attribute("pillscope.overall_risk", overall_risk)
        root_span.set_attribute("pillscope.safety_banner_locked", safety_banner_locked)
        root_span.set_attribute("pillscope.citation_verified", verified.citation_verified)
        root_span.set_attribute("pillscope.lifestyle_trigger_count", len(lifestyle_triggers))
        root_span.set_attribute("pillscope.latency_ms", latency_ms)

        if request.persist:
            scan_log = ScanLog(
                id=scan_id,
                user_id=user_id,
                profile_id=active_profile.id,
                input_mode=request.input_mode,
                raw_ocr_text=request.raw_ocr_text,
                edge_quality_metrics=request.edge_quality_metrics,
                submitted_query=request.submitted_query,
                resolved_salts=[{"canonical_salt": s} for s in resolved_salts],
                cabinet_snapshot=cabinet_snapshot,
                overall_risk=overall_risk,
                safety_banner_locked=safety_banner_locked,
                deterministic_alerts=[a.model_dump(mode="json") for a in deterministic_alerts],
                duplicate_salts=[d.model_dump(mode="json") for d in duplicate_salts],
                allergy_alerts=[a.model_dump(mode="json") for a in allergy_alerts],
                lifestyle_triggers=[t.model_dump(mode="json") for t in lifestyle_triggers],
                interactions=[i.model_dump(mode="json") for i in interactions],
                clinical_summary=verified.summary.model_dump(mode="json"),
                citations=[c.model_dump(mode="json") for c in verified.verified_citations],
                citation_verified=verified.citation_verified,
                trace_id=trace_id,
                latency_ms=latency_ms,
            )
            session.add(scan_log)
            await session.commit()

        event_seq += 1
        complete_event = ScanCompleteEvent(
            scan_id=scan_id,
            profile_id=active_profile.id,
            persisted=request.persist,
            submitted_query=request.submitted_query,
            resolved_salts=resolved_salts,
            overall_risk=overall_risk,
            safety_banner_locked=safety_banner_locked,
            deterministic_alerts=deterministic_alerts,
            duplicate_salts=duplicate_salts,
            allergy_alerts=allergy_alerts,
            lifestyle_triggers=lifestyle_triggers,
            interactions=interactions,
            clinical_summary=verified.summary,
            citations=verified.verified_citations,
            citation_verified=verified.citation_verified,
            blocked_citation_ids=verified.blocked_citation_ids,
            latency_ms=latency_ms,
        )
        yield _format_sse_event(event_seq, "complete", complete_event.model_dump_json())
