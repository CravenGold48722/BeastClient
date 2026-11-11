/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.Future;

import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/**
 * Teaches the shipped net the whole kit ({@code NeuralTrainer teach}): it
 * keeps its own moves exactly - only the last layer's item outputs are
 * fitted, on the features its hidden layers already compute - and learns
 * when to use each item from {@link #items}, DAgger style. The result becomes
 * the
 * training's starting point (the checkpoint ContinuousTrainer continues
 * from); like any candidate it's only promoted once it beats the champion.
 */
final class ItemTeacher
{
	static final int ROUNDS = 4, GAMES = 400;
	/** The item outputs to teach (NeuralAction's indexes), null = all. */
	static java.util.Set<Integer> ONLY;
	static final java.util.Map<String, Integer> OUTPUT_OF =
		java.util.Map.of("web", 10, "shield", 11, "pot", 12, "eat", 13, "water",
			14, "pearlIn", 15, "pearlOut", 16, "windJump", 17, "windHit", 18);
	
	static boolean taught(int output)
	{
		return ONLY == null ? output != 11 : ONLY.contains(output);
	}
	
	/** When a player who knows the kit uses each item (from what it sees). */
	static void items(NeuralObservation o, NeuralAction t)
	{
		// water only off the ground: there a sword takes 40 ticks
		// (Player.getDestroySpeed / 5), water ~7; on the ground the sword's
		// 8 beat the water's wait and pickup
		t.water = o.myInWeb && !o.myGround && o.waterLeft > 0;
		// buffs (Strength running low takes the others along) away from
		// their sword
		t.pot = o.potsLeft > 0 && o.myStrength < 0.11 && o.dist > 3.5;
		// eat when low with no absorption left, out of their reach; finish
		// what you started
		t.eat = o.gapplesLeft > 0 && (o.myEating > 0
			|| o.myHealth < 0.5 && o.absorption == 0 && o.dist > 4.5);
		// low and they come for you: a web at their feet, to get away and
		// eat (each item restarts the sword's charge, so not mid-fight)
		t.web = o.websLeft > 0 && !o.opInWeb && o.opGround && o.myHealth < 0.5
			&& o.dist > 2 && o.dist < 4.5 && o.opAlong > 0;
		// the shield when their hit is coming and yours isn't ready
		t.shield =
			o.hasShield && o.myShieldCooldown == 0 && o.myChargeTicks >= 7
				&& o.opChargeTicks <= 2 && o.theirReach < PvpSim.REACH + 0.5;
		boolean pearl =
			o.pearlsLeft > 0 && o.pearlCooldown == 0 && !o.pearlFlying;
		// after a weaker one who runs; away when losing, to eat
		t.pearlIn = pearl && o.dist > 12 && o.opHealth <= o.myHealth;
		t.pearlOut = pearl && o.myHealth < 0.3 && !o.opInWeb
			&& o.opHealth > o.myHealth + 0.2 && o.dist < 4 && o.gapplesLeft > 0;
		boolean wind = o.windLeft > 0 && o.windCooldown == 0;
		// up over one stuck in a web, charged, for a crit on the way down
		t.windJump = wind && o.opInWeb && o.myGround && o.dist < 3.5
			&& o.myChargeTicks == 0;
		// push them off when low and they're about to hit
		t.windHit = wind && !t.windJump && o.myHealth < 0.4 && o.dist < 3
			&& o.opChargeTicks == 0;
	}
	
	/** Plays the net's moves (or the student's) and records the labels. */
	static final class Teaching implements Policy
	{
		final OnlineLearner.NetPolicy original, student;
		final double beta;
		final NeuralTrainer.Dataset data;
		final NeuralAction moves = new NeuralAction(),
			teach = new NeuralAction(), rules = new NeuralAction();
		SplittableRandom random;
		
		Teaching(int[] sizes, float[] original, float[] student, double beta,
			NeuralTrainer.Dataset data)
		{
			this.original = new OnlineLearner.NetPolicy(sizes, original, false);
			this.student = student == null ? null
				: new OnlineLearner.NetPolicy(sizes, student, false);
			this.beta = beta;
			this.data = data;
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			original.reset(random.split());
			if(student != null)
				student.reset(random.split());
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			original.act(me, o, moves);
			teach.set(moves);
			rules.set(moves);
			items(o, rules);
			// only the taught items follow the rules
			if(taught(10))
				teach.web = rules.web;
			if(taught(11))
				teach.shield = rules.shield;
			if(taught(12))
				teach.pot = rules.pot;
			if(taught(13))
				teach.eat = rules.eat;
			if(taught(14))
				teach.water = rules.water;
			if(taught(15))
				teach.pearlIn = rules.pearlIn;
			if(taught(16))
				teach.pearlOut = rules.pearlOut;
			if(taught(17))
				teach.windJump = rules.windJump;
			if(taught(18))
				teach.windHit = rules.windHit;
			if(data != null && random.nextInt(2) == 0)
			{
				float[] x = new float[NeuralObservation.SIZE];
				o.encode(x);
				data.add(x, labels(teach));
			}
			if(student == null || random.nextDouble() < beta)
				out.set(teach);
			else
				student.act(me, o, out);
		}
	}
	
