/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.Arrays;
import java.util.Random;

/**
 * What happened in a fight, read with learned attention - a small version of
 * what ECHO (minecraftcombatai's V29, a 96-tick transformer) does. Every tick
 * the network's first hidden layer (its own summary of what it saw) is
 * added up, and every {@link #STEP} ticks their average is turned into a
 * query, a key and a value per head ({@link #HEADS} heads, learned
 * projections); the keys and values of the last {@link #TICKS} steps are
 * kept, and each head's softmax over (query . key + a learned weight for the
 * step's age) mixes their values into the next tick's
 * {@link NeuralObservation#context}, which the ticks in between reuse
 * (user, 2026-10-28: half the work - training ran 3.5x slower with it).
 * The same heads read a second ring, one average a second for the last
 * {@link #SECONDS} seconds (ECHO's makers speak of a two-minute context),
 * with ages of their own, into {@link NeuralObservation#memoryLong}.
 *
 * <p>
 * Its {@link #PARAMS} weights are the last part of the net's parameters
 * (NeuralNet.paramCount): the evolution trains them with everything else
 * (user, 2026-10-27: "add a transformer 96 tick thing to our current and keep
 * going"); Backprop leaves them be. A full transformer like ECHO's (2.3M
 * weights) is more than evolution can train. No allocation per tick.
 */
public final class NeuralHistory
{
	/** Ticks averaged into one stored step (and between two reads). */
	public static final int STEP = 2;
	/** Steps remembered (96 like ECHO's ticks: 9.6 s). */
	public static final int TICKS = 96;
	/** Seconds remembered in the long ring (2 minutes). */
	public static final int SECONDS = 120;
	/** The first hidden layer's features read per tick. */
	public static final int IN = 48;
	/** Attention heads, each with its own query/key/value projections. */
	public static final int HEADS = 3;
	/** Per head: query/key and value size. */
	public static final int DK = 16, DV = 16;
	/** What it gives the next tick, per ring: every head's value mix. */
	public static final int WIDTH = HEADS * DV;
	
	/**
	 * Offsets into its weights (after the net's layers). The query, key and
	 * value rows follow each other, so one projection makes all three.
	 */
	static final int WQ = 0, WK = WQ + HEADS * DK * IN,
		WV = WK + HEADS * DK * IN, POS = WV + HEADS * DV * IN,
		POS_LONG = POS + HEADS * TICKS;
	/** Its weights: three projections and the learned ages. */
	public static final int PARAMS = POS_LONG + HEADS * SECONDS;
	static final float SCALE = (float)(1 / Math.sqrt(DK));
	
	/**
	 * Starting weights, the same every time: small random projections, and
	 * ages that prefer the newest ticks a little (like the pool it replaced).
	 */
	public static float[] initialParams()
	{
		float[] p = new float[PARAMS];
		Random r = new Random(0xA77E);
		for(int i = 0; i < POS; i++)
			p[i] = (float)(r.nextGaussian() * 0.15);
		for(int h = 0; h < HEADS; h++)
		{
			for(int age = 0; age < TICKS; age++)
				p[POS + h * TICKS + age] = -0.02F * age;
			for(int age = 0; age < SECONDS; age++)
				p[POS_LONG + h * SECONDS + age] = -0.01F * age;
		}
		return p;
	}
	
	/**
	 * The loops the trainer runs with SIMD (VectorKernel; the game's JVM has
	 * no Vector API). All element by element - every output's sum is added
	 * in the same order, a multiply then an add - so it must give exactly
	 * what the plain loops give.
	 */
	public interface Ops
	{
		/**
		 * out[r] = sum over c (in order) of p[off + r * cols + c] * x[c],
		 * for r < out.length. key: the array whose weights these are (for a
		 * transposed copy).
		 */
		void project(float[] key, float[] p, int off, int cols, float[] x,
			float[] out);
		
		/**
		 * score[s] += q[qOff + d] * keys[kRow + d][s], d < DK in order,
		 * from <= s < len (each slot on its own: the same for any range).
		 */
		void dots(float[][] keys, int kRow, float[] q, int qOff, float[] score,
			int from, int len);
		
		/**
		 * score[0..n) = its softmax: e = {@link NeuralHistory#exp}(score -
		 * max), summed in order, times 1 / sum.
		 */
		void softmax(float[] score, int n);
		
		/**
		 * out[h * DV + d] += w[h][age] * values[slot * WIDTH + h * DV + d],
		 * ages 0 to n - 1 in order, slot = head + age (wrapping at len).
		 */
		void mix(float[] out, float[][] w, float[] values, int head, int n,
			int len);
	}
	
