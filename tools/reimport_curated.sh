#!/usr/bin/env bash
# Re-imports the curated manifests (forms with a curation/ file) from the private legacy export.
# Usage: bash tools/reimport_curated.sh "<path to .mforge>" <YYYY-MM-DD> [--preview-pending <out dir under .local/>]
# With --preview-pending, pending 'decision' corrections are applied too and the manifests are written to the given
# .local/ directory for visual review only (never into mappings/).
set -euo pipefail
M=$1; DATE=$2; PREVIEW=${3:-}; OUTDIR=${4:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
run() { # pdf template-id version encryption out-subdir
  local out="$ROOT/mappings/$5/$2@$3.json" extra=()
  if [ "$PREVIEW" = "--preview-pending" ]; then out="$OUTDIR/$2@$3.preview.json"; extra=(--preview-pending); fi
  python3 "$ROOT/tools/import_mforge.py" manifest "$M" --flow "Flow C" --pdf "$1" --template-id "$2" --template-version "$3" \
    --date "$DATE" --strict --encryption "$4" --curation "$ROOT/curation/$2@$3.json" "${extra[@]}" --out "$out"
}
run "ASB Home Loan Boarder Declaration Form.pdf" ASB_BOARDER_DECLARATION 12-22 preserve asb
run "Boarder-Income-Declaration 12.02.24.pdf" ANZ_BOARDER_INCOME_DECLARATION 02-24 reject anz
run "GHL_N&P Individual_8 19.pdf" GHL_NP_INDIVIDUAL_FORM 8-19 preserve ghl
run "GHL_N&P Trust_8 19.pdf" GHL_NP_TRUST_FORM 8-19 preserve ghl
