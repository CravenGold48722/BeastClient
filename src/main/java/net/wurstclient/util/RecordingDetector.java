/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Watches the system's process list for screen recording and streaming
 * software. The scanning runs on a daemon thread, because reading the whole
 * process list takes long enough to stutter the game if it's done on the
 * render thread.
 */
public final class RecordingDetector
{
	/**
	 * Executable names that belong to a recorder and nothing else. These are
	 * matched in full, since short fragments like "obs" also show up in
	 * completely unrelated programs.
	 */
	private static final List<String> FULL_NAMES =
		List.of("obs.exe", "obs64.exe", "obs32.exe", "obs", "obs-studio",
			"action.exe", "fraps.exe", "ffmpeg.exe", "ffmpeg", "loom.exe",
			"loom", "peek", "kazam", "vokoscreen", "vokoscreenng",
			"recordmydesktop", "gpu-screen-recorder", "wf-recorder",
			"simplescreenrecorder", "quicktime player", "screenflow");
	
	/** Name fragments that are distinctive enough to match anywhere. */
	private static final List<String> NAME_PARTS = List.of("streamlabs",
		"xsplit", "bandicam", "bdcam", "camtasia", "sharex", "dxtory", "medal",
		"screenrec", "flashbackrecorder", "icecream screen", "prismlivestudio",
		"twitch studio", "gamecaster", "screencast");
	
	private volatile Thread thread;
	private volatile boolean supported = true;
	private volatile long intervalMs = 3000;
	private volatile List<String> extraNames = List.of();
	private volatile String detectedApp;
	
	/**
	 * Starts the scanning thread, unless it's already running or this system
	 * doesn't let us read the process list. Must be called from the client
	 * thread.
	 */
	public void start()
	{
		if(thread != null || !supported)
			return;
		
		Thread newThread = new Thread(this::run, "Wurst Recording Detector");
		newThread.setDaemon(true);
		newThread.setPriority(Thread.MIN_PRIORITY);
		thread = newThread;
		newThread.start();
	}
	
	/**
	 * Stops the scanning thread and forgets whatever it last detected. Must be
	 * called from the client thread.
	 */
	public void stop()
	{
		Thread oldThread = thread;
		if(oldThread != null)
			oldThread.interrupt();
	}
	
	/** Returns true while a known recorder is running. */
	public boolean isRecording()
	{
		return detectedApp != null;
	}
	
	/**
	 * Returns the executable name of the recorder that was found, or null if
	 * none is running.
	 */
	public String getDetectedApp()
	{
		return detectedApp;
	}
	
	/** Returns false if this system doesn't allow listing processes. */
	public boolean isSupported()
	{
		return supported;
	}
	
	public void setInterval(long intervalMs)
	{
		this.intervalMs = Math.max(intervalMs, 100);
	}
	
	/** Adds the given comma-separated executable names to the search. */
	public void setExtraNames(String commaSeparated)
	{
		ArrayList<String> names = new ArrayList<>();
		for(String name : commaSeparated.split(","))
		{
			String trimmed = name.trim().toLowerCase(Locale.ROOT);
			if(!trimmed.isEmpty())
				names.add(trimmed);
		}
		
		extraNames = List.copyOf(names);
	}
	
	private void run()
	{
		try
		{
			while(true)
			{
				detectedApp = scan();
				
				if(!supported)
					break;
				
				Thread.sleep(intervalMs);
			}
			
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			
		}finally
		{
			detectedApp = null;
			thread = null;
		}
	}
	
	private String scan()
	{
		try
		{
			return ProcessHandle.allProcesses()
				.map(process -> process.info().command().orElse(""))
				.map(RecordingDetector::getFileName)
				.filter(name -> !name.isEmpty()).filter(this::isRecorder)
				.findFirst().orElse(null);
			
		}catch(UnsupportedOperationException e)
		{
			// some systems don't let us look at the process list at all
			supported = false;
			return null;
			
		}catch(Exception e)
		{
			// a process can disappear while we're reading it
			return null;
		}
	}
	
	private static String getFileName(String command)
	{
		int slash =
			Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
		return command.substring(slash + 1).toLowerCase(Locale.ROOT);
	}
	
	private boolean isRecorder(String fileName)
	{
		if(FULL_NAMES.contains(fileName))
			return true;
		
		for(String part : NAME_PARTS)
			if(fileName.contains(part))
				return true;
			
		for(String extra : extraNames)
			if(fileName.contains(extra))
				return true;
			
		return false;
	}
}
