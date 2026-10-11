#!/usr/bin/env python3
"""効果音を合成して app/src/main/res/raw/sfx_*.wav に書き出す（標準ライブラリの wave / math / random のみ）。

実行: python3 tools/sfxgen/gen_sfx.py
16bit・モノラル・22050Hz。短い音だけなので合計は数百 KB。乱数は固定シード（出力は決定論的）。
音: button, coin, fanfare, charge（召喚のチャージ）, flip（カードをめくる）, rare（レア確定）, ssr（SSR）,
alarm（警報）, vip（VIP 昇格）, chest（宝箱）,
M11 追加: collect（回収）, build_start（着工）, build_done（完成）, levelup（城の昇格）, unlock（機能の解放）,
purchase（購入完了）, notify（通知）, tap（軽いタップ）
"""

import math
import pathlib
import random
import struct
import wave

RATE = 22050
OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/raw"


def silence(sec):
    return [0.0] * int(RATE * sec)


def env(n, a=0.005, d=0.1, s=0.6, r=0.1, sustain_until=None):
    """ADSR（秒）。n サンプルの長さ。"""
    out = []
    a_n, d_n, r_n = max(1, int(a * RATE)), max(1, int(d * RATE)), max(1, int(r * RATE))
    hold = n - r_n if sustain_until is None else int(sustain_until * RATE)
    for i in range(n):
        if i < a_n:
            v = i / a_n
        elif i < a_n + d_n:
            v = 1 - (1 - s) * (i - a_n) / d_n
        elif i < hold:
            v = s
        else:
            v = s * max(0.0, 1 - (i - hold) / r_n)
        out.append(v)
    return out


def tone(freq, sec, partials=((1, 1.0),), a=0.005, d=0.1, s=0.6, r=0.1, vib=0.0, vib_hz=5.5, gain=1.0):
    n = int(RATE * sec)
    e = env(n, a, d, s, r)
    out = []
    for i in range(n):
        t = i / RATE
        f = freq * (1 + vib * math.sin(2 * math.pi * vib_hz * t))
        v = 0.0
        for k, amp in partials:
            v += amp * math.sin(2 * math.pi * f * k * t)
        out.append(v * e[i] * gain)
    return out


def bell(freq, sec, gain=1.0):
    return tone(freq, sec, ((1, 1.0), (2.76, 0.45), (5.4, 0.2), (8.93, 0.08)), a=0.002, d=sec * 0.8, s=0.0, r=0.05, gain=gain)


def brass(freq, sec, gain=1.0):
    return tone(freq, sec, ((1, 1.0), (2, 0.7), (3, 0.5), (4, 0.3), (5, 0.18), (6, 0.1)), a=0.03, d=0.08, s=0.8, r=0.12, vib=0.004, gain=gain * 0.55)


def noise(sec, seed=1, gain=1.0, a=0.01, r=0.1):
    rng = random.Random(seed)
    n = int(RATE * sec)
    e = env(n, a, 0.05, 0.7, r)
    return [(rng.random() * 2 - 1) * e[i] * gain for i in range(n)]


def mix(*tracks):
    n = max(len(t) for t in tracks)
    out = [0.0] * n
    for t in tracks:
        for i, v in enumerate(t):
            out[i] += v
    return out


def at(offset_sec, track):
    return silence(offset_sec) + track


def lowpass(x, k=0.15):
    y, p = [], 0.0
    for v in x:
        p += k * (v - p)
        y.append(p)
    return y


def sweep(f0, f1, sec, gain=1.0, shape=1.6, tremolo=0.0, tremolo_hz=12.0):
    n = int(RATE * sec)
    ph = 0.0
    out = []
    for i in range(n):
        u = i / n
        f = f0 + (f1 - f0) * (u ** shape)
        ph += 2 * math.pi * f / RATE
        v = math.sin(ph) + 0.35 * math.sin(2 * ph)
        t = i / RATE
        amp = (0.25 + 0.75 * u) * (1 + tremolo * math.sin(2 * math.pi * tremolo_hz * t) * 0.5)
        out.append(v * amp * gain)
    return out


def fade_out(x, sec=0.05):
    n = int(sec * RATE)
    for i in range(min(n, len(x))):
        x[-1 - i] *= i / n
    return x


def write(name, samples, peak=0.8):
    m = max(1e-9, max(abs(v) for v in samples))
    scale = peak / m
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / ("sfx_%s.wav" % name)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v * scale)) * 32767)) for v in samples))
    print("wrote", path.name, "%.2fs" % (len(samples) / RATE))


