package dev.notenoughflips;

/**
 * The only commands the mod ever sends, each after a click of yours (or a snipe alert with auto-open on).
 * Arguments are checked so nothing read from the network (auction lists, the data site) can turn into another command.
 */
final class Cmd {
	private Cmd() {}

	static void viewAuction(String uuid) {
		if (uuid == null) return;
		String u = uuid.replace("-", "");
		if (u.matches("[0-9a-fA-F]{32}")) Chat.command("viewauction " + u);
	}

	/** /ahs <name>: Hypixel's auction search (same character rules as /bz). */
	static void ahSearch(String search) {
		if (search == null) return;
		String s = search.replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
		if (!s.isEmpty() && s.length() <= 40) Chat.command("ahs " + s);
	}

	/** Open where an ingredient is bought: the Bazaar for bazaar items, the AH search for lowest-BIN ones. */
	static void buyIngredient(String how, String name) {
		if (how != null && how.startsWith("lowest BIN")) ahSearch(name);
		else if (how == null || how.startsWith("bazaar")) bazaar(name);
	}

	static void bazaar(String search) {
		if (search == null) return;
		String s = search.replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
		if (!s.isEmpty() && s.length() <= 40) Chat.command("bz " + s);
	}
}
