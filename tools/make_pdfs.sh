#!/usr/bin/env bash
# Rebuild docs/*.pdf from docs/*.html with headless Chrome (no extra dependencies).
set -euo pipefail
cd "$(dirname "$0")/../docs"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
for pair in "report.html:Sweep-Technical-Report.pdf" "proposal.html:Sweep-Proposal.pdf"; do
  src="${pair%%:*}"; out="${pair##*:}"
  "$CHROME" --headless=new --disable-gpu --no-pdf-header-footer --virtual-time-budget=8000 \
    --print-to-pdf="$PWD/$out" "file://$PWD/$src" 2>/dev/null
  echo "wrote docs/$out"
done
