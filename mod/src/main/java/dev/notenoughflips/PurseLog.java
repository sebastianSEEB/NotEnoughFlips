package dev.notenoughflips;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Your purse over time, read from the SkyBlock sidebar while you play (every 5 min) and kept for 8 days in
 * .minecraft/config/nef-purse.csv. Used to turn "coins on the AH" history into a potential-FCF line
 * when no local server / API key provides the wallet.
 */
final class PurseLog {
	record Point(long ts, double purse) {}

	private static final long KEEP_MS = 8L * 86_400_000L, EVERY_MS = 5 * 60_000L;
	private static final List<Point> points = new ArrayList<>();
	private static boolean loaded;

	private PurseLog() {}

	private static Path file() {
		return Accounts.file("purse.csv");
	}

	/** Another account is playing: forget this one's readings (they stay in its own file). */
	static synchronized void reset() {
		points.clear();
		loaded = false;
	}

	private static synchronized void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String[] parts = line.split(",");
				if (parts.length == 2) points.add(new Point(Long.parseLong(parts[0].trim()), Double.parseDouble(parts[1].trim())));
			}
		} catch (IOException | RuntimeException ignored) {
			// start fresh
		}
	}

	static synchronized void record(double purse) {
		load();
		long now = System.currentTimeMillis();
		if (!points.isEmpty() && now - points.get(points.size() - 1).ts() < EVERY_MS) return;
		points.add(new Point(now, purse));
		points.removeIf(p -> now - p.ts() > KEEP_MS);
		StringBuilder sb = new StringBuilder();
		for (Point p : points) sb.append(p.ts()).append(',').append((long) p.purse()).append('\n');
		try {
			Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException ignored) {
			// not critical
		}
	}

	/** Purse at time ts: last reading before it (within 12 h), else the first reading within 1 h after it. */
	static synchronized Double at(long ts) {
		load();
		Point before = null, after = null;
		for (Point p : points) {
			if (p.ts() <= ts) before = p;
			else { after = p; break; }
		}
		if (before != null && ts - before.ts() <= 12 * 3_600_000L) return before.purse();
		if (after != null && after.ts() - ts <= 3_600_000L) return after.purse();
		return null;
	}
}
