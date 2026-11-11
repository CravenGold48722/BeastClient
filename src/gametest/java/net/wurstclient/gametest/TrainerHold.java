/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.io.File;

import org.slf4j.LoggerFactory;

/**
 * Pauses the AimAssist trainer (scripts/train.ps1 hold) while this test
 * client runs. On the test laptop (4 cores, an iGPU on the same 15 W budget)
 * a full trainer next to the test made the client take 392 s to start, and
 * Fabric's gametest phaser race (ThreadingImplMixin) far likelier. The
 * trainer is suspended, not stopped - nothing it learned is lost - and the
 * script resumes it as soon as this process ends, however it ends.
 * {@code -PkeepTraining} leaves it running.
 */
public final class TrainerHold
{
	private TrainerHold()
	{}
	
	public static void start()
	{
		String script = System.getProperty("wurst.test.trainScript");
		if(script == null || !new File(script).isFile()
			|| !System.getProperty("os.name", "").startsWith("Windows"))
			return;
		
		try
		{
			new ProcessBuilder("powershell.exe", "-NoProfile",
				"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File",
				script, "hold", "-WhilePid",
				String.valueOf(ProcessHandle.current().pid()))
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			
		}catch(Exception e)
		{
			LoggerFactory.getLogger("Wurst Test")
				.warn("Couldn't pause training for the test run", e);
		}
	}
}
