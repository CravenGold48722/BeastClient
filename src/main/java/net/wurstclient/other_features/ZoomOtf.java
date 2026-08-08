/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.events.MouseScrollListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.MathUtils;

@SearchTags({"telescope", "optifine"})
@DontBlock
public final class ZoomOtf extends OtherFeature
	implements MouseScrollListener, UpdateListener
{
	/**
	 * The widest field of view the game can still draw sensibly. Zooming out
	 * past this point would push the projection matrix towards a degenerate
	 * frustum, so the FOV saturates here instead.
	 */
	private static final float MAX_FOV = 170F;
	
	/**
	 * How far the third person camera is allowed to be pushed back by the zoom,
	 * as a multiple of its normal distance. Matching the zoom level exactly
	 * keeps your character the same apparent size, but past a few times the
	 * normal distance the camera just ends up clipping into whatever is behind
	 * you, so it stops there.
	 */
	private static final double MAX_CAMERA_DISTANCE_FACTOR = 5;
	
	/** The strongest zoom that can be reached. 12700% is 128x magnification. */
	private static final double MAX_PERCENT = 12700;
	
	/**
	 * The weakest zoom that can be reached.
	 *
	 * <p>
	 * -60% is 0.4x, which works out to a 175 degree field of view at the
	 * default FOV setting. That is just past where {@link #MAX_FOV} takes over,
	 * so it is about as far out as zooming can usefully go.
	 */
	private static final double MIN_PERCENT = -60;
	
	/**
	 * How much gentler a scroll notch is below 0% than above it.
	 *
	 * <p>
	 * Zooming out only spans 1x down to 0.4x, while zooming in spans 1x up to
	 * 128x. Raising the step ratio to this power is what makes a notch cover
	 * the same share of the range in either direction, so the whole zoom-out
	 * range takes about as many notches as the whole zoom-in range.
	 */
	private static final double ZOOM_OUT_STEP_SCALE =
		Math.log(1 / (1 + MIN_PERCENT / 100)) / Math.log(1 + MAX_PERCENT / 100);
	
	private final SliderSetting level = new SliderSetting("Zoom level",
		"How far to zoom in when you first press the zoom key.\n\n"
			+ "0% is normal vision, 100% is twice as close, and 12700% is the"
			+ " 128x maximum. Negative values zoom back out to a wide angle"
			+ " view instead.",
		0, MIN_PERCENT, MAX_PERCENT, 1, ValueDisplay.INTEGER.withSuffix("%"));
	
	private final CheckboxSetting scroll = new CheckboxSetting(
		"Use mouse wheel", "If enabled, you can use the mouse wheel while"
			+ " zooming to zoom in even further.",
		true);
	
	private final SliderSetting scrollSpeed = new SliderSetting("Scroll speed",
		"How much one notch of the mouse wheel changes the zoom.\n\n"
			+ "This is a step size rather than a fixed amount, so a notch"
			+ " covers the same proportion of the zoom whether you are at 10%"
			+ " or at 10000%.",
		10, 1, 100, 1, ValueDisplay.INTEGER.withSuffix("%"));
	
	private final CheckboxSetting zoomInScreens = new CheckboxSetting(
		"Zoom in screens", "If enabled, you can also zoom while a screen (chat,"
			+ " inventory, etc.) is open.",
		false);
	
	private final CheckboxSetting showPercentage =
		new CheckboxSetting("Show percentage",
			"Shows the current zoom level on the action bar while you are"
				+ " zooming, and keeps it up to date as you scroll.",
			true);
	
	private final CheckboxSetting thirdPersonCamera = new CheckboxSetting(
		"Third-person camera",
		"Moves the camera further back as you zoom in while in third person, so"
			+ " that you zoom in on the world instead of on your own"
			+ " character.\n\n" + "Has no effect in first person.",
		true);
	
	private final TextFieldSetting keybind = new TextFieldSetting("Keybind",
		"Determines the zoom keybind.\n\n"
			+ "Instead of editing this value manually, you should go to Wurst"
			+ " Options -> Zoom and set it there.",
		"key.keyboard.v", this::isValidKeybind);
	
	/**
	 * The zoom currently in effect, as a magnification factor rather than a
	 * percentage. 1 is normal vision.
	 *
	 * <p>
	 * The scroll wheel steps this multiplicatively, which is what keeps a notch
	 * feeling the same at every zoom, so the factor is the natural thing to
	 * keep around. Percentages are derived from it wherever one is shown.
	 */
	private Double currentZoom;
	
	private Double defaultMouseSensitivity;
	
	/** Whether the action bar is currently showing our zoom readout. */
	private boolean showingPercentage;
	
	public ZoomOtf()
	{
		super("Zoom", "Allows you to zoom in.\n"
			+ "By default, the zoom is activated by pressing the \u00a7lV\u00a7r key.\n"
			+ "Go to Wurst Options -> Zoom to change this keybind.");
		addSetting(level);
		addSetting(scroll);
		addSetting(scrollSpeed);
		addSetting(zoomInScreens);
		addSetting(showPercentage);
		addSetting(thirdPersonCamera);
		addSetting(keybind);
		EVENTS.add(MouseScrollListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	public float changeFovBasedOnZoom(float fov)
	{
		OptionInstance<Double> mouseSensitivitySetting =
			MC.options.sensitivity();
		
		if(currentZoom == null)
			currentZoom = toMultiplier(level.getValue());
		
		if(!isZoomKeyPressed())
		{
			currentZoom = toMultiplier(level.getValue());
			
			if(defaultMouseSensitivity != null)
			{
				mouseSensitivitySetting.set(defaultMouseSensitivity);
				defaultMouseSensitivity = null;
			}
			
			return fov;
		}
		
		if(defaultMouseSensitivity == null)
			defaultMouseSensitivity = mouseSensitivitySetting.get();
			
		// Adjust mouse sensitivity in relation to zoom level. Zooming out
		// pushes this above the default, so it has to be clamped to the range
		// the option accepts - anything outside it would be rejected outright
		// and reset the sensitivity to its default.
		mouseSensitivitySetting.set(
			MathUtils.clamp(defaultMouseSensitivity / currentZoom, 0.0, 1.0));
		
		return (float)Math.min(fov / currentZoom, MAX_FOV);
	}
	
	/**
	 * Converts a zoom percentage into the magnification factor it stands for.
	 */
	private static double toMultiplier(double percent)
	{
		return 1 + percent / 100;
	}
	
	/** Converts a magnification factor back into a zoom percentage. */
	private static double toPercent(double multiplier)
	{
		return (multiplier - 1) * 100;
	}
	
	/** The zoom in effect right now, falling back to the configured start. */
	private double currentMultiplier()
	{
		return currentZoom != null ? currentZoom
			: toMultiplier(level.getValue());
	}
	
	/**
	 * Pushes the third person camera back in step with the zoom, so that
	 * zooming in third person magnifies the world rather than your own
	 * character. Called from CameraMixin, which only reaches this in third
	 * person.
	 */
	public float changeCameraDistanceBasedOnZoom(float distance)
	{
		if(!thirdPersonCamera.isChecked() || !isZoomKeyPressed())
			return distance;
		
		double zoom = currentMultiplier();
		
		return (float)(distance * Math.min(zoom, MAX_CAMERA_DISTANCE_FACTOR));
	}
	
	/**
	 * Keeps the zoom readout on the action bar current for as long as the zoom
	 * key is held, and takes it back down again once it isn't.
	 */
	@Override
	public void onUpdate()
	{
		if(MC.gui == null)
			return;
		
		boolean showing = showPercentage.isChecked() && isZoomKeyPressed();
		
		if(showing)
		{
			MC.gui.setOverlayMessage(buildZoomMessage(), false);
			showingPercentage = true;
			
		}else if(showingPercentage)
		{
			// An empty message leaves the slot blank rather than letting the
			// last percentage sit there fading out after the key is released.
			MC.gui.setOverlayMessage(Component.empty(), false);
			showingPercentage = false;
		}
	}
	
	/** "Zoom 300%", all in white. */
	private MutableComponent buildZoomMessage()
	{
		long percent = Math.round(toPercent(currentMultiplier()));
		
		return Component.literal("Zoom " + percent + "%")
			.withStyle(style -> style.withColor(0xFFFFFF));
	}
	
	@Override
	public void onMouseScroll(double amount)
	{
		if(!isZoomKeyPressed() || !scroll.isChecked())
			return;
		
		if(currentZoom == null)
			currentZoom = toMultiplier(level.getValue());
			
		// Stepping by a ratio rather than a fixed amount keeps a notch feeling
		// the same at every zoom, and dividing on the way back out makes the
		// two directions exact inverses. Multiplying by 0.9 to undo a 1.1
		// wouldn't, so scrolling up and back down used to drift downwards.
		double step = 1 + scrollSpeed.getValue() / 100;
		
		// Below 0% the same ratio would race to the far end, because zooming
		// out only spans 1x down to 0.4x while zooming in spans 1x up to 128x.
		// Shrinking the step by the ratio between the two makes one notch cover
		// the same share of the range whichever way you scroll.
		if(amount < 0 ? currentZoom <= 1 : currentZoom < 1)
			step = Math.pow(step, ZOOM_OUT_STEP_SCALE);
		
		if(amount > 0)
			currentZoom *= step;
		else if(amount < 0)
			currentZoom /= step;
		
		currentZoom = MathUtils.clamp(currentZoom, toMultiplier(MIN_PERCENT),
			toMultiplier(MAX_PERCENT));
	}
	
	public boolean isControllingScrollEvents()
	{
		return isZoomKeyPressed() && scroll.isChecked();
	}
	
	public Component getTranslatedKeybindName()
	{
		return InputConstants.getKey(keybind.getValue()).getDisplayName();
	}
	
	public void setBoundKey(String translationKey)
	{
		keybind.setValue(translationKey);
	}
	
	private boolean isZoomKeyPressed()
	{
		if(MC.screen != null && !zoomInScreens.isChecked())
			return false;
		
		return InputConstants.isKeyDown(MC.getWindow(),
			InputConstants.getKey(keybind.getValue()).getValue());
	}
	
	private boolean isValidKeybind(String keybind)
	{
		try
		{
			return InputConstants.getKey(keybind) != null;
			
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	public SliderSetting getLevelSetting()
	{
		return level;
	}
	
	public CheckboxSetting getScrollSetting()
	{
		return scroll;
	}
	
	public SliderSetting getScrollSpeedSetting()
	{
		return scrollSpeed;
	}
}
