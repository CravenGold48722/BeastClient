/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.Random;

import net.wurstclient.hacks.aimassist.neural.NeuralTrainer.Dataset;

/**
 * Supervised training for the imitation step: mini-batch Adam on
 * cross-entropy - softmax for the W/S and A/D choices, sigmoid for the
 * four keys. Plain loops; the net is small enough that this is fast.
 */
final class Backprop
{
	private Backprop()
	{}
	
	// how much each output counts: strafing is the teacher's coin flip, so
	// it barely counts; a missed hit or jump costs more than a stray one
	private static final float W_FORWARD = 1, W_STRAFE = 0.05F, W_JUMP = 1,
		W_SPRINT = 1, W_SNEAK = 0.5F, W_ATTACK = 1;
	private static final float POS_JUMP = 3, POS_ATTACK = 4;
	
	static void train(int[] sizes, float[] params, Dataset data, int epochs,
		float lr, Random random)
	{
		int n = params.length;
		float[] m = new float[n], v = new float[n], grad = new float[n];
		int layers = sizes.length;
		float[][] z = new float[layers][], a = new float[layers][],
			delta = new float[layers][];
		for(int l = 0; l < layers; l++)
		{
			z[l] = new float[sizes[l]];
			a[l] = new float[sizes[l]];
			delta[l] = new float[sizes[l]];
		}
		int[] offsets = offsets(sizes);
		
		int size = data.size;
		int[] order = new int[size];
		for(int i = 0; i < size; i++)
			order[i] = i;
		int batch = 256;
		int step = 0;
		for(int epoch = 0; epoch < epochs; epoch++)
		{
			for(int i = size - 1; i > 0; i--)
			{
				int j = random.nextInt(i + 1);
				int t = order[i];
				order[i] = order[j];
				order[j] = t;
			}
			for(int b = 0; b + batch <= size; b += batch)
			{
				java.util.Arrays.fill(grad, 0);
				for(int s = b; s < b + batch; s++)
				{
					int k = order[s];
					forward(sizes, params, offsets, data.x[k], z, a);
					outputDelta(a[layers - 1], data.y[k], delta[layers - 1]);
					backward(sizes, params, offsets, z, a, delta, grad);
				}
				step++;
				float c1 = 1 - (float)Math.pow(0.9, step);
				float c2 = 1 - (float)Math.pow(0.999, step);
				for(int j = 0; j < n; j++)
				{
					float g = grad[j] / batch;
					m[j] = 0.9F * m[j] + 0.1F * g;
					v[j] = 0.999F * v[j] + 0.001F * g * g;
					params[j] -= lr * (m[j] / c1)
						/ ((float)Math.sqrt(v[j] / c2) + 1e-8F);
				}
			}
		}
	}
	
	static int[] offsets(int[] sizes)
	{
		int[] off = new int[sizes.length];
		int p = 0;
		for(int l = 1; l < sizes.length; l++)
		{
			off[l] = p;
			p += sizes[l] * sizes[l - 1] + sizes[l];
		}
		return off;
	}
	
	static void forward(int[] sizes, float[] p, int[] off, float[] x,
		float[][] z, float[][] a)
	{
		int last = sizes.length - 1;
		System.arraycopy(x, 0, a[0], 0, sizes[0]);
		for(int l = 1; l <= last; l++)
		{
			int nIn = sizes[l - 1], nOut = sizes[l];
			int bias = off[l] + nIn * nOut;
			for(int o = 0; o < nOut; o++)
			{
				float sum = p[bias + o];
				int row = off[l] + o * nIn;
				for(int i = 0; i < nIn; i++)
					sum += p[row + i] * a[l - 1][i];
				z[l][o] = sum;
				a[l][o] = l == last ? sum : NeuralNet.tanh(sum);
			}
		}
	}
	
