#!/usr/bin/env bash
# docs/04 §1: ソース・バージョンカタログ・ビルド定義に、実決済 SDK / 権限への参照が無いこと。
# grep の終了コードを区別する: 0 = 見つかった（失敗）、1 = 無い（合格）、2 以上 = 検査できなかった（失敗）。
set -uo pipefail
cd "${REPO_ROOT:-.}"

paths=(
  app/src
  gradle/libs.versions.toml
  build.gradle.kts
  settings.gradle.kts
  core/build.gradle.kts
  ai/build.gradle.kts
  app/build.gradle.kts
)
for p in "${paths[@]}"; do
  if [[ ! -e "$p" ]]; then
    echo "::error::expected path is missing, cannot check: $p" >&2
    exit 2
  fi
done

grep -rIn -e "com.android.vending.BILLING" -e "com.android.billingclient" -e "play-billing" "${paths[@]}"
rc=$?
case "$rc" in
  0)
    echo "::error::billing SDK or permission reference found (see lines above)" >&2
    exit 1
    ;;
  1)
    echo "ok: no billing SDK or permission references"
    ;;
  *)
    echo "::error::grep failed with exit code $rc; the check did not run" >&2
    exit 2
    ;;
esac
