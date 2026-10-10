#!/usr/bin/env python3
"""ja(既定) と en の strings.xml のキーとプレースホルダが一致しているかを検査する。違いがあれば 1 を返す。"""
import re
import sys
import xml.etree.ElementTree as ET

BASE = "app/src/main/res"


def load(path):
    out = {}
    for el in ET.parse(path).getroot():
        name = el.get("name")
        if el.tag == "string":
            out[name] = "".join(el.itertext())
        elif el.tag == "plurals":
            out[name] = "|".join("".join(i.itertext()) for i in el)
    return out


def placeholders(s):
    return sorted(re.findall(r"%\d+\$[sd]|%[sd]", s))


def main():
    ja = load(f"{BASE}/values/strings.xml")
    en = load(f"{BASE}/values-en/strings.xml")
    bad = 0
    for k in sorted(ja.keys() - en.keys()):
        print(f"missing in en: {k}")
        bad += 1
    for k in sorted(en.keys() - ja.keys()):
        print(f"missing in ja: {k}")
        bad += 1
    for k in sorted(ja.keys() & en.keys()):
        if placeholders(ja[k]) != placeholders(en[k]):
            print(f"placeholder mismatch: {k}: {placeholders(ja[k])} vs {placeholders(en[k])}")
            bad += 1
    print(f"checked {len(ja)} ja / {len(en)} en keys, {bad} problem(s)")
    return 1 if bad else 0


sys.exit(main())
