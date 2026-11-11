/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The trainer's forward pass: the weights transposed (input by input, the
 * outputs side by side, padded to whole vectors), so each input is one
 * broadcast and a fused multiply-add per vector of outputs - 8 outputs at
 * once with AVX2. Every output is still summed bias first, then input 0, 1,
 * 2..., one rounding per input like forwardPlain's Math.fma (user,
 * 2026-10-27: was a separate multiply and add), so the result is bit for
 * bit {@link NeuralNet#forwardPlain}'s. ~5x faster.
 *
 * <p>
 * Needs {@code --add-modules jdk.incubator.vector}; without it the trainer
 * keeps the plain pass.
 */
public final class VectorKernel implements NeuralNet.Kernel
{
	private static final VectorSpecies<Float> SP =
		FloatVector.SPECIES_PREFERRED;
	private static final int L = SP.length();
	static final boolean SPARSE =
		!"false".equals(System.getProperty("nn.sparse"));
	
	/** Per net: transposed, padded weights and scratch. */
	private static final class Data
	{
		final int[] sizes;
		final int[] padded;
		final int[] offsets;
		final float[][] weights;
		final float[][] bias;
		final float[][] out;
		/** The second net's biases and outputs (forwardPair). */
		final float[][] biasB, outB;
		/** Scratch: the inputs of a layer that aren't 0. */
		final int[] nonZero;
		/** Shared: whose weights these are, as of which epoch. */
		float[] key;
		int epoch;
		
		Data(int[] sizes)
		{
			this.sizes = sizes;
			int layers = sizes.length;
			padded = new int[layers];
			offsets = new int[layers];
			weights = new float[layers][];
			bias = new float[layers][];
			out = new float[layers][];
			biasB = new float[layers][];
			outB = new float[layers][];
			int p = 0, widest = 0;
			for(int s : sizes)
				widest = Math.max(widest, s);
			nonZero = new int[widest];
			for(int l = 1; l < layers; l++)
			{
				padded[l] = (sizes[l] + L - 1) / L * L;
				offsets[l] = p;
				weights[l] = new float[sizes[l - 1] * padded[l]];
				bias[l] = new float[padded[l]];
				out[l] = new float[padded[l]];
				biasB[l] = new float[padded[l]];
				outB[l] = new float[padded[l]];
				p += sizes[l] * sizes[l - 1] + sizes[l];
			}
		}
		
		boolean fits(int[] other)
		{
			return java.util.Arrays.equals(sizes, other);
		}
		
		void load(float[] params)
		{
			for(int l = 1; l < sizes.length; l++)
			{
				int nIn = sizes[l - 1], nOut = sizes[l], pad = padded[l];
				float[] w = weights[l];
				int p = offsets[l];
				for(int o = 0; o < nOut; o++)
					for(int i = 0; i < nIn; i++)
						w[i * pad + o] = params[p + o * nIn + i];
			}
		}
	}
	
	/**
	 * Uses this kernel from now on (and its loops for NeuralHistory's pool).
	 * Without the Vector API, loading this class throws - the caller
	 * catches that.
	 */
	public static void install()
	{
		NeuralNet.setKernel(new VectorKernel());
		NeuralHistory.setOps(new HistoryOps());
	}
	
	/**
	 * NeuralHistory's two loops with SIMD - element by element, a multiply
	 * then an add like the plain loops: bit for bit the same.
	 */
	static final class HistoryOps implements NeuralHistory.Ops
	{
		/** A projection's weights, transposed (input by input). */
		private static final class Proj
		{
			float[] key;
			int off, rows, cols, epoch;
			float[] wt;
		}
		
		private static final int SLOTS = 16;
		private static final ThreadLocal<Proj[]> PROJ =
			ThreadLocal.withInitial(() -> new Proj[SLOTS]);
		private static final ThreadLocal<int[]> NEXT_PROJ =
			ThreadLocal.withInitial(() -> new int[1]);
		
		private static float[] transposed(float[] key, float[] p, int off,
			int rows, int cols)
		{
			int epoch = NeuralNet.weightsEpoch();
			Proj[] cache = PROJ.get();
			for(Proj c : cache)
				if(c != null && c.key == key && c.off == off && c.rows == rows
					&& c.cols == cols && c.epoch == epoch)
					return c.wt;
			int[] next = NEXT_PROJ.get();
			int slot = next[0];
			next[0] = (slot + 1) % SLOTS;
			Proj c = cache[slot];
			if(c == null)
				cache[slot] = c = new Proj();
			int pad = (rows + L - 1) / L * L;
			if(c.wt == null || c.wt.length != cols * pad)
				c.wt = new float[cols * pad];
			for(int r = 0; r < rows; r++)
				for(int i = 0; i < cols; i++)
					c.wt[i * pad + r] = p[off + r * cols + i];
			c.key = key;
			c.off = off;
			c.rows = rows;
			c.cols = cols;
			c.epoch = epoch;
			return c.wt;
		}
		
		@Override
		public void project(float[] key, float[] p, int off, int cols,
			float[] x, float[] out)
		{
			int rows = out.length, pad = (rows + L - 1) / L * L;
			float[] wt = transposed(key, p, off, rows, cols);
			int r = 0;
			for(; r + 6 * L <= pad; r += 6 * L)
				project6(wt, pad, r, cols, x, out, rows);
			for(; r < pad; r += L)
				project1(wt, pad, r, cols, x, out, rows);
		}
		
		// each output: 0, + its weight times input 0, 1, ... - like the plain
		// loop, a multiply then an add; six chunks share each broadcast
		private static void project6(float[] wt, int pad, int r, int cols,
			float[] x, float[] out, int rows)
		{
			FloatVector a0 = FloatVector.zero(SP), a1 = a0, a2 = a0, a3 = a0,
				a4 = a0, a5 = a0;
			for(int i = 0; i < cols; i++)
			{
				FloatVector xv = FloatVector.broadcast(SP, x[i]);
				int at = i * pad + r;
				a0 = a0.add(FloatVector.fromArray(SP, wt, at).mul(xv));
				a1 = a1.add(FloatVector.fromArray(SP, wt, at + L).mul(xv));
				a2 = a2.add(FloatVector.fromArray(SP, wt, at + 2 * L).mul(xv));
				a3 = a3.add(FloatVector.fromArray(SP, wt, at + 3 * L).mul(xv));
				a4 = a4.add(FloatVector.fromArray(SP, wt, at + 4 * L).mul(xv));
				a5 = a5.add(FloatVector.fromArray(SP, wt, at + 5 * L).mul(xv));
			}
			store(a0, out, r, rows);
			store(a1, out, r + L, rows);
			store(a2, out, r + 2 * L, rows);
			store(a3, out, r + 3 * L, rows);
			store(a4, out, r + 4 * L, rows);
			store(a5, out, r + 5 * L, rows);
		}
		
		private static void project1(float[] wt, int pad, int r, int cols,
			float[] x, float[] out, int rows)
		{
			FloatVector acc = FloatVector.zero(SP);
			for(int i = 0; i < cols; i++)
				acc = acc.add(FloatVector.fromArray(SP, wt, i * pad + r)
					.mul(FloatVector.broadcast(SP, x[i])));
			store(acc, out, r, rows);
		}
		
		private static void store(FloatVector v, float[] out, int r, int rows)
		{
			if(r + L <= rows)
				v.intoArray(out, r);
			else
				for(int l = 0; l < rows - r; l++)
					out[r + l] = v.lane(l);
		}
		
		@Override
		public void mix(float[] out, float[][] w, float[] values, int head,
			int n, int len)
		{
			// each output chunk stays in a register over all ages (newest
			// first, like the plain loop): slots head... then from 0
			int width = NeuralHistory.WIDTH, dv = NeuralHistory.DV;
			int a = Math.min(n, len - head);
			for(int h = 0; h < w.length; h++)
			{
				float[] wh = w[h];
				int at = h * dv, d = 0;
				for(; d + L <= dv; d += L)
				{
					int c = at + d;
					FloatVector acc = FloatVector.fromArray(SP, out, c);
					for(int age = 0, i = head * width + c; age < a; age++, i +=
						width)
						acc = acc.add(FloatVector.fromArray(SP, values, i)
							.mul(FloatVector.broadcast(SP, wh[age])));
					for(int age = a, i = c; age < n; age++, i += width)
						acc = acc.add(FloatVector.fromArray(SP, values, i)
							.mul(FloatVector.broadcast(SP, wh[age])));
					acc.intoArray(out, c);
				}
				for(; d < dv; d++)
					for(int age = 0, s = head; age < n; age++, s++)
					{
						if(s == len)
							s = 0;
						out[at + d] += wh[age] * values[s * width + at + d];
					}
			}
		}
		
		private static final FloatVector ZERO = FloatVector.zero(SP);
		
		@Override
		public void softmax(float[] score, int n)
		{
			float max = NeuralHistory.max(score, n);
			// NeuralHistory.exp lane by lane: the same operations in the
			// same order (floor by blend, 2^n by n * 2^23 into the exponent
			// bits - only operations the JIT compiles to instructions)
			int whole = n / L * L, i = 0;
			FloatVector maxv = FloatVector.broadcast(SP, max);
			for(; i < whole; i += L)
			{
				FloatVector x = FloatVector.fromArray(SP, score, i).sub(maxv);
				FloatVector y = x.mul(1.4426950F);
				FloatVector nf =
					(FloatVector)((IntVector)y.convert(VectorOperators.F2I, 0))
						.convert(VectorOperators.I2F, 0);
				nf = nf.blend(nf.sub(1), nf.compare(VectorOperators.GT, y));
				IntVector ni = (IntVector)nf.convert(VectorOperators.F2I, 0);
				FloatVector f = y.sub(nf);
				FloatVector p = f.mul(0.0013333558F).add(0.009618129F);
				p = f.mul(p).add(0.05550411F);
				p = f.mul(p).add(0.2402265F);
				p = f.mul(p).add(0.6931472F);
				p = f.mul(p).add(1);
				FloatVector e = ((IntVector)p.reinterpretAsInts())
					.add(ni.mul(1 << 23)).reinterpretAsFloats();
				e = e.blend(ZERO, x.compare(VectorOperators.LT, -80));
				e.intoArray(score, i);
			}
			for(; i < n; i++)
				score[i] = NeuralHistory.exp(score[i] - max);
			float inv = 1 / NeuralHistory.sum(score, n);
			FloatVector invv = FloatVector.broadcast(SP, inv);
			for(i = 0; i < whole; i += L)
				FloatVector.fromArray(SP, score, i).mul(invv).intoArray(score,
					i);
			for(; i < n; i++)
				score[i] *= inv;
		}
		
		@Override
		public void dots(float[][] keys, int kRow, float[] q, int qOff,
			float[] score, int from, int len)
		{
			int whole = from + (len - from) / L * L;
			for(int d = 0; d < NeuralHistory.DK; d++)
			{
				float qd = q[qOff + d];
				float[] row = keys[kRow + d];
				FloatVector qv = FloatVector.broadcast(SP, qd);
				int s = from;
				for(; s < whole; s += L)
					FloatVector.fromArray(SP, score, s)
						.add(FloatVector.fromArray(SP, row, s).mul(qv))
						.intoArray(score, s);
				for(; s < len; s++)
					score[s] += qd * row[s];
			}
		}
	}
	
	/**
	 * Per thread: the transposed weights of the last few shared weight
	 * arrays. Every fighter of a fight used to transpose its own copy of the
	 * same weights (all 19 opponents of an FFA), ~3% of the trainer's time
	 * in garbage collection.
	 */
	private static final int SHARED = 8;
	private static final ThreadLocal<Data[]> CACHE =
		ThreadLocal.withInitial(() -> new Data[SHARED]);
	private static final ThreadLocal<int[]> NEXT =
		ThreadLocal.withInitial(() -> new int[1]);
	
	private static Data shared(NeuralNet net, int epoch)
	{
		float[] key = net.sharedWeights;
		int[] sizes = net.sizesView();
		Data[] cache = CACHE.get();
		for(Data d : cache)
			if(d != null && d.key == key && (d.sizes == sizes || d.fits(sizes)))
			{
				if(d.epoch != epoch)
				{
					d.load(key);
					d.epoch = epoch;
				}
				return d;
			}
		int[] next = NEXT.get();
		int slot = next[0];
		next[0] = (slot + 1) % SHARED;
		Data d = cache[slot];
		if(d == null || !d.fits(sizes))
			cache[slot] = d = new Data(sizes);
		d.key = key;
		d.load(key);
		d.epoch = epoch;
		return d;
	}
	
	@Override
	public float[] forward(NeuralNet net, float[] input)
	{
		int epoch = NeuralNet.weightsEpoch();
		float[] params = net.getParams();
		Data d;
		if(net.sharedWeights != null)
			d = shared(net, epoch);
		else
		{
			d = (Data)net.kernelData;
			if(d == null)
			{
				d = new Data(net.getSizes());
				net.kernelData = d;
				net.kernelEpoch = epoch - 1;
			}
			if(net.kernelEpoch != epoch)
			{
				d.load(params);
				net.kernelEpoch = epoch;
			}
		}
		
		int[] sizes = d.sizes;
		int last = sizes.length - 1;
		float[] in = input;
		for(int l = 1; l <= last; l++)
		{
			int nIn = sizes[l - 1], nOut = sizes[l], pad = d.padded[l];
			float[] w = d.weights[l];
			float[] b = d.bias[l];
			float[] out = d.out[l];
			// biases live: FightAdapter moves them every few hits
			System.arraycopy(params, d.offsets[l] + nOut * nIn, b, 0, nOut);
			
			// Inputs that are 0 add nothing (+-0 to a sum that isn't -0), so
			// they're skipped: empty focus cells, flat ground, absent things
			// are a third or more of the first layer's inputs. Same order,
			// same sums as the plain pass.
			int[] nz = d.nonZero;
			int n = nonZero(nz, in, nIn);
			
			// up to six vectors of outputs per pass over the inputs (a 48 wide
			// layer in one): each input is read and broadcast once, and the
			// independent chains keep the adders busy
			int c = 0;
			for(; c + 6 * L <= pad; c += 6 * L)
				six(w, b, out, in, nz, n, pad, c);
			for(; c + 3 * L <= pad; c += 3 * L)
				three(w, b, out, in, nz, n, pad, c);
			for(; c + 2 * L <= pad; c += 2 * L)
				two(w, b, out, in, nz, n, pad, c);
			for(; c < pad; c += L)
				one(w, b, out, in, nz, n, pad, c);
			
			if(l != last)
				tanh(out, nOut);
			// the first hidden layer is NeuralHistory's (out is shared by
			// every net on this thread)
			if(l == 1)
				System.arraycopy(out, 0, net.getActivations(1), 0, nOut);
			in = out;
		}
		
		// the caller expects the net's own output buffer (and its sizes)
		float[] result = net.getActivations(last);
		System.arraycopy(in, 0, result, 0, sizes[last]);
		return result;
	}
	
	/**
	 * Two nets with the same weights (their own biases): every weight
	 * vector is loaded once for both. The inputs either one has (another's
	 * 0 adds exactly 0), in the same order - each output bit for bit what
	 * forward() gives it. ~1.6x faster a net in a free-for-all (user,
	 * 2026-10-27).
	 */
	@Override
	public void forwardPair(NeuralNet na, float[] inA, NeuralNet nb,
		float[] inB)
	{
		int epoch = NeuralNet.weightsEpoch();
		Data d = shared(na, epoch);
		float[] pa = na.getParams(), pb = nb.getParams();
		int[] sizes = d.sizes;
		int last = sizes.length - 1;
		float[] a = inA, b = inB;
		for(int l = 1; l <= last; l++)
		{
			int nIn = sizes[l - 1], nOut = sizes[l], pad = d.padded[l];
			float[] w = d.weights[l];
			float[] ba = d.bias[l], bb = d.biasB[l];
			float[] oa = d.out[l], ob = d.outB[l];
			System.arraycopy(pa, d.offsets[l] + nOut * nIn, ba, 0, nOut);
			System.arraycopy(pb, d.offsets[l] + nOut * nIn, bb, 0, nOut);
			int[] nz = d.nonZero;
			int n = nonZero(nz, a, b, nIn);
			int c = 0;
			for(; c + 3 * L <= pad; c += 3 * L)
				pairThree(w, ba, bb, oa, ob, a, b, nz, n, pad, c);
			for(; c < pad; c += L)
				pairOne(w, ba, bb, oa, ob, a, b, nz, n, pad, c);
			if(l != last)
			{
				tanh(oa, nOut);
				tanh(ob, nOut);
			}
			if(l == 1)
			{
				System.arraycopy(oa, 0, na.getActivations(1), 0, nOut);
				System.arraycopy(ob, 0, nb.getActivations(1), 0, nOut);
			}
			a = oa;
			b = ob;
		}
		System.arraycopy(a, 0, na.getActivations(last), 0, sizes[last]);
		System.arraycopy(b, 0, nb.getActivations(last), 0, sizes[last]);
	}
	
	/**
	 * The indices of the inputs that aren't 0 (all with nn.sparse=false),
	 * without a branch per input: which ones are 0 is random, and the
	 * mispredicted branches cost more than the loop.
	 */
	private static int nonZero(int[] nz, float[] in, int nIn)
	{
		int n = 0;
		for(int i = 0; i < nIn; i++)
		{
			nz[n] = i;
			n += in[i] != 0 || !SPARSE ? 1 : 0;
		}
		return n;
	}
	
	private static int nonZero(int[] nz, float[] a, float[] b, int nIn)
	{
		int n = 0;
		for(int i = 0; i < nIn; i++)
		{
			nz[n] = i;
			n += a[i] != 0 || b[i] != 0 || !SPARSE ? 1 : 0;
		}
		return n;
	}
	
	/**
	 * NeuralNet.tanh on out[0..n) lane by lane: the same operations, so the
	 * same bits.
	 */
	private static void tanh(float[] out, int n)
	{
		int i = 0;
		for(; i + L <= n; i += L)
		{
			FloatVector x = FloatVector.fromArray(SP, out, i);
			FloatVector x2 = x.mul(x);
			FloatVector y = x.mul(x2.add(27)).div(x2.mul(9).add(27));
			y = y.blend(-1, x.compare(VectorOperators.LE, -3));
			y = y.blend(1, x.compare(VectorOperators.GE, 3));
			y.intoArray(out, i);
		}
		for(; i < n; i++)
			out[i] = NeuralNet.tanh(out[i]);
	}
	
	private static void pairThree(float[] w, float[] ba, float[] bb, float[] oa,
		float[] ob, float[] ia, float[] ib, int[] nz, int n, int pad, int c)
	{
		FloatVector a0 = FloatVector.fromArray(SP, ba, c);
		FloatVector a1 = FloatVector.fromArray(SP, ba, c + L);
		FloatVector a2 = FloatVector.fromArray(SP, ba, c + 2 * L);
		FloatVector b0 = FloatVector.fromArray(SP, bb, c);
		FloatVector b1 = FloatVector.fromArray(SP, bb, c + L);
		FloatVector b2 = FloatVector.fromArray(SP, bb, c + 2 * L);
		for(int k = 0; k < n; k++)
		{
			int i = nz[k], row = c + i * pad;
			FloatVector xa = FloatVector.broadcast(SP, ia[i]);
			FloatVector xb = FloatVector.broadcast(SP, ib[i]);
			FloatVector w0 = FloatVector.fromArray(SP, w, row);
			FloatVector w1 = FloatVector.fromArray(SP, w, row + L);
			FloatVector w2 = FloatVector.fromArray(SP, w, row + 2 * L);
			a0 = w0.fma(xa, a0);
			a1 = w1.fma(xa, a1);
			a2 = w2.fma(xa, a2);
			b0 = w0.fma(xb, b0);
			b1 = w1.fma(xb, b1);
			b2 = w2.fma(xb, b2);
		}
		a0.intoArray(oa, c);
		a1.intoArray(oa, c + L);
		a2.intoArray(oa, c + 2 * L);
		b0.intoArray(ob, c);
		b1.intoArray(ob, c + L);
		b2.intoArray(ob, c + 2 * L);
	}
	
	private static void pairOne(float[] w, float[] ba, float[] bb, float[] oa,
		float[] ob, float[] ia, float[] ib, int[] nz, int n, int pad, int c)
	{
		FloatVector a0 = FloatVector.fromArray(SP, ba, c);
		FloatVector b0 = FloatVector.fromArray(SP, bb, c);
		for(int k = 0; k < n; k++)
		{
			int i = nz[k];
			FloatVector w0 = FloatVector.fromArray(SP, w, c + i * pad);
			a0 = w0.fma(FloatVector.broadcast(SP, ia[i]), a0);
			b0 = w0.fma(FloatVector.broadcast(SP, ib[i]), b0);
		}
		a0.intoArray(oa, c);
		b0.intoArray(ob, c);
	}
	
	private static void six(float[] w, float[] b, float[] out, float[] in,
		int[] nz, int n, int pad, int c)
	{
		FloatVector a0 = FloatVector.fromArray(SP, b, c);
		FloatVector a1 = FloatVector.fromArray(SP, b, c + L);
		FloatVector a2 = FloatVector.fromArray(SP, b, c + 2 * L);
		FloatVector a3 = FloatVector.fromArray(SP, b, c + 3 * L);
		FloatVector a4 = FloatVector.fromArray(SP, b, c + 4 * L);
		FloatVector a5 = FloatVector.fromArray(SP, b, c + 5 * L);
		for(int k = 0; k < n; k++)
		{
			int i = nz[k], row = c + i * pad;
			FloatVector x = FloatVector.broadcast(SP, in[i]);
			a0 = FloatVector.fromArray(SP, w, row).fma(x, a0);
			a1 = FloatVector.fromArray(SP, w, row + L).fma(x, a1);
			a2 = FloatVector.fromArray(SP, w, row + 2 * L).fma(x, a2);
			a3 = FloatVector.fromArray(SP, w, row + 3 * L).fma(x, a3);
			a4 = FloatVector.fromArray(SP, w, row + 4 * L).fma(x, a4);
			a5 = FloatVector.fromArray(SP, w, row + 5 * L).fma(x, a5);
		}
		a0.intoArray(out, c);
		a1.intoArray(out, c + L);
		a2.intoArray(out, c + 2 * L);
		a3.intoArray(out, c + 3 * L);
		a4.intoArray(out, c + 4 * L);
		a5.intoArray(out, c + 5 * L);
	}
	
	private static void three(float[] w, float[] b, float[] out, float[] in,
		int[] nz, int n, int pad, int c)
	{
		FloatVector a0 = FloatVector.fromArray(SP, b, c);
		FloatVector a1 = FloatVector.fromArray(SP, b, c + L);
		FloatVector a2 = FloatVector.fromArray(SP, b, c + 2 * L);
		for(int k = 0; k < n; k++)
		{
			int i = nz[k], row = c + i * pad;
			FloatVector x = FloatVector.broadcast(SP, in[i]);
			a0 = FloatVector.fromArray(SP, w, row).fma(x, a0);
			a1 = FloatVector.fromArray(SP, w, row + L).fma(x, a1);
			a2 = FloatVector.fromArray(SP, w, row + 2 * L).fma(x, a2);
		}
		a0.intoArray(out, c);
		a1.intoArray(out, c + L);
		a2.intoArray(out, c + 2 * L);
	}
	
	private static void two(float[] w, float[] b, float[] out, float[] in,
		int[] nz, int n, int pad, int c)
	{
		FloatVector a0 = FloatVector.fromArray(SP, b, c);
		FloatVector a1 = FloatVector.fromArray(SP, b, c + L);
		for(int k = 0; k < n; k++)
		{
			int i = nz[k], row = c + i * pad;
			FloatVector x = FloatVector.broadcast(SP, in[i]);
			a0 = FloatVector.fromArray(SP, w, row).fma(x, a0);
			a1 = FloatVector.fromArray(SP, w, row + L).fma(x, a1);
		}
		a0.intoArray(out, c);
		a1.intoArray(out, c + L);
	}
	
	private static void one(float[] w, float[] b, float[] out, float[] in,
		int[] nz, int n, int pad, int c)
	{
		FloatVector a0 = FloatVector.fromArray(SP, b, c);
		for(int k = 0; k < n; k++)
		{
			int i = nz[k];
			a0 = FloatVector.fromArray(SP, w, c + i * pad)
				.fma(FloatVector.broadcast(SP, in[i]), a0);
		}
		a0.intoArray(out, c);
	}
}
