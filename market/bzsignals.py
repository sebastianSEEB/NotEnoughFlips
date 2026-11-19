"""Bazaar misprices, correlations and suggestions (shown in the mod's Trade Desk → Signals window).

Input is any bazaar price history: hourly bars from a database (from_db) or SkyCofl's
7-day 2-hour bars (tools/bz_signals.py, GitHub Actions). Everything here is plain arithmetic on
log prices, so a 5 % move means the same for a 10-coin and a 10M-coin product.

What it looks for
-----------------
1. Misprice vs its own behaviour ("cheap" / "rich")
     move = log price now − median log price in the 12 h before the last ~6 h
     z    = move / (σ of its 1-bar returns · √bars)   — a random walk's normal move over that time
     flag |z| ≥ 2.5 and a move ≥ 4 %. Robust σ = 1.4826 · MAD, so spikes don't inflate it.
   Peers decide the confidence: the move minus the peers' median move, scaled by σ of the product's
   returns minus its peers' (z ≥ 2) = out of line = likely a dump / pump that reverts; if the whole
   group moved it's probably news (a mayor, an event), so low confidence. A drift the same way
   before the move (mean return t ≥ 2 over 24 bars, vs peers when it has them) lowers it too. A cheap product is only a BUY when the round trip pays after the 1.25 % tax: buy
   order now, sell offer back at the price before the drop (moved by what the peers did).
2. Correlations: Spearman (rank) ρ of 1-bar log returns, so a few shared spikes or a data glitch on
   the same bars can't make a pair. ρ ≥ 0.5 on ≥ 24 shared bars (p < 0.0001 at a week of 2 h bars) = they usually move together.
   Peers tell market-wide moves from one-product moves: if a product's correlated peers dropped
   just as much, the "dip" is the whole group (a mayor, an event) and is less likely to bounce.
3. Pair misprice: for a correlated pair the spread log A − β·log B (β from regressing the log price levels)
   normally hovers around its median; |z| ≥ 2 and ≥ 4 % apart → the laggard is cheap/rich vs its twin.
   Only when the spread mean-reverts: AR(1) φ of the spread < 1 with a half-life ≤ 48 h.
   "High" confidence for a cheap/rich product also needs proof that its own sudden moves usually
   bounce (≥ 3 past ones, ≥ 60 % at least one-third undone within 3 bars).
4. Lead–lag ("follow"): ρ(A now, B one bar later) ≥ 0.5 and stronger than the same-bar ρ means B
   tends to follow A. When A just made a ≥ 2.5 σ move and B hasn't, B is expected to follow.
   Lead–lag is only used when the history is fresh (the leader's move must be the latest bar).
5. Group events ("event"): ≥ 3 correlated products that all moved ≥ 15 % the same way at once = news
   (an update, a mayor). They're reported as one event and kept out of the cheap/pair lists, because
   the old prices are no guide. A pair spread beyond 8σ is treated the same way (relationship broke).
6. Recipe ratio ("craft" / "premium"): an enchanted product made from k units of another bazaar
   product. Crafting margin = sell offer for it after tax ÷ k buy orders − 1. Flagged when the margin
   is ≥ 3 % and unusually high for that pair, or ≥ 6 % outright; when the enchanted form is unusually
   cheap vs k raw, buying it instead of crafting is the tip.

Every suggestion carries: action, price now, target, edge after tax (fraction), confidence, the
units/hour the market trades (so the mod can size it to your purse) and the reasons.
Correlation is not causation and a week is a short sample: a suggestion is a lead to check, not a
promise — the mod shows the reasons with each one.
"""
from __future__ import annotations

import math
from statistics import median
from typing import Iterable, Optional


BZ_TAX = 0.0125

