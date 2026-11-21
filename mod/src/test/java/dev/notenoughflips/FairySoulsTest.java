package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

/** Fairy Soul markers: positions ship with the mod, land in the right place on screen, and the clicked one goes. */
class FairySoulsTest {

	private static final double[] CAM = {0, 0, 0};

	@Test
	void soulStraightAheadIsInTheMiddle() {
		double[] p = FairySouls.project(CAM, 0, 0, 70, 400, 300, 0, 0, 10);   // yaw 0 = facing south (+Z)
		assertEquals(200, p[0], 0.01);
		assertEquals(150, p[1], 0.01);
		assertTrue(p[2] > 0);
	}

	@Test
	void leftRightUpDownAndBehind() {
		// facing south, west (-X) is on your right
		assertTrue(FairySouls.project(CAM, 0, 0, 70, 400, 300, -5, 0, 10)[0] > 200);
		assertTrue(FairySouls.project(CAM, 0, 0, 70, 400, 300, 5, 0, 10)[0] < 200);
		assertTrue(FairySouls.project(CAM, 0, 0, 70, 400, 300, 0, 5, 10)[1] < 150, "higher up = higher on screen");
		assertTrue(FairySouls.project(CAM, 0, 0, 70, 400, 300, 0, 0, -10)[2] < 0, "behind you");
		// facing west (yaw 90): a soul to the west is straight ahead
		double[] w = FairySouls.project(CAM, 90, 0, 70, 400, 300, -10, 0, 0);
		assertEquals(200, w[0], 0.01);
		// looking straight down: a soul below is in the middle
		double[] d = FairySouls.project(CAM, 0, 90, 70, 400, 300, 0, -10, 0);
		assertEquals(200, d[0], 0.01);
		assertEquals(150, d[1], 0.01);
	}

	@Test
	void theSoulYouClickedIsTheNearestOneInReach() {
		List<int[]> pts = List.of(new int[]{0, 70, 0}, new int[]{3, 71, 2}, new int[]{40, 70, 40});
		int[] n = FairySouls.nearest(pts, 3, 71, 3, 10);
		assertEquals(3, n[0]);
		assertEquals(null, FairySouls.nearest(pts, 200, 70, 200, 10), "no soul in reach: nothing removed");
	}

	@Test
	void tabListAreasMapToIslands() {
		assertEquals("combat_1", FairySouls.islandFor("Spider's Den"));
		assertEquals("farming_1", FairySouls.islandFor(" The Barn"));
		assertEquals("mining_3", FairySouls.islandFor("Dwarven Mines"));
		assertEquals("hub", FairySouls.islandFor("Hub"));
	}

	@Test
	void shippedSoulTableCoversTheMainIslands() throws Exception {
		try (InputStream in = FairySouls.class.getResourceAsStream("/assets/notenoughflips/fairy-souls.json")) {
			assertTrue(in != null, "the Fairy Soul table must ship inside the mod");
			Map<String, List<int[]>> m = FairySouls.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
			assertTrue(m.get("hub").size() >= 70);
			for (String k : new String[]{"combat_1", "combat_3", "crimson_isle", "foraging_1", "farming_1", "mining_1", "mining_2", "mining_3",
					"winter", "dungeon_hub"})
				assertTrue(m.containsKey(k) && !m.get(k).isEmpty(), k);
		}
	}
}
