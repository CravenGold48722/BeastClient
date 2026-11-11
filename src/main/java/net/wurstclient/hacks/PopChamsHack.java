/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.GameClock;
import net.wurstclient.util.RenderUtils;

/**
 * A ghost of the player's hitbox where they popped a totem, rising and
 * fading. Idea from Meteor Client's PopChams (which draws a wireframe of the
 * player model), written for Beast.
 */
@SearchTags({"pop chams", "totem pop", "popchams"})
public final class PopChamsHack extends Hack
	implements PacketInputListener, UpdateListener, RenderListener
{
	private final SliderSetting time =
		new SliderSetting("Time", "How long a ghost stays.", 1, 0.1, 6, 0.1,
			ValueDisplay.DECIMAL.withSuffix("s"));
	private final SliderSetting rise = new SliderSetting("Rise",
		"How fast the ghost moves up (blocks per second).", 0.75, -4, 4, 0.05,
		ValueDisplay.DECIMAL);
	private final CheckboxSetting onlyOne = new CheckboxSetting("Only one",
		"Only the latest ghost per player.", false);
	private final CheckboxSetting fade =
		new CheckboxSetting("Fade out", "Fades the ghost away.", true);
	private final ColorSetting color = new ColorSetting("Color",
		"The ghost's color.", new Color(255, 255, 255));
	
	/** Entity IDs from the netty thread, handled in the next tick. */
	private final ConcurrentLinkedQueue<Integer> pops =
		new ConcurrentLinkedQueue<>();
	private final ArrayList<Ghost> ghosts = new ArrayList<>();
	
	public PopChamsHack()
	{
		super("PopChams", "Shows a ghost where another player popped a totem.",
			false);
		setCategory(Category.RENDER);
		addSetting(time);
		addSetting(rise);
		addSetting(onlyOne);
		addSetting(fade);
		addSetting(color);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		pops.clear();
		ghosts.clear();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		// netty thread: just note who popped
		if(event.getPacket() instanceof ClientboundEntityEventPacket packet
			&& packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH)
			pops.add(packet.entityId);
	}
	
	@Override
	public void onUpdate()
	{
		Integer id;
		while((id = pops.poll()) != null)
		{
			Entity entity = MC.level.getEntity(id);
			if(!(entity instanceof Player player) || player == MC.player)
				continue;
			
			UUID uuid = player.getUUID();
			if(onlyOne.isChecked())
				ghosts.removeIf(g -> g.uuid.equals(uuid));
			ghosts.add(
				new Ghost(uuid, player.getBoundingBox(), GameClock.nanoTime()));
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		long now = GameClock.nanoTime();
		double maxAge = time.getValue();
		ghosts.removeIf(g -> (now - g.startNanos) / 1e9 > maxAge);
		
		for(Ghost ghost : ghosts)
		{
			double age = (now - ghost.startNanos) / 1e9;
			AABB box = ghost.box.move(0, rise.getValue() * age, 0);
			double left = fade.isChecked() ? 1 - age / maxAge : 1;
			int rgb = color.getColorI() & 0xFFFFFF;
			RenderUtils.drawSolidBox(matrixStack, box,
				(int)(0x30 * left) << 24 | rgb, false);
			RenderUtils.drawOutlinedBox(matrixStack, box,
				(int)(0xC0 * left) << 24 | rgb, false);
		}
	}
	
	private record Ghost(UUID uuid, AABB box, long startNanos)
	{}
}
