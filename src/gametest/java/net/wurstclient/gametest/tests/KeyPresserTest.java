/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.util.KeyPresser;

public final class KeyPresserTest extends SingleplayerTest
{
	public KeyPresserTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing KeyPresser");
		
		// Presses that run out while the player ticks. Letting a press expire
		// used to crash the game a few seconds after joining a world.
		context.runOnClient(mc -> {
			KeyPresser.press(mc.options.keyJump);
			KeyPresser.press(mc.options.keySprint, 3);
		});
		context.waitTicks(5);
		
		if(context.computeOnClient(
			mc -> mc.options.keyJump.isDown() || mc.options.keySprint.isDown()))
			throw new RuntimeException("KeyPresser didn't let go of a key");
		
		context.waitFor(mc -> mc.player.onGround());
		context.waitTick();
	}
}