# thresholds (kept here so tests and the README quote the same numbers)
MIN_UNITS_WEEK = 2_000          # both sides
MIN_COINS_WEEK = 5_000_000      # coins traded per week on the thinner side
MAX_SPREAD = 0.60               # wider than this = thin / manipulated, skipped
Z_SELF = 2.5
MIN_MOVE = 0.04
MIN_EDGE = 0.03
RHO_PAIR = 0.5
RHO_LEAD = 0.5
Z_PAIR = 2.0
MIN_BARS = 24
CLIP = 0.30
Z_BREAK = 8.0                   # a pair spread this far out = the relationship broke (news), not a trade
BIG_MOVE = 0.15                 # log move that counts as "big" for group events
MARKET_EVENT = 6                # this many big movers the same way at once = market-wide event
NEWS_MOVE = 0.30                # a single product moving this much is more likely news than a mispricing


# ----------------------------------------------------------------------------- helpers

def _mid(buy: Optional[float], sell: Optional[float]) -> Optional[float]:
    b = buy if buy and buy > 0 else None
    s = sell if sell and sell > 0 else None
    if b and s:
        return math.sqrt(b * s)
    return b or s


def _robust(xs: list[float]) -> tuple[float, float]:
    m = median(xs)
    mad = median([abs(x - m) for x in xs])
    return m, 1.4826 * mad


def _pearson(a: list[float], b: list[float]) -> Optional[float]:
    n = len(a)
    if n < 3:
        return None
    ma, mb = sum(a) / n, sum(b) / n
    sab = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    saa = sum((x - ma) ** 2 for x in a)
    sbb = sum((y - mb) ** 2 for y in b)
    if saa <= 0 or sbb <= 0:
        return None
    return sab / math.sqrt(saa * sbb)


def _ranks(xs: list[float]) -> list[float]:
    order = sorted(range(len(xs)), key=lambda i: xs[i])
    r = [0.0] * len(xs)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and xs[order[j + 1]] == xs[order[i]]:
            j += 1
        for k in range(i, j + 1):
            r[order[k]] = (i + j) / 2
        i = j + 1
    return r


def _spearman(a: list[float], b: list[float]) -> Optional[float]:
    """Rank correlation: a few shared spikes (or a data glitch on the same bars) can't make two products a pair."""
    return _pearson(_ranks(a), _ranks(b))


def _slope(y: list[float], x: list[float]) -> float:
    n = len(x)
    mx, my = sum(x) / n, sum(y) / n
    sxx = sum((v - mx) ** 2 for v in x)
    return sum((u - mx) * (v - my) for u, v in zip(x, y)) / sxx if sxx > 0 else 0.0


def _clip(r: float) -> float:
    return max(-CLIP, min(CLIP, r))


def pretty(pid: str) -> str:
    return " ".join(w.capitalize() for w in pid.lower().split("_") if w)


# ----------------------------------------------------------------------------- core

