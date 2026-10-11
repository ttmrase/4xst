#!/usr/bin/env python3
"""BGM を Standard MIDI File（Format 1・GM 音色）として生成し、app/src/main/res/raw/bgm_*.mid に書き出す。
標準ライブラリのみ。出力は決定論的（固定シード）。Android 標準の MediaPlayer で再生できる（APK への増加は数 KB）。

実行: python3 tools/bgmgen/gen_bgm.py [出力ディレクトリ]     曲の一覧: python3 tools/bgmgen/gen_bgm.py --list

曲（60〜90 秒・ループ可能。小節の頭の和音で始まり、進行を 1 周して終わる）:
  title（タイトル）/ city（城内）/ map（ワールドマップ）/ battle（戦闘）/ shop（ショップ）/ summon（召喚）
作り方: 和音進行（ローマ数字）→ ベース・コード・アルペジオ → 旋律（和音の構成音を強拍に置き、次の拍へ音階で歩く）→ ドラム。
旋律はフレーズ（4 小節）を A A' B A'' のように繰り返し、同じ音型が戻るようにする。
"""
import pathlib
import random
import struct
import sys

PPQ = 480
OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/raw"

MAJOR = [0, 2, 4, 5, 7, 9, 11]
MINOR = [0, 2, 3, 5, 7, 8, 10]
DORIAN = [0, 2, 3, 5, 7, 9, 10]
LYDIAN = [0, 2, 4, 6, 7, 9, 11]
PENT = [0, 2, 4, 7, 9]

# GM 音色番号（0 始まり）
PROG = {
    "harp": 46, "strings": 48, "pizz": 45, "horn": 60, "brass": 61, "flute": 73, "oboe": 68, "timpani": 47,
    "guitar": 24, "vibes": 11, "marimba": 12, "bass": 32, "choir": 52, "celesta": 8, "pad": 89, "piano": 0,
    "fiddle": 110, "clarinet": 71, "synbass": 38, "glock": 9, "cello": 42,
}
# ドラム（GM ch.10）
KICK, SNARE, HAT, OPEN_HAT, TOM, CRASH, SHAKER, RIDE, STICK = 36, 38, 42, 46, 45, 49, 70, 51, 37


class Track:
    def __init__(self, name, channel, program=None):
        self.name, self.channel, self.program = name, channel, program
        self.events = []  # (tick, order, bytes)

    def note(self, tick, dur, pitch, vel=80):
        pitch = max(0, min(127, pitch))
        self.events.append((tick, 1, bytes([0x90 | self.channel, pitch, max(1, min(127, vel))])))
        self.events.append((tick + max(1, dur), 0, bytes([0x80 | self.channel, pitch, 0])))

    def render(self, extra=b""):
        data = bytearray()
        data += b"\x00\xff\x03" + bytes([len(self.name)]) + self.name.encode("ascii")
        data += extra
        if self.program is not None:
            data += b"\x00" + bytes([0xC0 | self.channel, self.program])
        last = 0
        for tick, _, ev in sorted(self.events, key=lambda e: (e[0], e[1])):
            data += vlq(tick - last) + ev
            last = tick
        return bytes(data)


def vlq(n):
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    return bytes(reversed(out))


def chunk(tag, body):
    return tag + struct.pack(">I", len(body)) + body


def write_midi(path, tracks, bpm, total_ticks):
    tempo = struct.pack(">I", int(60_000_000 / bpm))[1:]
    meta = b"\x00\xff\x51\x03" + tempo + b"\x00\xff\x58\x04\x04\x02\x18\x08"
    chunks = [chunk(b"MThd", struct.pack(">HHH", 1, len(tracks) + 1, PPQ))]
    conductor = bytearray(meta)
    conductor += vlq(total_ticks) + b"\xff\x2f\x00"
    chunks.append(chunk(b"MTrk", bytes(conductor)))
    for t in tracks:
        end = max([e[0] for e in t.events] + [total_ticks])
        body = t.render()
        # 末尾はループ長ちょうどで終える（最後の発音の余韻を切らない）
        last = max([e[0] for e in t.events] + [0])
        body += vlq(max(0, end - last)) + b"\xff\x2f\x00"
        chunks.append(chunk(b"MTrk", body))
    pathlib.Path(path).write_bytes(b"".join(chunks))


