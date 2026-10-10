#!/usr/bin/env bash
# docs/04 §1: 決済事業者・ストア・カード会社の名称やロゴを模倣しない。
# アプリ・コア・AI のソースとリソースに、これらのブランド名が現れたら失敗する（docs/ は対象外）。
set -euo pipefail

pattern='\b(visa|mastercard|paypal|paypay|stripe|amex|american express|jcb|google pay|google play|apple pay|app store|line pay|rakuten pay|merpay)\b|楽天ペイ|メルペイ|アップルペイ|グーグルプレイ|d払い'

if grep -rIniE "$pattern" app/src/main core/src/main ai/src/main \
  --include=*.kt --include=*.xml --include=*.json --include=*.kts --include=*.md; then
  echo "::error::payment brand name found in app sources/resources (see lines above). Do not imitate payment brands." >&2
  exit 1
fi
echo "ok: no payment brand names in app/core/ai sources"
