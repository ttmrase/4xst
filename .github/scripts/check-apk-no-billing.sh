#!/usr/bin/env bash
# docs/04 §1: ビルド後の「マージ済みマニフェスト」と「APK の権限」に billing が無いこと（依存ライブラリが混ぜ込む権限も拾う）。
# 使い方: check-apk-no-billing.sh <APK のパス>
# 検査できなかった（マニフェストや aapt2 が見つからない等）場合も失敗にする。
set -uo pipefail
cd "${REPO_ROOT:-.}"
apk="${1:?usage: $0 <apk>}"

# grep の終了コード: 0 = 見つかった（失敗）、1 = 無い（合格）、2 以上 = 検査できなかった（失敗）
no_match() {
  local what="$1"
  shift
  grep -i "billing" "$@"
  local rc=$?
  case "$rc" in
    0)
      echo "::error::billing found in ${what} (see lines above)" >&2
      return 1
      ;;
    1) return 0 ;;
    *)
      echo "::error::grep failed (exit $rc) while checking ${what}" >&2
      return 2
      ;;
  esac
}

manifests=()
while IFS= read -r f; do manifests+=("$f"); done < <(find app/build/intermediates -type f -name AndroidManifest.xml -path '*merged_manifest*' 2>/dev/null | sort)
if [[ ${#manifests[@]} -eq 0 ]]; then
  echo "::error::no merged manifest found under app/build/intermediates; the build layout changed or the build did not run" >&2
  exit 2
fi
for m in "${manifests[@]}"; do
  no_match "merged manifest $m" "$m" || exit $?
done
echo "ok: ${#manifests[@]} merged manifest(s) without billing"

[[ -f "$apk" ]] || { echo "::error::APK not found: $apk" >&2; exit 2; }
aapt2="${AAPT2:-}"
if [[ -z "$aapt2" ]]; then
  aapt2="$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
fi
if [[ -z "$aapt2" || ! -x "$aapt2" ]]; then
  echo "::error::aapt2 not found (ANDROID_HOME=${ANDROID_HOME:-unset}); cannot read the APK permissions" >&2
  exit 2
fi
perms="$(mktemp)"
trap 'rm -f "$perms"' EXIT
"$aapt2" dump permissions "$apk" > "$perms" || { echo "::error::aapt2 failed on $apk" >&2; exit 2; }
if ! grep -q "^package:" "$perms"; then
  echo "::error::aapt2 output has no package line; refusing to pass" >&2
  exit 2
fi
no_match "APK permissions of $apk" "$perms" || exit $?
echo "ok: no billing permission in $apk"
