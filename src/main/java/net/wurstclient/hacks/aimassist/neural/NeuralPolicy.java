/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.io.IOException;
import java.io.InputStream;

/**
 * AimAssist's trained network: loaded once from the jar
 * ({@code assets/wurst/neural/aimassist_pvp.bin}, written by the trainer in
 * src/test), then one forward pass a tick with no allocation.
 */
public final class NeuralPolicy
{
	public static final String RESOURCE =
		"/assets/wurst/neural/aimassist_pvp.bin";
	
	private static NeuralPolicy instance;
	private static boolean loadFailed;
	
	private final NeuralNet net;
	private final float[] input = new float[NeuralObservation.SIZE];
	private final NeuralAction action = new NeuralAction();
	
	private NeuralPolicy(NeuralNet net)
	{
		this.net = net;
	}
	
	/** The shipped network, or null if it couldn't be loaded. */
	public static synchronized NeuralPolicy get()
	{
		if(instance != null || loadFailed)
			return instance;
		
		try(InputStream in = NeuralPolicy.class.getResourceAsStream(RESOURCE))
		{
			if(in == null)
				throw new IOException("Missing " + RESOURCE);
			instance = new NeuralPolicy(NeuralNet.read(in));
		}catch(IOException e)
		{
			loadFailed = true;
			e.printStackTrace();
		}
		return instance;
	}
	
	/** This tick's keys. The returned object is reused every call. */
	public NeuralAction decide(NeuralObservation obs)
	{
		obs.encode(input);
		action.decode(net.forward(input));
		return action;
	}
	
	public int[] getSizes()
	{
		return net.getSizes();
	}
}
