import secrets
from typing import Annotated

from arq.connections import RedisSettings, create_pool
from fastapi import APIRouter, Depends, Header, HTTPException, status

from backend.app.core.config import Settings, get_settings
from backend.app.schemas.safety import MonographIngestJobRequest, MonographIngestJobResponse

router = APIRouter(prefix="/api/v1/internal/jobs", tags=["internal"])


def _verify_admin_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_admin_api_key: Annotated[str | None, Header(alias="X-Admin-API-Key")] = None,
) -> None:
    if not x_admin_api_key or not secrets.compare_digest(
        x_admin_api_key, settings.internal_admin_api_key
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid or missing internal admin API key.",
        )


@router.post(
    "/ingest",
    response_model=MonographIngestJobResponse,
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(_verify_admin_key)],
)
async def enqueue_monograph_ingestion(
    body: MonographIngestJobRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> MonographIngestJobResponse:
    pool = await create_pool(RedisSettings.from_dsn(settings.redis_url))
    try:
        job = await pool.enqueue_job(
            "ingest_drug_monographs_job",
            body.drug_names,
            body.source,
        )
        if job is None:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Failed to enqueue ARQ monograph ingestion job.",
            )
        return MonographIngestJobResponse(
            job_id=job.job_id,
            drug_names=body.drug_names,
        )
    finally:
        await pool.aclose()
