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

# エミュレータを暖める。エミュレータの GPU はソフトウェア実装で、初めて使う描画の組み合わせをその場でコンパイルする。
# インストール直後の最初のテストで画面を次々に開くと、エミュレータのプロセスごと落ちた（ui run 133〜138・149: 最初のテストの
# 途中で adb から端末が消え、アプリ側には例外も ANR も無い）。テストの前にアプリを何度か起動しておいた run 143〜145 では
# 一度も落ちていない。そこで、テストの前にアプリを数回起動して描かせておく。テストは、いつもどおりまっさらなデータから始める。
warmup="$out/logs/warmup.txt"
echo "host memory before: $(free -m | awk 'NR==2{print "used " $3 " MB, available " $7 " MB"}')" > "$warmup"
if timeout 900 ./gradlew :app:installDebug -PwithAndroid=true > "$out/logs/install.log" 2>&1; then
  for i in 1 2 3 4 5; do
    timeout 30 adb shell pm clear com.ttmrase.crownfall > /dev/null 2>&1 || true
    timeout 40 adb shell am start -W -n com.ttmrase.crownfall/.MainActivity > /dev/null 2>&1 || true
    sleep 5
    state="$(timeout 10 adb get-state 2>&1 | tr -d '\r' | tail -1)"
    echo "warm-up start $i: device=$state host_available_mb=$(free -m | awk 'NR==2{print $7}')" >> "$warmup"
    [[ "$state" != "device" ]] && break
  done
  timeout 20 adb shell am force-stop com.ttmrase.crownfall > /dev/null 2>&1 || true
  timeout 30 adb shell pm clear com.ttmrase.crownfall > /dev/null 2>&1 || true
else
  echo "installDebug failed: $(grep -E '^e: |FAILED|What went wrong' -A1 "$out/logs/install.log" | head -6 | tr '\n' ' ')" >> "$warmup"
fi

# テストの開始・終了を logcat で追う（固まったとき「どのテストか」を result.txt に出すため）
adb logcat -v time -s TestRunner:I > "$out/logs/testrunner.txt" 2>&1 &
logcat_pid=$!
# 性能の行（PerfUiTest）は、あとのテストの logcat に押し流されないよう、流れてくるそばからファイルに残す
adb logcat -v time -s CrownfallPerf:I > "$out/logs/perf.txt" 2>&1 &
perf_pid=$!
# 警告以上の行も流れてくるそばから残す。端末が途中で応答しなくなると、あとから `adb logcat -d` では何も取れない
# （ui run 133: 最初のテストで端末が落ち、要約の crash / ANR の欄が空だった）
adb logcat -v time '*:W' > "$out/logs/warn.txt" 2>&1 &
warn_pid=$!
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
kill "$logcat_pid" "$perf_pid" "$warn_pid" 2>/dev/null || true

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
  echo "--- device state at the end ---"
  timeout 20 adb devices 2>&1 | tail -3
  pgrep -fa 'qemu-system|emulator' | cut -c1-120 | head -3 || echo "no emulator process"
  echo "--- warm-up before the tests ---"
  cat "$warmup"
  echo "host kernel, out-of-memory kills: $(sudo -n dmesg 2>/dev/null | grep -ciE 'out of memory|oom-kill|killed process')"
  echo "--- crash / ANR lines (live logcat, warnings and above) ---"
  grep -aE 'FATAL EXCEPTION|E/AndroidRuntime|Fatal signal|F/DEBUG|ANR in |am_anr|lowmemorykiller|Low on memory|OutOfMemory|WIN DEATH|Force finishing|Killing [0-9]+:com\.ttmrase|SurfaceFlinger.*(crash|abort|died)' \
    "$out/logs/warn.txt" | cut -c1-200 | tail -25
  echo "--- waits that ran out in the tests (CrownfallTest) ---"
  grep -a 'CrownfallTest' "$out/logs/warn.txt" | cut -c1-160 | tail -8
  echo "--- last lines before the stream ended ---"
  tail -n 12 "$out/logs/warn.txt" | cut -c1-200
  echo "--- app threads when it hung (ANR trace): main, then the test thread ---"
  python3 - "$out/logs/anr" <<'PY'
import glob, os, sys
files = sorted(glob.glob(os.path.join(sys.argv[1], "**", "*"), recursive=True), key=os.path.getmtime, reverse=True)
for f in files:
    if not os.path.isfile(f):
        continue
    text = open(f, errors="replace").read()
    if "com.ttmrase.crownfall" not in text:
        continue
    # アプリのメインスレッド（先頭だけ）と、テストを動かしているスレッド（"Instr: ..."）。固まったとき、
    # テストがどの待ちで止まっているかは後者に出る（ui run 145: メインスレッドは暇で、原因が読めなかった）
    for name, keep in (('"main"', 14), ('"Instr: ', 60)):
        start = text.find(name)
        if start < 0:
            continue
        block = text[start:].split("\n\n", 1)[0]
        print("\n".join(line[:200] for line in block.splitlines()[:keep]))
        print()
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
