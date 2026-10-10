#!/usr/bin/env bash
# ui ジョブのスクリーンショットを、専用ブランチ qa-screenshots に通常のコミットで追記する（force push はしない）。
# コンテナからは Actions の artifact に届かないので、Director / Debugger は git fetch origin qa-screenshots で画像を見る。
# runs/<run_number>-<short_sha>/ に、幅 540px の JPEG（1 run 3MB 以下）と summary.txt を置く。統合ブランチには画像を入れない。
set -uo pipefail
src="${SCREENSHOT_DIR:-ui-out/screenshots}"
run="${RUN_NUMBER:?}"
sha="${SHA:?}"
short="${sha:0:7}"
status="${JOB_STATUS:-unknown}"
dest="runs/${run}-${short}"
keep_runs=40

shopt -s nullglob
files=("$src"/*.png)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "no screenshots in $src; writing the summary only"
fi

work="$(mktemp -d)"
mkdir -p "$work/shots"
for f in "${files[@]}"; do cp "$f" "$work/shots/"; done
if [[ ${#files[@]} -gt 0 ]]; then
  quality=72
  while :; do
    rm -f "$work"/shots/*.jpg
    for f in "$work"/shots/*.png; do
      convert "$f" -resize '540x>' -strip -quality "$quality" "${f%.png}.jpg" || mogrify -format jpg "$f"
    done
    bytes="$(cat "$work"/shots/*.jpg 2>/dev/null | wc -c)"
    [[ "$bytes" -le 3000000 || "$quality" -le 30 ]] && break
    quality=$((quality - 14))
  done
  rm -f "$work"/shots/*.png
fi

repo_dir="$(mktemp -d)"
cd "$repo_dir"
git init -q
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git remote add origin "${QA_REMOTE_URL:-https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git}"

push_once() {
  if git fetch -q --depth 1 origin qa-screenshots 2>/dev/null; then
    git checkout -q -B qa-screenshots FETCH_HEAD
  else
    git checkout -q --orphan qa-screenshots
    git rm -rq --cached . 2>/dev/null || true
  fi
  rm -rf "$dest"
  mkdir -p "$dest"
  cp "$work"/shots/*.jpg "$dest"/ 2>/dev/null || true
  {
    echo "run: ${run}"
    echo "commit: ${sha}"
    echo "job status: ${status}"
    echo "images: $(ls "$dest"/*.jpg 2>/dev/null | wc -l)"
    echo "bytes: $(cat "$dest"/*.jpg 2>/dev/null | wc -c)"
    echo "actions: https://github.com/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID:-}"
  } > "$dest/summary.txt"
  # 古い run を畳む（直近 keep_runs 件だけ残す）
  if [[ -d runs ]]; then
    ls -1 runs | sort -t- -k1,1n | head -n -"$keep_runs" 2>/dev/null | while read -r old; do git rm -rq "runs/$old"; done
  fi
  git add -A
  git commit -q -m "ui run ${run} (${short}): ${status}, $(ls "$dest"/*.jpg 2>/dev/null | wc -l) image(s)" || return 0
  git push -q origin qa-screenshots
}

for attempt in 1 2 3 4; do
  if push_once; then
    echo "published ${dest} to qa-screenshots"
    exit 0
  fi
  echo "push failed (attempt ${attempt}); retrying"
  sleep $((attempt * 3))
done
echo "could not publish screenshots" >&2
exit 1
