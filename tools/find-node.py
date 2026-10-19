#!/usr/bin/env python3
"""Finds nodes in a `uiautomator dump` XML for device-test.sh. Never taps anything itself.

  find-node.py <dump.xml> <mode> [kind value]
  modes:
    list                    every node with text or desc: text | desc | bounds
    find <kind> <value>     prints "cx cy" of the one match (exit 3 none, 4 several)
    has  <kind> <value>     exit 0 if any match, else 3
  kinds: text, desc, textprefix, label (text or desc), labelprefix.
  Several matches: text boxes are dropped, then the clickable one if exactly one is, else the topmost with --first.
"""
import re
import sys
import xml.etree.ElementTree as ET

args = [a for a in sys.argv[1:] if a != "--first"]
first = "--first" in sys.argv
path, mode, rest = args[0], args[1], args[2:]
nodes = []
for n in ET.parse(path).iter("node"):
    b = [int(x) for x in re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))]
    nodes.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "b": b,
                  "clickable": n.get("clickable") == "true", "edit": n.get("class", "").endswith("EditText")})


def match(n, kind, v):
    t, d = n["text"], n["desc"]
    return {"text": t == v, "desc": d == v, "textprefix": t.startswith(v),
            "label": v in (t, d), "labelprefix": t.startswith(v) or d.startswith(v)}[kind]


if mode == "list":
    for n in nodes:
        if n["text"] or n["desc"]:
            print(f'{n["text"]} | {n["desc"]} | {n["b"]}')
elif mode in ("find", "has"):
    found = [n for n in nodes if match(n, rest[0], rest[1])]
    if mode == "has":
        sys.exit(0 if found else 3)
    if not found:
        sys.exit(3)
    if len(found) > 1:
        # A search box echoes what was typed into it; the thing to tap is the other match.
        found = [n for n in found if not n["edit"]] or found
    if len(found) > 1:
        click = [n for n in found if n["clickable"]]
        if len(click) == 1:
            found = click
        elif first:
            found.sort(key=lambda n: n["b"][1])
        else:
            print(f"{len(found)} matches for {rest[0]}={rest[1]}", file=sys.stderr)
            sys.exit(4)
    l, t, r, b = found[0]["b"]
    print((l + r) // 2, (t + b) // 2)
else:
    sys.exit(2)
