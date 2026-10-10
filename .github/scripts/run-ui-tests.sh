#!/usr/bin/env bash
# エミュレータが起動した状態で呼ぶ（reactivecircus/android-emulator-runner の script から）。
# androidTest を実行し、成否にかかわらず logcat・テストレポート・スクリーンショットを ui-out/ に集める。
# スクリーンショットは Screenshots.kt がアプリの外部ファイル領域に書く。connected*AndroidTest は終了時に
# アプリをアンインストールして外部ファイルも消えるので、leaveApksInstalledAfterRun で残してから pull する。
set -uo pipefail
cd "${REPO_ROOT:-.}"
out="ui-out"
rm -rf "$out"
mkdir -p "$out/screenshots" "$out/logs"
adb logcat -c || true

# テストの開始・終了を logcat で追う（固まったとき「どのテストか」を result.txt に出すため）
adb logcat -v time -s TestRunner:I > "$out/logs/testrunner.txt" 2>&1 &
logcat_pid=$!
# 1 テスト 4 分で打ち切る（AndroidJUnitRunner の timeout_msec）。全体も 22 分で打ち切る
timeout 22m ./gradlew :app:connectedDebugAndroidTest -PwithAndroid=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=240000 2>&1 | tee "$out/logs/gradle.log"
status=${PIPESTATUS[0]}
kill "$logcat_pid" 2>/dev/null || true

# 結果の要約（コンパイルエラー、失敗したテスト）。ジョブのサマリと qa-screenshots の summary に出す
{
  echo "gradle exit status: $status"
  echo "--- compile errors (e: ...) ---"
  grep -E '^e: ' "$out/logs/gradle.log" | sed 's|file:///home/runner/work/4xst/4xst/||' | head -40
  echo "--- failed tests ---"
  grep -E ' FAILED$|^> There were failing tests|Tests? .* failed|AssertionError|Exception:' "$out/logs/gradle.log" | head -40
  echo "--- per test (androidTest-results xml) ---"
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
for f in sorted(glob.glob("app/build/outputs/androidTest-results/**/*.xml", recursive=True)):
    for tc in ET.parse(f).getroot().iter("testcase"):
        bad = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
        name = tc.get("classname", "").rsplit(".", 1)[-1] + "." + tc.get("name", "")
        if bad is None:
            print("PASS", name)
        else:
            print("FAIL", name)
            for line in (bad.get("message") or bad.text or "").strip().splitlines()[:4]:
                print("     ", line[:200])
PY
  echo "--- TestRunner (started / finished / failed) ---"
  grep -E 'started:|finished:|failed:' "$out/logs/testrunner.txt" | sed -E 's/^([0-9-]+ [0-9:.]+) .*TestRunner.*: /\1 /' | tail -40
  echo "--- instrumentation results ---"
  grep -hE 'Starting [0-9]+ tests|Finished [0-9]+ tests' "$out/logs/gradle.log" | head -10
} > "$out/result.txt" 2>&1

adb logcat -d > "$out/logs/logcat.txt" 2>&1 || true
adb pull /sdcard/Android/data/com.ttmrase.crownfall/files/screenshots/. "$out/screenshots/" || echo "no screenshots on the device"
for d in app/build/reports/androidTests app/build/outputs/androidTest-results; do
  [[ -d "$d" ]] && cp -r "$d" "$out/logs/$(basename "$d")"
done
cat "$out/result.txt"
echo "screenshots: $(find "$out/screenshots" -type f | wc -l) file(s); gradle exit status: $status"
exit "$status"
