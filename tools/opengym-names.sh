#!/usr/bin/env bash
# Builds Ridge's openGym exercise-name table (spec/03 §1) from a checkout of openGym's
# catalogue: one "id<TAB>name" line per exercise, names only. The catalogue's text is AGPL like
# Ridge (credited in NOTICE); its pictures and clips are licensed to openGym alone and are
# never read here.
#
#   git clone --depth 1 --filter=blob:none --sparse https://github.com/DuarteSantos8/openGym.git /tmp/og
#   git -C /tmp/og sparse-checkout set catalogue/exercises
#   tools/opengym-names.sh /tmp/og
set -euo pipefail
src=${1:?usage: tools/opengym-names.sh <openGym checkout>}
out="$(cd "$(dirname "$0")/.." && pwd)/android/app/src/main/assets/opengym/exercises.tsv"
mkdir -p "$(dirname "$out")"
find "$src/catalogue/exercises" -maxdepth 1 -name '*.json' -print0 \
    | xargs -0 jq -r 'select((.id|type)=="string" and (.name|type)=="string") | [.id, (.name|gsub("[\\t\\r\\n]+"; " "))] | @tsv' \
    | sort -t$'\t' -k1,1 > "$out.tmp"
mv "$out.tmp" "$out"
echo "$out: $(wc -l < "$out") exercises from openGym $(git -C "$src" rev-parse --short HEAD 2>/dev/null || echo '(unknown commit)')"
