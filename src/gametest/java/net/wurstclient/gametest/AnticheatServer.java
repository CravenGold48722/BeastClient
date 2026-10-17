/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A real Minecraft server with an anticheat on it (GrimAC on Fabric, Intave
 * on Paper), in its own process, set up by gradle/anticheat.gradle. The
 * gametests join it like any player and collect every flag it raises: Grim
 * prints them to its console (verbose output is switched on for the tests),
 * Intave writes them to its own log file.
 *
 * <p>
 * The test bot isn't op there; commands go to the server console.
 */
public final class AnticheatServer
{
	/** Same name as WurstTest.LOGGER, without loading game classes early. */
	private static final org.slf4j.Logger LOGGER =
		org.slf4j.LoggerFactory.getLogger("Wurst Test");
	
	private static final Pattern FORMATTING =
		Pattern.compile("\u001B\\[[0-9;]*m|§.");
	
	/** The test client's name: flags for other players aren't its. */
	private static final String CLIENT_NAME = "Wurst-Bot";
	
	private final String name;
	private final File dir;
	private final int port;
	private final String jar;
	private final Pattern flagLine;
	/** null: flags come from the console */
	private final File flagLogDir;
	
	private Process process;
	private BufferedWriter console;
	private final CountDownLatch started = new CountDownLatch(1);
	private final CountDownLatch selfTestsDone = new CountDownLatch(1);
	private final List<String> flags = new ArrayList<>();
	private final Map<File, Long> readUpTo = new HashMap<>();
	private volatile boolean stopping;
	private volatile Pattern awaitedAnswer;
	private final LinkedBlockingQueue<String> answers =
		new LinkedBlockingQueue<>();
	
	private AnticheatServer(String name, File dir, int port, String jar,
		Pattern flagLine, File flagLogDir)
	{
		this.name = name;
		this.dir = dir;
		this.port = port;
		this.jar = jar;
		this.flagLine = flagLine;
		this.flagLogDir = flagLogDir;
	}
	
	/** GrimAC on Fabric, or null if the build left it out. */
	public static AnticheatServer grim()
	{
		return fromProperty("Grim", "wurst.test.grimServer",
			"fabric-server.jar", Pattern.compile("Grim » .* failed "), null);
	}
	
	/** Intave on Paper, or null if the build left it out. */
	public static AnticheatServer intave()
	{
		return fromProperty("Intave", "wurst.test.intaveServer", "paper.jar",
			Pattern.compile("\\(DET\\) "), new File("plugins/Intave/logs"));
	}
	
	/** -Dwurst.test.xServer=folder:port (the folder may contain a colon) */
	private static AnticheatServer fromProperty(String name, String property,
		String jar, Pattern flagLine, File flagLogDir)
	{
		String value = System.getProperty(property);
		if(value == null)
			return null;
		
		int colon = value.lastIndexOf(':');
		File dir = new File(value.substring(0, colon));
		return new AnticheatServer(name, dir,
			Integer.parseInt(value.substring(colon + 1)), jar, flagLine,
			flagLogDir == null ? null : new File(dir, flagLogDir.getPath()));
	}
	
	public String getName()
	{
		return name;
	}
	
	public File getDir()
	{
		return dir;
	}
	
	public String getAddress()
	{
		return "127.0.0.1:" + port;
	}
	
	public boolean isStarted()
	{
		return process != null;
	}
	