def grid(series: dict[str, list[tuple]], step_ms: int) -> dict[str, dict[int, tuple]]:
    """{pid: {bucket: (log mid, buy, sell)}}; last point in a bucket wins."""
    out = {}
    for pid, pts in series.items():
        g = {}
        for p in sorted(pts):
            ts, buy, sell = p[0], p[1], p[2]
            m = _mid(buy, sell)
            if m:
                g[int(ts // step_ms)] = (math.log(m), buy, sell)
        if g:
            out[pid] = g
    return out


def _returns(g: dict[int, tuple]) -> dict[int, float]:
    """1-bar log returns keyed by the later bucket (only between adjacent buckets)."""
    r = {}
    for b in g:
        if b - 1 in g:
            r[b] = _clip(g[b][0] - g[b - 1][0])
    return r


def liquid(live: dict[str, dict]) -> set[str]:
    ok = set()
    for pid, l in live.items():
        buy, sell = l.get("buy") or 0, l.get("sell") or 0
        bw, sw = l.get("buy_week") or 0, l.get("sell_week") or 0
        if buy <= 0 or sell <= 0 or min(bw, sw) < MIN_UNITS_WEEK:
            continue
        if min(bw, sw) * sell < MIN_COINS_WEEK or (buy - sell) / buy > MAX_SPREAD:
            continue
        ok.add(pid)
    return ok


def analyse(series: dict[str, list[tuple]], live: dict[str, dict], step_ms: int,
            recipes: Optional[dict[str, tuple[str, float]]] = None, names: Optional[dict[str, str]] = None,
            now_ms: Optional[int] = None, source: str = "", max_pairs_per: int = 5) -> dict:
    """series: {pid: [(ts_ms, instant_buy, instant_sell), ...]}; live: {pid: {buy, sell, buy_week, sell_week}}.
    recipes: {output: (ingredient, count)} for single-ingredient bazaar recipes (compression)."""
    names = names or {}

    def nm(p: str) -> str:
        n = names.get(p)
        if not n or p.startswith("ENCHANTMENT_") or n.strip().lower() == "enchanted book":
            return pretty(p.replace("ENCHANTMENT_", "", 1)) if p.startswith("ENCHANTMENT_") else pretty(p)
        return "".join(ch for ch in n if ord(ch) < 0xE000 or ord(ch) > 0xF8FF).strip()
    G = grid(series, step_ms)
    ok = liquid(live)
    pids = sorted(p for p in G if p in ok and len(G[p]) >= MIN_BARS)
    last_bucket = max((max(g) for g in G.values()), default=0)
    if now_ms is None:
        now_ms = (last_bucket + 1) * step_ms
    recent = max(1, math.ceil(6 * 3_600_000 / step_ms))          # bars treated as "now", left out of the baseline
    R = {p: _returns(G[p]) for p in pids}

    # --- self misprice: the move over the last ~6 h vs what this product normally moves in that time
    ref_len = max(2, math.ceil(12 * 3_600_000 / step_ms))         # "before" = the 12 h before the recent window
    self_dev: dict[str, dict] = {}
    for p in pids:
        g = G[p]
        keys = sorted(g)
        if len(keys) < recent + ref_len + 4:
            continue
        ref_keys = keys[-(recent + ref_len):-recent]
        ref = median([g[k][0] for k in ref_keys])
        l = live[p]
        now_log = math.log(_mid(l["buy"], l["sell"]))
        sig_r = max(_robust(list(R[p].values()))[1] if len(R[p]) >= 8 else 0.0, 0.005)
        # bars between the middle of the reference window and now (the history can lag the live price by hours)
        ref_mid = (ref_keys[0] + ref_keys[-1]) / 2 + 0.5
        h = max(recent + ref_len / 2, now_ms / step_ms - ref_mid)
        move = now_log - ref
        week_med = median([g[k][0] for k in keys])
        self_dev[p] = {"move": move, "z": move / (sig_r * math.sqrt(h)), "sig_r": sig_r, "h": h,
                       "cut": keys[-recent], "week": math.exp(now_log - week_med) - 1,
                       "ref_buy": median([g[k][1] for k in ref_keys if g[k][1]] or [l["buy"]]),
                       "ref_sell": median([g[k][2] for k in ref_keys if g[k][2]] or [l["sell"]]), "bars": len(keys)}

    # --- correlations (same bar and one bar later)
    corr: list[dict] = []
    peers: dict[str, list[tuple[float, str]]] = {p: [] for p in pids}
    for i, a in enumerate(pids):
        ra = R[a]
        for b in pids[i + 1:]:
            rb = R[b]
            common = [k for k in ra if k in rb]
            if len(common) >= MIN_BARS:
                x, y = [ra[k] for k in common], [rb[k] for k in common]
                rho = _spearman(x, y)
                if rho is not None and rho >= RHO_PAIR:
                    corr.append({"a": a, "b": b, "rho": rho, "lag": 0, "beta": _slope(x, y), "n": len(common)})
                    peers[a].append((rho, b))
                    peers[b].append((rho, a))
            for lead, follow, rl, rf in ((a, b, ra, rb), (b, a, rb, ra)):
                lk = [k for k in rl if k + 1 in rf]
                if len(lk) < MIN_BARS:
                    continue
                x, y = [rl[k] for k in lk], [rf[k + 1] for k in lk]
                r1 = _spearman(x, y)
                if r1 is None or r1 < RHO_LEAD:
                    continue
                same = [k for k in rl if k in rf]
                r0 = _spearman([rl[k] for k in same], [rf[k] for k in same]) if len(same) >= MIN_BARS else 0.0
                if r1 > abs(r0 or 0.0):
                    corr.append({"a": lead, "b": follow, "rho": r1, "lag": 1, "beta": _slope(y, x), "n": len(lk)})
    for p in peers:
        peers[p].sort(reverse=True)

    signals: list[dict] = []
    tax = 1 - BZ_TAX

    def units_h(pid: str) -> float:
        l = live[pid]
        return min(l.get("buy_week") or 0, l.get("sell_week") or 0) / 168.0

    def peer_move(pid: str) -> tuple[Optional[float], int]:
        ps = [self_dev[q]["move"] for _, q in peers[pid][:max_pairs_per] if q in self_dev]
        return (median(ps) if ps else None), len(ps)

    def residuals(pid: str) -> dict[int, float]:
        """This product's returns minus its peers' median return (what's left after the group); own returns if no peers."""
        ps = [q for _, q in peers[pid][:max_pairs_per] if q in R]
        if not ps:
            return R[pid]
        res = {}
        for k, v in R[pid].items():
            o = [R[q][k] for q in ps if k in R[q]]
            if o:
                res[k] = v - median(o)
        return res

    def drift(res: dict[int, float], cut: int, bars: int = 24) -> tuple[float, float]:
        """(mean return per bar, its t-stat) over the bars before the recent window: a real trend, not a random walk's wander."""
        xs = [res[k] for k in sorted(res) if k < cut][-bars:]
        if len(xs) < 8:
            return 0.0, 0.0
        m = sum(xs) / len(xs)
        sd = math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))
        return m, (m / (sd / math.sqrt(len(xs))) if sd > 0 else 0.0)

    def bounces(res: dict[int, float], cut: int) -> tuple[int, int]:
        """(big one-bar moves before the recent window, how many were at least one-third undone within 3 bars)."""
        ks = sorted(k for k in res if k < cut)
        sd = max(_robust([res[k] for k in ks])[1] if len(ks) >= 8 else 0.0, 0.005)
        n = back = 0
        skip_to = -1
        for k in ks[:-3]:
            v = res[k]
            if k <= skip_to or abs(v) < Z_SELF * sd or abs(v) < MIN_MOVE / 2:
                continue
            n += 1
            skip_to = k + 3                              # the rebound itself isn't a new event
            later = sum(res.get(k + j, 0.0) for j in (1, 2, 3))
            if (later < 0) != (v < 0) and abs(later) >= abs(v) / 3:
                back += 1
        return n, back

    # 1) cheap / rich
    for p, s in self_dev.items():
        dev = math.exp(s["move"]) - 1
        if abs(s["z"]) < Z_SELF or abs(dev) < MIN_MOVE:
            continue
        l = live[p]
        pm, npeers = peer_move(p)
        idio = s["move"] - (pm or 0.0)
        res = residuals(p)
        rs = max(_robust(list(res.values()))[1] if len(res) >= 8 else s["sig_r"], 0.005)
        z_idio = idio / (rs * math.sqrt(s["h"])) if pm is not None else s["z"]
        why = [f"{dev * 100:+.1f}% in the last ~{round(s['h'] * step_ms / 3_600_000)}h, {abs(s['z']):.1f}× its normal move "
               f"({s['week'] * 100:+.1f}% vs its 7-day median)"]
        out_of_line = abs(z_idio) >= Z_PAIR and (idio < 0) == (s["move"] < 0)
        if pm is not None:
            why.append(f"its {npeers} closest peers moved {(math.exp(pm) - 1) * 100:+.1f}%"
                       + (" → this one is out of line" if out_of_line else " → the whole group moved"))
        m, t = drift(res, s["cut"])
        trending = abs(t) >= 2.0 and (m < 0) == (s["move"] < 0)
        if trending:
            per_h = m / (step_ms / 3_600_000) * 100
            why.append(f"it was already sliding this way ({per_h:+.2f}%/h{' vs its peers' if pm is not None else ''}): may keep going")
        n_big, n_back = bounces(res, s["cut"])
        reverts = n_big >= 3 and n_back / n_big >= 0.6
        if n_big >= 3:
            why.append(f"past sudden moves bounced back {n_back} of {n_big} times")
        news = abs(dev) >= NEWS_MOVE
        if news:
            why.append("a move this big is usually news (update, mayor, event), not a mispricing")
        conf = ("low" if news else "high" if out_of_line and not trending and reverts
                else "medium" if (out_of_line or pm is None) and not trending else "low")
        adj = math.exp(pm or 0.0)                       # if the group moved, the fair price moved with it
        if dev < 0:
            cost = l["sell"] + 0.1
            target = s["ref_buy"] * adj - 0.1
            edge = target * tax / cost - 1
            if edge < MIN_EDGE:
                continue
            signals.append(_sig("cheap", p, nm(p), "buy", cost, target, edge, conf, s["z"], units_h(p), why,
                                f"{nm(p)} dropped {abs(dev) * 100:.0f}%",
                                f"Buy order at {_c(cost)}, sell offer back near {_c(target)} when it recovers. Give it 1-2 days: "
                                f"in the backtest most of these recovered within 24-48 h, not within 6 h"))
        else:
            sell_now = l["sell"]
            target = s["ref_sell"] * adj
            edge = sell_now / max(1e-9, target) - 1
            if edge < MIN_EDGE:
                continue
            # backtest: after a jump like this the price usually kept rising for another day or two (selling at
            # once lost 1-4 % vs holding), so this is a warning not to buy into it, not a reason to dump
            why.append("backtest: jumps like this usually kept going for 1-2 days")
            signals.append(_sig("rich", p, nm(p), "watch", sell_now, target, edge, conf, s["z"], units_h(p), why,
                                f"{nm(p)} jumped {dev * 100:.0f}%",
                                f"Don't buy into the jump; if you hold some, there's no rush to sell. It's back to normal under {_c(s['ref_buy'] * adj)}"))

    # 1b) group events: correlated products that all made a big move the same way
    big = {p: d["move"] for p, d in self_dev.items() if abs(d["move"]) >= BIG_MOVE and abs(d["z"]) >= Z_SELF}
    parent = {p: p for p in big}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for p in big:
        for _, q in peers[p]:
            if q in big and (big[q] < 0) == (big[p] < 0):
                parent[find(p)] = find(q)
    groups: dict[str, list[str]] = {}
    for p in big:
        groups.setdefault(find(p), []).append(p)
    event_groups = [m for m in groups.values() if len(m) >= 3]
    # many big movers the same way in the same hours = a market-wide event, even without a week of correlation
    grouped = {q for m in event_groups for q in m}
    for sign in (-1, 1):
        rest = [q for q in big if q not in grouped and (big[q] < 0) == (sign < 0)]
        if len(rest) >= MARKET_EVENT:
            event_groups.append(rest)
    for members in event_groups:
        members.sort(key=lambda q: big[q])
        avg = median([big[q] for q in members])
        down = avg < 0
        names_list = ", ".join(f"{nm(q)} {(math.exp(big[q]) - 1) * 100:+.0f}%" for q in members[:6])
        lead = members[0] if down else members[-1]
        signals.append(_sig("event", lead, nm(lead), "avoid" if down else "sell", live[lead]["sell"], live[lead]["sell"],
                            math.exp(avg) - 1, "high", self_dev[lead]["z"], units_h(lead),
                            [f"{len(members)} products all {'fell' if down else 'jumped'} ≥ {BIG_MOVE * 100:.0f}% at once: {names_list}"
                             + (" …" if len(members) > 6 else ""),
                             "a whole group moving = news (update, mayor, event), so the old prices are no guide"],
                            f"{len(members)} related products {'crashed' if down else 'pumped'} {abs(math.exp(avg) - 1) * 100:.0f}%",
                            ("Don't buy the dip blindly; wait until it stops falling. Sell what you hold if you need the coins."
                             if down else "Sell what you hold into the pump; don't buy now."),
                            other=members[1] if len(members) > 1 else None))
        signals[-1]["members"] = members
    in_event = {q for ms in event_groups for q in ms}
    signals[:] = [x for x in signals if x["type"] == "event" or x["id"] not in in_event]

    # 2) pair misprice (spread vs its twin)
    seen_pair = set()
    for c in corr:
        if c["lag"] != 0:
            continue
        a, b = c["a"], c["b"]
        common = [k for k in G[a] if k in G[b]]
        if len(common) < MIN_BARS:
            continue
        # hedge ratio from the price levels (Engle–Granger): log A ≈ α + β·log B
        beta = _slope([G[a][k][0] for k in common], [G[b][k][0] for k in common])
        if not (0.2 <= beta <= 5):
            continue
        spread = [G[a][k][0] - beta * G[b][k][0] for k in common]
        base_k = spread[:-recent] if len(spread) > recent + MIN_BARS // 2 else spread
        med, sc = _robust(base_k)
        sc = max(sc, 0.01)
        now_sp = math.log(_mid(live[a]["buy"], live[a]["sell"])) - beta * math.log(_mid(live[b]["buy"], live[b]["sell"]))
        z = (now_sp - med) / sc
        gap = math.exp(now_sp - med) - 1
        if abs(z) < Z_PAIR or abs(gap) < MIN_MOVE:
            continue
        # both legs just made a big move the same way (a crash / pump hit them differently): that's news, not a pair trade
        ma, mb = self_dev.get(a, {}).get("move", 0.0), self_dev.get(b, {}).get("move", 0.0)
        if abs(z) > Z_BREAK or ((ma < 0) == (mb < 0) and min(abs(ma), abs(mb)) >= BIG_MOVE):
            continue
        # the spread must pull back to its middle (AR(1) φ < 1, half-life ≤ 2 days), else it's not a pair trade
        dev_sp = [x - med for x in spread]
        phi = _slope(dev_sp[1:], dev_sp[:-1])
        if phi >= 1:
            continue
        half = (math.log(0.5) / math.log(phi) if phi > 0.01 else 0.5) * step_ms / 3_600_000
        if half > 48:
            continue
        cheap, rich = (a, b) if z < 0 else (b, a)
        key = (cheap, rich)
        if key in seen_pair:
            continue
        seen_pair.add(key)
        l = live[cheap]
        cost = l["sell"] + 0.1
        g = abs(gap) if cheap == a else abs(gap) / max(beta, 1e-9)
        target = (l["buy"] - 0.1) * (1 + g)
        edge = target * tax / cost - 1
        why = [f"{nm(cheap)} and {nm(rich)} move together (ρ {c['rho']:.2f}, {c['n']} bars)",
               f"{nm(cheap)} is {g * 100:.1f}% cheap relative to {nm(rich)} ({abs(z):.1f}σ)",
               f"gaps like this have closed half-way in ~{half:.0f}h"]
        conf = "high" if c["rho"] >= 0.75 and abs(z) >= 2.5 and half <= 24 else "medium"
        # backtest (tools/backtest_signals.py): buying the lagging one of a pair had no edge (hit 0-15 %,
        # median return below zero at 6-48 h), so a pair is only a hint for which of the two to buy if you need one
        why.append("backtest: pair gaps didn't close reliably enough to trade on their own")
        signals.append(_sig("pair", cheap, nm(cheap), "prefer", cost, target, edge, "low", z,
                            units_h(cheap), why, f"{nm(cheap)} lags {nm(rich)}",
                            f"If you need one of the two, buy {nm(cheap)} now, it's the cheaper of the pair",
                            other=rich, rho=c["rho"]))

    # 3) lead-lag: B follows A (only when the history is fresh: the lead move must be the latest one)
    fresh = now_ms / step_ms - (last_bucket + 1) <= 1.5
    for c in corr:
        if c["lag"] != 1 or not fresh:
            continue
        a, b = c["a"], c["b"]
        ra = R[a]
        if not ra:
            continue
        last = max(ra)
        if last < last_bucket - 1:                       # A's latest move is old news
            continue
        sd = _robust(list(ra.values()))[1] or 0.0
        sd = max(sd, 0.005)
        move = ra[last]
        if abs(move) < 2.5 * sd or abs(move) < MIN_MOVE:
            continue
        rb_last = R[b].get(last + 0) or 0.0
        if abs(rb_last) > 0.5 * abs(move):               # B already moved
            continue
        exp = c["beta"] * move
        l = live[b]
        why = [f"{nm(b)} has followed {nm(a)} one bar later (ρ {c['rho']:.2f}, {c['n']} bars)",
               f"{nm(a)} just moved {move * 100:+.1f}% ({abs(move) / sd:.1f}σ), {nm(b)} only {rb_last * 100:+.1f}%"]
        if exp > 0:
            cost = l["sell"] + 0.1
            target = (l["buy"] - 0.1) * (1 + exp)
            edge = target * tax / cost - 1
            if edge < MIN_EDGE:
                continue
            signals.append(_sig("follow", b, nm(b), "buy", cost, target, edge, "medium" if c["rho"] >= 0.6 else "low",
                                move / sd, units_h(b), why, f"{nm(b)} may follow {nm(a)} up",
                                f"Buy order at {_c(cost)} before it follows (~{exp * 100:.0f}% expected)", other=a, rho=c["rho"]))
        elif exp < -MIN_MOVE:
            signals.append(_sig("follow", b, nm(b), "sell", l["sell"], l["sell"] * (1 + exp), -exp, "medium" if c["rho"] >= 0.6 else "low",
                                move / sd, units_h(b), why, f"{nm(b)} may follow {nm(a)} down",
                                f"Sell {nm(b)} you hold before it follows (~{exp * 100:.0f}% expected)", other=a, rho=c["rho"]))

    # 4) recipe ratio (compression)
    for out, (ing, k) in (recipes or {}).items():
        if out not in live or ing not in live or out not in ok or ing not in ok or k <= 0:
            continue
        lo, li = live[out], live[ing]
        # a buy order only fills when the spread is tight; otherwise you pay the instant-buy price (same as craft_flips)
        unit = li["sell"] + 0.1 if li["sell"] >= 0.9 * li["buy"] else li["buy"]
        cost = k * unit
        out_price = lo["buy"] - 0.1 if lo["sell"] >= 0.9 * lo["buy"] else lo["sell"]     # wide spread: offers sit, sell instantly
        rev = out_price * tax
        margin = rev / cost - 1
        hist = []
        if out in G and ing in G:
            for kk in G[out]:
                if kk in G[ing]:
                    hist.append(G[out][kk][0] - math.log(k) - G[ing][kk][0])
        zr = None
        if len(hist) >= MIN_BARS:
            m, sc = _robust(hist)
            now_r = math.log(_mid(lo["buy"], lo["sell"])) - math.log(k) - math.log(_mid(li["buy"], li["sell"]))
            zr = (now_r - m) / max(sc, 0.01)
        why = [f"1 {nm(out)} = {k:g} {nm(ing)}"]
        if zr is not None:
            why.append(f"the price ratio is {zr:+.1f}σ vs this week")
        if margin >= 0.06 or (margin >= MIN_EDGE and zr is not None and zr >= Z_PAIR):
            signals.append(_sig("craft", out, nm(out), "craft", cost, out_price, margin, "high" if margin >= 0.06 else "medium",
                                zr or 0.0, units_h(out), why, f"Craft {nm(out)} from {nm(ing)}",
                                f"Buy {k:g} {nm(ing)} ({'orders at' if unit < li['buy'] else 'instantly at'} {_c(unit)}), craft, {'sell offer at' if out_price > lo['sell'] else 'sell instantly at'} {_c(out_price)}",
                                other=ing))
        elif zr is not None and zr <= -Z_PAIR:
            save = 1 - (lo["buy"]) / (k * li["buy"])
            if save >= MIN_MOVE:
                signals.append(_sig("premium", out, nm(out), "prefer", lo["buy"], k * li["buy"], save, "medium", zr, units_h(out), why,
                                    f"{nm(out)} is cheap vs {k:g} {nm(ing)}",
                                    f"Need {nm(out)}? Buy it ready-made: {save * 100:.0f}% cheaper than crafting it from {k:g} {nm(ing)}",
                                    other=ing))

    signals[:] = [x for x in signals if x["type"] == "event" or x["id"] not in in_event]   # pairs/follows on event members too
    rank = {"high": 3, "medium": 2, "low": 1}
    signals.sort(key=lambda s: (s["type"] == "event", rank[s["conf"]], min(abs(s["edge"]), 0.5) * math.sqrt(max(1.0, s["units_h"] * s["price"]))),
                 reverse=True)
    corr.sort(key=lambda c: c["rho"], reverse=True)
    shown = {s["id"] for s in signals[:80]} | {s.get("other") for s in signals[:80] if s.get("other")}
    return {
        "generated": now_ms, "source": source, "step_h": step_ms / 3_600_000, "products": len(pids),
        "history_lag_h": round(max(0.0, (now_ms - (last_bucket + 1) * step_ms) / 3_600_000), 1),
        "liquid": len(ok), "pairs": len([c for c in corr if c["lag"] == 0]), "leads": len([c for c in corr if c["lag"] == 1]),
        "signals": signals[:80],
        "correlations": [dict(c, rho=round(c["rho"], 3), beta=round(c["beta"], 3)) for c in corr[:300]],
        "names": {p: nm(p) for p in shown | {c["a"] for c in corr[:300]} | {c["b"] for c in corr[:300]}},
        # price history (mid) of every product in a suggestion, for the Trade Desk chart: [first bucket, step ms, values…]
        "spark": {p: _spark(G[p], step_ms) for p in shown if p in G},
    }


