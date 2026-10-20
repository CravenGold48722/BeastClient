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
	
	public static int paramCount(int[] sizes)
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
			for(int o = 0; o < nOut; o++)
			{
				float sum = params[bias + o];
				int row = p + o * nIn;
				for(int i = 0; i < nIn; i++)
					sum += params[row + i] * in[i];
				out[o] = l == last ? sum : tanh(sum);
			}
			p = bias + nOut;
		}
		return act[last];
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
	
	public static NeuralNet read(InputStream stream) throws IOException
	{
		DataInputStream in = new DataInputStream(stream);
		if(in.readInt() != MAGIC)
			throw new IOException("Not a Beast neural net");
		int version = in.readInt();
		if(version != NeuralObservation.VERSION)
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
		for(int i = 0; i < params.length; i++)
			params[i] = in.readFloat();
		return new NeuralNet(sizes, params);
	}
}