	/** First output of the items (web), as in NeuralAction. */
	static final int FIRST_ITEM = 10;
	/** A missed item counts this many times a stray one (Backprop's). */
	static final float POS_WEIGHT = 3;
	
	/**
	 * Logistic regression of each item output on the last hidden layer
	 * (frozen), from where it is now: the moves don't change at all.
	 */
	static void fitItemOutputs(int[] sizes, float[] params,
		NeuralTrainer.Dataset data)
	{
		int last = sizes.length - 1, nIn = sizes[last - 1], n = data.size;
		int[] off = Backprop.offsets(sizes);
		float[][] z = new float[sizes.length][], a = new float[sizes.length][];
		for(int l = 0; l < sizes.length; l++)
		{
			z[l] = new float[sizes[l]];
			a[l] = new float[sizes[l]];
		}
		float[][] h = new float[n][];
		for(int k = 0; k < n; k++)
		{
			Backprop.forward(sizes, params, off, data.x[k], z, a);
			h[k] = a[last - 1].clone();
		}
		
		Random random = new Random(11);
		int[] order = new int[n];
		for(int i = 0; i < n; i++)
			order[i] = i;
		for(int out = FIRST_ITEM; out < FIRST_ITEM + 9; out++)
		{
			if(!taught(out))
				continue;
			int label = 6 + out - FIRST_ITEM;
			int row = off[last] + out * nIn;
			int bias = off[last] + nIn * sizes[last] + out;
			float[] w = new float[nIn + 1], m = new float[nIn + 1],
				v = new float[nIn + 1], g = new float[nIn + 1];
			System.arraycopy(params, row, w, 0, nIn);
			w[nIn] = params[bias];
			int step = 0, batch = 256;
			for(int epoch = 0; epoch < 6; epoch++)
			{
				for(int i = n - 1; i > 0; i--)
				{
					int j = random.nextInt(i + 1), t = order[i];
					order[i] = order[j];
					order[j] = t;
				}
				for(int from = 0; from < n; from += batch)
				{
					Arrays.fill(g, 0);
					int to = Math.min(n, from + batch);
					for(int q = from; q < to; q++)
					{
						int k = order[q];
						byte y = data.y[k][label];
						if(y < 0)
							continue;
						float sum = w[nIn];
						for(int i = 0; i < nIn; i++)
							sum += w[i] * h[k][i];
						float p = 1 / (1 + (float)Math.exp(-sum));
						float d = y == 1 ? POS_WEIGHT * (p - 1) : p;
						for(int i = 0; i < nIn; i++)
							g[i] += d * h[k][i];
						g[nIn] += d;
					}
					// Adam
					step++;
					float lr = 0.01F, b1 = 0.9F, b2 = 0.999F;
					double c1 = 1 - Math.pow(b1, step),
						c2 = 1 - Math.pow(b2, step);
					for(int i = 0; i <= nIn; i++)
					{
						float gi = g[i] / (to - from);
						m[i] = b1 * m[i] + (1 - b1) * gi;
						v[i] = b2 * v[i] + (1 - b2) * gi * gi;
						w[i] -= (float)(lr * (m[i] / c1)
							/ (Math.sqrt(v[i] / c2) + 1e-8));
					}
				}
			}
			System.arraycopy(w, 0, params, row, nIn);
			params[bias] = w[nIn];
		}
	}
	
	static byte[] labels(NeuralAction a)
	{
		return new byte[]{(byte)(1 - a.forward), (byte)(1 - a.strafe),
			b(a.jump), b(a.sprint), b(a.sneak), b(a.attack), b(a.web),
			b(a.shield), b(a.pot), b(a.eat), b(a.water), b(a.pearlIn),
			b(a.pearlOut), b(a.windJump), b(a.windHit)};
	}
	
	private static byte b(boolean v)
	{
		return (byte)(v ? 1 : 0);
	}
	
