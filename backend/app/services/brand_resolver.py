from sqlalchemy import Float, cast, func, or_, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import BrandSaltMapping
from backend.app.schemas.drugs import (
    BrandCandidate,
    DrugResolveRequest,
    DrugResolveResponse,
    MatchStrategy,
    SaltComponent,
)
from backend.app.services.drug_normalizer import (
    canonical_salt_keys,
    normalize_brand_name,
    normalize_canonical_salt,
    parse_ocr_salts,
    strip_dosage_form_suffix,
)


def _mapping_to_candidate(
    mapping: BrandSaltMapping,
    match_strategy: MatchStrategy,
    confidence: float,
) -> BrandCandidate:
    salts = [SaltComponent.model_validate(item) for item in mapping.generic_salts]
    return BrandCandidate(
        brand_mapping_id=mapping.id,
        brand_name=mapping.brand_name,
        manufacturer=mapping.manufacturer,
        country_code=mapping.country_code.strip(),
        dosage_form=mapping.dosage_form,
        generic_salts=salts,
        canonical_salt_keys=list(mapping.canonical_salt_keys),
        match_strategy=match_strategy,
        confidence=round(min(max(confidence, 0.0), 1.0), 4),
    )


class BrandSaltResolver:
    def __init__(self, trigram_threshold: float = 0.25) -> None:
        self._trigram_threshold = trigram_threshold

    async def resolve(
        self,
        session: AsyncSession,
        request: DrugResolveRequest,
    ) -> DrugResolveResponse:
        norm_query = normalize_brand_name(request.query)
        core_query = strip_dosage_form_suffix(norm_query)
        extracted_salts = parse_ocr_salts(request.raw_ocr_text)

        candidates: list[BrandCandidate] = []
        seen_ids: set[str] = set()

        exact_matches = await self._find_exact_brand_matches(
            session=session,
            norm_query=norm_query,
            core_query=core_query,
            country_code=request.country_code,
            limit=request.limit,
        )
        for mapping, score in exact_matches:
            seen_ids.add(str(mapping.id))
            candidates.append(_mapping_to_candidate(mapping, "exact_brand", score))

        if len(candidates) < request.limit and norm_query:
            fuzzy_matches = await self._find_trigram_matches(
                session=session,
                norm_query=norm_query,
                core_query=core_query,
                country_code=request.country_code,
                limit=request.limit * 2,
            )
            for mapping, score in fuzzy_matches:
                if str(mapping.id) in seen_ids:
                    continue
                seen_ids.add(str(mapping.id))
                candidates.append(_mapping_to_candidate(mapping, "trigram_brand", score))
                if len(candidates) >= request.limit:
                    break

        if len(candidates) < request.limit:
            salt_query = normalize_canonical_salt(request.query)
            if salt_query:
                salt_matches = await self._find_by_canonical_salt(
                    session=session,
                    canonical_salt=salt_query,
                    country_code=request.country_code,
                    limit=request.limit - len(candidates),
                )
                for mapping in salt_matches:
                    if str(mapping.id) in seen_ids:
                        continue
                    seen_ids.add(str(mapping.id))
                    candidates.append(_mapping_to_candidate(mapping, "exact_salt", 0.85))

        if not candidates and extracted_salts:
            keys = canonical_salt_keys(extracted_salts)
            candidates.append(
                BrandCandidate(
                    brand_mapping_id=None,
                    brand_name=request.query.strip(),
                    manufacturer=None,
                    country_code=(request.country_code or "IN").upper(),
                    dosage_form=None,
                    generic_salts=extracted_salts,
                    canonical_salt_keys=keys,
                    match_strategy="ocr_salt_parse",
                    confidence=0.65,
                )
            )

        candidates.sort(key=lambda item: (-item.confidence, item.brand_name))
        return DrugResolveResponse(
            query=request.query,
            normalized_query=norm_query,
            candidates=candidates[: request.limit],
            extracted_salts=extracted_salts,
        )

    async def search(
        self,
        session: AsyncSession,
        query: str,
        country_code: str | None = None,
        limit: int = 10,
    ) -> list[BrandCandidate]:
        response = await self.resolve(
            session=session,
            request=DrugResolveRequest(
                query=query,
                country_code=country_code,
                limit=limit,
            ),
        )
        return response.candidates

    async def _find_exact_brand_matches(
        self,
        session: AsyncSession,
        norm_query: str,
        core_query: str,
        country_code: str | None,
        limit: int,
    ) -> list[tuple[BrandSaltMapping, float]]:
        if not norm_query:
            return []

        conditions = [BrandSaltMapping.normalized_brand == norm_query]
        if core_query:
            conditions.append(BrandSaltMapping.normalized_brand == core_query)
            conditions.append(BrandSaltMapping.normalized_brand.like(f"{core_query} %"))

        stmt = select(BrandSaltMapping).where(or_(*conditions))
        if country_code:
            stmt = stmt.where(BrandSaltMapping.country_code == country_code.upper())
        stmt = stmt.limit(limit * 3)

        rows = list((await session.execute(stmt)).scalars().all())
        scored: list[tuple[BrandSaltMapping, float]] = []
        for row in rows:
            if row.normalized_brand == norm_query:
                scored.append((row, 1.0))
            elif strip_dosage_form_suffix(row.normalized_brand) == core_query:
                scored.append((row, 0.97))

        scored.sort(key=lambda pair: (-pair[1], pair[0].brand_name))
        return scored[:limit]

    async def _find_trigram_matches(
        self,
        session: AsyncSession,
        norm_query: str,
        core_query: str,
        country_code: str | None,
        limit: int,
    ) -> list[tuple[BrandSaltMapping, float]]:
        target_query = core_query or norm_query
        await session.execute(
            text("SELECT set_config('pg_trgm.similarity_threshold', :threshold, true)"),
            {"threshold": str(self._trigram_threshold)},
        )

        sim_full = func.similarity(BrandSaltMapping.normalized_brand, norm_query)
        sim_core = func.similarity(BrandSaltMapping.normalized_brand, target_query)
        word_sim = func.word_similarity(target_query, BrandSaltMapping.normalized_brand)
        score_expr = cast(
            func.greatest(sim_full, sim_core, word_sim * 0.92),
            Float,
        ).label("trgm_score")

        stmt = select(BrandSaltMapping, score_expr).where(
            or_(
                BrandSaltMapping.normalized_brand.op("%")(norm_query),
                BrandSaltMapping.normalized_brand.op("%")(target_query),
                word_sim >= self._trigram_threshold,
            )
        )
        if country_code:
            stmt = stmt.where(BrandSaltMapping.country_code == country_code.upper())

        stmt = stmt.order_by(score_expr.desc(), BrandSaltMapping.brand_name.asc()).limit(limit)
        rows = (await session.execute(stmt)).all()
        results: list[tuple[BrandSaltMapping, float]] = []
        for row in rows:
            mapping: BrandSaltMapping = row[0]
            score = float(row[1])
            if score >= self._trigram_threshold:
                results.append((mapping, score))
        return results

    async def _find_by_canonical_salt(
        self,
        session: AsyncSession,
        canonical_salt: str,
        country_code: str | None,
        limit: int,
    ) -> list[BrandSaltMapping]:
        stmt = select(BrandSaltMapping).where(
            BrandSaltMapping.canonical_salt_keys.contains([canonical_salt])
        )
        if country_code:
            stmt = stmt.where(BrandSaltMapping.country_code == country_code.upper())
        stmt = stmt.order_by(BrandSaltMapping.brand_name.asc()).limit(limit)
        return list((await session.execute(stmt)).scalars().all())
