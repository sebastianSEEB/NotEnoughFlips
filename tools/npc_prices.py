"""
Build the NPC shop price table the mod ships with (mod/src/main/resources/assets/notenoughflips/npc-prices.json).

Source: the NotEnoughUpdates item repository (MIT, https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO), whose
NPC items list their shop trades as {"type": "npc_shop", "cost": ["SKYBLOCK_COIN:8.0"], "result": "BONE:1"}.
Every trade is kept as coins + ingredients per item; the mod prices the ingredients live (bazaar / lowest BIN) and
uses the cheapest way, so nobody should pay more than that on the auction house.

    git clone --depth 1 https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO /tmp/neu
    python tools/npc_prices.py /tmp/neu
"""
import json
import pathlib
import sys
import time


def parse(entry):
    """"ITEM:3" -> ("ITEM", 3.0); "ITEM" -> ("ITEM", 1.0); anything unreadable -> ("", 0)."""
    item, sep, qty = entry.rpartition(":")
    if not sep:
        return entry, 1.0
    try:
        return item, float(qty)
    except ValueError:
        return "", 0.0


def build(repo):
    """{item id: [[coins per item, [[ingredient, qty per item], ...]], ...]}: every NPC shop way to get one item."""
    shops = {}
    for f in sorted(pathlib.Path(repo, "items").glob("*.json")):
        try:
            doc = json.loads(f.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            continue
        for r in doc.get("recipes", []) or []:
            if not isinstance(r, dict) or r.get("type") != "npc_shop":
                continue
            item, qty = parse(str(r.get("result", "")))
            if not item or qty <= 0:
                continue
            coins, parts, ok = 0.0, [], True
            for c in r.get("cost") or []:
                cid, cq = parse(str(c))
                if not cid or cq <= 0:
                    ok = False
                    break
                if cid == "SKYBLOCK_COIN":
                    coins += cq
                elif cid.startswith("SKYBLOCK_"):         # bits, copper, motes, pelts…: not bought with coins
                    ok = False
                    break
                else:
                    parts.append([cid, round(cq / qty, 4)])
            if not ok or (coins <= 0 and not parts):
                continue
            opt = [round(coins / qty, 2), sorted(parts)]
            if opt not in shops.setdefault(item, []):
                shops[item].append(opt)
    return shops


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "/tmp/neu"
    prices = build(repo)
    out = pathlib.Path(__file__).resolve().parent.parent / "mod/src/main/resources/assets/notenoughflips/npc-prices.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"generated": int(time.time() * 1000), "source": "NotEnoughUpdates-REPO (MIT)",
                               "shops": dict(sorted(prices.items()))}, separators=(",", ":")), encoding="utf-8")
    print(f"{len(prices)} items -> {out}")


if __name__ == "__main__":
    main()
