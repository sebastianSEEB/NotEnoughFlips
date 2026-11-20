package dev.notenoughflips;

import java.lang.reflect.Field;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Where a container screen's background is drawn: {left, top, width, height}. Reads the screen's own
 * leftPos/topPos/imageWidth/imageHeight when they can be found by name (26.x is unobfuscated; the names
 * after them are 1.21.11's intermediary names), so it follows the recipe book shifting the inventory.
 * Otherwise uses the vanilla layout (inventory 176×166, chest 176×(114 + rows×18), centred).
 */
final class GuiPos {
	static volatile int mouseX = -1, mouseY = -1;   // last mouse position in GUI coordinates (set while a menu renders)

	private static final String[][] NAMES = {
			{"leftPos", "field_2776"}, {"topPos", "field_2800"}, {"imageWidth", "field_2792"}, {"imageHeight", "field_2779"}};
	private static Field[] fields;
	private static boolean tried;

	private GuiPos() {}

	private static Field find(String[] names) {
		for (String n : names) {
			try {
				Field f = AbstractContainerScreen.class.getDeclaredField(n);
				if (f.getType() == int.class) {
					f.setAccessible(true);
					return f;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// try the next name
			}
		}
		return null;
	}

	static int[] box(Screen s) {
		if (!tried) {
			tried = true;
			Field[] fs = new Field[4];
			boolean ok = true;
			for (int i = 0; i < 4; i++) {
				fs[i] = find(NAMES[i]);
				ok &= fs[i] != null;
			}
			fields = ok ? fs : null;
		}
		if (fields != null && s instanceof AbstractContainerScreen<?>) {
			try {
				int l = fields[0].getInt(s), t = fields[1].getInt(s), w = fields[2].getInt(s), h = fields[3].getInt(s);
				if (w > 0 && h > 0) return new int[]{l, t, w, h};
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// fall back to the vanilla layout
			}
		}
		int w = 176, h = 166;
		if (s instanceof AbstractContainerScreen<?> acs && acs.getMenu() instanceof ChestMenu chest) h = 114 + chest.getRowCount() * 18;
		return new int[]{(s.width - w) / 2, (s.height - h) / 2, w, h};
	}
}
