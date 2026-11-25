"""Seasonality of bazaar prices over the SkyBlock year, for the Not Enough Flips data site (GitHub Actions).

    python tools/seasonality.py SITE_DIR [backfill_requests]

The SkyBlock calendar repeats every 124 real hours (one SkyBlock year): Spooky Festival, Season of Jerry, New Year,
the Traveling Zoo and the mayor election all come back every ~5 days. So a few months of prices hold dozens of
"years", enough to tell a real yearly pattern from noise.

Each run:
  1. adds this hour's bazaar prices to a long history at 2-hour resolution (data/_hist/season.json.gz, ~200 days);
  2. backfills missing older weeks from SkyCofl's public bazaar history (2-hour points per 7-day window), a limited
     number of requests per run so it stays well under their rate limit, most traded products first;
  3. for every product with enough years: maps each price onto the SkyBlock calendar, removes the slow trend (each
     year's own median), and measures the typical shape of a year (median per 2-hour slot), how strong it is against
     the noise, and the best buy slot → sell slot with how often that trade actually paid after tax and spread;
  4. writes data/seasonality.json (every analysed product, best first) and data/season/<ID>.json (each year's prices
     slot by slot, for the in-game chart that overlays every year).
"""
from __future__ import annotations

import gzip
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

EPOCH_S = 1_560_275_700                 # SkyBlock year 1, Early Spring 1st
YEAR_S = 124 * 3600                     # one SkyBlock year = 124 real hours
SLOT_S = 2 * 3600                       # 2-hour slots
BINS = YEAR_S // SLOT_S                 # 62 slots per SkyBlock year
KEEP_SLOTS = 200 * 24 // 2              # ~200 days
BZ_TAX = 0.0125
MIN_YEARS = 6
# A pattern counts as tradeable only when it holds up out of sample AND stands out from the noise. In tests on random
# prices this let 0 of 40 series through, while catching 85 % of 8 % yearly swings and all 12 % ones.
MIN_GAIN, MIN_WIN, MIN_SNR = 0.03, 0.70, 2.5


def tradeable(a: dict) -> bool:
    b = a.get("best")
    return bool(b) and b["gain"] >= MIN_GAIN and b["win"] >= MIN_WIN and a["snr"] >= MIN_SNR
MONTHS = ["Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
          "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"]
# (name, month index, first day, last day) on the SkyBlock calendar
EVENTS = [("Spooky Festival", 7, 29, 31), ("Season of Jerry", 11, 24, 26), ("New Year", 11, 29, 31),
          ("Traveling Zoo", 3, 1, 3), ("Traveling Zoo", 9, 1, 3), ("Election results", 2, 27, 27)]
SEASONAL = ["GREEN_CANDY", "PURPLE_CANDY", "ECTOPLASM", "PUMPKIN_GUTS", "SPOOKY_SHARD", "WEREWOLF_SKIN", "SOUL_FRAGMENT",
            "WHITE_GIFT", "GREEN_GIFT", "RED_GIFT", "GLACIAL_FRAGMENT", "ENCHANTED_ICE", "ENCHANTED_PACKED_ICE",
            "ENCHANTED_SNOW_BLOCK", "BOOSTER_COOKIE", "RECOMBOBULATOR_3000"]


# ---- SkyBlock calendar ----

