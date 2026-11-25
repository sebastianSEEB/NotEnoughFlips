"""
Build the crafting-recipe table the mod ships with (mod/src/main/resources/assets/notenoughflips/recipes.json).

Source: the NotEnoughUpdates item repository (MIT, https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO). Each item
lists its crafting-table recipe(s) as a 3x3 grid {"A1": "ITEM:3", ...} (plus "count" when it makes more than one) and the
unlock as "crafttext": "Requires: Ender Pearl VIII" / "Requires: Enderman Slayer 5" / "Requires: HotM 5". The mod
prices the ingredients live and checks the unlocks against your profile.

    git clone --depth 1 https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO /tmp/neu
    python tools/recipes.py /tmp/neu
"""
import json
import pathlib
import sys

GRID = ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]


def grid(r):
    """{ingredient: total qty} for one 3x3 recipe, or None if unreadable."""
    parts = {}
    for k in GRID:
        v = str(r.get(k, "") or "")
        if not v:
            continue
        item, sep, qty = v.rpartition(":")
        if not sep:
            item, qty = v, "1"
        try:
            q = float(qty)
        except ValueError:
            return None
        if not item or q <= 0:
            return None
        parts[item] = parts.get(item, 0) + q
    return parts or None


def build(repo):
    out = {}
    for f in sorted(pathlib.Path(repo, "items").glob("*.json")):
        try:
            doc = json.loads(f.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            continue
        iid = doc.get("internalname") or f.stem
        req = (doc.get("crafttext") or "").replace("Requires:", "").strip()
        soulbound = "Soulbound" in " ".join(doc.get("lore") or [])
        recipes = []
        if isinstance(doc.get("recipe"), dict):
            recipes.append((doc["recipe"], 1))
        for r in doc.get("recipes", []) or []:
            if isinstance(r, dict) and r.get("type") == "crafting":
                recipes.append((r, r.get("count", 1)))
        for r, count in recipes:
            target = r.get("overrideOutputId") or iid
            parts = grid(r)
            if not parts or target in parts:
                continue
            try:
                count = max(1, int(count))
            except (TypeError, ValueError):
                count = 1
            entry = {"n": count, "i": sorted([k, round(v, 4)] for k, v in parts.items())}
            if req:
                entry["r"] = req
            if soulbound:
                entry["sb"] = 1                     # can't be sold: no flip
            lst = out.setdefault(target, [])
            if entry not in lst:
                lst.append(entry)
    return out


if __name__ == "__main__":
    repo = sys.argv[1] if len(sys.argv) > 1 else "/tmp/neu"
    data = build(repo)
    dest = pathlib.Path(__file__).resolve().parent.parent / "mod/src/main/resources/assets/notenoughflips/recipes.json"
    dest.write_text(json.dumps({"source": "NotEnoughUpdates-REPO items/*.json crafting recipes (MIT)", "recipes": data},
                               separators=(",", ":"), sort_keys=True))
    print(dest, len(data), "items,", sum(len(v) for v in data.values()), "recipes,", dest.stat().st_size // 1024, "KB")
