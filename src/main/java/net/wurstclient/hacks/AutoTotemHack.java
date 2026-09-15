/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.hack.HackList;
import net.wurstclient.mixinterface.IMultiPlayerGameMode;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"auto totem", "offhand", "off-hand"})
public final class AutoTotemHack extends Hack
	implements UpdateListener, PacketInputListener
{
	private final CheckboxSetting showCounter = new CheckboxSetting(
		"Show totem counter", "Displays the number of totems you have.", true);
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"Amount of ticks to wait before equipping the next totem.\n\n"
			+ "Ignored right after a totem pops - the replacement always goes"
			+ " in immediately.",
		0, 0, 20, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting health = new SliderSetting("Health",
		"Won't equip a totem until your health reaches this value or falls"
			+ " below it.\n" + "0 = always active\n\n"
			+ "Ignored right after a totem pops.",
		0, 0, 10, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts")
			.withLabel(1, "1 heart").withLabel(0, "ignore"));
	
	private final CheckboxSetting overrideInput =
		new CheckboxSetting("Override input",
			"Freezes the game's input handling while a replacement totem is"
				+ " going in, so nothing can interfere with the swap.\n\n"
				+ "For the length of the freeze, movement, jumping, sneaking,"
				+ " sprinting, every keybind, and all attacking and item use -"
				+ " yours and other hacks' alike - are dropped on the floor."
				+ " Getting the totem back into your offhand outranks all of"
				+ " it.",
			true);
	
	private final SliderSetting freezeTicks = new SliderSetting("Freeze ticks",
		"How many ticks the input freeze lasts once a totem pops.\n\n"
			+ "The swap itself takes two ticks when the offhand isn't empty -"
			+ " one to put the totem in, one to put the displaced item back -"
			+ " so 2 is the shortest value that covers a whole swap.\n\n"
			+ "0 disables the freeze without turning off the setting above.",
		2, 0, 10, 1, ValueDisplay.INTEGER);
	
	private int nextTickSlot;
	private int totems;
	private int timer;
	private boolean wasTotemInOffhand;
	
	/**
	 * Set from the network thread the instant the server tells us a totem
	 * popped, and consumed on a following client tick. Volatile because it is
	 * written off-thread.
	 */
	private volatile boolean totemPopped;
	
	/**
	 * How many more ticks the pop still counts as urgent.
	 *
	 * <p>
	 * The pop effect and the packet that empties the offhand slot don't
	 * necessarily land on the same tick, so urgency has to survive a few ticks
	 * of the offhand still looking full. It's bounded so a pop we could never
	 * act on (no totems left, container open) can't silently freeze the
	 * player's input minutes later.
	 */
	private int urgentTicksLeft;
	
	/** How long a pop stays urgent, in ticks. */
	private static final int URGENT_WINDOW_TICKS = 60;
	
	/**
	 * How many more ticks the game's input is frozen for while a totem goes
	 * back into the offhand.
	 *
	 * <p>
	 * Volatile and armed straight from the network thread, so the freeze is
	 * already in effect for whatever the client does next after the pop
	 * arrives - it doesn't wait for our own tick to come around.
	 */
	private volatile int freezeTicksLeft;
	
	public AutoTotemHack()
	{
		super("AutoTotem");
		setCategory(Category.COMBAT);
		addSetting(showCounter);
		addSetting(delay);
		addSetting(health);
		addSetting(overrideInput);
		addSetting(freezeTicks);
	}
	
	@Override
	public String getRenderName()
	{
		if(!showCounter.isChecked())
			return getName();
		
		if(totems == 1)
			return getName() + " [1 totem]";
		
		return getName() + " [" + totems + " totems]";
	}
	
	@Override
	protected void onEnable()
	{
		nextTickSlot = -1;
		totems = 0;
		timer = 0;
		wasTotemInOffhand = false;
		totemPopped = false;
		urgentTicksLeft = 0;
		freezeTicksLeft = 0;
		
		// Registered at the front of the listener list so that a totem is back
		// in the offhand before any other hack gets a chance to run that tick.
		EVENTS.addFirst(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		freezeTicksLeft = 0;
		totemPopped = false;
		urgentTicksLeft = 0;
	}
	
	/**
	 * Watches for the server-sent "this entity was saved by a totem" effect so
	 * a replacement can go in on the very next tick, instead of waiting to
	 * notice that the offhand went empty.
	 */
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!(event.getPacket() instanceof ClientboundEntityEventPacket packet))
			return;
		
		if(packet.getEventId() != EntityEvent.PROTECTED_FROM_DEATH)
			return;
			
		// This runs on the network thread, so everything it touches is read
		// defensively.
		try
		{
			ClientLevel level = MC.level;
			if(level == null || MC.player == null)
				return;
			
			Entity entity = packet.getEntity(level);
			if(entity == MC.player)
			{
				totemPopped = true;
				// Armed here rather than on our next tick so the freeze is
				// already up for the rest of this one. If it turns out there
				// is no totem to equip, the next tick drops it again.
				freezeTicksLeft = freezeTicks.getValueI();
			}
			
		}catch(Exception e)
		{
			// A torn read of the entity list is harmless here - worst case we
			// miss one pop and fall back to the normal offhand check below.
		}
	}
	
	/**
	 * Whether the game's input should be thrown away right now because a totem
	 * is being swapped in.
	 *
	 * <p>
	 * Read from ClientInputMixin (movement, jumping, sneaking, sprinting),
	 * MinecraftMixin (keybinds and held-down attacks) and
	 * MultiPlayerGameModeMixin (attacks and item use, whether they come from
	 * the player or from another hack).
	 */
	public boolean isFreezingInput()
	{
		return isEnabled() && overrideInput.isChecked() && freezeTicksLeft > 0;
	}
	
	/**
	 * Null-safe version of {@link #isFreezingInput()} for mixins, which can
	 * run before the hack list exists.
	 */
	public static boolean isInputFrozen()
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		return hax != null && hax.autoTotemHack.isFreezingInput();
	}
	
	@Override
	public void onUpdate()
	{
		try
		{
			updateTotem();
			
		}finally
		{
			// Exactly one tick of the freeze is spent per tick, whichever
			// branch above returned early.
			if(freezeTicksLeft > 0)
				freezeTicksLeft--;
		}
	}
	
	private void updateTotem()
	{
		finishMovingTotem();
		
		int nextTotemSlot = searchForTotems();
		
		// Open the urgency window when a pop is first seen, then let it tick
		// down. It can't be consumed right here, because the offhand slot may
		// not have been emptied by the server yet.
		if(totemPopped)
		{
			totemPopped = false;
			urgentTicksLeft = URGENT_WINDOW_TICKS;
		}else if(urgentTicksLeft > 0)
			urgentTicksLeft--;
		
		if(isTotem(MC.player.getOffhandItem()))
		{
			// A totem is in place, so there is nothing urgent left to do.
			urgentTicksLeft = 0;
			wasTotemInOffhand = true;
			
			// The freeze isn't cleared here. A pop and the packet that empties
			// the offhand can land a couple of ticks apart, so a totem sitting
			// there right now doesn't mean the swap is over - it may not have
			// started yet. Letting the counter run out covers that gap.
			
			return;
		}
		
		if(wasTotemInOffhand)
		{
			timer = delay.getValueI();
			wasTotemInOffhand = false;
		}
		
		// Nothing to equip, so don't sit on a freeze that can't pay off.
		if(nextTotemSlot == -1)
		{
			freezeTicksLeft = 0;
			return;
		}
		
		// don't move items while a container is open
		if(MC.screen instanceof AbstractContainerScreen
			&& !(MC.screen instanceof InventoryScreen
				|| MC.screen instanceof CreativeModeInventoryScreen))
		{
			freezeTicksLeft = 0;
			return;
		}
		
		// A pop outranks everything: it skips the health gate, skips the delay,
		// and re-arms the input freeze from this tick, so the swap and the
		// follow-up click that puts the displaced item back are both covered.
		if(urgentTicksLeft > 0)
		{
			urgentTicksLeft = 0;
			timer = 0;
			if(overrideInput.isChecked())
				freezeTicksLeft = freezeTicks.getValueI();
			moveToOffhand(nextTotemSlot);
			return;
		}
		
		float healthF = health.getValueF();
		if(healthF > 0 && MC.player.getHealth() > healthF * 2F)
			return;
		
		if(timer > 0)
		{
			timer--;
			return;
		}
		
		moveToOffhand(nextTotemSlot);
	}
	
	private void moveToOffhand(int itemSlot)
	{
		boolean offhandEmpty = MC.player.getOffhandItem().isEmpty();
		
		IMultiPlayerGameMode im = IMC.getInteractionManager();
		im.windowClick_PICKUP(itemSlot);
		im.windowClick_PICKUP(45);
		
		if(!offhandEmpty)
			nextTickSlot = itemSlot;
	}
	
	private void finishMovingTotem()
	{
		if(nextTickSlot == -1)
			return;
		
		IMultiPlayerGameMode im = IMC.getInteractionManager();
		im.windowClick_PICKUP(nextTickSlot);
		nextTickSlot = -1;
	}
	
	private int searchForTotems()
	{
		totems = InventoryUtils.count(this::isTotem, 40, true);
		if(totems <= 0)
			return -1;
		
		int totemSlot = InventoryUtils.indexOf(this::isTotem, 40);
		return InventoryUtils.toNetworkSlot(totemSlot);
	}
	
	private boolean isTotem(ItemStack stack)
	{
		return stack.is(Items.TOTEM_OF_UNDYING);
	}
}
