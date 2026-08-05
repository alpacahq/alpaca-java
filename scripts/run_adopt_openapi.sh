#!/usr/bin/env bash
# Bootstrap .venv-openapi and run scripts/adopt_openapi.py with forwarded args.
# Prefer Gradle lifecycle tasks (no nested gradlew):
#   ./gradlew adoptOpenApiDryRun | adoptOpenApi | adoptOpenApiBreaking
# This shell entrypoint is for standalone use; it may invoke ./gradlew itself.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VENV="${ROOT}/.venv-openapi"
PYTHON="${VENV}/bin/python"
PIP="${VENV}/bin/pip"
REQUIREMENTS="${ROOT}/scripts/requirements.txt"
STAMP="${VENV}/.requirements-stamp"

if [[ ! -x "${PYTHON}" ]]; then
  echo "Creating ${VENV} ..."
  python3 -m venv "${VENV}"
fi

if [[ ! -f "${STAMP}" ]] || ! cmp -s "${REQUIREMENTS}" "${STAMP}"; then
  "${PIP}" install -q -r "${REQUIREMENTS}"
  cp "${REQUIREMENTS}" "${STAMP}"
fi

export PYTHONPATH="${ROOT}"
exec "${PYTHON}" "${ROOT}/scripts/adopt_openapi.py" "$@"
