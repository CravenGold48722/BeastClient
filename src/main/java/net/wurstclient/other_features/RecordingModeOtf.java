/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RecordingDetector;

@SearchTags({"recording mode", "youtuber mode", "YouTuberMode", "streamer mode",
	"StreamerMode", "obs", "stream", "record", "screen share"})
@DontBlock
public final class RecordingModeOtf extends OtherFeature
	implements UpdateListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"§lAuto§r hides the client while a recording or streaming"
			+ " program is running, and shows it again once that program is"
			+ " closed.\n"
			+ "§lAlways§r keeps the client hidden no matter what.\n"
			+ "§lOff§r disables this feature.",
		Mode.values(), Mode.AUTO);
	
	private final CheckboxSetting hideHackList = new CheckboxSetting(
		"Hide HackList", "Hides the list of active hacks.", true);
	
	private final CheckboxSetting hideTabGui =
		new CheckboxSetting("Hide TabGUI", "Hides the TabGUI.", true);
	
	private final CheckboxSetting hideLogo = new CheckboxSetting("Hide logo",
		"Hides the Beast logo and version in the top left corner.", true);
	
	private final CheckboxSetting hidePinnedWindows =
		new CheckboxSetting("Hide pinned windows",
			"Hides ClickGUI windows that are pinned to the screen.", true);
	
	private final CheckboxSetting hideEsps = new CheckboxSetting("Hide ESPs",
		"Hides everything that hacks draw into the world: ESP boxes, tracers,"
			+ " lines, path nodes and in-world text.\n"
			+ "The hacks themselves keep running, they just stop drawing.",
		true);
	
	private final CheckboxSetting hideWurstOptions =
		new CheckboxSetting("Hide Beast Options",
			"Hides the Beast Options button in the pause menu.", true);
	
	private final CheckboxSetting chatMessages =
		new CheckboxSetting("Chat messages",
			"Tells you in chat when recording mode turns itself on or off.\n"
				+ "§cThese messages are part of the recording§r, so turn"
				+ " this off if you don't want them on camera.",
			true);
	
	private final SliderSetting checkInterval = new SliderSetting(
		"Check interval", "How often to look for recording software.", 3, 1, 30,
		1, ValueDisplay.INTEGER.withSuffix("s"));
	
	private final TextFieldSetting extraApps = new TextFieldSetting(
		"Extra apps",
		"Comma-separated list of extra program names to watch for, for example"
			+ " §6nvidia share.exe, zoom.exe§r.\n"
			+ "Useful for recorders that are always running in the background"
			+ " and that Beast therefore doesn't detect on its own.",
		"");
	
	private final RecordingDetector detector = new RecordingDetector();
	private String lastExtraApps = "";
	private boolean wasActive;
	
	public RecordingModeOtf()
	{
		super("RecordingMode",
			"Hides the client's UI while you are recording or streaming, and"
				+ " brings it back when you stop.");
		
		addSetting(mode);
		addSetting(hideHackList);
		addSetting(hideTabGui);
		addSetting(hideLogo);
		addSetting(hidePinnedWindows);
		addSetting(hideEsps);
		addSetting(hideWurstOptions);
		addSetting(chatMessages);
		addSetting(checkInterval);
		addSetting(extraApps);
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(mode.getSelected() == Mode.AUTO)
		{
			detector.setInterval((long)(checkInterval.getValue() * 1000));
			
			String extra = extraApps.getValue();
			if(!extra.equals(lastExtraApps))
			{
				detector.setExtraNames(extra);
				lastExtraApps = extra;
			}
			
			detector.start();
			
		}else
			detector.stop();
		
		boolean active = isActive();
		if(active == wasActive)
			return;
		
		wasActive = active;
		if(chatMessages.isChecked())
			ChatUtils.message(getToggleMessage(active));
	}
	
	/** Returns true while the client should be hiding itself. */
	public boolean isActive()
	{
		return switch(mode.getSelected())
		{
			case AUTO -> detector.isRecording();
			case ALWAYS -> true;
			case OFF -> false;
		};
	}
	
	public boolean shouldHideHackList()
	{
		return isActive() && hideHackList.isChecked();
	}
	
	public boolean shouldHideTabGui()
	{
		return isActive() && hideTabGui.isChecked();
	}
	
	public boolean shouldHideLogo()
	{
		return isActive() && hideLogo.isChecked();
	}
	
	public boolean shouldHidePinnedWindows()
	{
		return isActive() && hidePinnedWindows.isChecked();
	}
	
	public boolean shouldHideEsps()
	{
		return isActive() && hideEsps.isChecked();
	}
	
	public boolean shouldHideWurstOptions()
	{
		return isActive() && hideWurstOptions.isChecked();
	}
	
	/** Text for the button in Minecraft's own options screen. */
	public String getButtonText()
	{
		return "Recording Mode: " + mode.getSelected();
	}
	
	/** Switches to the next mode, for the button in Minecraft's options. */
	public void cycleMode()
	{
		mode.selectNext();
	}
	
	/** Describes what the feature is currently doing, for tooltips. */
	public String getStatusText()
	{
		if(isActive())
		{
			String app = detector.getDetectedApp();
			if(mode.getSelected() == Mode.AUTO && app != null)
				return "Hiding the client because " + app + " is running.";
			
			return "Hiding the client.";
		}
		
		if(mode.getSelected() == Mode.OFF)
			return "Turned off. The client is always visible.";
		
		if(!detector.isSupported())
			return "Can't read this system's process list, so recording"
				+ " software won't be detected.";
		
		return "Waiting for a recording program to start.";
	}
	
	private String getToggleMessage(boolean active)
	{
		if(!active)
			return "Recording mode off. The client is visible again.";
		
		String app = detector.getDetectedApp();
		if(mode.getSelected() == Mode.AUTO && app != null)
			return "Detected " + app + ". Hiding the client.";
		
		return "Recording mode on. Hiding the client.";
	}
	
	@Override
	public boolean isEnabled()
	{
		return isActive();
	}
	
	@Override
	public String getPrimaryAction()
	{
		return mode.getSelected() == Mode.OFF ? "Enable" : "Disable";
	}
	
	@Override
	public void doPrimaryAction()
	{
		mode.setSelected(mode.getSelected() == Mode.OFF ? Mode.AUTO : Mode.OFF);
	}
	
	public static enum Mode
	{
		AUTO("Auto"),
		
		ALWAYS("Always"),
		
		OFF("Off");
		
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