	/** Starts the server process; doesn't wait for it to finish starting. */
	public void start()
	{
		String java = ProcessHandle.current().info().command().orElse("java");
		try
		{
			// small heaps: a flat world with one player needs little, and the
			// test machine paging (client, Gradle, an IDE...) stalled the
			// servers
			// for seconds - lag flags everywhere (Intave, a bigger plugin:
			// more)
			// (user, 2026-10-17: a memory limit for the tests - Grim 768 MB,
			// Intave 1 GB, were 1 / 1.5)
			String heap = flagLogDir != null ? "-Xmx1G" : "-Xmx768M";
			// the client's flags too (build.gradle): G1 with short pauses,
			// compact object headers, unused heap given back to Windows
			process = new ProcessBuilder(java, "-Xms256M", heap, "-XX:+UseG1GC",
				"-XX:MaxGCPauseMillis=50", "-XX:+ParallelRefProcEnabled",
				"-XX:+UseCompactObjectHeaders", "-XX:+UseStringDeduplication",
				"-XX:G1PeriodicGCInterval=10000", "-XX:MinHeapFreeRatio=10",
				"-XX:MaxHeapFreeRatio=25", "-XX:ReservedCodeCacheSize=128M",
				"-jar", jar, "nogui").directory(dir).redirectErrorStream(true)
					.start();
			
		}catch(IOException e)
		{
			throw new RuntimeException("Couldn't start the " + name + " server",
				e);
		}
		
		console =
			new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
				StandardCharsets.UTF_8));
		
		Thread reader = new Thread(this::readConsole, name + " console");
		reader.setDaemon(true);
		reader.start();
		
		// never left running if the test JVM dies
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if(process.isAlive())
				process.destroyForcibly();
		}));
	}
	
	private void readConsole()
	{
		try(BufferedReader in =
			new BufferedReader(new InputStreamReader(process.getInputStream(),
				StandardCharsets.UTF_8)))
		{
			String line;
			while((line = in.readLine()) != null)
			{
				line = FORMATTING.matcher(line).replaceAll("");
				Pattern awaited = awaitedAnswer;
				if(awaited != null && awaited.matcher(line).find())
					answers.offer(line);
				
				// the end of Intave's own integration tests, passed or not
				if(line.contains("integration tests completed successfully")
					|| line.matches(".*\\[Intave\\].*Reported an? \\w+: .*"))
					selfTestsDone.countDown();
				
				if(line.contains("Done (") && line.contains("For help"))
					started.countDown();
				
				if(flagLogDir == null && flagLine.matcher(line).find())
				{
					// VexBot's bot (AimAssist (Auto combo)) is a player too
					if(line.contains(CLIENT_NAME))
						addFlag(line);
					else
						LOGGER.info("[{} flag, another player] {}", name,
							line.trim());
				}else if(line.contains("ERROR") || line.contains("Exception"))
					LOGGER.warn("[{} server] {}", name, line);
			}
			
		}catch(IOException e)
		{
			if(!stopping)
				LOGGER.warn("[{} server] console closed", name, e);
		}
		
		started.countDown();
	}
	
	/** Waits until the server is up. */
	public void awaitStarted()
	{
		try
		{
			if(!started.await(5, TimeUnit.MINUTES) || !process.isAlive())
				throw new RuntimeException("The " + name
					+ " server didn't start - see " + new File(dir, "logs"));
			
		}catch(InterruptedException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Runs a command on the server console and returns the first console
	 * line after it that matches {@code answer}, or null after two seconds.
	 */
	public synchronized String query(String command, Pattern answer)
	{
		answers.clear();
		awaitedAnswer = answer;
		try
		{
			command(command);
			return answers.poll(2, TimeUnit.SECONDS);
			
		}catch(InterruptedException e)
		{
			throw new RuntimeException(e);
			
		}finally
		{
			awaitedAnswer = null;
		}
	}
	
	/**
	 * Waits until the server is up and has nothing else to do. Intave's
	 * development build runs its own integration tests on the server thread
	 * after a start in an environment it hasn't checked yet (a new Intave
	 * build, an OS update): over 30 seconds of a frozen server and a busy
	 * CPU, which showed up as lag flags and failed scenarios on the other
	 * server. They aren't announced, only their end is, so: if the server
	 * answers console queries slowly, wait for that end.
	 */
	public void awaitSettled()
	{
		awaitStarted();
		
		// settled: eight queries in a row (about two seconds) answered within
		// half a second each; at most five minutes. (A busy server answers in
		// seconds or not at all.)
		long deadline = System.nanoTime() + 300_000_000_000L;
		boolean busy = false;
		for(int quick = 0; quick < 8 && System.nanoTime() < deadline;)
		{
			long asked = System.nanoTime();
			String answer =
				query("time query gametime", Pattern.compile("time is"));
			if(answer != null && System.nanoTime() - asked < 500_000_000L)
				quick++;
			else
			{
				quick = 0;
				if(!busy)
					LOGGER.info("The {} server is busy - waiting for it", name);
				busy = true;
			}
			sleep(250);
		}
		
		// Intave's tests also run in short pieces with a quick server in
		// between - once it was busy, wait for their end
		if(busy && flagLogDir != null)
			awaitSelfTests();
	}
	
	private void awaitSelfTests()
	{
		try
		{
			if(!selfTestsDone.await(5, TimeUnit.MINUTES))
				LOGGER.warn("The {} server was still busy after 5 minutes",
					name);
			
		}catch(InterruptedException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private static void sleep(long millis)
	{
		try
		{
			Thread.sleep(millis);
			
		}catch(InterruptedException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	/** Runs a command on the server console. */
	public synchronized void command(String command)
	{
		try
		{
			console.write(command);
			console.newLine();
			console.flush();
			
		}catch(IOException e)
		{
			throw new RuntimeException(name + " server console is gone", e);
		}
	}
	
	/** The flags raised since the last call, oldest first. */
	public List<String> takeFlags()
	{
		if(flagLogDir != null)
			readFlagLog();
		
		synchronized(flags)
		{
			List<String> taken = new ArrayList<>(flags);
			flags.clear();
			return taken;
		}
	}
	
	/** Reads what Intave appended to its log files since the last call. */
	private void readFlagLog()
	{
		File[] logs = flagLogDir.listFiles((d, n) -> n.endsWith(".log"));
		if(logs == null)
			return;
		
		for(File log : logs)
		{
			long from = readUpTo.getOrDefault(log, 0L);
			if(log.length() <= from)
				continue;
			
			try(RandomAccessFile file = new RandomAccessFile(log, "r"))
			{
				file.seek(from);
				byte[] bytes = new byte[(int)(file.length() - from)];
				file.readFully(bytes);
				// only whole lines; the rest is read next time
				String text = new String(bytes, StandardCharsets.UTF_8);
				int end = text.lastIndexOf('\n') + 1;
				readUpTo.put(log, from + text.substring(0, end)
					.getBytes(StandardCharsets.UTF_8).length);
				for(String line : text.substring(0, end).split("\r?\n"))
					if(flagLine.matcher(line).find())
						addFlag(FORMATTING.matcher(line).replaceAll(""));
					
			}catch(IOException e)
			{
				LOGGER.warn("Couldn't read {}", log, e);
			}
		}
	}
	
	private void addFlag(String line)
	{
		LOGGER.warn("[{} flag] {}", name, line.trim());
		synchronized(flags)
		{
			flags.add(line.trim());
		}
	}
	
	/** Stops the server the normal way (saving), forcibly if it hangs. */
	public void stop()
	{
		if(process == null || !process.isAlive())
			return;
		
		stopping = true;
		try
		{
			command("stop");
			if(!process.waitFor(60, TimeUnit.SECONDS))
				process.destroyForcibly();
			
		}catch(RuntimeException | InterruptedException e)
		{
			process.destroyForcibly();
		}
	}
}
