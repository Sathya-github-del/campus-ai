#!/usr/bin/env bash
# Start the backend with uvicorn. Reads .env from this directory (copy .env.example first).
# Usage: ./run.sh [--reload]     HOST/PORT env vars override the bind address (default 127.0.0.1:8000).
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -d .venv ]]; then
  python3 -m venv .venv
  .venv/bin/pip install -q -r requirements.txt
fi
# Single worker on purpose: rate limits are in-process (see README "Scaling").
exec .venv/bin/uvicorn --factory app.api:create_app \
  --host "${HOST:-127.0.0.1}" --port "${PORT:-8000}" --proxy-headers "$@"
