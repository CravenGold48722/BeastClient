/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Random;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.KeyPresser;

/**
 * Skates along the ground in the gliding pose the way a player can in
 * vanilla: elytra bouncing. Only keys are pressed (see {@link KeyPresser}) -
 * vanilla jumps, opens the elytra (its own START_FALL_FLYING when jump is
 * pressed again in the air) and moves; the camera stays yours.
 *
 * <p>
 * Each hop: space goes down on the ground once the glide has ended (the
 * server's "stopped gliding" comes back a tick or so after landing),
 * stays down 1-2 ticks after take-off, is let go for 1-3 ticks and pressed
 * again, which opens the elytra - vanilla only opens it on a fresh press in
 * the air. It's held from there until the landing. Timings vary like a
 * hand's.
 *
 * <p>
 * The old version (ported from CevAPI) claimed to be off the ground while
 * walking, opened the elytra on the ground with its own look packet and set
 * the speed itself (1.5 blocks/tick): Grim GroundSpoof, ElytraB "no jump",
 * TickTimer and Simulation, Intave physics and protocolscanner.
 */
@SearchTags({"elytra walk", "ground skating", "elytra bounce", "ElytraBounce"})
public final class ElytraWalkHack extends Hack implements UpdateListener
{
	private final CheckboxSetting autoElytra =
		new CheckboxSetting("Auto elytra",
			"description.wurst.setting.elytrawalk.auto_elytra", true);
	
	private final CheckboxSetting sprint = new CheckboxSetting("Sprint",
		"Presses your sprint key while bouncing. A sprinting jump pushes you"
			+ " forward, so the hops add up to speed.",
		true);
	
	private final Random random = new Random();
	
	/** Ticks since leaving the ground (0 on it). */
	private int airTicks;
	/** This hop: ticks space stays down after take-off. */
	private int holdAfterTakeoff;
	/** This hop: ticks space is let go before the press that opens it. */
	private int gap;
	/** This hop's elytra press went out; space is held until landing. */
	private boolean deployed;
	/** Ticks to wait after a screen closed before the first hop. */
	private int reactionTicks;
	
	private ItemStack originalChest;
	private boolean warnedMissingElytra;
	private int equipRetry;
	
	public ElytraWalkHack()
	{
		super("ElytraWalk");
		setCategory(Category.MOVEMENT);
		addSetting(autoElytra);
		addSetting(sprint);
	}
	
	@Override
	protected void onEnable()
	{
		resetHop();
		originalChest = null;
		warnedMissingElytra = false;
		equipRetry = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		if(autoElytra.isChecked())
			restoreElytra();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer p = MC.player;
		if(p == null)
			return;
		
		if(equipRetry > 0)
			equipRetry--;
		if(!hasUsableElytra())
		{
			resetHop();
			if(autoElytra.isChecked() && equipRetry == 0 && MC.screen == null)
			{
				equipRetry = 20;
				equipElytra();
			}
			if(!hasUsableElytra() && !warnedMissingElytra)
			{
				ChatUtils.warning("ElytraWalk: waiting for a usable elytra."
					+ " Equip or repair one, or put a spare in your inventory"
					+ " with room to unequip your chest item.");
				warnedMissingElytra = true;
			}
			return;
		}
		warnedMissingElytra = false;
		
		// walking forward drives it; nothing else is touched
		if(!canBounce(p) || !MC.options.keyUp.isDown())
		{
			resetHop();
			// a hand needs a moment after a screen (Auto elytra's inventory)
			// closes before it's on the keys
			if(MC.screen != null)
				reactionTicks = 3 + random.nextInt(4);
			return;
		}
		if(reactionTicks > 0)
		{
			reactionTicks--;
			return;
		}
		
		if(sprint.isChecked() && !p.isSprinting())
			KeyPresser.press(MC.options.keySprint);
		
		if(p.onGround())
		{
			// Still gliding on the ground - the server's "stopped" hasn't
			// come back yet: let go and jump once it has, a tick later. A jump
			// in that tick is vanilla, but Intave's elytra simulation doesn't
			// expect one (physics, a 0.517 offset whenever it happened).
			if(p.isFallFlying())
			{
				airTicks = 0;
				deployed = false;
				return;
			}
			
			// space down: vanilla jumps right away
			KeyPresser.press(MC.options.keyJump);
			startHop();
			return;
		}
		
		airTicks++;
		if(deployed)
		{
			// held until the next landing
			KeyPresser.press(MC.options.keyJump);
			return;
		}
		
		// still gliding from the last hop (the server's "stopped" not here
		// yet): a press now wouldn't open it, wait
		if(p.isFallFlying())
			return;
		
		if(airTicks <= holdAfterTakeoff)
		{
			KeyPresser.press(MC.options.keyJump);
			return;
		}
		
		// let go, then press again: vanilla opens the elytra
		if(airTicks <= holdAfterTakeoff + gap)
			return;
		
		KeyPresser.press(MC.options.keyJump);
		deployed = true;
	}
	
