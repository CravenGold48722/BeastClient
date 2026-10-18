/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.SdlUtils;
import net.wurstclient.util.KeyPresser;

@SearchTags({"wind charge", "auto wind charge", "windcharge"})
public final class WindChargeKeyHack extends Hack implements UpdateListener
{
	// The CevAPI original registered a vanilla key mapping, which would show
	// up in the controls menu (and to servers probing translation keys).
	private final TextFieldSetting activationKey = new TextFieldSetting("Key",
		"The key that throws a wind charge while WindChargeKey is on, e.g."
			+ " \"space\", \"r\" or \"mouse.4\".",
		"space");
	private boolean keyWasDown;
	
	private final SliderSetting switchDelayMs = new SliderSetting(
		"Switch delay", 50, 0, 500, 5, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting throwDelayMs = new SliderSetting("Throw delay",
		200, 0, 1000, 25, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final CheckboxSetting silentMode = new CheckboxSetting("Silent",
		"Switches to the wind charge, uses it, and immediately switches back"
			+ " within the same tick.",
		false);
	
	private final CheckboxSetting autoJump = new CheckboxSetting("Auto jump",
		"Automatically jumps before throwing if you are on the ground.", true);
	
	private final CheckboxSetting lookDownBeforeThrow = new CheckboxSetting(
		"Look down",
		"Faces straight down before throwing to maximize launch height.", true);
	
	private final SliderSetting jumpDelayMs = new SliderSetting("Jump delay",
		10, 0, 200, 5, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private int originalSlot = -1;
	private boolean needsSlotRestore;
	private boolean firstThrow = true;
	private boolean jumpScheduled;
	private long lastThrowTime;
	private long switchBackTime;
	private long jumpStartTime;
	private boolean awaitingLaunch;
	private int pendingSlot = -1;
	private boolean pendingSilent;
	private boolean pendingLookDown;
	private long launchDeadline;
	private boolean restorePitch;
	private float savedPitch;
	private long restorePitchAt;
	
	public WindChargeKeyHack()
	{
		super("WindChargeKey");
		setCategory(Category.COMBAT);
		addPossibleKeybind("windchargekey use",
			"Use WindCharge (while WindChargeKey is enabled)");
		
		addSetting(activationKey);
		addSetting(switchDelayMs);
		addSetting(throwDelayMs);
		addSetting(silentMode);
		addSetting(autoJump);
		addSetting(lookDownBeforeThrow);
		addSetting(jumpDelayMs);
	}
	
	@Override
	protected void onEnable()
	{
		resetState();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		if(needsSlotRestore && originalSlot != -1 && MC.player != null)
			MC.player.getInventory().setSelectedSlot(originalSlot);
		
		resetState();
	}
	
	private boolean isActivationKeyDown()
	{
		String name = activationKey.getValue().trim().toLowerCase();
		if(name.isEmpty())
			return false;
		
		String translationKey =
			name.startsWith("mouse.") ? "key." + name : "key.keyboard." + name;
		InputConstants.Key key;
		try
		{
			key = InputConstants.getKey(translationKey);
			
		}catch(IllegalArgumentException e)
		{
			return false;
		}
		
		if(key.getType() == InputConstants.Type.MOUSE)
			return SdlUtils.isMouseButtonPressed(key.getValue());
		
		return SdlUtils.getKeyState(key.getValue()) == InputConstants.PRESS;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		if(MC.screen != null)
			return;
		
		boolean keyDown = isActivationKeyDown();
		if(keyDown && !keyWasDown)
			handleWindChargeThrow();
		keyWasDown = keyDown;
		
		long now = System.currentTimeMillis();
		
		if(jumpScheduled && autoJump.isChecked()
			&& now - jumpStartTime >= getJumpDelay())
		{
			jumpScheduled = false;
			
			if(MC.player.onGround())
				KeyPresser.press(MC.options.keyJump);
		}
		
		if(awaitingLaunch)
		{
			boolean airborne = !MC.player.onGround();
			boolean upward = MC.player.getDeltaMovement().y > 0.08;
			boolean ready = upward || (!autoJump.isChecked() && airborne)
				|| now >= launchDeadline;
			
			if(ready)
			{
				awaitingLaunch = false;
				int slot = pendingSlot;
				boolean silent = pendingSilent;
				boolean lookDown = pendingLookDown;
				clearPending();
				performThrow(slot, silent, lookDown);
			}
		}
		
		if(restorePitch && now >= restorePitchAt)
			restorePitchImmediate(savedPitch);
		
		if(needsSlotRestore && now - switchBackTime >= getSwitchDelay())
		{
			if(originalSlot != -1
				&& originalSlot != MC.player.getInventory().getSelectedSlot())
				MC.player.getInventory().setSelectedSlot(originalSlot);
			
			needsSlotRestore = false;
			originalSlot = -1;
		}
		
	}
	
	private void handleWindChargeThrow()
	{
		long now = System.currentTimeMillis();
		
		if(awaitingLaunch)
			return;
		
		if(!firstThrow && now - lastThrowTime < getThrowDelay())
			return;
		
		int windChargeSlot = findWindChargeInHotbar();
		if(windChargeSlot == -1)
			return;
		
		boolean silent = silentMode.isChecked();
		boolean lookDown = lookDownBeforeThrow.isChecked();
		
		if(autoJump.isChecked() && MC.player.onGround())
		{
			clearPending();
			pendingSlot = windChargeSlot;
			pendingSilent = silent;
			pendingLookDown = lookDown;
			launchDeadline = now + Math.max(250L, getJumpDelay() + 200L);
			awaitingLaunch = true;
			scheduleJump(now);
			return;
		}
		
		performThrow(windChargeSlot, silent, lookDown);
	}
	
	public void useFromKeybind()
	{
		if(MC.player == null || MC.level == null || MC.screen != null)
			return;
		if(!isEnabled())
			return;
		handleWindChargeThrow();
	}
	
	private void scheduleJump(long now)
	{
		long delay = getJumpDelay();
		if(delay <= 0)
		{
			if(MC.player.onGround())
				KeyPresser.press(MC.options.keyJump);
			return;
		}
		
		jumpScheduled = true;
		jumpStartTime = now;
	}
	
	private void performThrow(int preferredSlot, boolean silent,
		boolean lookDown)
	{
		clearPending();
		
		int slot = resolveWindChargeSlot(preferredSlot);
		if(slot == -1)
			return;
		
		float previousPitch = 0F;
		boolean appliedLookDown = lookDown && lookDownBeforeThrow.isChecked();
		if(appliedLookDown)
			previousPitch = applyLookDown();
		
		long now = System.currentTimeMillis();
		boolean success = silent ? throwWindChargeSilently(slot)
			: throwWindChargeNormally(slot, now);
		
		if(!success)
		{
			if(appliedLookDown)
				restorePitchImmediate(previousPitch);
			return;
		}
		
		if(appliedLookDown)
		{
			savedPitch = previousPitch;
			restorePitch = true;
			restorePitchAt = now + 150;
		}else
		{
			restorePitch = false;
			restorePitchAt = 0;
		}
		
		firstThrow = false;
		lastThrowTime = now;
	}
	
	private int resolveWindChargeSlot(int preferredSlot)
	{
		if(preferredSlot >= 0 && preferredSlot < 9)
		{
			ItemStack stack = MC.player.getInventory().getItem(preferredSlot);
			if(stack.is(Items.WIND_CHARGE))
				return preferredSlot;
		}
		
		return findWindChargeInHotbar();
	}
	
	private float applyLookDown()
	{
		float oldPitch = MC.player.getXRot();
		float downwardPitch = 90F;
		MC.player.setXRot(downwardPitch);
		MC.player.connection.send(new ServerboundMovePlayerPacket.Rot(
			MC.player.getYRot(), downwardPitch, MC.player.onGround(),
			MC.player.horizontalCollision));
		return oldPitch;
	}
	
	private void restorePitchImmediate(float pitch)
	{
		if(MC.player == null)
			return;
		
		MC.player.setXRot(pitch);
		MC.player.connection
			.send(new ServerboundMovePlayerPacket.Rot(MC.player.getYRot(),
				pitch, MC.player.onGround(), MC.player.horizontalCollision));
		restorePitch = false;
		restorePitchAt = 0;
	}
	
	private void clearPending()
	{
		awaitingLaunch = false;
		pendingSlot = -1;
		pendingSilent = false;
		pendingLookDown = false;
		launchDeadline = 0;
		jumpScheduled = false;
		jumpStartTime = 0;
	}
	
	private boolean throwWindChargeSilently(int slot)
	{
		int currentSlot = MC.player.getInventory().getSelectedSlot();
		MC.player.getInventory().setSelectedSlot(slot);
		boolean success = useSelectedItem();
		MC.player.getInventory().setSelectedSlot(currentSlot);
		return success;
	}
	
	private boolean throwWindChargeNormally(int slot, long now)
	{
		originalSlot = MC.player.getInventory().getSelectedSlot();
		MC.player.getInventory().setSelectedSlot(slot);
		
		boolean success = useSelectedItem();
		
		if(success)
		{
			needsSlotRestore = true;
			switchBackTime = now;
			return true;
		}
		
		MC.player.getInventory().setSelectedSlot(originalSlot);
		originalSlot = -1;
		return false;
	}
	
	private boolean useSelectedItem()
	{
		if(MC.player == null || MC.gameMode == null)
			return false;
		
		ItemStack stack = MC.player.getMainHandItem();
		if(stack.isEmpty())
			return false;
		
		InteractionResult result =
			MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
		
		if(result.consumesAction())
			MC.player.swing(InteractionHand.MAIN_HAND);
		
		return result.consumesAction();
	}
	
	private int findWindChargeInHotbar()
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.is(Items.WIND_CHARGE))
				return i;
		}
		
		return -1;
	}
	
	private long getSwitchDelay()
	{
		return Math.max(0L, Math.round(switchDelayMs.getValue()));
	}
	
	private long getThrowDelay()
	{
		return Math.max(0L, Math.round(throwDelayMs.getValue()));
	}
	
	private long getJumpDelay()
	{
		return Math.max(0L, Math.round(jumpDelayMs.getValue()));
	}
	
	private void resetState()
	{
		clearPending();
		
		if(restorePitch && MC.player != null)
			restorePitchImmediate(savedPitch);
		
		originalSlot = -1;
		needsSlotRestore = false;
		firstThrow = true;
		lastThrowTime = 0;
		switchBackTime = 0;
		restorePitch = false;
		restorePitchAt = 0;
		savedPitch = 0F;
	}
}
