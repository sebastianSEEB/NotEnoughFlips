"""Tests for tools/seasonality.py: the SkyBlock calendar, the history store, and finding a real yearly pattern."""
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import seasonality as S  # noqa: E402


def test_calendar():
    assert S.BINS == 62
    assert S.sb_date(S.EPOCH_S) == (1, 0, 1)                         # year 1, Early Spring 1st
    assert S.sb_date(S.EPOCH_S + 1200) == (1, 0, 2)                  # a SkyBlock day is 20 minutes
    assert S.sb_date(S.EPOCH_S + 31 * 1200) == (1, 1, 1)             # 31 days a month
    assert S.sb_date(S.EPOCH_S + S.YEAR_S) == (2, 0, 1)              # 124 hours a year
    assert S.bin_of(S.EPOCH_S + S.YEAR_S + 3 * 3600) == 1
    assert S.slot_of(S.slot_ts(1234)) == 1234 and S.slot_of(S.slot_ts(1234)) % S.BINS == S.bin_of(S.slot_ts(1234))
    assert S.bin_label(0) == "Early Spring 1"
    # Spooky Festival (Autumn 29-31) falls in exactly one or two slots
    spooky = [b for b in range(S.BINS) if "Spooky Festival" in S.events_in(b)]
    assert 1 <= len(spooky) <= 2
    assert S.bin_label(spooky[0]).startswith("Autumn")


def test_store_merge_keeps_first_and_trims():
    st = {"items": {}, "vol": {}, "done": {}}
    assert S.merge(st, "X", [[10, 5, 4], [11, 6, 5]]) == 2
    assert S.merge(st, "X", [[11, 9, 9], [12, 7, 6]]) == 1
    assert st["items"]["X"][1] == [11, 6, 5]
    S.trim(st, S.EPOCH_S + (11 + S.KEEP_SLOTS) * S.SLOT_S + 10)  # slots ≤ 11 are now too old
    assert [r[0] for r in st["items"]["X"]] == [12]
    S.trim(st, S.EPOCH_S + (20 + S.KEEP_SLOTS) * S.SLOT_S)
    assert "X" not in st["items"]


def seasonal_rows(years: int, amp: float, noise: float, trend: float = 0.0, seed: int = 1):
    """Synthetic product: price peaks around Spooky Festival each SkyBlock year."""
    rnd = random.Random(seed)
    spooky = next(b for b in range(S.BINS) if "Spooky Festival" in S.events_in(b))
    start = S.EPOCH_S + 400 * S.YEAR_S
    rows = []
    for k in range(years * S.BINS):
        ts = start + k * S.SLOT_S
        b = S.bin_of(ts)
        d = min((b - spooky) % S.BINS, (spooky - b) % S.BINS)
        season = amp * math.exp(-(d ** 2) / 8)                            # bump around Spooky
        mid = 1000 * (1 + trend * k / S.BINS) * (1 + season) * (1 + rnd.gauss(0, noise))
        rows.append([S.slot_of(ts), mid * 1.01, mid * 0.99])
    return rows, spooky


def test_finds_a_real_yearly_pattern():
    rows, spooky = seasonal_rows(years=12, amp=0.30, noise=0.03, trend=0.02)
    a = S.analyse(rows)
    assert a is not None and a["years"] >= 11
    assert max(range(S.BINS), key=lambda b: a["curve"][b]) == spooky        # dearest slot = Spooky
    b = a["best"]
    assert b["sell"] in (spooky - 1, spooky, spooky + 1)
    assert b["gain"] > 0.15 and b["win"] >= 0.9
    assert a["snr"] > 3


def test_noise_alone_is_not_a_pattern():
    # picking the best window after the fact "finds" +5-8 % in pure noise; tested out of sample it must not
    passed = 0
    for seed in range(1, 21):
        rows, _ = seasonal_rows(years=12, amp=0.0, noise=0.05, seed=seed)
        a = S.analyse(rows)
        assert a is not None and a["snr"] < 3
        passed += S.tradeable(a)
    assert passed == 0, f"{passed} of 20 noise series looked tradeable"


def test_small_real_pattern_is_found():
    rows, spooky = seasonal_rows(years=12, amp=0.10, noise=0.03, trend=0.02, seed=3)
    a = S.analyse(rows)
    assert S.tradeable(a) and abs(a["best"]["sell"] - spooky) <= 1


def test_too_little_history():
    rows, _ = seasonal_rows(years=3, amp=0.3, noise=0.02)
    assert S.analyse(rows) is None


def test_backfill_stops_at_its_deadline():
    store = {"items": {}, "vol": {"CANDY": 1e6}, "done": {}}
    calls = []
    real = S.cofl_week
    S.cofl_week = lambda pid, end: calls.append(pid) or []
    try:
        assert S.backfill(store, 1.8e9, 100, minutes=0) == 0      # out of time: no requests, nothing lost
        assert S.backfill(store, 1.8e9, 3, pause=0, minutes=1) == 3
    finally:
        S.cofl_week = real
    assert len(calls) == 3


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