# ───────── 作曲部品 ─────────
def degree_pitch(root, scale, degree, octave=0):
    """音階の度数（0 始まり、負も可）→ MIDI 音高。"""
    n = len(scale)
    return root + scale[degree % n] + 12 * (degree // n + octave)


def chord_tones(root, scale, degree, seventh=False):
    tones = [degree, degree + 2, degree + 4] + ([degree + 6] if seventh else [])
    return [degree_pitch(root, scale, d) for d in tones]


class Song:
    def __init__(self, name, bpm, root, scale, progression, bars, seed, program):
        self.name, self.bpm, self.root, self.scale = name, bpm, root, scale
        self.prog, self.bars = progression, bars
        self.rng = random.Random(seed)
        self.program = program
        self.bar_ticks = PPQ * 4
        self.tracks = {}

    def track(self, name, channel, program=None):
        t = Track(name, channel, program)
        self.tracks[name] = t
        return t

    def degree_at(self, bar):
        return self.prog[bar % len(self.prog)]

    # 旋律: 4 小節フレーズ（和音は進行の base 小節目から数える）
    def phrase(self, rhythm_pool, rng, base, span=(0, 9)):
        bars = []
        for b in range(4):
            deg = self.degree_at(base + b)
            beats = rng.choice(rhythm_pool)
            notes = []
            cur = deg + 2 + rng.choice([0, 2, 4])
            for i, (pos, dur) in enumerate(beats):
                strong = pos % 2 == 0 or i == 0
                if strong:
                    cur = rng.choice([deg, deg + 2, deg + 4, deg + 7])
                else:
                    cur += rng.choice([-1, 1, 1, -2, 2])
                cur = max(span[0], min(span[1], cur))
                notes.append((pos, dur, cur))
            bars.append(notes)
        # 最後の小節は和音の構成音で終えて、次のフレーズへ戻りやすくする
        pos, dur, _ = bars[-1][-1]
        bars[-1][-1] = (pos, dur, self.degree_at(base + 3) + rng.choice([0, 2, 4]))
        return bars

    def melody(self, track, rhythm_pool, octave=1, vel=84, start_bar=0, rest_every=0, hold=1.0):
        rng = random.Random(self.rng.random())
        half = max(1, len(self.prog) // 4)  # 進行 8 小節 = 4 小節のフレーズ 2 つ
        a = [self.phrase(rhythm_pool, rng, 4 * h) for h in range(half)]
        b = [self.phrase(rhythm_pool, rng, 4 * h) for h in range(half)]
        for bar in range(start_bar, self.bars):
            block = bar // 4
            if rest_every and (block // 2) % rest_every == rest_every - 1 and bar % 4 >= 2:
                continue
            side = a if (block // half) % 2 == 0 else b
            phr = side[block % half][bar % 4]
            for pos, dur, deg in phr:
                t = bar * self.bar_ticks + int(pos * PPQ / 2)
                track.note(t, int(dur * PPQ / 2 * hold), degree_pitch(self.root, self.scale, deg, octave), vel)

    def bass(self, track, pattern="root5", octave=-2, vel=84):
        for bar in range(self.bars):
            deg = self.degree_at(bar)
            r = degree_pitch(self.root, self.scale, deg, octave)
            f = degree_pitch(self.root, self.scale, deg + 4, octave)
            base = bar * self.bar_ticks
            if pattern == "root5":
                for i, p in enumerate([r, f, r, f]):
                    track.note(base + i * PPQ, int(PPQ * 0.9), p, vel)
            elif pattern == "walk":
                third = degree_pitch(self.root, self.scale, deg + 2, octave)
                for i, p in enumerate([r, third, f, degree_pitch(self.root, self.scale, deg + 5, octave)]):
                    track.note(base + i * PPQ, int(PPQ * 0.85), p, vel)
            elif pattern == "drive":
                for i in range(8):
                    track.note(base + i * PPQ // 2, int(PPQ * 0.45), r if i % 4 != 3 else f, vel)
            elif pattern == "whole":
                track.note(base, self.bar_ticks - 10, r, vel)
            elif pattern == "half":
                track.note(base, PPQ * 2 - 10, r, vel)
                track.note(base + PPQ * 2, PPQ * 2 - 10, f, vel)

    def pad(self, track, octave=0, vel=60, seventh=False):
        for bar in range(self.bars):
            tones = chord_tones(self.root, self.scale, self.degree_at(bar), seventh)
            for p in tones:
                track.note(bar * self.bar_ticks, self.bar_ticks - 20, p + 12 * octave, vel)

    def arp(self, track, octave=0, vel=62, steps=8, pattern=(0, 1, 2, 1)):
        for bar in range(self.bars):
            tones = chord_tones(self.root, self.scale, self.degree_at(bar))
            tones = tones + [tones[0] + 12]
            for i in range(steps):
                p = tones[pattern[i % len(pattern)] % len(tones)] + 12 * octave
                track.note(bar * self.bar_ticks + i * (self.bar_ticks // steps), int(self.bar_ticks / steps * 0.9), p, vel)

    def drums(self, track, style):
        for bar in range(self.bars):
            base = bar * self.bar_ticks
            last = bar % 4 == 3
            if style == "march":
                for i in range(4):
                    track.note(base + i * PPQ, 60, KICK if i % 2 == 0 else SNARE, 80 if i % 2 == 0 else 70)
                for i in range(8):
                    track.note(base + i * PPQ // 2, 40, HAT, 46)
            elif style == "soft":
                track.note(base, 60, KICK, 62)
                track.note(base + 2 * PPQ, 60, KICK, 56)
                for i in range(4):
                    track.note(base + i * PPQ + PPQ // 2, 40, SHAKER, 40)
            elif style == "battle":
                for i in range(8):
                    track.note(base + i * PPQ // 2, 40, HAT, 70 if i % 2 == 0 else 52)
                for i in (0, 3, 4, 7):
                    track.note(base + i * PPQ // 2, 60, KICK, 104)
                for i in (2, 6):
                    track.note(base + i * PPQ // 2, 60, SNARE, 100)
                if last:
                    for k in range(4):
                        track.note(base + 3 * PPQ + k * PPQ // 4, 40, TOM + (k % 2) * 3, 92)
                if bar % 8 == 0:
                    track.note(base, 200, CRASH, 90)
            elif style == "swing":
                for i in range(4):
                    track.note(base + i * PPQ, 40, RIDE, 54)
                    track.note(base + i * PPQ + PPQ * 2 // 3, 40, RIDE, 40)
                track.note(base, 60, KICK, 70)
                track.note(base + 2 * PPQ, 60, KICK, 60)
                track.note(base + PPQ, 60, STICK, 60)
                track.note(base + 3 * PPQ, 60, STICK, 60)
            elif style == "tim":
                track.note(base, 120, TOM, 70)
                if bar % 2 == 1:
                    track.note(base + 2 * PPQ, 120, TOM, 60)
            elif style == "none":
                pass


def make_title():
    s = Song("title", 84, 60, MAJOR, [0, 5, 3, 4, 0, 5, 1, 4], 24, 11, "title")
    s.melody(s.track("melody", 0, PROG["horn"]), [[(0, 3), (3, 1), (4, 2), (6, 2)], [(0, 2), (2, 2), (4, 4)],
             [(0, 4), (4, 2), (6, 2)]], octave=1, vel=88, rest_every=2)
    s.melody(s.track("counter", 1, PROG["flute"]), [[(0, 1), (1, 1), (2, 1), (3, 1), (4, 4)], [(0, 2), (2, 2), (4, 2), (6, 2)]],
             octave=2, vel=66, start_bar=8)
    s.pad(s.track("strings", 2, PROG["strings"]), octave=0, vel=58)
    s.arp(s.track("harp", 3, PROG["harp"]), octave=1, vel=58)
    s.bass(s.track("bass", 4, PROG["cello"]), "half", octave=-2, vel=78)
    s.drums(s.track("drums", 9), "tim")
    return s


def make_city():
    s = Song("city", 96, 62, PENT, [0, 3, 1, 2, 0, 3, 2, 1], 32, 23, "city")
    s.melody(s.track("melody", 0, PROG["flute"]), [[(0, 2), (2, 1), (3, 1), (4, 4)], [(0, 1), (1, 1), (2, 2), (4, 2), (6, 2)],
             [(0, 3), (3, 1), (4, 2), (6, 2)]], octave=1, vel=80, rest_every=3)
    s.arp(s.track("guitar", 1, PROG["guitar"]), octave=0, vel=64, steps=8)
    s.pad(s.track("pad", 2, PROG["strings"]), octave=0, vel=44)
    s.bass(s.track("bass", 3, PROG["bass"]), "root5", octave=-2, vel=72)
    s.melody(s.track("glock", 4, PROG["glock"]), [[(0, 1), (4, 1)], [(2, 1), (6, 1)]], octave=2, vel=50, start_bar=12)
    s.drums(s.track("drums", 9), "soft")
    return s


def make_map():
    s = Song("map", 100, 57, DORIAN, [0, 0, 6, 3, 0, 0, 6, 4], 32, 31, "map")
    s.melody(s.track("melody", 0, PROG["horn"]), [[(0, 2), (2, 2), (4, 2), (6, 1), (7, 1)], [(0, 4), (4, 2), (6, 2)],
             [(0, 1), (1, 1), (2, 2), (4, 4)]], octave=1, vel=86, rest_every=3)
    s.melody(s.track("fiddle", 1, PROG["fiddle"]), [[(0, 1), (1, 1), (2, 1), (3, 1), (4, 1), (5, 1), (6, 2)]], octave=2, vel=60,
             start_bar=8, rest_every=2)
    s.pad(s.track("strings", 2, PROG["strings"]), octave=0, vel=56)
    s.bass(s.track("bass", 3, PROG["bass"]), "drive", octave=-2, vel=76)
    s.arp(s.track("harp", 4, PROG["harp"]), octave=1, vel=50, steps=4, pattern=(0, 2, 1, 2))
    s.drums(s.track("drums", 9), "march")
    return s


def make_battle():
    s = Song("battle", 150, 52, MINOR, [0, 0, 5, 6, 0, 0, 3, 4], 40, 47, "battle")
    s.melody(s.track("melody", 0, PROG["brass"]), [[(0, 1), (1, 1), (2, 1), (3, 1), (4, 2), (6, 2)], [(0, 2), (2, 1), (3, 1), (4, 4)],
             [(0, 1), (2, 1), (3, 1), (4, 1), (6, 1), (7, 1)]], octave=1, vel=98, hold=0.8)
    s.melody(s.track("strings_hi", 1, PROG["strings"]), [[(0, 1), (1, 1), (2, 1), (3, 1), (4, 1), (5, 1), (6, 1), (7, 1)]], octave=2,
             vel=70, start_bar=8)
    s.bass(s.track("bass", 2, PROG["synbass"]), "drive", octave=-2, vel=90)
    s.pad(s.track("pad", 3, PROG["pizz"]), octave=0, vel=70)
    s.arp(s.track("pizz", 4, PROG["pizz"]), octave=1, vel=60, steps=8, pattern=(0, 2, 1, 2))
    s.drums(s.track("drums", 9), "battle")
    return s


def make_shop():
    s = Song("shop", 112, 60, MAJOR, [1, 4, 0, 5, 1, 4, 0, 0], 32, 59, "shop")
    s.melody(s.track("melody", 0, PROG["vibes"]), [[(0, 1), (1, 1), (2, 2), (4, 1), (5, 1), (6, 2)], [(0, 2), (2, 1), (3, 1), (4, 2), (6, 2)],
             [(0, 1), (2, 1), (3, 2), (5, 1), (6, 2)]], octave=1, vel=82, rest_every=3)
    s.melody(s.track("clarinet", 1, PROG["clarinet"]), [[(0, 4), (4, 2), (6, 2)]], octave=1, vel=52, start_bar=8, rest_every=2)
    s.arp(s.track("marimba", 2, PROG["marimba"]), octave=0, vel=58, steps=8, pattern=(0, 1, 2, 1))
    s.bass(s.track("bass", 3, PROG["bass"]), "walk", octave=-2, vel=76)
    s.pad(s.track("piano", 4, PROG["piano"]), octave=0, vel=40, seventh=True)
    s.drums(s.track("drums", 9), "swing")
    return s


def make_summon():
    s = Song("summon", 72, 57, LYDIAN, [0, 4, 5, 3, 0, 4, 1, 3], 24, 71, "summon")
    s.melody(s.track("melody", 0, PROG["choir"]), [[(0, 4), (4, 4)], [(0, 3), (3, 1), (4, 4)], [(0, 2), (2, 2), (4, 4)]], octave=1, vel=70,
             rest_every=2, hold=1.0)
    s.pad(s.track("pad", 1, PROG["pad"]), octave=0, vel=52)
    s.arp(s.track("celesta", 2, PROG["celesta"]), octave=2, vel=52, steps=8, pattern=(0, 2, 1, 3))
    s.arp(s.track("harp", 3, PROG["harp"]), octave=0, vel=50, steps=4, pattern=(0, 1, 2, 1))
    s.bass(s.track("bass", 4, PROG["cello"]), "whole", octave=-2, vel=70)
    s.drums(s.track("drums", 9), "tim")
    return s


SONGS = [make_title, make_city, make_map, make_battle, make_shop, make_summon]


def build(song):
    tracks = list(song.tracks.values())
    total = song.bars * song.bar_ticks
    return tracks, total


def info(song):
    tracks, total = build(song)
    secs = total / PPQ * 60 / song.bpm
    insts = [t.name + ("(drums)" if t.channel == 9 else "") for t in tracks]
    prog_names = {v: k for k, v in PROG.items()}
    insts = [prog_names.get(t.program, "drums") if t.channel != 9 else "drums" for t in tracks]
    return secs, insts


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    out = pathlib.Path(args[0]) if args else OUT
    listing = "--list" in sys.argv
    if not listing:
        out.mkdir(parents=True, exist_ok=True)
    for make in SONGS:
        song = make()
        tracks, total = build(song)
        secs, insts = info(song)
        if not listing:
            write_midi(out / ("bgm_%s.mid" % song.name), tracks, song.bpm, total)
        print("%-7s %5.1f s  %3d bpm  %2d bars  %s" % (song.name, secs, song.bpm, song.bars, ", ".join(insts)))


if __name__ == "__main__":
    main()
