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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Restock telling potions apart: with a splash potion of Harming first in the
 * inventory and one of Healing after it, restocking splash potions into a
 * hotbar slot has to bring the Healing one (the default "Potion effect").
 */
public final class RestockHackTest extends SingleplayerTest
{
	private static final int HOTBAR_SLOT = 8;
	
	public RestockHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing Restock with splash potions");
		
		try
		{
			clearInventory();
			runCommand("item replace entity @s inventory.0 with"
				+ " minecraft:splash_potion[minecraft:potion_contents="
				+ "{potion:\"minecraft:harming\"}]");
			runCommand("item replace entity @s inventory.1 with"
				+ " minecraft:splash_potion[minecraft:potion_contents="
				+ "{potion:\"minecraft:healing\"}]");
			context.waitTicks(2);
			
			runWurstCommand(
				"itemlist Restock Items add minecraft:splash_potion");
			runWurstCommand("setslider Restock Slot " + HOTBAR_SLOT);
			runWurstCommand("t Restock on");
			context.waitTicks(20);
			
			ItemStack restocked = context.computeOnClient(
				mc -> mc.player.getInventory().getItem(HOTBAR_SLOT).copy());
			boolean harmingLeft = context.computeOnClient(mc -> {
				for(int i = 9; i < 36; i++)
					if(hasEffect(mc.player.getInventory().getItem(i), true))
						return true;
				return false;
			});
			logger.info("Restocked into slot {}: {}, harming still in the"
				+ " inventory: {}", HOTBAR_SLOT, restocked, harmingLeft);
			
			if(!restocked.is(Items.SPLASH_POTION)
				|| !hasEffect(restocked, false))
				throw new RuntimeException(
					"Restock didn't bring the splash potion of Healing: "
						+ restocked);
			if(!harmingLeft)
				throw new RuntimeException(
					"Restock moved the splash potion of Harming");
			
		}finally
		{
			runWurstCommand("t Restock off");
			runWurstCommand("itemlist Restock Items reset");
			runWurstCommand("setslider Restock Slot 0");
			context.waitTicks(10); // the inventory it opened closes again
			clearInventory();
			clearChat();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(5);
		}
	}
	
	private static boolean hasEffect(ItemStack stack, boolean harming)
	{
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if(contents == null)
			return false;
		for(var effect : contents.getAllEffects())
			if(effect.is(harming ? MobEffects.INSTANT_DAMAGE
				: MobEffects.INSTANT_HEALTH))
				return true;
		return false;
	}
}
