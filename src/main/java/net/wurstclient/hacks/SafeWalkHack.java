/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.KeyPresser;

/**
 * Keeps you from walking off edges.
 *
 * <p>
 * "Sneak at edges" (default): your sneak key goes down - a real press, see
 * {@link KeyPresser} - the tick before your next move would take you over
 * the edge, and vanilla's own sneaking stops you there, like a player
 * bridging. Off: you're stopped at the edge without sneaking, which no
 * vanilla client does (Grim Simulation: sneak-style movement without the
 * sneak input).
 */
@SearchTags({"safe walk", "SneakSafety", "sneak safety", "SpeedBridgeHelper",
	"speed bridge helper"})
public final class SafeWalkHack extends Hack implements UpdateListener
{
	private final CheckboxSetting sneak = new CheckboxSetting("Sneak at edges",
		"Presses your sneak key right before an edge, so vanilla's sneaking"
			+ " stops you there - what a player does.\n\n"
			+ "Off: stops you at edges without sneaking. No vanilla client can,"
			+ " so anti-cheats flag it.",
		true);
	
	private final SliderSetting edgeDistance = new SliderSetting(
		"Sneak edge distance",
		"How close SafeWalk will let you get to the edge before sneaking.\n\n"
			+ "This setting is only used when \"Sneak at edges\" is enabled.",
		0.05, 0.05, 0.25, 0.001, ValueDisplay.DECIMAL.withSuffix("m"));
	
	/** Most a walking/sprinting player speeds up in one tick, plus a bit. */
	private static final double MAX_SPEEDUP = 0.15;
	
	public SafeWalkHack()
	{
		super("SafeWalk");
		setCategory(Category.MOVEMENT);
		addSetting(sneak);
		addSetting(edgeDistance);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().parkourHack.setEnabled(false);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(!sneak.isChecked() || player == null || !player.onGround())
			return;
			
		// where this tick's move can take you: last tick's speed (after
		// friction) plus the most input can add, in the direction you go
		Vec3 v = player.getDeltaMovement().multiply(1, 0, 1);
		double speed = v.horizontalDistance();
		Vec3 next = speed > 1e-4 ? v.add(v.scale(MAX_SPEEDUP / speed)) : v;
		
		if(isOverEdge(player, Vec3.ZERO) || isOverEdge(player, next))
			KeyPresser.press(MC.options.keyShift);
	}
	
	private boolean isOverEdge(LocalPlayer player, Vec3 offset)
	{
		AABB box = player.getBoundingBox().move(offset)
			.expandTowards(0, -player.maxUpStep(), 0)
			.inflate(-edgeDistance.getValue(), 0, -edgeDistance.getValue());
		return MC.level.noCollision(player, box);
	}
	
	/**
	 * With "Sneak at edges" off: vanilla's sneak edge-stop without sneaking
	 * (LocalPlayerMixin.isStayingOnGroundSurface()).
	 */
	public boolean clipsAtEdges()
	{
		return isEnabled() && !sneak.isChecked();
	}
}
