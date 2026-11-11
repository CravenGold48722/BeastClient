/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;

/**
 * Fabric's test thread joins the tick phaser from inside itself, after
 * {@code runTestThread} has already returned to the render thread. On a busy
 * CPU (training next to the test) the render thread got through a whole
 * phase cycle alone first and parked in PHASE_TEST waiting for the test
 * thread's tasks, while the test thread, registered a moment too late,
 * waited for the render thread to arrive at the next phase - both waited
 * forever, before the first test (2026-11-03: 20 minutes at the title screen,
 * the test thread with 0 ms of CPU time). The render thread now waits until
 * the test thread has registered before it goes on with its tick.
 */
@Mixin(value = ThreadingImpl.class, remap = false)
public abstract class ThreadingImplMixin
{
	@Unique
	private static int partiesBefore;
	
	@Inject(method = "runTestThread", at = @At("HEAD"))
	private static void beforeTestThread(Runnable testRunner, CallbackInfo ci)
	{
		partiesBefore = ThreadingImpl.PHASER.getRegisteredParties();
	}
	
	@Inject(method = "runTestThread", at = @At("RETURN"))
	private static void awaitTestThreadRegistered(Runnable testRunner,
		CallbackInfo ci)
	{
		long deadline = System.nanoTime() + 60_000_000_000L;
		while(ThreadingImpl.PHASER.getRegisteredParties() <= partiesBefore
			&& ThreadingImpl.testThread != null
			&& ThreadingImpl.testThread.isAlive()
			&& System.nanoTime() < deadline)
			try
			{
				Thread.sleep(1);
				
			}catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
	}
}
