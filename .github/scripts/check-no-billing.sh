#!/usr/bin/env bash
# docs/04 §1: 実決済 SDK（Play Billing 等）への依存を持たない。依存グラフに "billing" を含む行が 1 件でもあれば失敗する。
#
# 使い方: check-no-billing.sh <Gradle プロジェクト> <configuration> [Gradle の追加引数...]
#   例:   check-no-billing.sh :core runtimeClasspath
#         check-no-billing.sh :app debugRuntimeClasspath -PwithAndroid=true
#
# テスト用: CHECK_NO_BILLING_FILE に依存グラフ相当のテキストファイルを指定すると、Gradle を呼ばずにそれを検査する。
set -euo pipefail

project="${1:?usage: $0 <project> <configuration> [gradle args...]}"
configuration="${2:?usage: $0 <project> <configuration> [gradle args...]}"
shift 2

report="$(mktemp)"
trap 'rm -f "$report"' EXIT

if [[ -n "${CHECK_NO_BILLING_FILE:-}" ]]; then
  cp "$CHECK_NO_BILLING_FILE" "$report"
else
  # Gradle が失敗したら（configuration が無い等）ここで止まる。空のレポートで素通りさせない。
  ./gradlew "${project}:dependencies" --configuration "$configuration" --console=plain "$@" > "$report"
fi

# 検査が空振りしていないことの確認（どの Kotlin モジュールの依存グラフにも kotlin-stdlib が出る）。
if ! grep -q "kotlin-stdlib" "$report"; then
  echo "::error::dependency report for ${project} ${configuration} looks empty; refusing to pass" >&2
  exit 2
fi

if grep -i "billing" "$report"; then
  echo "::error::${project} ${configuration} depends on a billing artifact (see lines above). Real payments are forbidden." >&2
  exit 1
fi
echo "ok: no billing artifact in ${project} ${configuration}"
