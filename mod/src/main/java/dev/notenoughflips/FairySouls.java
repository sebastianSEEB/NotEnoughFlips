package dev.notenoughflips;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * Fairy Soul waypoints: a marker on screen (through walls) for every Fairy Soul on the island you're on that you haven't
 * clicked yet, with its distance; the nearest one is labelled, and points the way from the screen edge when it's behind
 * you. Click a soul in game and its marker goes away ("SOUL! You found a Fairy Soul!", or "already found" for souls
 * collected before the mod knew). Kept per account and SkyBlock profile.
 *
 * Only shows where things are: it never moves, clicks or walks for you.
 * Soul positions ship inside the mod (assets/notenoughflips/fairy-souls.json, from NotEnoughUpdates-REPO, MIT).
 */
final class FairySouls {
	private FairySouls() {}

	private static NefConfig cfg;
	private static Map<String, List<int[]>> souls = Map.of();
	/** profile → island → found positions ("x,y,z"). */
	private static final Map<String, Map<String, Set<String>>> found = new HashMap<>();
	private static String loadedFor;

	private static volatile String island;           // Hypixel location mode, e.g. "hub"; null while unknown
	private static Object lastLevel;
	private static long joinedAt, locrawAt;
	private static boolean locrawSent;
	private static String profile = "default";
	private static long tickAt;
	private static double[] prevEye, eye;

	/** Tab list "Area: ..." names → island key. */
	private static final Map<String, String> AREAS = Map.ofEntries(
			Map.entry("hub", "hub"),
			Map.entry("spider's den", "combat_1"),
			Map.entry("the end", "combat_3"),
			Map.entry("crimson isle", "crimson_isle"),
			Map.entry("the park", "foraging_1"),
			Map.entry("galatea", "foraging_2"),
			Map.entry("moonglade marsh", "foraging_2"),
			Map.entry("the farming islands", "farming_1"),
			Map.entry("the barn", "farming_1"),
			Map.entry("mushroom desert", "farming_1"),
			Map.entry("gold mine", "mining_1"),
			Map.entry("deep caverns", "mining_2"),
			Map.entry("dwarven mines", "mining_3"),
			Map.entry("jerry's workshop", "winter"),
			Map.entry("dungeon hub", "dungeon_hub"),
			Map.entry("backwater bayou", "fishing_1"),
			Map.entry("lotus atoll", "lotus_atoll"));

	static void init(NefConfig config) {
		cfg = config;
		souls = load();
		HudHook.register("fairy_souls", (p, size) -> {
			try {
				if (cfg.fairySouls) paint(p, size[0], size[1]);
			} catch (RuntimeException ignored) {
				// never break the HUD
			}
		});
	}

	// ---- data ----

	private static Map<String, List<int[]>> load() {
		Map<String, List<int[]>> out = new HashMap<>();
		try (InputStream in = FairySouls.class.getResourceAsStream("/assets/notenoughflips/fairy-souls.json")) {
			if (in == null) return out;
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			out.putAll(parse(root));
		} catch (Exception e) {
			System.err.println("[NEF] fairy souls: " + e);
		}
		return out;
	}

	/** {"islands": {"hub": [[x,y,z], ...]}} → island → positions. */
	static Map<String, List<int[]>> parse(JsonObject root) {
		Map<String, List<int[]>> out = new HashMap<>();
		JsonObject isl = root.getAsJsonObject("islands");
		if (isl == null) return out;
		for (Map.Entry<String, JsonElement> e : isl.entrySet()) {
			List<int[]> pts = new ArrayList<>();
			for (JsonElement p : e.getValue().getAsJsonArray()) {
				JsonArray a = p.getAsJsonArray();
				pts.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
			}
			out.put(e.getKey(), pts);
		}
		return out;
	}

	static int total(String isl) {
		List<int[]> l = souls.get(isl);
		return l == null ? 0 : l.size();
	}

	private static String pos(int[] p) {
		return p[0] + "," + p[1] + "," + p[2];
	}

	private static Set<String> foundOn(String isl) {
		loadFound();
		return found.computeIfAbsent(profile, k -> new HashMap<>()).computeIfAbsent(isl, k -> new HashSet<>());
	}

