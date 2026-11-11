/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small fully connected network (multi-layer perceptron): tanh hidden
 * layers, a linear output layer. Everything lives in one flat float array
 * and a few preallocated buffers, so a forward pass allocates nothing - a
 * 26-24-12-10 net is ~1,000 multiply-adds, well under a microsecond.
 *
 * <p>
 * No Minecraft classes, so the trainer (src/test) runs it without the game.
 * Layout per layer: the weights row by row ({@code out x in}), then the
 * biases.
 */
public final class NeuralNet
{
	private static final int MAGIC = 0x424E4E31; // "BNN1"
	
	private final int[] sizes;
	private final float[] params;
	private final float[][] act;
	
	/**
	 * A faster forward pass the trainer plugs in (SIMD through the Vector
	 * API, which the game's JVM doesn't have). It must give exactly what
	 * {@link #forward} gives.
	 */
	public interface Kernel
	{
		float[] forward(NeuralNet net, float[] input);
		
		/** Two nets with the same weights (own biases), one pass. */
		default void forwardPair(NeuralNet a, float[] inA, NeuralNet b,
			float[] inB)
		{
			forward(a, inA);
			forward(b, inB);
		}
	}
	
	private static volatile Kernel kernel;
	/**
	 * Bumped whenever some code changes weights in place (Backprop), so a
	 * kernel's copy of them is rebuilt. Biases are always read live.
	 */
	private static final AtomicInteger WEIGHTS_EPOCH = new AtomicInteger();
	
	/** The kernel's own data for this net (e.g. transposed weights). */
	public Object kernelData;
	/**
	 * If set: this net's weights (not its biases) are always this array's,
	 * which nobody changes in place (FightAdapter moves only biases) - the
	 * trainer's kernel then shares one copy of them per thread.
	 */
	public float[] sharedWeights;
	public int kernelEpoch = -1;
	
	public NeuralNet(int[] sizes, float[] params)
	{
		if(params.length != paramCount(sizes))
			throw new IllegalArgumentException("Expected " + paramCount(sizes)
				+ " parameters, got " + params.length);
		
		this.sizes = sizes.clone();
		this.params = params;
		act = new float[sizes.length][];
		for(int i = 0; i < sizes.length; i++)
			act[i] = new float[sizes[i]];
	}
	
	/**
	 * All its parameters: the layers, then NeuralHistory's attention
	 * (version 13) - the evolution trains both.
	 */
	public static int paramCount(int[] sizes)
	{
		return layerCount(sizes) + NeuralHistory.PARAMS;
	}
	
	/** The layers' parameters (where NeuralHistory's start). */
	public static int layerCount(int[] sizes)
	{
		int n = 0;
		for(int i = 1; i < sizes.length; i++)
			n += sizes[i] * sizes[i - 1] + sizes[i];
		return n;
	}
	
	/**
	 * Runs the network. The returned array is an internal buffer, valid until
	 * the next call; not thread-safe (one net per thread).
	 */
	public float[] forward(float[] input)
	{
		Kernel k = kernel;
		if(k != null)
			return k.forward(this, input);
		return forwardPlain(input);
	}
	
	/** {@link #forward} without the kernel. */
	public float[] forwardPlain(float[] input)
	{
		System.arraycopy(input, 0, act[0], 0, sizes[0]);
		int p = 0;
		int last = sizes.length - 1;
		for(int l = 1; l <= last; l++)
		{
			float[] in = act[l - 1];
			float[] out = act[l];
			int nIn = sizes[l - 1];
			int nOut = sizes[l];
			int bias = p + nOut * nIn;
			boolean hidden = l != last;
			// four rows at a time: four independent sums keep the CPU busy
			// (one sum waits for each add), each summed in the same order
			// as one at a time - bit for bit the same result. Fused
			// multiply-adds (one rounding instead of two, like the
			// trainer's vector kernel - the same sums bit for bit)
			int o = 0;
			for(; o + 4 <= nOut; o += 4)
			{
				int r0 = p + o * nIn, r1 = r0 + nIn, r2 = r1 + nIn,
					r3 = r2 + nIn;
				float s0 = params[bias + o], s1 = params[bias + o + 1],
					s2 = params[bias + o + 2], s3 = params[bias + o + 3];
				for(int i = 0; i < nIn; i++)
				{
					float x = in[i];
					s0 = Math.fma(params[r0 + i], x, s0);
					s1 = Math.fma(params[r1 + i], x, s1);
					s2 = Math.fma(params[r2 + i], x, s2);
					s3 = Math.fma(params[r3 + i], x, s3);
				}
				out[o] = hidden ? tanh(s0) : s0;
				out[o + 1] = hidden ? tanh(s1) : s1;
				out[o + 2] = hidden ? tanh(s2) : s2;
				out[o + 3] = hidden ? tanh(s3) : s3;
			}
			for(; o < nOut; o++)
			{
				float sum = params[bias + o];
				int row = p + o * nIn;
				for(int i = 0; i < nIn; i++)
					sum = Math.fma(params[row + i], in[i], sum);
				out[o] = hidden ? tanh(sum) : sum;
			}
			p = bias + nOut;
		}
		return act[last];
	}
	