	private static volatile Ops ops;
	
	public static void setOps(Ops o)
	{
		ops = o;
	}
	
	// keys feature by feature (keyT[h * DK + d][slot]: the scores of all
	// stored steps build up side by side), values step by step. Written
	// backwards: the newest is at head, the one before at head + 1, ... so
	// a step's age and its learned weight run alongside the slots.
	private final float[][] keyT = new float[HEADS * DK][TICKS],
		slowKeyT = new float[HEADS * DK][SECONDS];
	private final float[] value = new float[TICKS * WIDTH],
		slowValue = new float[SECONDS * WIDTH];
	/** Query, key and value (one projection), then key and value. */
	private final float[] qkv = new float[3 * WIDTH], kv = new float[2 * WIDTH];
	private final float[] in = new float[IN], second = new float[IN],
		step = new float[IN];
	private final float[] bySlot = new float[Math.max(TICKS, SECONDS)];
	/** Per head: the score, then the weight, of every stored step by age. */
	private final float[][] score = new float[HEADS][Math.max(TICKS, SECONDS)];
	private final float[] longPool = new float[WIDTH],
		shortPool = new float[WIDTH];
	private int count, head, slowCount, slowHead, secondTicks, stepTicks;
	private boolean longDue, shortDue;
	/** The weights the last tick was read with. */
	private float[] params;
	private int off;
	
	/** A new fight against someone else: nothing remembered. */
	public void reset()
	{
		resetFight();
		slowCount = slowHead = 0;
		longDue = false;
		Arrays.fill(longPool, 0);
	}
	
	/**
	 * A new fight against the same player (or the mirror image changed):
	 * the last seconds start over, the last minutes stay.
	 */
	public void resetFight()
	{
		count = head = 0;
		secondTicks = stepTicks = 0;
		shortDue = false;
		Arrays.fill(second, 0);
		Arrays.fill(step, 0);
		Arrays.fill(shortPool, 0);
	}
	
	public int size()
	{
		return count;
	}
	
	public int seconds()
	{
		return slowCount;
	}
	
	/**
	 * Writes the attended last ticks into ctx and the attended last
	 * minutes into ctxLong (all zero before anything is stored).
	 */
	public void context(float[] ctx, float[] ctxLong)
	{
		if(shortDue)
		{
			shortDue = false;
			attend(keyT, value, count, head, TICKS, POS, shortPool);
		}
		System.arraycopy(shortPool, 0, ctx, 0, WIDTH);
		if(longDue)
		{
			longDue = false;
			attend(slowKeyT, slowValue, slowCount, slowHead, SECONDS, POS_LONG,
				longPool);
		}
		System.arraycopy(longPool, 0, ctxLong, 0, WIDTH);
	}
	
	private void attend(float[][] keys, float[] values, int n, int head,
		int len, int pos, float[] out)
	{
		Arrays.fill(out, 0);
		if(n == 0 || params == null)
			return;
		Ops o = ops;
		// ages 0 to a - 1 at slots head..., the rest from slot 0 on
		int a = Math.min(n, len - head);
		// until the ring is full, only slots head..len - 1 hold anything (the
		// first 10 s of every fight scored all 96 - 2026-10-30)
		int from = n < len ? head : 0;
		for(int h = 0; h < HEADS; h++)
		{
			float[] score = this.score[h];
			Arrays.fill(bySlot, from, len, 0);
			if(o != null)
				o.dots(keys, h * DK, qkv, h * DK, bySlot, from, len);
			else
				for(int d = 0; d < DK; d++)
				{
					float qd = qkv[h * DK + d];
					float[] row = keys[h * DK + d];
					for(int s = from; s < len; s++)
						bySlot[s] += qd * row[s];
				}
			int ages = off + pos + h * len;
			for(int age = 0; age < a; age++)
				score[age] = bySlot[head + age] * SCALE + params[ages + age];
			for(int age = a; age < n; age++)
				score[age] = bySlot[age - a] * SCALE + params[ages + age];
			if(o != null)
				o.softmax(score, n);
			else
				softmax(score, n);
		}
		if(o != null)
			o.mix(out, score, values, head, n, len);
		else
			for(int age = 0, s = head; age < n; age++, s++)
			{
				if(s == len)
					s = 0;
				int row = s * WIDTH;
				for(int h = 0; h < HEADS; h++)
				{
					float wh = score[h][age];
					int at = h * DV;
					for(int d = 0; d < DV; d++)
						out[at + d] += wh * values[row + at + d];
				}
			}
	}
	
