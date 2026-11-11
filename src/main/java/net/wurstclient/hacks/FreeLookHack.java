/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.Rotation;

/**
 * Looks around without turning the player (or turns the player without
 * moving the camera). Idea from Meteor Client's FreeLook, written for Beast:
 * the camera hooks are in {@code freecam/CameraMixin} and
 * {@code freecam/LocalPlayerMixin}, next to Freecam's, which wins when both
 * are on.
 */
@SearchTags({"free look", "freelook", "look around", "perspective"})
public final class FreeLookHack extends Hack implements UpdateListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"Camera: the mouse turns only the camera, the player keeps facing"
			+ " where it was (the arrow keys turn the player).\n"
			+ "Player: the mouse turns the player as usual, the camera stays"
			+ " (the arrow keys turn the camera).",
		Mode.values(), Mode.CAMERA);
	private final CheckboxSetting thirdPerson = new CheckboxSetting(
		"Third person", "Switches to third person while FreeLook is on.", true);
	private final SliderSetting sensitivity = new SliderSetting(
		"Camera sensitivity", "How fast the mouse turns the camera.", 1, 0.1, 3,
		0.05, ValueDisplay.DECIMAL.withSuffix("x"));
	private final CheckboxSetting arrows = new CheckboxSetting("Arrow keys",
		"The arrow keys turn whatever the mouse doesn't.", true);
	private final SliderSetting arrowSpeed =
		new SliderSetting("Arrow speed", "How fast the arrow keys turn.", 4,
			0.5, 10, 0.5, ValueDisplay.DECIMAL.withSuffix("°/tick"));
	
	private float cameraYaw;
	private float cameraPitch;
	private CameraType oldCameraType;
	
	public FreeLookHack()
	{
		super("FreeLook",
			"Look around without turning, or turn without moving the camera.",
			false);
		setCategory(Category.RENDER);
		addSetting(mode);
		addSetting(thirdPerson);
		addSetting(sensitivity);
		addSetting(arrows);
		addSetting(arrowSpeed);
	}
	
	@Override
	protected void onEnable()
	{
		cameraYaw = MC.player.getYRot();
		cameraPitch = MC.player.getXRot();
		oldCameraType = MC.options.getCameraType();
		if(thirdPerson.isChecked()
			&& oldCameraType != CameraType.THIRD_PERSON_BACK)
			MC.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		if(thirdPerson.isChecked() && oldCameraType != null
			&& MC.options.getCameraType() != oldCameraType)
			MC.options.setCameraType(oldCameraType);
		oldCameraType = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(!arrows.isChecked() || MC.screen != null)
			return;
		
		float step = arrowSpeed.getValueF();
		float yaw = 0;
		float pitch = 0;
		if(isDown(GLFW.GLFW_KEY_LEFT))
			yaw -= step;
		if(isDown(GLFW.GLFW_KEY_RIGHT))
			yaw += step;
		if(isDown(GLFW.GLFW_KEY_UP))
			pitch -= step;
		if(isDown(GLFW.GLFW_KEY_DOWN))
			pitch += step;
		if(yaw == 0 && pitch == 0)
			return;
		
		if(mode.getSelected() == Mode.PLAYER)
		{
			cameraYaw += yaw;
			cameraPitch = Mth.clamp(cameraPitch + pitch, -90, 90);
			return;
		}
		
		// turning the player: whole mouse steps, like a mouse would
		new Rotation(MC.player.getYRot() + yaw,
			Mth.clamp(MC.player.getXRot() + pitch, -90, 90))
				.applyToClientPlayer();
	}
	
	private static boolean isDown(int key)
	{
		return InputConstants.isKeyDown(MC.getWindow(), key);
	}
	
	/** For {@code LocalPlayerMixin}: whether the mouse turns the camera. */
	public boolean isTurningCamera()
	{
		return isEnabled() && mode.getSelected() == Mode.CAMERA;
	}
	
	/** For {@code LocalPlayerMixin}: the mouse's turn, in vanilla's units. */
	public void turn(double deltaYaw, double deltaPitch)
	{
		double scale = 0.15 * sensitivity.getValue();
		cameraYaw += (float)(deltaYaw * scale);
		cameraPitch =
			Mth.clamp(cameraPitch + (float)(deltaPitch * scale), -90, 90);
	}
	
	public float getCameraYaw()
	{
		return cameraYaw;
	}
	
	public float getCameraPitch()
	{
		return cameraPitch;
	}
	
	private enum Mode
	{
		CAMERA("Camera"),
		PLAYER("Player");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
