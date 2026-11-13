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

# エミュレータのランチャーなど、アプリ以外の「応答していません」ダイアログがフォーカスを奪うと、
# Espresso の戻る操作が全部 RootViewWithoutFocusException で落ちる（ui run 119）。システムのエラーダイアログを出さない設定にし、
# すでに出ているものは閉じる。
adb shell settings put global hide_error_dialogs 1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true

# テストの開始・終了を logcat で追う（固まったとき「どのテストか」を result.txt に出すため）
adb logcat -v time -s TestRunner:I > "$out/logs/testrunner.txt" 2>&1 &
logcat_pid=$!
# 性能の行（PerfUiTest）は、あとのテストの logcat に押し流されないよう、流れてくるそばからファイルに残す
adb logcat -v time -s CrownfallPerf:I > "$out/logs/perf.txt" 2>&1 &
perf_pid=$!
# gradle はバックグラウンドで動かし、20 分で見切る（pipe で tee に繋ぐと、固まったときに timeout が効かず job の上限まで待つ）
./gradlew :app:connectedDebugAndroidTest -PwithAndroid=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  > "$out/logs/gradle.log" 2>&1 &
gradle_pid=$!
tail -n +1 -f "$out/logs/gradle.log" &
tail_pid=$!
deadline=$((SECONDS + 1200))
hung=0
while kill -0 "$gradle_pid" 2>/dev/null; do
  if [[ $SECONDS -ge $deadline ]]; then
    hung=1
    echo "::error::androidTest did not finish in 20 minutes: capturing the screen and the app stack, then stopping"
    # 端末やデーモンが固まっていても、ここで止まらないように各コマンドに時間切れを付ける（ui run 120・126 は後始末で 13 分止まった）
    timeout 30 adb exec-out screencap -p > "$out/screenshots/HANG_screen.png" || true
    timeout 20 adb root >/dev/null 2>&1 || true
    pid="$(timeout 20 adb shell pidof com.ttmrase.crownfall | tr -d '\r')"
    [[ -n "$pid" ]] && timeout 20 adb shell kill -3 "$pid" || true
    sleep 3
    timeout 60 adb pull /data/anr "$out/logs/anr" >/dev/null 2>&1 || true
    timeout 60 ./gradlew --stop >/dev/null 2>&1 || true
    kill "$gradle_pid" 2>/dev/null || true
    break
  fi
  sleep 5
done
wait "$gradle_pid"
status=$?
[[ $hung -eq 1 ]] && status=124
kill "$tail_pid" 2>/dev/null || true
kill "$logcat_pid" "$perf_pid" 2>/dev/null || true

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
  echo "--- perf (logcat CrownfallPerf) ---"
  grep 'CrownfallPerf' "$out/logs/perf.txt" | sed -E 's/^[0-9-]+ [0-9:.]+ +[A-Z]\/CrownfallPerf\([ 0-9]+\): //' | tail -8
  echo "--- instrumentation results ---"
  grep -hE 'Starting [0-9]+ tests|Finished [0-9]+ tests' "$out/logs/gradle.log" | head -10
} > "$out/result.txt" 2>&1

timeout 60 adb logcat -d > "$out/logs/logcat.txt" 2>&1 || true
# 固まった・落ちたときの手がかりを要約に残す（artifact に届かない環境でも qa-screenshots の summary で読める）:
# アプリの例外と ANR の行、ANR のトレースからアプリのメインスレッドのスタック
{
  echo "--- crash / ANR lines (logcat) ---"
  grep -E 'FATAL EXCEPTION|AndroidRuntime: (Process|java\.|kotlin\.|Caused by|\tat com\.ttmrase)|ANR in |am_anr|lowmemorykiller|Low on memory' \
    "$out/logs/logcat.txt" | cut -c1-220 | tail -40
  echo "--- main thread of the app (ANR trace) ---"
  python3 - "$out/logs/anr" <<'PY'
import glob, os, sys
files = sorted(glob.glob(os.path.join(sys.argv[1], "**", "*"), recursive=True), key=os.path.getmtime, reverse=True)
for f in files:
    if not os.path.isfile(f):
        continue
    text = open(f, errors="replace").read()
    if "com.ttmrase.crownfall" not in text:
        continue
    start = text.find('"main"')
    if start >= 0:
        print("\n".join(line[:200] for line in text[start:].splitlines()[:45]))
    break
PY
} >> "$out/result.txt" 2>&1
timeout 120 adb pull /sdcard/Android/data/com.ttmrase.crownfall/files/screenshots/. "$out/screenshots/" || echo "no screenshots on the device"
for d in app/build/reports/androidTests app/build/outputs/androidTest-results; do
  [[ -d "$d" ]] && cp -r "$d" "$out/logs/$(basename "$d")"
done
cat "$out/result.txt"
echo "screenshots: $(find "$out/screenshots" -type f | wc -l) file(s); gradle exit status: $status"
exit "$status"
