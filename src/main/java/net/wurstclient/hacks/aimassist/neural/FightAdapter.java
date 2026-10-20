/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.SplittableRandom;

/**
 * Learning in the middle of a fight, every exchange: the network's biases
 * (the 10 output biases: how readily it walks in, strafes, jumps, sprints,
 * sneaks and swings) get a small offset that is trained from the hits
 * themselves.
 *
 * <p>
 * Each test plays two exchanges with the offset nudged one way and two with
 * it nudged the opposite way (antithetic finite differences, i.e.
 * evolution strategies with one pair); the hits landed (+1, a crit +1.5)
 * minus the hits taken (-1) of the two halves decide which way the offset
 * moves. So within a fight it drifts toward what works against this
 * opponent, and it's kept per opponent for the next fight.
 *
 * <p>
 * The same adapter plays in every simulated fight of the training (both
 * sides), so the evolved weights are the ones that work best <i>with</i>
 * it. No allocation per decision; an update is ~18 multiplications.
 * One adapter per fighter, one thread.
 */
public final class FightAdapter
{
	/** Exchanges per half of a test. */
	static final int EXCHANGES = 4;
	/** How far a test nudges the biases. */
	static final float SIGMA = 0.1F;
	/** How far one test moves the offset (at most). */
	static final float LR = 0.1F;
	/** Forgetting per test, so noise doesn't add up. */
	static final float DECAY = 0.995F;
	/** The offset never goes further than this per bias. */
	static final float CAP = 0.6F;
	/**
	 * Only the output layer's biases (the 10 key tendencies): with the
	 * hidden ones too the noise of ~15 hits a fight cost more than it found.
	 */
	static final boolean OUTPUT_ONLY = true;
	
	private final int[] biasIndex;
	private final float[] params;
	private final NeuralNet net;
	private final boolean adapting;
	private float[] base;
	
	private final float[] delta, eps;
	private SplittableRandom random;
	/** 0 = playing +eps, 1 = playing -eps. */
	private int half;
	private int events;
	private double rewardPlus, rewardMinus;
	private long tests;
	
	public FightAdapter(int[] sizes, float[] base, long seed, boolean adapting)
	{
		int first = OUTPUT_ONLY ? sizes.length - 1 : 1;
		int n = 0;
		for(int l = first; l < sizes.length; l++)
			n += sizes[l];
		biasIndex = new int[n];
		int p = 0, k = 0;
		for(int l = 1; l < sizes.length; l++)
		{
			p += sizes[l] * sizes[l - 1];
			for(int o = 0; o < sizes[l]; o++)
				if(l >= first)
					biasIndex[k++] = p + o;
			p += sizes[l];
		}
		
		this.base = base;
		this.adapting = adapting;
		params = base.clone();
		net = new NeuralNet(sizes, params);
		delta = new float[n];
		eps = new float[n];
		restart(seed);
	}
	
	/** A new fight: a new random stream, the offset kept. */
	public void restart(long seed)
	{
		random = new SplittableRandom(seed);
		half = 0;
		events = 0;
		rewardPlus = rewardMinus = 0;
		drawEps();
		apply();
	}
	
	/** The weights to adapt from (a tuned or newer net). */
	public void setBase(float[] base)
	{
		if(base == this.base)
			return;
		if(base.length != params.length)
			throw new IllegalArgumentException("wrong size");
		this.base = base;
		apply();
	}
	
	/** Runs the adapted network; the result is valid until the next call. */
	public float[] forward(float[] input)
	{
		return net.forward(input);
	}
	
	/**
	 * One exchange's result for us: +1 our hit (1.5 a crit), -1 theirs, or
	 * anything in between.
	 */
	public void reward(double r)
	{
		if(!adapting)
			return;
		if(half == 0)
			rewardPlus += r;
		else
			rewardMinus += r;
		if(++events < EXCHANGES)
			return;
		events = 0;
		if(half == 0)
		{
			half = 1;
			apply();
			return;
		}
		
		// -1..1: which half did better, by how much
		double diff = (rewardPlus - rewardMinus) / (2.5 * EXCHANGES);
		diff = Math.max(-1, Math.min(1, diff));
		for(int k = 0; k < delta.length; k++)
		{
			float d = DECAY * delta[k] + (float)(LR * diff) * eps[k];
			delta[k] = Math.max(-CAP, Math.min(CAP, d));
		}
		tests++;
		half = 0;
		rewardPlus = rewardMinus = 0;
		drawEps();
		apply();
	}
	
	/** What has been learned (a copy). */
	public float[] getDelta()
	{
		return delta.clone();
	}
	
	/** Takes what was learned earlier (null or the wrong size: nothing). */
	public void setDelta(float[] d)
	{
		if(d == null || d.length != delta.length)
			return;
		for(int k = 0; k < delta.length; k++)
			delta[k] = Math.max(-CAP, Math.min(CAP, d[k]));
		apply();
	}
	
	public int deltaSize()
	{
		return delta.length;
	}
	
	/** Finished tests (each one = an update of the offset). */
	public long getTests()
	{
		return tests;
	}
	
	private void drawEps()
	{
		if(!adapting)
			return;
		for(int k = 0; k < eps.length; k++)
		{
			// +-1 per bias (Rademacher): same size every time
			eps[k] = random.nextBoolean() ? 1 : -1;
		}
	}
	
	private void apply()
	{
		System.arraycopy(base, 0, params, 0, params.length);
		if(!adapting)
			return;
		float s = half == 0 ? SIGMA : -SIGMA;
		for(int k = 0; k < biasIndex.length; k++)
			params[biasIndex[k]] += delta[k] + s * eps[k];
	}
}