def _spark(g: dict[int, tuple], step_ms: int) -> list:
    first, last = min(g), max(g)
    vals = [round(math.exp(g[k][0]), 4) if k in g else None for k in range(first, last + 1)]
    return [first * step_ms, step_ms] + vals


def _c(v: float) -> str:
    v = float(v)
    for div, suf in ((1e9, "B"), (1e6, "M"), (1e3, "k")):
        if abs(v) >= div:
            return f"{v / div:.2f}".rstrip("0").rstrip(".") + suf
    return f"{v:.1f}"


def _sig(kind, pid, name, action, price, target, edge, conf, z, units_h, why, title, text, other=None, rho=None) -> dict:
    d = {"type": kind, "id": pid, "name": name, "action": action, "price": round(price, 2), "target": round(target, 2),
         "edge": round(edge, 4), "conf": conf, "z": round(z, 2), "units_h": round(units_h, 1), "title": title, "text": text, "why": why}
    if other:
        d["other"] = other
    if rho is not None:
        d["rho"] = round(rho, 3)
    return d


# ----------------------------------------------------------------------------- database source

def from_db(c, cfg, now_ms: int, hours: int = 168) -> Optional[dict]:
    """Signals from a database of hourly bars (needs ≥ 24 h of bazaar data)."""
    since = (now_ms // 3_600_000) - hours
    rows = c.execute("SELECT series, hour, avg FROM hourly WHERE series LIKE 'bz:%' AND hour >= ?", (since,)).fetchall()
    series: dict[str, dict[int, list]] = {}
    for s, h, avg in rows:
        _, pid, side = s.split(":", 2)
        series.setdefault(pid, {}).setdefault(h, [None, None])[0 if side == "buy" else 1] = avg
    if not series or max(len(v) for v in series.values()) < MIN_BARS:
        return None
    live = {r[0]: {"buy": r[1], "sell": r[2], "buy_week": r[3], "sell_week": r[4]}
            for r in c.execute("SELECT product, buy, sell, buy_week, sell_week FROM bz_now")}
    pts = {pid: [(h * 3_600_000, v[0], v[1]) for h, v in hs.items()] for pid, hs in series.items()}
    return analyse(pts, live, 3_600_000, now_ms=now_ms, source="hourly history")


def recipes_from_neu(recipes: dict, bazaar_ids: Iterable[str]) -> dict[str, tuple[str, float]]:
    """Single-ingredient bazaar → bazaar recipes (the compression chains) from tools/craft_flips.load_recipes output."""
    bz = set(bazaar_ids)
    out = {}
    for o, rs in recipes.items():
        if o not in bz:
            continue
        for ings, cnt in rs:
            if len(ings) == 1:
                (i, n), = ings.items()
                if i in bz and n > 0 and cnt > 0:
                    out[o] = (i, n / cnt)
                    break
    return out
