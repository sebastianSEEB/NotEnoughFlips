"""Walk-forward backtest of the bazaar mismatch signals (market/bzsignals.py).

    python tools/backtest_signals.py raw_inputs.json [out.json]

raw_inputs.json is what tools/bz_signals.py saves: {"series": {pid: [[ts, buy, sell], ...]}, "live": {...},
"recipes": {...}, "names": {...}}. For every 2 h bar after a 3-day warm-up the signals are recomputed from the bars up to
that moment only (no look-ahead), then each new signal is followed forward:

  buy signals (cheap, pair, follow-buy)   enter with a buy order at the signal's price (top buy order + 0.1).
      hit    = within the horizon the instant-buy price (the cheapest sell offer) reaches the target, so a sell offer
               at the target fills → exit at target × (1 − tax)
      miss   = exit at the end of the horizon by selling instantly (top buy order) × (1 − tax)
  sell signals (rich, follow-sell)         "sell what you hold now": compared with holding it to the end of the horizon
      hit    = the price fell back to the target within the horizon
      return = what selling now saved vs selling instantly at the end (positive = the signal was right)

A baseline runs the same buy rule on the same products at every bar (no signal), so the edge is signal − baseline.
A product that keeps signalling counts once until its horizon has passed. Volumes are today's (the history has prices only).
"""
from __future__ import annotations

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market import bzsignals  # noqa: E402

TAX = 0.0125
HORIZONS_BARS = {"6h": 3, "12h": 6, "24h": 12, "48h": 24}
WARMUP_BARS = 36                        # 3 days of 2 h bars before the first signal


def load(path: str) -> dict:
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    series = {p: sorted((int(r[0]), float(r[1] or 0), float(r[2] or 0)) for r in rows) for p, rows in d["series"].items()}
    return {"series": series, "live": d["live"], "recipes": d.get("recipes") or {}, "names": d.get("names") or {}}


def step_of(series: dict) -> int:
    gaps = []
    for rows in series.values():
        gaps += [b[0] - a[0] for a, b in zip(rows, rows[1:]) if b[0] > a[0]]
    return int(statistics.median(gaps)) if gaps else 7_200_000


def follow_buy(rows: list, i: int, cost: float, target: float, bars: int) -> tuple[bool, float, int]:
    """(hit, return after tax, bars held) for a buy at bar i."""
    end = min(len(rows) - 1, i + bars)
    for j in range(i + 1, end + 1):
        if rows[j][1] >= target:                                  # a sell offer at the target fills
            return True, target * (1 - TAX) / cost - 1, j - i
    return False, rows[end][2] * (1 - TAX) / cost - 1, end - i


def follow_sell(rows: list, i: int, now_sell: float, target: float, bars: int) -> tuple[bool, float, int]:
    end = min(len(rows) - 1, i + bars)
    hit = any(rows[j][2] <= target for j in range(i + 1, end + 1))
    later = rows[end][2]
    return hit, (now_sell - later) / now_sell if now_sell > 0 else 0.0, end - i


