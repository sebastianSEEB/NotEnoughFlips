package dev.notenoughflips;

import java.util.function.BiConsumer;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Draw on top of a screen after it renders (GuiGraphicsExtractor version). */
final class RenderHook {
	private RenderHook() {}

	static void register(Screen screen, BiConsumer<Screen, Highlight.Painter> drawer) {
		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> {
			GuiPos.mouseX = mouseX;
			GuiPos.mouseY = mouseY;
			drawer.accept(s, new Highlight.Painter() {
					@Override
					public void fill(int x1, int y1, int x2, int y2, int argb) {
						graphics.fill(x1, y1, x2, y2, argb);
					}

					@Override
					public void text(String text, int x, int y, int argb) {
						graphics.text(Minecraft.getInstance().font, Component.literal(text), x, y, argb, false);
					}
				});
		});
	}
}
