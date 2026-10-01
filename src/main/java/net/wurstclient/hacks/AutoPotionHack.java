/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.Rotation;

@SearchTags({"AutoPotion", "auto potion", "AutoSplashPotion",
	"auto splash potion"})
public final class AutoPotionHack extends Hack implements UpdateListener
{
	private final SliderSetting health = new SliderSetting("Health",
		"Throws a potion when your health reaches this value or falls below it.",
		6, 0.5, 9.5, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts"));
	
	private int timer;
	private float restorePitch = Float.NaN;
	
	public AutoPotionHack()
	{
		super("AutoPotion");
		
		setCategory(Category.COMBAT);
		addSetting(health);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		timer = 0;
		
		if(MC.player != null)
			restorePitch();
	}
	
	private void restorePitch()
	{
		if(Float.isNaN(restorePitch))
			return;
		
		new Rotation(MC.player.getYRot(), restorePitch).applyToClientPlayer();
		restorePitch = Float.NaN;
	}
	
	@Override
	public void onUpdate()
	{
		restorePitch();
		
		// search potion in hotbar
		int potionInHotbar = findPotion(0, 9);
		
		// check if any potion was found
		if(potionInHotbar != -1)
		{
			// check timer
			if(timer > 0)
			{
				timer--;
				return;
			}
			
			// check health
			if(MC.player.getHealth() > health.getValueF() * 2F)
				return;
			
			// save old slot
			int oldSlot = MC.player.getInventory().getSelectedSlot();
			
			// actually look down - the throw packet carries the camera's own
			// rotation, so a faked look packet wouldn't aim the potion anyway
			restorePitch = MC.player.getXRot();
			new Rotation(MC.player.getYRot(), 90).applyToClientPlayer();
			
			// throw potion in hotbar
			MC.player.getInventory().setSelectedSlot(potionInHotbar);
			InteractionSimulator.rightClickItem(InteractionHand.MAIN_HAND);
			
			// reset slot (the pitch goes back up next tick, so this tick's
			// movement packet still shows you looking down)
			MC.player.getInventory().setSelectedSlot(oldSlot);
			
			// reset timer
			timer = 10;
			
			return;
		}
		
		// search potion in inventory
		int potionInInventory = findPotion(9, 36);
		
		// move potion in inventory to hotbar
		if(potionInInventory != -1)
			IMC.getInteractionManager()
				.windowClick_QUICK_MOVE(potionInInventory);
	}
	
	private int findPotion(int startSlot, int endSlot)
	{
		for(int i = startSlot; i < endSlot; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			
			// filter out non-splash potion items
			if(stack.getItem() != Items.SPLASH_POTION)
				continue;
			
			// search for instant health effects
			if(ItemUtils.hasEffect(stack, MobEffects.INSTANT_HEALTH))
				return i;
		}
		
		return -1;
	}
}
