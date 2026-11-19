package dev.notenoughflips;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/** Keys (Options → Controls → Key Binds, under Miscellaneous). Fabric API: KeyMappingHelper in this Minecraft version. */
final class Keys {
	private static KeyMapping ordersHud;

	private Keys() {}

	static void register() {
		ordersHud = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.notenoughflips.orders_hud", InputConstants.Type.KEYSYM,
				InputConstants.KEY_O, KeyMapping.Category.MISC));
	}

	static void tick(NefConfig cfg) {
		if (ordersHud == null) return;
		while (ordersHud.consumeClick()) {
			cfg.bzHud = !cfg.bzHud;
			cfg.save();
		}
	}
}