def slot_of(ts_s: float) -> int:
    """2-hour slot number counted from the SkyBlock epoch, so slot % 62 is the slot within the SkyBlock year."""
    return int((ts_s - EPOCH_S) // SLOT_S)


def slot_ts(slot: int) -> float:
    """Real time (s) of the middle of a slot."""
    return EPOCH_S + slot * SLOT_S + SLOT_S / 2


def year_of(ts_s: float) -> int:
    return int((ts_s - EPOCH_S) // YEAR_S)


def bin_of(ts_s: float) -> int:
    """Slot within the SkyBlock year, 0..61."""
    return int(((ts_s - EPOCH_S) % YEAR_S) // SLOT_S)


def sb_date(ts_s: float) -> tuple[int, int, int]:
    """(year, month index 0..11, day 1..31) on the SkyBlock calendar."""
    into = (ts_s - EPOCH_S) % YEAR_S
    day_total = int(into // 1200)            # a SkyBlock day is 20 real minutes
    return year_of(ts_s) + 1, day_total // 31, day_total % 31 + 1


def bin_label(b: int) -> str:
    """'Autumn 29' for the start of slot b."""
    _, m, d = sb_date(EPOCH_S + b * SLOT_S)
    return f"{MONTHS[m]} {d}"


def events_in(b: int) -> list[str]:
    """Events overlapping slot b (each slot is 6 SkyBlock days)."""
    start = b * SLOT_S
    out = []
    for name, m, d0, d1 in EVENTS:
        es = (m * 31 + d0 - 1) * 1200
        ee = (m * 31 + d1) * 1200
        if es < start + SLOT_S and ee > start and name not in out:
            out.append(name)
    return out


# ---- the long history ----

def load_store(path: Path) -> dict:
    try:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            s = json.load(f)
        if isinstance(s.get("items"), dict):
            s.setdefault("vol", {})
            s.setdefault("done", {})
            return s
    except (OSError, ValueError):
        pass
    return {"items": {}, "vol": {}, "done": {}}


def merge(store: dict, pid: str, rows: list) -> int:
    """Add [slot, buy, sell] rows (buy ≥ sell); an existing slot keeps its first value. Returns rows added."""
    have = {r[0]: r for r in store["items"].get(pid, [])}
    n = 0
    for r in rows:
        if r[0] not in have and (r[1] > 0 or r[2] > 0):
            have[r[0]] = r
            n += 1
    store["items"][pid] = sorted(have.values())
    return n


def trim(store: dict, now_s: float) -> None:
    cut = slot_of(now_s) - KEEP_SLOTS
    for pid in list(store["items"]):
        rows = [r for r in store["items"][pid] if r[0] > cut]
        if rows:
            store["items"][pid] = rows
        else:
            del store["items"][pid]


def add_snapshot(store: dict, bazaar: dict, now_s: float) -> int:
    s = slot_of(now_s)
    n = 0
    for pid, p in (bazaar.get("products") or {}).items():
        q = p.get("quick_status") or {}
        buy, sell = float(q.get("buyPrice") or 0), float(q.get("sellPrice") or 0)
        if buy <= 0 and sell <= 0:
            continue
        hi, lo = max(buy, sell), min(buy, sell)
        n += merge(store, pid, [[s, rnd(hi), rnd(lo)]])
        store["vol"][pid] = int(q.get("buyMovingWeek") or 0) + int(q.get("sellMovingWeek") or 0)
    return n


def rnd(x: float) -> float:
    return float(f"{x:.5g}") if abs(x) < 100_000 else round(x)


# ---- backfill from SkyCofl ----

def cofl_week(pid: str, end_s: int, timeout: float = 20) -> list | None:
    """2-hour points for the 7 days before end_s: [[slot, hi, lo]], [] when none, None on error."""
    start = datetime.fromtimestamp(end_s - 7 * 86400, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    end = datetime.fromtimestamp(end_s, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    url = f"https://sky.coflnet.com/api/bazaar/{pid}/history?start={start}&end={end}"
    req = urllib.request.Request(url, headers={"User-Agent": "NotEnoughFlips data (seasonality backfill)"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 429:
            raise
        return [] if e.code in (400, 404) else None
    except (OSError, ValueError):
        return None
    rows = []
    for p in data or []:
        try:
            ts = datetime.fromisoformat(str(p["timestamp"]).replace("Z", "+00:00"))
            if ts.tzinfo is None:
                ts = ts.replace(tzinfo=timezone.utc)
            b, s = float(p.get("buy") or 0), float(p.get("sell") or 0)
        except (KeyError, ValueError, TypeError):
            continue
        if b <= 0 and s <= 0:
            continue
        rows.append([slot_of(ts.timestamp()), rnd(max(b, s)), rnd(min(b, s) if min(b, s) > 0 else max(b, s))])
    return rows


def backfill(store: dict, now_s: float, budget: int, weeks: int = 26, top: int = 300, pause: float = 0.75,
             minutes: float = 30) -> int:
    """Fetch missing weeks (most traded products first, newest weeks first) until budget requests are used or
    `minutes` of wall time pass, so the run always gets to save what it fetched before the job's time limit."""
    if budget <= 0:
        return 0
    stop = time.monotonic() + minutes * 60
    products = [p for p in SEASONAL if p in store["vol"]]
    products += [p for p in sorted(store["vol"], key=lambda p: -store["vol"][p]) if p not in products][:top]
    week = 7 * 86400
    grid = int(now_s // week * week)                  # windows on a fixed weekly grid: a finished week is fetched once
    day = int(now_s // 86400)
    cut = grid - (weeks + 1) * week
    store["done"] = {k: v for k, v in store["done"].items() if int(k.split("@")[1]) >= cut}
    used = 0
    for w in range(0, weeks + 1):
        end = grid + week - w * week                   # w = 0: this unfinished week, refreshed once a day
        for pid in products:
            key = f"{pid}@{end}" if end <= now_s else f"{pid}@{end}@{day}"
            if key in store["done"]:
                continue
            if used >= budget or time.monotonic() >= stop:
                return used
            try:
                rows = cofl_week(pid, end)
            except urllib.error.HTTPError:
                time.sleep(60)                         # rate limited: back off and stop for this run
                return used
            used += 1
            if used % 250 == 0:
                print(f"seasonality: backfill {used} requests so far", flush=True)
            if rows is not None:
                merge(store, pid, rows)
                store["done"][key] = 1
            time.sleep(pause)
    return used


# ---- analysis ----

def analyse(rows: list) -> dict | None:
    """Seasonal profile of one product from [slot, buy, sell] rows, or None without enough years."""
    pts = []
    for s, hi, lo in rows:
        mid = (hi + lo) / 2 if hi > 0 and lo > 0 else max(hi, lo)
        if mid > 0:
            pts.append((slot_ts(s), mid, (hi - lo) / mid if hi > 0 and lo > 0 else 0.0))
    if len(pts) < MIN_YEARS * BINS * 0.5:
        return None
    by_year: dict[int, list] = {}
    for ts, mid, sp in pts:
        by_year.setdefault(year_of(ts), []).append((ts, mid, sp))
    years = sorted(y for y, v in by_year.items() if len(v) >= BINS * 0.5)   # at least half a year covered
    if len(years) < MIN_YEARS:
        return None
    med = {y: statistics.median(m for _, m, _ in by_year[y]) for y in years}
    # each year's price relative to its own median: slow trends drop out, the yearly shape stays
    grid: dict[int, list] = {y: [None] * BINS for y in years}
    rel_by_bin: list[list[float]] = [[] for _ in range(BINS)]
    for y in years:
        for ts, mid, _ in by_year[y]:
            b = bin_of(ts)
            grid[y][b] = mid
            rel_by_bin[b].append(mid / med[y] - 1)
    if any(len(v) < 3 for v in rel_by_bin):
        return None
    curve = [statistics.median(v) for v in rel_by_bin]
    resid = [x - curve[b] for b in range(BINS) for x in rel_by_bin[b]]
    noise = statistics.pstdev(resid) or 1e-9
    amp = max(curve) - min(curve)
    spread = statistics.median(sp for _, _, sp in pts)
    # The best trade, tested honestly. Picking the best of 64 buy/sell pairs after the fact finds "patterns" in pure
    # noise (a test with random prices showed +5-8 %, winning ~75 % of years). So every year is held out in turn: the
    # trade is chosen from the OTHER years only, then checked on the held-out year. Gain and win rate are those
    # out-of-sample results, the ones you'd have actually got.
    def trade(y: int, b: int, s: int) -> float | None:
        buy = grid[y][b]
        y2 = y if s > b else y + 1
        sell = grid[y2][s] if y2 in grid else None
        # buy and sell with orders near the mid, pay the tax and half the spread for slippage
        return sell / buy * (1 - BZ_TAX) - 1 - spread / 2 if buy and sell else None

    def pick(use: list[int]) -> tuple[int, int] | None:
        c = [statistics.median(v) if v else 0.0 for v in
             ([grid[y][b] / med[y] - 1 for y in use if grid[y][b]] for b in range(BINS))]
        cheap = sorted(range(BINS), key=lambda b: c[b])[:6]
        dear = sorted(range(BINS), key=lambda b: -c[b])[:6]
        best, bs = None, None
        for b in cheap:
            for s in dear:
                g = [x for x in (trade(y, b, s) for y in use) if x is not None]
                if len(g) >= 3:
                    sc = statistics.median(g)
                    if bs is None or sc > bs:
                        best, bs = (b, s), sc
        return best

    oos = []
    for y in years:
        p = pick([u for u in years if u != y])
        if p:
            g = trade(y, *p)
            if g is not None:
                oos.append(g)
    best = None
    p = pick(years)
    if p and len(oos) >= MIN_YEARS - 1:
        b, s = p
        best = {"buy": b, "sell": s, "hold_h": ((s - b) % BINS or BINS) * 2, "gain": statistics.median(oos),
                "win": sum(1 for x in oos if x > 0) / len(oos), "n": len(oos)}
    return {"years": len(years), "curve": curve, "amp": amp, "snr": amp / noise, "spread": spread, "best": best,
            "grid": {str(y + 1): grid[y] for y in years}, "last": pts[-1][1]}


def summary(pid: str, a: dict, vol: int) -> dict:
    b = a["best"] or {}
    out = {"id": pid, "years": a["years"], "amp": round(a["amp"], 4), "snr": round(a["snr"], 2), "spread": round(a["spread"], 4),
           "vol": vol, "last": rnd(a["last"]), "curve": [round(x, 4) for x in a["curve"]], "ok": tradeable(a)}
    if b:
        out.update({"buy_bin": b["buy"], "sell_bin": b["sell"], "hold_h": b["hold_h"], "gain": round(b["gain"], 4),
                    "win": round(b["win"], 3), "trades": b["n"]})
    return out


def main(site_dir: str, budget: str = "600", minutes: str = "30") -> None:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from market.hypixel import HypixelClient  # noqa: E402

    site = Path(site_dir)
    data = site / "data"
    (data / "_hist").mkdir(parents=True, exist_ok=True)
    path = data / "_hist" / "season.json.gz"
    now = time.time()
    store = load_store(path)
    added = add_snapshot(store, HypixelClient("https://api.hypixel.net/v2", "NotEnoughFlips data").bazaar(), now)
    used = backfill(store, now, int(budget), minutes=float(minutes))
    trim(store, now)
    with gzip.open(path, "wt", encoding="utf-8") as f:
        json.dump(store, f, separators=(",", ":"))

    season = data / "season"
    season.mkdir(exist_ok=True)
    rows, keep = [], set()
    for pid, r in store["items"].items():
        if not all(c.isalnum() or c in "_:" for c in pid):
            continue
        a = analyse(r)
        if a is None:
            continue
        rows.append(summary(pid, a, store["vol"].get(pid, 0)))
        grid = {y: [rnd(v) if v else None for v in g] for y, g in a["grid"].items()}
        (season / f"{pid}.json").write_text(json.dumps({"id": pid, "years": grid}, separators=(",", ":")), encoding="utf-8")
        keep.add(f"{pid}.json")
    for f in season.iterdir():
        if f.name not in keep:
            f.unlink()
    # reliable plays first: a real pattern (signal over noise), a trade that paid in most years, worth the bother
    rows.sort(key=lambda x: (not x["ok"], -(x.get("gain", 0) * x.get("win", 0) * min(x["snr"], 3))))
    out = {"updated": int(now * 1000), "epoch_s": EPOCH_S, "year_s": YEAR_S, "bins": BINS,
           "labels": [bin_label(b) for b in range(BINS)], "events": [events_in(b) for b in range(BINS)], "products": rows}
    (data / "seasonality.json").write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")
    good = [x for x in rows if x["ok"]]
    print(f"seasonality: +{added} slots, {used} backfill requests, {len(store['items'])} products stored, "
          f"{len(rows)} analysed, {len(good)} tradeable (out of sample ≥3 % in ≥70 % of years, signal/noise ≥2.5)")
    for x in good[:15]:
        print(f"  {x['id']}: buy {bin_label(x['buy_bin'])} → sell {bin_label(x['sell_bin'])} ({x['hold_h']}h) "
              f"median {x['gain']:+.1%}, won {x['win']:.0%} of {x['trades']}, snr {x['snr']}")


if __name__ == "__main__":
    main(*sys.argv[1:])