	/** dLoss/dOutput for one sample. */
	static void outputDelta(float[] out, byte[] y, float[] d)
	{
		softmaxDelta(out, 0, y[0], W_FORWARD, d);
		softmaxDelta(out, 3, y[1], W_STRAFE, d);
		sigmoidDelta(out, 6, y[2], W_JUMP, POS_JUMP, d);
		sigmoidDelta(out, 7, y[3], W_SPRINT, 1, d);
		sigmoidDelta(out, 8, y[4], W_SNEAK, 1, d);
		sigmoidDelta(out, 9, y[5], W_ATTACK, POS_ATTACK, d);
	}
	
	private static void softmaxDelta(float[] out, int from, int label, float w,
		float[] d)
	{
		float max = Math.max(out[from], Math.max(out[from + 1], out[from + 2]));
		float e0 = (float)Math.exp(out[from] - max);
		float e1 = (float)Math.exp(out[from + 1] - max);
		float e2 = (float)Math.exp(out[from + 2] - max);
		float sum = e0 + e1 + e2;
		d[from] = w * (e0 / sum - (label == 0 ? 1 : 0));
		d[from + 1] = w * (e1 / sum - (label == 1 ? 1 : 0));
		d[from + 2] = w * (e2 / sum - (label == 2 ? 1 : 0));
	}
	
	private static void sigmoidDelta(float[] out, int i, int label, float w,
		float posWeight, float[] d)
	{
		float p = 1 / (1 + (float)Math.exp(-out[i]));
		// weighted binary cross-entropy: positives count posWeight times
		d[i] = w * (label == 1 ? posWeight * (p - 1) : p);
	}
	
	static void backward(int[] sizes, float[] p, int[] off, float[][] z,
		float[][] a, float[][] delta, float[] grad)
	{
		for(int l = sizes.length - 1; l >= 1; l--)
		{
			int nIn = sizes[l - 1], nOut = sizes[l];
			int bias = off[l] + nIn * nOut;
			for(int o = 0; o < nOut; o++)
			{
				float d = delta[l][o];
				if(d == 0)
					continue;
				int row = off[l] + o * nIn;
				for(int i = 0; i < nIn; i++)
					grad[row + i] += d * a[l - 1][i];
				grad[bias + o] += d;
			}
			if(l > 1)
				for(int i = 0; i < nIn; i++)
				{
					float s = 0;
					for(int o = 0; o < nOut; o++)
						s += p[off[l] + o * nIn + i] * delta[l][o];
					delta[l - 1][i] = s * NeuralNet.tanhDerivative(z[l - 1][i]);
				}
		}
	}
	
	/** How often each head picks what the teacher did. */
	static String accuracy(int[] sizes, float[] params, Dataset data)
	{
		NeuralNet net = new NeuralNet(sizes, params);
		NeuralAction act = new NeuralAction();
		int[] right = new int[6];
		int[] attackPos = new int[2];
		int n = Math.min(data.size, 50_000);
		for(int k = 0; k < n; k++)
		{
			act.decode(net.forward(data.x[k]));
			byte[] y = data.y[k];
			if(1 - act.forward == y[0])
				right[0]++;
			if(1 - act.strafe == y[1])
				right[1]++;
			if((act.jump ? 1 : 0) == y[2])
				right[2]++;
			if((act.sprint ? 1 : 0) == y[3])
				right[3]++;
			if((act.sneak ? 1 : 0) == y[4])
				right[4]++;
			if((act.attack ? 1 : 0) == y[5])
				right[5]++;
			if(y[5] == 1)
			{
				attackPos[1]++;
				if(act.attack)
					attackPos[0]++;
			}
		}
		return String.format(
			"W/S %.0f%%, jump %.0f%%, sprint %.0f%%, sneak %.0f%%, attack %.0f%% (hits %d/%d)",
			100.0 * right[0] / n, 100.0 * right[2] / n, 100.0 * right[3] / n,
			100.0 * right[4] / n, 100.0 * right[5] / n, attackPos[0],
			attackPos[1]);
	}
}
