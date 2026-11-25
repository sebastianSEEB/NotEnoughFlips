package dev.notenoughflips;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * What you have, for craft flips: your inventory (read from the game every 10 s, always current) plus your sacks, ender
 * chest, backpacks, personal vault and bags from your profile (the /nef apikey lookup, every 5 minutes). Sacks are
 * always in the profile; the rest only when Hypixel's API setting "Inventory API" is on (/settings → API Settings).
 */
final class Owned {
	private Owned() {}

	private static volatile Map<String, Long> inventory = Map.of();      // live, from the game
	private static volatile Map<String, Long> stored = Map.of();         // sacks + storage, from the profile
	private static volatile boolean storageApi, sacksKnown;

	static boolean sacksKnown() {
		return sacksKnown;
	}

	/** True when the profile showed your ender chest / backpacks (Inventory API on). */
	static boolean storageKnown() {
		return storageApi;
	}

	/** How many of an item you have in total. */
	static long have(String id) {
		return inventory.getOrDefault(id, 0L) + stored.getOrDefault(id, 0L);
	}

	/** All of it (for tests and the build): id → count. */
	static Map<String, Long> all() {
		Map<String, Long> m = new HashMap<>(stored);
		inventory.forEach((k, v) -> m.merge(k, v, Long::sum));
		return m;
	}

	static void reset() {
		inventory = Map.of();
		stored = Map.of();
		storageApi = false;
		sacksKnown = false;
	}

	/** Client thread, every 10 s: count what's in your inventory right now. */
	static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Map<String, Long> m = new HashMap<>();
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st == null || st.isEmpty()) continue;
			String id = ItemKeys.keyFor(st);
			if (id != null) m.merge(id, (long) st.getCount(), Long::sum);
		}
		inventory = m;
	}

	/** Your member entry from the profiles API: sacks always, storage when the Inventory API is on. */
	static void fromProfile(JsonObject member) {
		if (member == null) return;
		Map<String, Long> m = new HashMap<>();
		JsonObject inv = HudFormatter.obj(member, "inventory");
		JsonObject sacks = inv != null && inv.has("sacks_counts") ? HudFormatter.obj(inv, "sacks_counts") : HudFormatter.obj(member, "sacks_counts");
		if (sacks != null) {
			for (Map.Entry<String, JsonElement> e : sacks.entrySet())
				if (e.getValue().isJsonPrimitive() && e.getValue().getAsLong() > 0) m.merge(e.getKey(), e.getValue().getAsLong(), Long::sum);
			sacksKnown = true;
		}
		boolean storage = false;
		if (inv != null) {
			// not inv_contents: your inventory is read live from the game instead (the profile copy is minutes old)
			for (String k : new String[]{"ender_chest_contents", "personal_vault_contents"}) storage |= blob(HudFormatter.obj(inv, k), m);
			JsonObject bp = HudFormatter.obj(inv, "backpack_contents");
			if (bp != null) for (Map.Entry<String, JsonElement> e : bp.entrySet())
				if (e.getValue().isJsonObject()) storage |= blob(e.getValue().getAsJsonObject(), m);
			JsonObject bags = HudFormatter.obj(inv, "bag_contents");
			if (bags != null) for (String k : new String[]{"fishing_bag", "quiver"}) blob(HudFormatter.obj(bags, k), m);
		}
		stored = m;
		storageApi = storage;
	}

	private static boolean blob(JsonObject o, Map<String, Long> into) {
		String data = HudFormatter.str(o, "data");
		if (data == null || data.isEmpty()) return false;
		try {
			AhScanner.countItems(data, into);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
