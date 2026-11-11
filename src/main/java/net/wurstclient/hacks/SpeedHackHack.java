/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.KeyPresser;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"speed hack"})
public final class SpeedHackHack extends Hack implements UpdateListener
{
	private final SliderSetting multiplier = new SliderSetting("Multiplier",
		"How much each mini jump multiplies your speed by. The top speed"
			+ " grows with it (1.8x: 0.66 blocks a tick, the most that"
			+ " worked on NoCheat+).",
		1.8, 1, 100, 0.1, ValueDisplay.DECIMAL.withSuffix("x"));
	
	public SpeedHackHack()
	{
		super("SpeedHack");
		setCategory(Category.MOVEMENT);
		addSetting(multiplier);
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
	}
	
	@Override
	public void onUpdate()
	{
		// return if sneaking or not walking
		if(MC.player.isShiftKeyDown()
			|| MC.player.zza == 0 && MC.player.xxa == 0)
			return;
		
		// activate sprint if walking forward
		if(MC.player.zza > 0 && !MC.player.horizontalCollision)
			KeyPresser.press(MC.options.keySprint);
		
		// activate mini jump if on ground
		if(!MC.player.onGround())
			return;
		
		Vec3 v = MC.player.getDeltaMovement();
		double m = multiplier.getValue();
		MC.player.setDeltaMovement(v.x * m, v.y + 0.1, v.z * m);
		
		v = MC.player.getDeltaMovement();
		double currentSpeed = Math.sqrt(Math.pow(v.x, 2) + Math.pow(v.z, 2));
		
		// limit speed to highest value that works on NoCheat+ version
		// 3.13.0-BETA-sMD5NET-b878
		// UPDATE: Patched in NoCheat+ version 3.13.2-SNAPSHOT-sMD5NET-b888
		// (scaled with the multiplier: at the default 1.8x it's that value)
		double maxSpeed = 0.66F * m / 1.8;
		
		if(currentSpeed > maxSpeed)
			MC.player.setDeltaMovement(v.x / currentSpeed * maxSpeed, v.y,
				v.z / currentSpeed * maxSpeed);
	}
}
