#!/usr/bin/env sh
set -eu

if [ "${RUN_MIGRATIONS:-false}" = "true" ]; then
  echo "[pillscope] Running Alembic migrations..."
  alembic upgrade head
fi

if [ "${RUN_BOOTSTRAP_SEED:-false}" = "true" ]; then
  echo "[pillscope] Running idempotent knowledge-base bootstrap..."
  python -m backend.scripts.bootstrap_db
fi

exec "$@"
