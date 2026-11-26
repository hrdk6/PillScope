FROM ghcr.io/astral-sh/uv:0.6-python3.12-bookworm-slim AS builder

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=0

WORKDIR /app

COPY pyproject.toml uv.lock .python-version ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --extra ml

FROM python:3.12-slim-bookworm AS runtime

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONPATH="/app" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HF_HOME="/home/appuser/.cache/huggingface"

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    libgomp1 \
    libxrender1 \
    libxext6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 appuser \
    && useradd --uid 10001 --gid 10001 --create-home appuser

WORKDIR /app

COPY --from=builder /app/.venv /app/.venv
COPY alembic.ini ./
COPY backend/__init__.py ./backend/__init__.py
COPY backend/app ./backend/app
COPY backend/scripts ./backend/scripts
COPY ml/__init__.py ./ml/__init__.py
COPY ml/ddi ./ml/ddi
COPY ml/artifacts ./ml/artifacts
COPY eval/__init__.py ./eval/__init__.py
COPY eval/tracing ./eval/tracing
COPY infra/docker/entrypoint.sh /app/entrypoint.sh

RUN sed -i 's/\r$//' /app/entrypoint.sh \
    && chmod +x /app/entrypoint.sh \
    && mkdir -p /app/data/cache /home/appuser/.cache/huggingface \
    && chown -R appuser:appuser /app/data /home/appuser/.cache

USER appuser

EXPOSE 8000

ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["uvicorn", "backend.app.main:app", "--host", "0.0.0.0", "--port", "8000"]
