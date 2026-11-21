#!/usr/bin/env python3
"""
Builds mod/src/main/resources/assets/notenoughflips/fairy-souls.json: every Fairy Soul's block position per SkyBlock
island (keyed by Hypixel's location mode, e.g. "hub", "combat_1").

Source: the NotEnoughUpdates item repository (MIT, https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO),
constants/fairy_souls.json.

    curl -sSfL -o /tmp/fairy_souls.json \
      https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/fairy_souls.json
    python3 tools/fairy_souls.py /tmp/fairy_souls.json
"""
import json
import pathlib
import sys

src = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "/tmp/fairy_souls.json"))
out = {}
for island, souls in src.items():
    if not isinstance(souls, list):
        continue
    pts = []
    for s in souls:
        x, y, z = (int(float(v)) for v in str(s).split(","))
        pts.append([x, y, z])
    out[island] = pts
dest = pathlib.Path(__file__).resolve().parent.parent / "mod/src/main/resources/assets/notenoughflips/fairy-souls.json"
dest.write_text(json.dumps({"source": "NotEnoughUpdates-REPO constants/fairy_souls.json (MIT)", "islands": out},
                           separators=(",", ":")))
print(dest, sum(len(v) for v in out.values()), "souls on", len(out), "islands")