def run(data: dict) -> dict:
    series, live_now, recipes, names = data["series"], data["live"], data["recipes"], data["names"]
    recipes = {k: tuple(v) for k, v in recipes.items()}
    step = step_of(series)
    times = sorted({r[0] for rows in series.values() for r in rows})
    index = {p: {r[0]: k for k, r in enumerate(rows)} for p, rows in series.items()}
    max_h = max(HORIZONS_BARS.values())
    last_signal: dict[tuple, int] = {}
    trades = []                                                   # one row per (signal, horizon)
    base = defaultdict(list)                                      # horizon → baseline returns
    steps = 0
    for ti, t in enumerate(times):
        if ti < WARMUP_BARS or ti >= len(times) - 3:
            continue
        steps += 1
        cut = {p: [r for r in rows if r[0] <= t] for p, rows in series.items()}
        live = {}
        for p, rows in cut.items():
            if not rows or rows[-1][0] != t:
                continue
            lv = live_now.get(p) or {}
            live[p] = {"buy": rows[-1][1], "sell": rows[-1][2], "buy_week": lv.get("buy_week", 0), "sell_week": lv.get("sell_week", 0)}
        res = bzsignals.analyse(cut, live, step, recipes, names, now_ms=t + step)
        for s in res.get("signals", []):
            p, typ, act = s["id"], s["type"], s["action"]
            # "prefer" (pairs) is followed like a buy and "watch" (jumps) like a sell, so their record stays visible
            act = {"prefer": "buy", "watch": "sell"}.get(act, act)
            if act not in ("buy", "sell") or p not in index or t not in index[p] or s["price"] <= 0:
                continue
            key = (p, typ, act)
            if key in last_signal and ti - last_signal[key] < HORIZONS_BARS["24h"]:
                continue                                          # the same signal still running
            last_signal[key] = ti
            rows, i = series[p], index[p][t]
            for hname, bars in HORIZONS_BARS.items():
                if i + bars >= len(rows):
                    continue
                if act == "buy":
                    hit, ret, held = follow_buy(rows, i, s["price"], s["target"], bars)
                else:
                    hit, ret, held = follow_sell(rows, i, s["price"], s["target"], bars)
                trades.append({"t": t, "id": p, "type": typ, "action": act, "conf": s["conf"], "edge": s["edge"],
                               "z": s["z"], "h": hname, "hit": hit, "ret": ret, "held_bars": held})
        # baseline: buy every product the signals could pick (liquid) at this bar, with a typical +4 % target
        for p in bzsignals.liquid(live):
            rows, i = series[p], index[p].get(t)
            if i is None:
                continue
            if rows[i][1] <= 0 or rows[i][2] <= 0:
                continue
            cost = rows[i][2] + 0.1
            for hname, bars in HORIZONS_BARS.items():
                if i + bars < len(rows):
                    base[hname].append(follow_buy(rows, i, cost, rows[i][1] * 1.04, bars)[1])
    return {"step_ms": step, "bars": len(times), "steps": steps, "trades": trades, "baseline": base}


def trimmed(v: list, cut: float = 0.05) -> float:
    """Mean without the top and bottom 5 % (a few broken bars can't decide it)."""
    v = sorted(v)
    k = int(len(v) * cut)
    v = v[k:len(v) - k] if len(v) > 2 * k + 1 else v
    return statistics.fmean(v) if v else 0.0


def summarize(r: dict) -> dict:
    out = {"bars": r["bars"], "test_steps": r["steps"], "step_hours": r["step_ms"] / 3_600_000, "groups": []}
    groups = defaultdict(list)
    for tr in r["trades"]:
        groups[(tr["action"], tr["type"], tr["conf"], tr["h"])].append(tr)
        groups[(tr["action"], "ALL", tr["conf"], tr["h"])].append(tr)
        groups[(tr["action"], "ALL", "ALL", tr["h"])].append(tr)
    for (act, typ, conf, h), ts in sorted(groups.items()):
        rets = [x["ret"] for x in ts]
        b = r["baseline"].get(h, [])
        out["groups"].append({
            "action": act, "type": typ, "conf": conf, "horizon": h, "n": len(ts),
            "hit_rate": sum(x["hit"] for x in ts) / len(ts),
            "avg_ret": trimmed(rets), "median_ret": statistics.median(rets),
            "win_rate": sum(x > 0 for x in rets) / len(rets),
            "baseline_avg": trimmed(b) if (b and act == "buy") else None,
            "baseline_median": statistics.median(b) if (b and act == "buy") else None,
        })
    return out


def main(path: str, out: str | None = None) -> None:
    s = summarize(run(load(path)))
    print(f"{s['bars']} bars of {s['step_hours']:.0f} h · {s['test_steps']} test points (after a 3-day warm-up)\n")
    print(f"{'action':6} {'type':7} {'conf':6} {'hor':4} {'n':>4} {'hit':>6} {'win':>6} {'avg*':>8} {'median':>8} {'base*':>8} {'base med':>8}")
    for g in s["groups"]:
        if g["type"] == "ALL" or g["n"] >= 3:
            b = "" if g["baseline_avg"] is None else f"{g['baseline_avg'] * 100:+7.2f}%"
            bm = "" if g["baseline_median"] is None else f"{g['baseline_median'] * 100:+7.2f}%"
            print(f"{g['action']:6} {g['type']:7} {g['conf']:6} {g['horizon']:4} {g['n']:4d} {g['hit_rate'] * 100:5.0f}% "
                  f"{g['win_rate'] * 100:5.0f}% {g['avg_ret'] * 100:+7.2f}% {g['median_ret'] * 100:+7.2f}% {b:>8} {bm:>8}")
    print("\n* trimmed mean (top and bottom 5 % left out)")
    if out:
        Path(out).write_text(json.dumps(s, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main(*sys.argv[1:])