	/** Souls not clicked yet on this island. */
	static List<int[]> left(String isl) {
		List<int[]> all = souls.get(isl);
		if (all == null) return List.of();
		Set<String> f = foundOn(isl);
		List<int[]> out = new ArrayList<>();
		for (int[] p : all) if (!f.contains(pos(p))) out.add(p);
		return out;
	}

	private static Path file() {
		return Accounts.file("fairy-souls.json");
	}

	private static void loadFound() {
		String acc = Accounts.id();
		if (acc.equals(loadedFor)) return;
		loadedFor = acc;
		found.clear();
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> pe : root.entrySet()) {
				Map<String, Set<String>> m = new HashMap<>();
				for (Map.Entry<String, JsonElement> ie : pe.getValue().getAsJsonObject().entrySet()) {
					Set<String> s = new HashSet<>();
					for (JsonElement x : ie.getValue().getAsJsonArray()) s.add(x.getAsString());
					m.put(ie.getKey(), s);
				}
				found.put(pe.getKey(), m);
			}
		} catch (Exception e) {
			System.err.println("[NEF] fairy souls file: " + e);
		}
	}

	private static void saveFound() {
		JsonObject root = new JsonObject();
		for (Map.Entry<String, Map<String, Set<String>>> pe : found.entrySet()) {
			JsonObject po = new JsonObject();
			for (Map.Entry<String, Set<String>> ie : pe.getValue().entrySet()) {
				JsonArray a = new JsonArray();
				ie.getValue().stream().sorted().forEach(a::add);
				po.add(ie.getKey(), a);
			}
			root.add(pe.getKey(), po);
		}
		try {
			Files.writeString(file(), root.toString());
		} catch (Exception e) {
			System.err.println("[NEF] fairy souls save: " + e);
		}
	}

	/** The unfound soul nearest to (x, y, z) within maxDist blocks, or null. */
	static int[] nearest(List<int[]> pts, double x, double y, double z, double maxDist) {
		int[] best = null;
		double bd = maxDist * maxDist;
		for (int[] p : pts) {
			double dx = p[0] + 0.5 - x, dy = p[1] + 0.5 - y, dz = p[2] + 0.5 - z, d = dx * dx + dy * dy + dz * dz;
			if (d <= bd) { bd = d; best = p; }
		}
		return best;
	}

	// ---- chat ----

	/** True when the message is our own hidden /locraw answer (and it's used to know the island). */
	static boolean onChat(Component message) {
		String s = message.getString().replaceAll("§.", "").trim();
		if (s.startsWith("{") && s.contains("\"server\"")) {
			boolean ours = locrawAt > 0 && System.currentTimeMillis() - locrawAt < 15_000L;
			try {
				JsonObject o = JsonParser.parseString(s).getAsJsonObject();
				if (o.has("mode")) {
					String gt = o.has("gametype") ? o.get("gametype").getAsString() : "";
					island = "SKYBLOCK".equals(gt) ? o.get("mode").getAsString().toLowerCase(Locale.ROOT) : null;
				}
			} catch (RuntimeException ignored) {
				// not JSON after all
			}
			if (ours) locrawAt = 0;
			return ours;
		}
		if (s.contains("found a Fairy Soul") || s.contains("already found that Fairy Soul")) collected();
		return false;
	}

	/** You clicked a soul: its marker goes away. */
	private static void collected() {
		Minecraft mc = Minecraft.getInstance();
		String isl = island;
		if (mc.player == null || isl == null) return;
		int[] p = nearest(left(isl), mc.player.getX(), mc.player.getY() + 1, mc.player.getZ(), 10);
		if (p == null) return;
		foundOn(isl).add(pos(p));
		saveFound();
	}

	// ---- island ----

	/** Every client tick: notice server switches, learn the island (tab list "Area:", or Hypixel's /locraw once). */
	static void tick() {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (mc.player == null || mc.level == null) {
			lastLevel = null;
			return;
		}
		prevEye = eye;
		eye = new double[]{mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()};
		tickAt = now;
		if (mc.level != lastLevel) {                 // new server / island
			lastLevel = mc.level;
			island = null;
			joinedAt = now;
			locrawSent = false;
			prevEye = null;
		}
		if (!cfg.fairySouls || mc.player.tickCount % 20 != 0) return;
		readTab(mc);
		if (island == null && !locrawSent && now - joinedAt > 2500 && onHypixel(mc)) {
			locrawSent = true;                        // asks Hypixel where you are; the answer is hidden from chat
			locrawAt = now;
			mc.player.connection.sendCommand("locraw");
		}
	}

	private static boolean onHypixel(Minecraft mc) {
		var sd = mc.getCurrentServer();
		return sd != null && sd.ip != null && sd.ip.toLowerCase(Locale.ROOT).contains("hypixel");
	}

	private static void readTab(Minecraft mc) {
		var conn = mc.getConnection();
		if (conn == null) return;
		for (var info : conn.getListedOnlinePlayers()) {
			Component c = info.getTabListDisplayName();
			if (c == null) continue;
			String line = c.getString().replaceAll("§.", "").trim();
			if (line.startsWith("Profile:")) {
				String pr = line.substring(8).replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
				if (!pr.isEmpty() && !pr.equals(profile)) profile = pr;
			} else if (line.startsWith("Area:") || line.startsWith("Dungeon:")) {
				String k = islandFor(line.substring(line.indexOf(':') + 1));
				if (k != null) island = k;
			}
		}
	}

	/** "Spider's Den" → "combat_1"; an island the table doesn't know is matched by name ("Lotus Atoll" → "lotus_atoll"). */
	static String islandFor(String area) {
		String a = area.replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
		String k = AREAS.get(a);
		if (k != null) return k;
		String n = a.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
		return souls.containsKey(n) ? n : null;
	}

	// ---- drawing ----

	/**
	 * World point → screen {x, y, depth}; depth ≤ 0 = behind you. Camera at cam looking along yaw/pitch (degrees,
	 * Minecraft's: yaw 0 = south/+Z, pitch +90 = straight down), vertical field of view fov, screen w × h.
	 */
	static double[] project(double[] cam, double yaw, double pitch, double fov, int w, int h, double px, double py, double pz) {
		double ya = Math.toRadians(yaw), pa = Math.toRadians(pitch);
		double fx = -Math.sin(ya) * Math.cos(pa), fy = -Math.sin(pa), fz = Math.cos(ya) * Math.cos(pa);
		double rx = -Math.cos(ya), ry = 0, rz = -Math.sin(ya);
		double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;   // up = right × forward
		double dx = px - cam[0], dy = py - cam[1], dz = pz - cam[2];
		double depth = dx * fx + dy * fy + dz * fz, sx = dx * rx + dy * ry + dz * rz, sy = dx * ux + dy * uy + dz * uz;
		double f = (h / 2.0) / Math.tan(Math.toRadians(fov) / 2);
		if (depth <= 0.05) {                          // behind: direction only (for the edge arrow)
			double d = Math.max(1e-6, Math.hypot(sx, sy));
			return new double[]{w / 2.0 + sx / d * w, h / 2.0 - sy / d * h, depth};
		}
		return new double[]{w / 2.0 + sx / depth * f, h / 2.0 - sy / depth * f, depth};
	}

	private static final int PINK = 0xFFFF55FF, PINK_DIM = 0xB0E070E0, DARK = 0xC0000000;

	private static void paint(Highlight.Painter p, int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		var open = BidHelper.currentScreen();          // hidden behind menus, kept with chat open
		if (mc.player == null || open != null && !(open instanceof net.minecraft.client.gui.screens.ChatScreen)) return;
		String isl = island;
		if (isl == null || !souls.containsKey(isl)) return;
		List<int[]> left = left(isl);
		int total = total(isl);
		double[] cam = camera(mc);
		double yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
		double fov = mc.options.fov().get();
		int[] near = nearest(left, cam[0], cam[1], cam[2], 1e9);

		String head = "Fairy Souls  " + (total - left.size()) + "/" + total + " here";
		McStyle.panel(p, 4, 4, 4 + mc.font.width(head) + 12, 18);
		p.text(head, 10, 8, left.isEmpty() ? 0xFF55FF55 : McStyle.TEXT);
		if (left.isEmpty()) return;

		for (int[] s : left) {
			double[] sp = project(cam, yaw, pitch, fov, w, h, s[0] + 0.5, s[1] + 0.5, s[2] + 0.5);
			if (s == near) continue;                  // drawn last, on top
			if (sp[2] <= 0.05 || sp[0] < 0 || sp[0] > w || sp[1] < 0 || sp[1] > h) continue;
			int x = (int) sp[0], y = (int) sp[1];
			double dist = dist(cam, s);
			int r = dist < 32 ? 3 : 2;
			p.fill(x - r - 1, y - r - 1, x + r + 1, y + r + 1, DARK);
			p.fill(x - r, y - r, x + r, y + r, PINK_DIM);
			if (dist < 64) label(p, mc, (int) Math.round(dist) + "m", x, y + r + 3, 0xFFE0B0E0);
		}
		if (near != null) {
			double[] sp = project(cam, yaw, pitch, fov, w, h, near[0] + 0.5, near[1] + 0.5, near[2] + 0.5);
			String txt = "Fairy Soul " + (int) Math.round(dist(cam, near)) + "m";
			boolean on = sp[2] > 0.05 && sp[0] >= 0 && sp[0] <= w && sp[1] >= 0 && sp[1] <= h;
			int x = (int) Math.max(14, Math.min(w - 14, sp[0])), y = (int) Math.max(14, Math.min(h - 24, sp[1]));
			p.fill(x - 5, y - 5, x + 5, y + 5, DARK);
			p.fill(x - 4, y - 4, x + 4, y + 4, PINK);
			p.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFFFFFF);
			label(p, mc, on ? txt : txt + "  (" + arrow(sp, w, h) + ")", x, y + 8, PINK);
		}
	}

	private static String arrow(double[] sp, int w, int h) {
		double dx = sp[0] - w / 2.0, dy = sp[1] - h / 2.0;
		if (sp[2] <= 0.05 && Math.abs(dx) < Math.abs(dy) * 0.3) return "behind you";
		if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? "right" : "left";
		return dy > 0 ? "down" : "up";
	}

	private static void label(Highlight.Painter p, Minecraft mc, String t, int cx, int y, int color) {
		int tw = mc.font.width(t);
		p.fill(cx - tw / 2 - 2, y - 1, cx + tw / 2 + 2, y + 9, DARK);
		p.text(t, cx - tw / 2, y, color);
	}

	private static double dist(double[] cam, int[] s) {
		double dx = s[0] + 0.5 - cam[0], dy = s[1] + 0.5 - cam[1], dz = s[2] + 0.5 - cam[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Eye position, smoothed between ticks so markers don't jitter while you walk. */
	private static double[] camera(Minecraft mc) {
		double[] e = eye, pe = prevEye;
		if (e == null) return new double[]{mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()};
		if (pe == null) return e;
		double t = Math.max(0, Math.min(1, (System.currentTimeMillis() - tickAt) / 50.0));
		return new double[]{pe[0] + (e[0] - pe[0]) * t, pe[1] + (e[1] - pe[1]) * t, pe[2] + (e[2] - pe[2]) * t};
	}

	// ---- count in the inventory corner ----

	private static volatile int profileFound = -1, profileUnspent = -1;   // from your SkyBlock profile (API key)

	private static final Map<String, String> NAMES = Map.ofEntries(
			Map.entry("hub", "Hub"), Map.entry("combat_1", "Spider's Den"), Map.entry("combat_3", "The End"),
			Map.entry("crimson_isle", "Crimson Isle"), Map.entry("foraging_1", "The Park"), Map.entry("foraging_2", "Galatea"),
			Map.entry("foraging_3", "Foraging"), Map.entry("farming_1", "Farming Islands"), Map.entry("mining_1", "Gold Mine"),
			Map.entry("mining_2", "Deep Caverns"), Map.entry("mining_3", "Dwarven Mines"), Map.entry("winter", "Jerry's Workshop"),
			Map.entry("dungeon_hub", "Dungeon Hub"), Map.entry("fishing_1", "Backwater Bayou"), Map.entry("lotus_atoll", "Lotus Atoll"),
			Map.entry("safari", "Safari"));

	/** Your member entry of the selected profile: souls collected and not yet exchanged at Tia. */
	static void fromProfile(JsonObject member) {
		if (member == null) return;
		JsonObject fs = HudFormatter.obj(member, "fairy_soul");
		Double got = fs != null ? HudFormatter.num(fs, "total_collected") : HudFormatter.num(member, "fairy_souls_collected");
		Double unspent = fs != null ? HudFormatter.num(fs, "unspent_souls") : HudFormatter.num(member, "fairy_souls");
		if (got != null) profileFound = got.intValue();
		if (unspent != null) profileUnspent = unspent.intValue();
	}

	/** Every soul in the game, from the shipped list. */
	static int grandTotal() {
		int n = 0;
		for (List<int[]> l : souls.values()) n += l.size();
		return n;
	}

	/** Pure: the lines for the corner, top first. Empty when nothing is known. */
	static List<String> cornerLines(int found, int all, int unspent, String islandName, int islandFound, int islandAll) {
		List<String> out = new ArrayList<>();
		if (found >= 0 && all > 0) out.add("✦ Fairy Souls " + Math.min(found, all) + "/" + all);
		if (islandName != null && islandAll > 0)                             // what the markers know, not your profile
			out.add(islandName + ": " + (islandFound >= islandAll ? "all found ✔" : (islandAll - islandFound) + " markers left"));
		if (unspent >= 5) out.add((unspent / 5) + " to exchange at Tia");
		return out;
	}

	/** Bottom-right corner, just under your inventory. */
	static void paintCorner(Screen s, Highlight.Painter p) {
		if (cfg == null || !cfg.fairySoulCorner || !(s instanceof InventoryScreen)) return;
		String isl = island;
		boolean here = isl != null && souls.containsKey(isl);
		List<String> lines = cornerLines(profileFound, grandTotal(), profileUnspent,
				here ? NAMES.getOrDefault(isl, isl) : null, here ? total(isl) - left(isl).size() : 0, here ? total(isl) : 0);
		if (lines.isEmpty()) return;
		int[] box = GuiPos.box(s);
		var font = Minecraft.getInstance().font;
		int right = box[0] + box[2], y = box[1] + box[3] + 3;               // box is left, top, width, height
		int[] discord = DiscordButton.rect(s);
		if (discord != null && discord[1] > box[1] + box[3]) right = discord[0] - 5;   // left of the Discord button
		for (int i = 0; i < lines.size(); i++) {
			String t = lines.get(i);
			int x = right - font.width(t);
			if (y + 9 > s.height) break;
			p.text(t, x, y, i == 0 ? 0xFFFF77FF : McStyle.MUTED);
			y += 10;
		}
	}

	// ---- commands ----

	static String status() {
		String isl = island;
		if (isl == null) return "Fairy Souls: " + (cfg.fairySouls ? "on" : "off") + ". Island not known yet (or no souls here).";
		if (!souls.containsKey(isl)) return "Fairy Souls: no souls on this island (" + isl + ").";
		return "Fairy Souls: " + (cfg.fairySouls ? "on" : "off") + ". " + (total(isl) - left(isl).size()) + "/" + total(isl)
				+ " found on this island.";
	}

	/** Show every soul on this island again. */
	static String resetIsland() {
		String isl = island;
		if (isl == null) return "Island not known yet.";
		foundOn(isl).clear();
		saveFound();
		return "All " + total(isl) + " souls on this island are shown again.";
	}

	/** Mark every soul on this island as found (e.g. you finished it before using the mod). */
	static String allFound() {
		String isl = island;
		if (isl == null || !souls.containsKey(isl)) return "Island not known yet.";
		Set<String> f = foundOn(isl);
		for (int[] p : souls.get(isl)) f.add(pos(p));
		saveFound();
		return "All souls on this island marked as found.";
	}
}
