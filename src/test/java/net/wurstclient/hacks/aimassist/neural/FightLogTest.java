/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A recorded fight comes back as it was written, and the trainer learns
 * from it: a copy of the player for the league, and a nudge toward the
 * moves that paid off.
 */
public class FightLogTest
{
	@Test
	public void writesAndReadsAFight(@TempDir Path dir)
	{
		UUID them = new UUID(1, 2);
		write(dir, them, 300, 1);
		List<FightLog.Fight> fights = FightLog.readAll(dir);
		assertEquals(1, fights.size());
		FightLog.Fight f = fights.get(0);
		assertEquals(them, f.opponent);
		assertEquals("Some one", f.name);
		assertEquals(1, f.result);
		assertEquals(300, f.ours.size());
		assertEquals(300, f.theirs.size());
		FightLog.Row r = f.ours.get(10);
		assertEquals(10, r.tick);
		// 16-bit floats: 0.25 steps are exact
		assertEquals(0.25F * (10 % 8), r.x[3]);
		assertTrue(r.human);
		assertEquals(10 / 20F, r.taken);
	}
	
	/**
	 * The game closed mid-fight (no end, the file never closed): what was
	 * flushed counts. Before the flushes such a file was 10 bytes.
	 */
	@Test
	public void aFightCutOffIsStillRead(@TempDir Path dir)
	{
		FightLog log = new FightLog(dir);
		log.begin(new UUID(5, 6), "Some one");
		float[] x = new float[NeuralObservation.SIZE];
		byte[] y = new byte[Backprop.LABELS];
		for(int t = 0; t < 250; t++)
		{
			log.ours(t, x, y, true, 0, 0);
			log.theirs(t, x, y);
		}
		List<FightLog.Fight> fights = FightLog.readAll(dir);
		assertEquals(1, fights.size());
		// flushed every 100 ticks
		assertEquals(200, fights.get(0).ours.size());
		assertEquals(0, fights.get(0).result);
		// (closed for @TempDir's cleanup)
		log.end(0);
	}
	
	@Test
	public void aShortFightIsNotKept(@TempDir Path dir)
	{
		write(dir, new UUID(3, 4), 20, 0);
		assertTrue(FightLog.readAll(dir).isEmpty());
	}
	
	@Test
	public void theTrainerLearnsFromFights(@TempDir Path dir)
	{
		write(dir.resolve("fights"), new UUID(5, 6), 600, -1);
		int[] sizes = {NeuralObservation.SIZE, 8, NeuralAction.OUTPUTS};
		float[] theta = new float[NeuralNet.paramCount(sizes)];
		Random rnd = new Random(1);
		for(int i = 0; i < theta.length; i++)
			theta[i] = (float)(rnd.nextGaussian() * 0.1);
		float[] before = theta.clone();
		float[] nudged =
			ContinuousTrainer.learnFromFights(dir, sizes, theta, 42);
		assertArrayEquals(before, theta, "theta itself is never changed");
		assertNotSame(theta, nudged);
		assertFalse(Arrays.equals(theta, nudged));
		// 600 of their samples: one copy
		assertEquals(1, NeuralTrainer.REAL.size());
		NeuralTrainer.REAL = List.of();
	}
	
	@Test
	public void movesThatPaidOffCountMore()
	{
		java.util.Map<Integer, FightLog.Row> at = new java.util.HashMap<>();
		for(int t = 0; t < 30; t++)
			// a hit of 5 at tick 15
			at.put(t, row(t, t >= 15 ? 5 : 0, 0));
		double good = ContinuousTrainer.advantage(at, 8, true);
		double later = ContinuousTrainer.advantage(at, 20, true);
		assertEquals(5, good, 1e-6);
		assertEquals(0, later, 1e-6);
		// for them the same hit was taken
		assertEquals(-ContinuousTrainer.ADV_TAKEN * 5,
			ContinuousTrainer.advantage(at, 8, false), 1e-6);
		assertTrue(ContinuousTrainer.weight(good) > 10);
		assertTrue(ContinuousTrainer.weight(-10) < 0.01);
	}
	
	private static FightLog.Row row(int tick, float dealt, float taken)
	{
		return new FightLog.Row(tick, new float[1], new byte[1], true, dealt,
			taken);
	}
	
	private static void write(Path dir, UUID them, int ticks, int result)
	{
		FightLog log = new FightLog(dir);
		log.begin(them, "Some one");
		float[] x = new float[NeuralObservation.SIZE];
		for(int t = 0; t < ticks; t++)
		{
			x[3] = 0.25F * (t % 8);
			byte[] y = new byte[Backprop.LABELS];
			Arrays.fill(y, (byte)-1);
			y[0] = (byte)(t % 3);
			y[5] = (byte)(t % 12 == 0 ? 1 : 0);
			log.ours(t, x, y, true, t / 30F, t / 20F);
			log.theirs(t, x, y);
		}
		log.end(result);
	}
}
