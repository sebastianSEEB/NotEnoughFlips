package dev.notenoughflips;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Personal files are kept per Minecraft account, so two accounts on the same PC never mix their trades, purse,
 * coins history, watched auctions or bazaar orders:
 *
 *   .minecraft/config/notenoughflips/accounts/<uuid>/trades.csv, purse.csv, coins-history.csv, watch.json, bz-orders.json
 *
 * Shared market data (lowest BINs, AH market size) stays in .minecraft/config/notenoughflips/.
 * Before you join a world the account is not known yet; files then go to accounts/default.
 */
final class Accounts {
	private static volatile String current;

	private Accounts() {}

	/** The current account's UUID (32 hex), or "default" before the player is known. */
	static String id() {
		String c = current;
		return c == null ? "default" : c;
	}

	/** File of the current account (folders are created on demand). */
	static Path file(String name) {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("notenoughflips").resolve("accounts").resolve(id());
		try {
			Files.createDirectories(dir);
		} catch (Exception ignored) {
			// writing will fail later and be ignored, like before
		}
		return dir.resolve(name);
	}

	/** Shared (not personal) data file. */
	static Path shared(String name) {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("notenoughflips");
		try {
			Files.createDirectories(dir);
		} catch (Exception ignored) {
			// see above
		}
		return dir.resolve(name);
	}

	/** Called every tick: when another account is playing, every personal store reloads from that account's files. */
	static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		UUID u = mc.player.getUUID();
		String id = u.toString().replace("-", "").toLowerCase(Locale.ROOT);
		if (id.equals(current)) return;
		current = id;
		PurseLog.reset();
		ProfitLog.reset();
		Watch.reload();
		BzOrders.reload();
		MyWallet.reset();
		AhScanner.reset();
	}
}
