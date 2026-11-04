#!/usr/bin/env bash
# Downloads the sing-box rule sets used by the "bypass Russian sites" option.
#   scripts/download-rulesets.sh <output dir>
set -euo pipefail
out="${1:?usage: $0 <output dir>}"
mkdir -p "$out"
base="https://raw.githubusercontent.com/SagerNet"
curl -fsSL --retry 3 -o "$out/geosite-category-ru.srs" "$base/sing-geosite/rule-set/geosite-category-ru.srs"
curl -fsSL --retry 3 -o "$out/geoip-ru.srs" "$base/sing-geoip/rule-set/geoip-ru.srs"
ls -l "$out"
