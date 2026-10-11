#!/usr/bin/env python3
"""生成した MIDI をピアノロールの PNG にする（確認用。PIL が要る。MIDI 自体の生成は標準ライブラリのみ）。

実行: python3 tools/bgmgen/preview.py [MIDI ディレクトリ] [出力 PNG]
各曲を 1 段のピアノロールにして縦に並べる（横軸は時間、縦軸は音高。トラックごとに色分け、ドラムは下の帯）。
"""
import pathlib
import struct
import sys

from PIL import Image, ImageDraw

RAW = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/raw"
COLORS = ["#ff7b7b", "#7bc4ff", "#a6e07b", "#ffd36b", "#c59bff", "#7bffe0", "#ff9bd6", "#d0d0d0"]


def read_vlq(b, i):
    n = 0
    while True:
        c = b[i]
        i += 1
        n = (n << 7) | (c & 0x7F)
        if not c & 0x80:
            return n, i


def parse(path):
    b = pathlib.Path(path).read_bytes()
    assert b[:4] == b"MThd"
    fmt, ntr, ppq = struct.unpack(">HHH", b[8:14])
    i = 14
    tracks, tempo = [], 500000
    for _ in range(ntr):
        assert b[i:i + 4] == b"MTrk"
        ln = struct.unpack(">I", b[i + 4:i + 8])[0]
        j, end, t, status = i + 8, i + 8 + ln, 0, 0
        notes, active, prog, ch = [], {}, None, 0
        while j < end:
            d, j = read_vlq(b, j)
            t += d
            if b[j] == 0xFF:
                typ = b[j + 1]
                ln2, j = read_vlq(b, j + 2)
                if typ == 0x51:
                    tempo = int.from_bytes(b[j:j + 3], "big")
                j += ln2
                continue
            if b[j] & 0x80:
                status = b[j]
                j += 1
            kind, ch = status & 0xF0, status & 0x0F
            if kind == 0x90 and b[j + 1] > 0:
                active[(ch, b[j])] = t
                j += 2
            elif kind in (0x80, 0x90):
                s = active.pop((ch, b[j]), None)
                if s is not None:
                    notes.append((s, t, b[j], ch))
                j += 2
            elif kind == 0xC0:
                prog = b[j]
                j += 1
            else:
                j += 2
        tracks.append((notes, prog, ch))
        i = end
    return ppq, tempo, tracks


def main():
    src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else RAW
    out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else pathlib.Path("/tmp/bgm_preview.png")
    files = sorted(src.glob("bgm_*.mid"))
    W, H = 1400, 190
    img = Image.new("RGB", (W, H * len(files)), "#1b1b22")
    d = ImageDraw.Draw(img)
    for n, f in enumerate(files):
        ppq, tempo, tracks = parse(f)
        total = max((nt[1] for tr in tracks for nt in tr[0]), default=1)
        y0 = n * H
        d.text((8, y0 + 4), "%s  %.0f s  %.0f bpm" % (f.stem, total / ppq * tempo / 1e6, 60e6 / tempo), fill="#ffffff")
        for k, (notes, prog, ch) in enumerate(tracks):
            for s, e, p, c in notes:
                x0, x1 = 8 + s / total * (W - 16), 8 + e / total * (W - 16)
                if c == 9:
                    y = y0 + H - 14 - (p % 12) * 2
                    d.rectangle([x0, y, max(x1, x0 + 1), y + 1], fill="#888888")
                else:
                    y = y0 + H - 36 - (p - 24) * 1.4
                    d.rectangle([x0, y, max(x1, x0 + 1.5), y + 1.5], fill=COLORS[k % len(COLORS)])
    img.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