	private void startHop()
	{
		airTicks = 0;
		deployed = false;
		// a hand: mostly a quick tap, now and then a longer one
		holdAfterTakeoff = 1 + (random.nextInt(4) == 0 ? 1 : 0);
		gap = 1 + random.nextInt(random.nextInt(3) == 0 ? 3 : 2);
	}
	
	private void resetHop()
	{
		airTicks = 0;
		deployed = false;
		holdAfterTakeoff = 1;
		gap = 1;
	}
	
	private boolean canBounce(LocalPlayer p)
	{
		return MC.screen == null && !p.isPassenger() && !p.isInWater()
			&& !p.isInLava() && !p.getAbilities().flying && !p.onClimbable();
	}
	
	/**
	 * Whether it's bouncing right now; ElytraPitch leaves the pitch alone
	 * then.
	 */
	public boolean isBouncing()
	{
		return isEnabled() && MC.player != null && hasUsableElytra()
			&& canBounce(MC.player) && MC.options.keyUp.isDown();
	}
	
	private static boolean isUsableElytra(ItemStack stack)
	{
		return stack.is(Items.ELYTRA) && (!stack.isDamageableItem()
			|| stack.getMaxDamage() - stack.getDamageValue() > 1);
	}
	
	private boolean hasUsableElytra()
	{
		return MC.player != null
			&& isUsableElytra(MC.player.getItemBySlot(EquipmentSlot.CHEST));
	}
	
	private boolean hasElytra()
	{
		return MC.player != null
			&& MC.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
	}
	
	/** Shift-clicks, through the real inventory screen (InventoryOpener). */
	private void equipElytra()
	{
		LocalPlayer p = MC.player;
		if(p == null)
			return;
		int slot =
			InventoryUtils.indexOf(ElytraWalkHack::isUsableElytra, 36, false);
		if(slot < 0)
			return;
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if(!chest.isEmpty() && p.getInventory().getFreeSlot() < 0)
			return;
		if(!chest.isEmpty())
		{
			if(originalChest == null && !chest.is(Items.ELYTRA))
				originalChest = chest.copy();
			IMC.getInteractionManager().windowClick_QUICK_MOVE(6);
		}
		slot =
			InventoryUtils.indexOf(ElytraWalkHack::isUsableElytra, 36, false);
		if(slot >= 0)
			IMC.getInteractionManager()
				.windowClick_QUICK_MOVE(InventoryUtils.toNetworkSlot(slot));
	}
	
	private void restoreElytra()
	{
		LocalPlayer p = MC.player;
		if(p == null || originalChest == null || !hasElytra()
			|| p.getInventory().getFreeSlot() < 0)
			return;
		IMC.getInteractionManager().windowClick_QUICK_MOVE(6);
		int slot = InventoryUtils
			.indexOf(stack -> stack.is(originalChest.getItem()), 36, false);
		if(slot >= 0)
			IMC.getInteractionManager()
				.windowClick_QUICK_MOVE(InventoryUtils.toNetworkSlot(slot));
		originalChest = null;
	}
	
	@Override
	public String getStatusText()
	{
		return MC.player != null && !hasUsableElytra()
			? "[Waiting for usable elytra]" : null;
	}
}