	/**
	 * Two nets with the same {@link #sharedWeights} (own biases) at once:
	 * each one's output in its own buffers, exactly as two forward() calls
	 * would give.
	 */
	public static void forwardPair(NeuralNet a, float[] inA, NeuralNet b,
		float[] inB)
	{
		Kernel k = kernel;
		if(k != null && a.sharedWeights != null
			&& a.sharedWeights == b.sharedWeights)
		{
			k.forwardPair(a, inA, b, inB);
			return;
		}
		a.forward(inA);
		b.forward(inB);
	}
	
	public static void setKernel(Kernel k)
	{
		kernel = k;
	}
	
	/** Call after changing any net's weights in place. */
	public static void weightsChanged()
	{
		WEIGHTS_EPOCH.incrementAndGet();
	}
	
	public static int weightsEpoch()
	{
		return WEIGHTS_EPOCH.get();
	}
	
	/** The activations of a layer after the last forward pass. */
	public float[] getActivations(int layer)
	{
		return act[layer];
	}
	
	/**
	 * A fast tanh (Pade approximant, exact at 0 and +-3 and within 0.02 in
	 * between). The trainer uses the same function, so the trained weights
	 * see exactly what they were trained with.
	 */
	public static float tanh(float x)
	{
		if(x <= -3)
			return -1;
		if(x >= 3)
			return 1;
		float x2 = x * x;
		return x * (27 + x2) / (27 + 9 * x2);
	}
	
	/** The derivative of {@link #tanh} at x, for the trainer's backprop. */
	public static float tanhDerivative(float x)
	{
		if(x <= -3 || x >= 3)
			return 0;
		float x2 = x * x;
		float d = 27 + 9 * x2;
		return (729 - 162 * x2 + 9 * x2 * x2) / (d * d);
	}
	
	/** The layer sizes themselves, for hot paths: don't change them. */
	public int[] sizesView()
	{
		return sizes;
	}
	
	public int[] getSizes()
	{
		return sizes.clone();
	}
	
	public float[] getParams()
	{
		return params;
	}
	
	public NeuralNet copy()
	{
		return new NeuralNet(sizes, params.clone());
	}
	
	public void write(OutputStream stream) throws IOException
	{
		DataOutputStream out = new DataOutputStream(stream);
		out.writeInt(MAGIC);
		out.writeInt(NeuralObservation.VERSION);
		out.writeInt(sizes.length);
		for(int size : sizes)
			out.writeInt(size);
		for(float p : params)
			out.writeFloat(p);
		out.flush();
	}
	
	/**
	 * Any version and size, unchecked - only for growing an old net into
	 * the current inputs (the trainer's widen).
	 */
	public static NeuralNet readAnyVersion(InputStream stream)
		throws IOException
	{
		DataInputStream in = new DataInputStream(stream);
		if(in.readInt() != MAGIC)
			throw new IOException("Not a Beast neural net");
		int version = in.readInt();
		int[] sizes = new int[in.readInt()];
		for(int i = 0; i < sizes.length; i++)
			sizes[i] = in.readInt();
		float[] params = new float[paramCount(sizes)];
		// before version 13 there was no attention: it starts fresh
		int stored = version >= 13 ? params.length : layerCount(sizes);
		for(int i = 0; i < stored; i++)
			params[i] = in.readFloat();
		if(stored < params.length)
			System.arraycopy(NeuralHistory.initialParams(), 0, params, stored,
				NeuralHistory.PARAMS);
		return new NeuralNet(sizes, params);
	}
	
	public static NeuralNet read(InputStream stream) throws IOException
	{
		DataInputStream in = new DataInputStream(stream);
		if(in.readInt() != MAGIC)
			throw new IOException("Not a Beast neural net");
		int version = in.readInt();
		// 12 has the same inputs, only no attention yet (it starts fresh)
		if(version != NeuralObservation.VERSION && version != 12)
			throw new IOException("Net trained for observation version "
				+ version + ", this is " + NeuralObservation.VERSION);
		
		int[] sizes = new int[in.readInt()];
		for(int i = 0; i < sizes.length; i++)
			sizes[i] = in.readInt();
		if(sizes[0] != NeuralObservation.SIZE
			|| sizes[sizes.length - 1] != NeuralAction.OUTPUTS)
			throw new IOException(
				"Wrong input/output size: " + Arrays.toString(sizes));
		
		float[] params = new float[paramCount(sizes)];
		int stored = version >= 13 ? params.length : layerCount(sizes);
		for(int i = 0; i < stored; i++)
			params[i] = in.readFloat();
		if(stored < params.length)
			System.arraycopy(NeuralHistory.initialParams(), 0, params, stored,
				NeuralHistory.PARAMS);
		return new NeuralNet(sizes, params);
	}
}