	/** args: teach, the neural folder. */
	static void teach(String[] args) throws Exception
	{
		Path folder = Path.of(args[1]);
		// teach water,web,...: only those (else all)
		if(args.length > 2)
		{
			ONLY = new java.util.HashSet<>();
			for(String item : args[2].split(","))
				ONLY.add(OUTPUT_OF.get(item));
		}
		NeuralPolicy shipped = NeuralPolicy.get();
		if(shipped == null)
		{
			System.out.println("No shipped net to teach");
			System.exit(2);
		}
		int[] sizes = shipped.getSizes();
		int shippedHash = Arrays.hashCode(shipped.getParams());
		Path checkpoint = folder.resolve("trainer")
			.resolve("checkpoint_" + Integer.toHexString(shippedHash) + ".bin");
		// from where training is (its checkpoint), else the shipped net
		float[] start = shipped.getParams();
		try(java.io.InputStream in =
			java.nio.file.Files.newInputStream(checkpoint))
		{
			NeuralNet net = NeuralNet.read(in);
			if(Arrays.equals(net.getSizes(), sizes))
			{
				start = net.getParams();
				System.out.println("Teaching the training's checkpoint");
			}
		}catch(java.io.IOException e)
		{
			// none: the shipped net
		}
		final float[] original = start;
		float[] params = original.clone();
		NeuralTrainer.Dataset data = new NeuralTrainer.Dataset();
		for(int round = 0; round < ROUNDS; round++)
		{
			double beta = round == 0 ? 1 : Math.pow(0.5, round);
			float[] snapshot = round == 0 ? null : params.clone();
			List<Future<?>> jobs = new ArrayList<>();
			for(int g = 0; g < GAMES; g++)
			{
				final int game = g, r = round;
				jobs.add(NeuralTrainer.POOL.submit(() -> {
					Teaching me =
						new Teaching(sizes, original, snapshot, beta, data);
					// the scripted players, and the net itself
					int k = ScriptedPolicies.ALL.length;
					Policy opp = game % (k + 1) == k
						? new OnlineLearner.NetPolicy(sizes, original, false)
						: ScriptedPolicies.ALL[game % (k + 1)].get();
					PvpSim sim = new PvpSim(7_000_003L * r + game, me, opp,
						NeuralTrainer.MAX_TICKS);
					sim.a.onlyGoodHits = true;
					sim.run();
				}));
			}
			for(Future<?> f : jobs)
				f.get();
			fitItemOutputs(sizes, params, data);
			System.out.printf("Teaching round %d (beta %.2f): %d samples, %s%n",
				round, beta, data.size,
				Backprop.accuracy(sizes, params, data.x, data.y, data.size));
		}
		
		System.out.printf(
			"Teacher (the net's moves + the item rules) vs the"
				+ " untaught net: %.0f%%%n",
			teacherVsOriginal(sizes, original) * 100);
		System.out.println("Per fight: " + usage(sizes, params));
		double[] rates =
			NeuralTrainer.benchmarkRates(sizes, params, original, 400, 0x7EAC);
		System.out.println("Taught: "
			+ Arrays.toString(Arrays.stream(rates)
				.mapToObj(v -> Math.round(v * 100) + "%").toArray())
			+ " (the scripted players, then the untaught net)");
		
		NeuralTrainer.save(params, sizes, checkpoint);
		System.out.println("Saved as the training's starting point: "
			+ checkpoint.getFileName());
	}
	
	/** The rules themselves against the untaught net (a draw = half). */
	private static double teacherVsOriginal(int[] sizes, float[] original)
	{
		double won = 0;
		int games = 200;
		for(int g = 0; g < games; g++)
		{
			PvpSim sim = new PvpSim(55_000 + g,
				new Teaching(sizes, original, null, 1, null),
				new OnlineLearner.NetPolicy(sizes, original, false),
				NeuralTrainer.MAX_TICKS);
			sim.a.onlyGoodHits = sim.b.onlyGoodHits = true;
			int r = sim.outcome(sim.run());
			won += r > 0 ? 1 : r == 0 ? 0.5 : 0;
		}
		return won / games;
	}
	
	/**
	 * How the net uses its items, per fight, against the scripted players
	 * and itself: webs placed, seconds stuck in webs, webs washed away with
	 * water, potions, golden apples, pearls, wind charges.
	 */
	static String usage(int[] sizes, float[] params)
	{
		int games = 240, k = ScriptedPolicies.ALL.length;
		long webs = 0, stuck = 0, waters = 0, pots = 0, gapples = 0, pearls = 0,
			winds = 0, blocked = 0;
		for(int g = 0; g < games; g++)
		{
			Policy opp = g % (k + 1) == k
				? new OnlineLearner.NetPolicy(sizes, params, false)
				: ScriptedPolicies.ALL[g % (k + 1)].get();
			PvpSim sim = new PvpSim(99_000 + g,
				new OnlineLearner.NetPolicy(sizes, params, false), opp,
				NeuralTrainer.MAX_TICKS);
			sim.a.onlyGoodHits = true;
			sim.b.onlyGoodHits = g % (k + 1) == k;
			sim.run();
			SimFighter a = sim.a;
			webs += a.websPlaced;
			stuck += a.stuckTotal;
			waters += a.washes;
			pots += a.potsUsed;
			gapples += a.gapplesEaten;
			pearls += PvpSim.KIT_PEARLS - a.pearls;
			winds += PvpSim.KIT_WIND - a.winds;
			blocked += a.blocked;
		}
		double n = games;
		return String.format(
			"webs %.1f, stuck in webs %.1f s, washed out with water %.2f,"
				+ " potions %.1f, golden apples %.1f, pearls %.1f,"
				+ " wind charges %.1f, hits blocked with the shield %.1f",
			webs / n, stuck / n / 20, waters / n, pots / n, gapples / n,
			pearls / n, winds / n, blocked / n);
	}
}