def main():
    # ボタン: 短いクリック
    write("button", mix(tone(1180, 0.05, ((1, 1.0), (2, 0.3)), a=0.001, d=0.04, s=0.0, r=0.01), noise(0.012, 3, 0.25)), 0.55)
    # コイン: 2 音のベル
    write("coin", mix(bell(1319, 0.5), at(0.07, bell(1760, 0.6))), 0.75)
    # ファンファーレ
    notes = [(523.3, 0.16), (659.3, 0.16), (784.0, 0.16), (1046.5, 0.7)]
    t, parts = 0.0, []
    for f, d in notes:
        parts.append(at(t, brass(f, d + 0.1)))
        t += d
    chord = [brass(523.3, 0.9, 0.5), brass(659.3, 0.9, 0.5), brass(784.0, 0.9, 0.5)]
    parts += [at(t, c) for c in chord]
    write("fanfare", mix(*parts), 0.8)
    # 召喚のチャージ: 上昇音 + ノイズの高まり
    write("charge", fade_out(mix(sweep(180, 1500, 1.9, 0.9, 1.7, 0.6), noise(1.9, 5, 0.12, a=1.5, r=0.2))), 0.8)
    # カードをめくる
    write("flip", lowpass(noise(0.16, 7, 1.0, a=0.01, r=0.12), 0.5), 0.6)
    # レア確定: 昇るベルの分散和音
    seq = [784.0, 987.8, 1174.7, 1568.0, 1975.5, 2349.3]
    write("rare", mix(*[at(0.09 * i, bell(f, 0.9, 0.7)) for i, f in enumerate(seq)], at(0.5, tone(392, 1.0, ((1, 1.0), (2, 0.3)), a=0.05, d=0.3, s=0.4, r=0.5, gain=0.4))), 0.8)
    # SSR: チャージ → 衝撃 → 金のコード → きらめき
    charge = sweep(220, 1900, 1.1, 0.7, 1.4, 0.4)
    hit = mix(tone(110, 0.5, ((1, 1.0), (2, 0.5)), a=0.003, d=0.3, s=0.0, r=0.1, gain=1.0), noise(0.25, 11, 0.5, a=0.002, r=0.2))
    chord = [brass(f, 1.2, 0.5) for f in (523.3, 659.3, 784.0, 1046.5)]
    sparkle = [at(1.15 + 0.07 * i, bell(1568 * (1 + (i % 4) * 0.25), 0.7, 0.45)) for i in range(10)]
    write("ssr", mix(charge, at(1.1, hit), *[at(1.1, c) for c in chord], *sparkle), 0.85)
    # 警報: 二音のサイレン 3 周
    siren = []
    for k in range(3):
        siren += tone(660, 0.22, ((1, 1.0), (3, 0.3), (5, 0.15)), a=0.005, d=0.02, s=0.9, r=0.02)
        siren += tone(880, 0.22, ((1, 1.0), (3, 0.3), (5, 0.15)), a=0.005, d=0.02, s=0.9, r=0.02)
    write("alarm", siren, 0.7)
    # VIP: 荘厳な上昇コード + ベル
    prog = [(392.0, 523.3, 659.3), (440.0, 587.3, 740.0), (523.3, 659.3, 784.0)]
    parts = []
    for i, ch in enumerate(prog):
        parts += [at(0.45 * i, brass(f, 0.6, 0.6)) for f in ch]
    parts += [at(1.35, brass(f, 1.1, 0.7)) for f in (523.3, 659.3, 784.0, 1046.5)]
    parts += [at(1.35 + 0.08 * i, bell(1568 + 220 * i, 0.9, 0.4)) for i in range(6)]
    write("vip", mix(*parts), 0.85)
    # 宝箱: きしみ + きらめき
    creak = sweep(140, 320, 0.5, 0.5, 1.0, 0.8, 18.0)
    write("chest", fade_out(mix(creak, noise(0.5, 13, 0.08, a=0.2, r=0.2), *[at(0.5 + 0.06 * i, bell(1760 + 160 * i, 0.5, 0.5)) for i in range(6)])), 0.75)
    # --- M11 追加 ---
    # 回収: ぽんと弾ける短い泡 + 小さなベル
    write("collect", mix(sweep(380, 900, 0.09, 0.8, 0.8), at(0.06, bell(1568, 0.35, 0.6))), 0.7)
    # 着工: 金づちの 2 打
    hammer = lambda t: at(t, mix(tone(210, 0.12, ((1, 1.0), (2.3, 0.5)), a=0.001, d=0.08, s=0.0, r=0.03), noise(0.03, 17, 0.5, a=0.001, r=0.03)))
    write("build_start", mix(hammer(0.0), hammer(0.16)), 0.75)
    # 完成: 明るい 2 音のチャイム
    write("build_done", mix(bell(1046.5, 0.8, 0.8), at(0.14, bell(1568.0, 1.0, 0.8)), at(0.14, bell(2093.0, 0.8, 0.3))), 0.8)
    # 城の昇格: 小さなファンファーレ（上昇 4 音 + 和音）+ きらめき
    up = [(392.0, 0.12), (523.3, 0.12), (659.3, 0.12), (784.0, 0.12)]
    t, parts = 0.0, []
    for f, d in up:
        parts.append(at(t, brass(f, d + 0.08)))
        t += d
    parts += [at(t, brass(f, 1.0, 0.6)) for f in (523.3, 659.3, 784.0, 1046.5)]
    parts += [at(t + 0.06 * i, bell(1318.5 * (1 + 0.12 * i), 0.6, 0.35)) for i in range(7)]
    write("levelup", mix(*parts), 0.85)
    # 機能の解放: 鍵が開くような金属音 + 上昇するきらめき
    click = mix(tone(920, 0.05, ((1, 1.0), (2.4, 0.4)), a=0.001, d=0.04, s=0.0, r=0.01), noise(0.02, 19, 0.3, a=0.001, r=0.02))
    write("unlock", mix(click, at(0.1, click), *[at(0.22 + 0.07 * i, bell(1175 * (1 + i * 0.26), 0.7, 0.55)) for i in range(5)]), 0.8)
    # 購入完了: レジのチン + コイン
    write("purchase", mix(bell(2093.0, 0.9, 0.8), at(0.08, bell(2637.0, 0.9, 0.6)), at(0.25, bell(1319, 0.5, 0.5)), at(0.31, bell(1760, 0.6, 0.5))), 0.8)
    # 通知: やさしい 2 音
    write("notify", mix(bell(988, 0.5, 0.7), at(0.18, bell(1319, 0.7, 0.7))), 0.7)
    # 軽いタップ（全部のボタンに付けても邪魔にならない小さな音）
    write("tap", mix(tone(1560, 0.035, ((1, 1.0), (2, 0.2)), a=0.001, d=0.03, s=0.0, r=0.005)), 0.35)


if __name__ == "__main__":
    main()