	private static void softmax(float[] score, int n)
	{
		float max = max(score, n);
		for(int i = 0; i < n; i++)
			score[i] = exp(score[i] - max);
		float inv = 1 / sum(score, n);
		for(int i = 0; i < n; i++)
			score[i] *= inv;
	}
	
	/** The largest of a[0..n) (no NaNs here; -0 or 0 gives the same exp). */
	public static float max(float[] a, int n)
	{
		float max = Float.NEGATIVE_INFINITY;
		for(int i = 0; i < n; i++)
			if(a[i] > max)
				max = a[i];
		return max;
	}
	
	/**
	 * a[0..n) added up in eight running sums (independent additions, not
	 * one long chain), then those pairwise - the plain and the SIMD softmax
	 * both use this.
	 */
	public static float sum(float[] a, int n)
	{
		float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0;
		int i = 0;
		for(; i + 8 <= n; i += 8)
		{
			s0 += a[i];
			s1 += a[i + 1];
			s2 += a[i + 2];
			s3 += a[i + 3];
			s4 += a[i + 4];
			s5 += a[i + 5];
			s6 += a[i + 6];
			s7 += a[i + 7];
		}
		for(; i < n; i++)
			s0 += a[i];
		return (s0 + s1 + (s2 + s3)) + (s4 + s5 + (s6 + s7));
	}
	
	/**
	 * e^x for x <= 0, to ~1e-4 relative (2^x: whole part by the exponent
	 * bits, the rest by a polynomial) - Math.exp 96 times a tick cost more
	 * than the network itself.
	 */
	public static float exp(float x)
	{
		if(x < -80)
			return 0;
		float y = x * 1.4426950F;
		// floor (y <= 0 here)
		int n = (int)y;
		if(n > y)
			n--;
		float f = y - n;
		float p = 1 + f * (0.6931472F + f * (0.2402265F
			+ f * (0.05550411F + f * (0.009618129F + f * 0.0013333558F))));
		return Float.intBitsToFloat(Float.floatToRawIntBits(p) + (n << 23));
	}
	
	/**
	 * Adds this tick (after the forward pass); every {@link #STEP}th stores
	 * the average's query, key and value.
	 */
	public void push(NeuralNet net)
	{
		float[] h = net.getActivations(1);
		int m = Math.min(IN, h.length);
		for(int j = 0; j < m; j++)
		{
			step[j] += h[j];
			second[j] += h[j];
		}
		params = net.getParams();
		off = NeuralNet.layerCount(net.sizesView());
		float[] key = net.sharedWeights != null ? net.sharedWeights : params;
		if(++secondTicks >= 20)
			pushSecond(key);
		if(++stepTicks < STEP)
			return;
		for(int j = 0; j < IN; j++)
			in[j] = step[j] / stepTicks;
		Arrays.fill(step, 0);
		stepTicks = 0;
		project(key, WQ, in, qkv);
		head = head == 0 ? TICKS - 1 : head - 1;
		for(int r = 0; r < HEADS * DK; r++)
			keyT[r][head] = qkv[WIDTH + r];
		System.arraycopy(qkv, 2 * WIDTH, value, head * WIDTH, WIDTH);
		if(count < TICKS)
			count++;
		shortDue = true;
	}
	
	/** A second's average into the long ring. */
	private void pushSecond(float[] key)
	{
		for(int j = 0; j < IN; j++)
			second[j] /= secondTicks;
		project(key, WK, second, kv);
		slowHead = slowHead == 0 ? SECONDS - 1 : slowHead - 1;
		for(int r = 0; r < HEADS * DK; r++)
			slowKeyT[r][slowHead] = kv[r];
		System.arraycopy(kv, WIDTH, slowValue, slowHead * WIDTH, WIDTH);
		Arrays.fill(second, 0);
		secondTicks = 0;
		if(slowCount < SECONDS)
			slowCount++;
		longDue = true;
	}
	
	/** A projection: out.length outputs from IN inputs. */
	private void project(float[] key, int at, float[] x, float[] out)
	{
		Ops o = ops;
		if(o != null)
		{
			o.project(key, params, off + at, IN, x, out);
			return;
		}
		int p = off + at;
		for(int r = 0; r < out.length; r++)
		{
			float s = 0;
			int row = p + r * IN;
			for(int c = 0; c < IN; c++)
				s += params[row + c] * x[c];
			out[r] = s;
		}
	}
}
