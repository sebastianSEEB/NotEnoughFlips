package dev.notenoughflips;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-side chat message, Minecraft 26.x version. */
final class Chat {
	private Chat() {}

	static void say(Component c) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(c);
		});
	}

	/** Short UI "ping" for alerts (item sold, outbid, order filled). Never throws. */
	static void ping() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
						net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 1.0F));
			} catch (RuntimeException ignored) {
				// no sound is fine
			}
		});
	}

	/** Snipe countdown: a short tick, or a high "go" when the BIN becomes buyable (next frame; NefConfig.snipeBeatLeadMs covers it). */
	static void beat(boolean go) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
						go ? net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING : net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT, go ? 2.0F : 1.0F));
			} catch (RuntimeException ignored) {
				// no sound is fine
			}
		});
	}

	/** Runs a server command the player asked for (e.g. "viewauction <id>"), without the leading slash. */
	static void command(String cmd) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.connection.sendCommand(cmd);
		});
	}
}
