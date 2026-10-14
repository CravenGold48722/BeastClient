/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import static net.wurstclient.gametest.WurstClientTestHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.wurstclient.gametest.AnticheatServer;
import net.wurstclient.gametest.PacketTrace;
import net.wurstclient.gametest.WurstTest;

/**
 * Plays a few scenarios with Wurst's legit-meant hacks on a real server with
 * a real anticheat ({@link AnticheatServer}) and reports every flag it
 * raised, per scenario. Only the baseline (plain walking and jumping, no
 * hacks) must stay clean - a flag there means the setup itself is off, so
 * nothing else can be trusted; the hack scenarios are reported, not failed.
 */
public final class AnticheatTest
{
	private static final String BOT = "Wurst-Bot";
	
	private final ClientGameTestContext context;
	private final TestInput input;
	private final AnticheatServer server;
	private final Logger logger = WurstTest.LOGGER;
	private final Map<String, List<String>> report = new LinkedHashMap<>();
	private final Map<String, String> notes = new LinkedHashMap<>();
	private final Map<String, PacketTrace.Stats> stats = new LinkedHashMap<>();
	private BlockPos home;
	
	public AnticheatTest(ClientGameTestContext context, AnticheatServer server)
	{
		this.context = context;
		this.input = context.getInput();
		this.server = server;
	}
	
	public void run()
	{
		logger.info("Testing on the {} server", server.getName());
		server.awaitStarted();
		server.command("gamerule spawn_mobs false");
		server.command("gamerule advance_time false");
		server.command("gamerule advance_weather false");
		server.command("time set noon");
		server.command("weather clear");
		server.command("kill @e[type=!player]");
		join();
		
		try
		{
			// no deaths from the falls; damage is server-side only, so the
			// anticheat sees the same movement
			server.command(
				"effect give " + BOT + " minecraft:resistance infinite 4 true");
			server.command(
				"effect give " + BOT + " minecraft:saturation infinite 0 true");
			context.waitTicks(20);
			server.takeFlags();
			PacketTrace.takeStats();
			
			scenario("baseline", this::baseline);
			scenario("control (Flight)", this::control);
			// with -Pfeatures, only the setup checks (baseline, control)
			if(AnticheatFeatureSweep.FEATURES.isEmpty())
			{
				scenario("Killaura", this::killaura);
				scenario("MaceAssist", this::maceAssist);
				scenario("ScaffoldWalk", this::scaffoldWalk);
			}
			
			// every hack and command, each with its own line
			if(!Boolean.getBoolean("wurst.test.noFeatureSweep"))
				new AnticheatFeatureSweep(this, context).run();
			
		}finally
		{
			leave();
		}
		
		logReport();
		
		List<String> unexplained = unexplainedFlags("baseline");
		if(!unexplained.isEmpty())
			throw new RuntimeException(server.getName()
				+ " flagged plain walking and jumping: " + unexplained.get(0));
		
		// the scenarios themselves must work; the features' are reported only
		List<String> broken = failed.stream()
			.filter(n -> !n.startsWith("hack ") && !n.startsWith("cmd "))
			.toList();
		if(!broken.isEmpty())
			throw new RuntimeException(server.getName() + " scenario(s) "
				+ broken + " failed - see the log");
		
		if(report.get("control (Flight)").isEmpty())
			throw new RuntimeException(server.getName()
				+ " didn't flag Flight - its flags aren't reaching the test");
	}
	
	/**
	 * A scenario's flags minus the timer flags its server lag explains: a
	 * client ticking evenly looks ahead of real time to a timer check while
	 * the server sends nothing (its pings keep that check's clock), so with
	 * the server silent for over half a second, Timer/TimerLimit flags come
	 * from the server, not the client.
	 */
	private List<String> unexplainedFlags(String scenario)
	{
		return unexplainedFlagsOf(report.get(scenario), stats.get(scenario));
	}
	
	private static List<String> unexplainedFlagsOf(List<String> flags,
		PacketTrace.Stats s)
	{
		boolean serverLagged = s != null && s.serverSilenceMs() > 500;
		boolean clientEven = s != null && s.fastestSecond() <= 22;
		List<String> unexplained = new ArrayList<>();
		for(String flag : flags)
			if(!(serverLagged && clientEven && TIMER_FLAG.matcher(flag).find()))
				unexplained.add(flag);
		return unexplained;
	}
	
	/**
	 * Whether the last scenario raised flags. Anticheats hold a grudge:
	 * Intave reports a movement only once the player's violation level is
	 * past 50, and after the Flight control it sat around 400, so every
	 * tiny mis-prediction of the next feature became a flag and a setback.
	 * The feature sweep reconnects after such a scenario, so each feature
	 * starts with a clean record, like a player who just joined.
	 */
	boolean lastFlagged()
	{
		return lastFlagged;
	}
	
	private boolean lastFlagged;
	
	private static final Pattern TIMER_FLAG = Pattern
		.compile("failed Timer(Limit)? |timer", Pattern.CASE_INSENSITIVE);
	
	/**
	 * Runs a scenario and records the flags it raised, plus what it did
	 * (the scenario's return value, e.g. how much damage a hit did - a clean
	 * report from a hack that never hit means nothing) and how evenly the
	 * client ticked and the server answered.
	 */
	void scenario(String name, Supplier<String> scenario)
	{
		logger.info("{}: {}", server.getName(), name);
		server.takeFlags();
		PacketTrace.takeStats();
		String note = null;
		try
		{
			note = scenario.get();
			
		}catch(RuntimeException | AssertionError e)
		{
			// one stuck scenario mustn't cost the other 200 results
			logger.error("{}: {} failed", server.getName(), name, e);
			note = "FAILED: " + e;
			failed.add(name);
		}
		
		context.waitTicks(10);
		record(name, note);
		reset();
	}
	
	/**
	 * One feature's test (AnticheatFeatureSweep): like {@link #scenario}, but
	 * the test settles and cleans up itself, only as much as it needs.
	 * Flags from before it (the last test's cleanup teleports) are dropped.
	 */
	void featureScenario(String name, Supplier<String> body)
	{
		logger.info("{}: {}", server.getName(), name);
		server.takeFlags();
		PacketTrace.takeStats();
		String note;
		try
		{
			note = body.get();
			
		}catch(RuntimeException | AssertionError e)
		{
			logger.error("{}: {} failed", server.getName(), name, e);
			note = "FAILED: " + e;
			failed.add(name);
		}
		record(name, note);
	}
	
	/** Scenarios that threw instead of finishing. */
	private final List<String> failed = new ArrayList<>();
	
	private void record(String name, String note)
	{
		record(name, note, server.takeFlags(), PacketTrace.takeStats());
	}
	
	private void record(String name, String note, List<String> flags,
		PacketTrace.Stats s)
	{
		report.put(name, flags);
		lastFlagged = !unexplainedFlagsOf(flags, s).isEmpty();
		stats.put(name, s);
		// a check that failed while the anticheat flagged it: most likely
		// the anticheat undid it (setback, cancelled hit) - not the same as
		// a feature that never acted
		if(note != null && note.contains(FeatureTestKit.FAIL)
			&& !flags.isEmpty())
			note = note.replace(FeatureTestKit.FAIL,
				"DIDN'T WORK (flagged - the anticheat likely undid it): ");
		notes.put(name, note == null ? s.toString() : note + "; " + s);
		logger.info("{}: {} - {} flag(s){} [{}]", server.getName(), name,
			flags.size(), flags.isEmpty() ? "" : " " + summarize(flags),
			notes.get(name));
	}
	
	// ── Scenarios ──
	
	/** No hacks: standing, walking, jumping. Must not flag. */
	private String baseline()
	{
		context.waitTicks(20);
		input.holdKeyFor(GLFW.GLFW_KEY_W, 20);
		input.pressKey(GLFW.GLFW_KEY_SPACE);
		context.waitTicks(20);
		input.holdKeyFor(GLFW.GLFW_KEY_S, 10);
		context.waitTicks(20);
		return null;
	}
	
	/** Flight - blatant; it must flag, or flags aren't getting through. */
	private String control()
	{
		runWurstCommand(context, "t Flight on");
		input.holdKeyFor(GLFW.GLFW_KEY_SPACE, 20);
		input.holdKeyFor(GLFW.GLFW_KEY_W, 20);
		runWurstCommand(context, "t Flight off");
		context.waitFor(mc -> mc.player.onGround(), 200);
		return null;
	}
	
	/** Killaura on a still husk in front of you, for three seconds. */
	private String killaura()
	{
		item("hotbar.0", "diamond_sword");
		summonHusk(home.offset(0, 0, 2));
		tp(home, 0, 0);
		context.waitTicks(10);
		runWurstCommand(context, "t Killaura on");
		context.waitTicks(60);
		runWurstCommand(context, "t Killaura off");
		return huskDamage();
	}
	
	/**
	 * MaceAssist's trigger bot on a plain fall onto a husk, from 12 blocks up
	 * and 1.5 to the side, looking 50 degrees down and away from it (the mace
	 * aim turns you sideways; the pitch is yours).
	 */
	private String maceAssist()
	{
		item("hotbar.0", "mace");
		BlockPos target = home.offset(0, 0, 4);
		summonHusk(target);
		runWurstCommand(context, "setslider MaceAssist Aim_speed 1800");
		runWurstCommand(context, "setcheckbox MaceAssist Mace_trigger_bot on");
		runWurstCommand(context, "setcheckbox MaceAssist Mace_aim_assist on");
		runWurstCommand(context, "t MaceAssist on");
		server.command(String.format(Locale.ROOT, "tp %s %.1f %d %.1f 0 50",
			BOT, target.getX() + 2.0, target.getY() + 12, target.getZ() + 0.5));
		context.waitFor(mc -> !mc.player.onGround(), 40);
		context.waitFor(mc -> mc.player.onGround(), 200);
		context.waitTicks(20);
		runWurstCommand(context, "t MaceAssist off");
		runWurstCommand(context, "setcheckbox MaceAssist Mace_trigger_bot off");
		runWurstCommand(context, "setcheckbox MaceAssist Mace_aim_assist off");
		runWurstCommand(context, "setslider MaceAssist Aim_speed 15");
		return huskDamage();
	}
	
	/** ScaffoldWalk bridging north from a block in the air. */
	private String scaffoldWalk()
	{
		item("hotbar.0", "stone 64");
		BlockPos ledge = home.offset(0, 10, 0);
		server.command(String.format(Locale.ROOT, "setblock %d %d %d stone",
			ledge.getX(), ledge.getY(), ledge.getZ()));
		server.command(String.format(Locale.ROOT, "tp %s %.1f %d %.1f 180 0",
			BOT, ledge.getX() + 0.5, ledge.getY() + 1, ledge.getZ() + 0.5));
		context.waitTicks(20);
		runWurstCommand(context, "t ScaffoldWalk on");
		input.holdKeyFor(GLFW.GLFW_KEY_W, 80);
		runWurstCommand(context, "t ScaffoldWalk off");
		context.waitTicks(10);
		
		String left = server.query("clear " + BOT + " stone 0",
			Pattern.compile("Found (\\d+) matching"));
		Matcher m =
			left == null ? null : Pattern.compile("Found (\\d+)").matcher(left);
		int placed =
			m != null && m.find() ? 64 - Integer.parseInt(m.group(1)) : 64;
		
		server.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air",
			ledge.getX() - 3, ledge.getY(), ledge.getZ() - 30, ledge.getX() + 3,
			ledge.getY(), ledge.getZ() + 3));
		return placed + " block(s) placed";
	}
	
	/** How much health the test husk lost, from the server. */
	String huskDamage()
	{
		String answer =
			server.query("data get entity @e[type=husk,limit=1] Health",
				Pattern.compile("entity data: ([0-9.]+)f"));
		if(answer == null)
			return "husk health unknown";
		
		Matcher m = Pattern.compile("entity data: ([0-9.]+)f").matcher(answer);
		m.find();
		float lost = 1024 - Float.parseFloat(m.group(1));
		return lost > 0
			? String.format(Locale.ROOT, "husk took %.1f damage", lost)
			: "never hit the husk";
	}
	
	// ── Helpers ──
	
	void join()
	{
		PacketTrace.start();
		connect();
		
		// A fresh server is busy for a few seconds after the first join
		// (sending chunks, warming up) and can go silent for over a second -
		// timer checks flag that (see unexplainedFlags). Those flags go in
		// the report as "join", not in the baseline.
		// After a long stall Grim's timer keeps flagging for a few seconds,
		// so: until a two-second stretch without flags and without the
		// server going silent (at most 30 s).
		List<String> joinFlags = new ArrayList<>();
		long silence = 0;
		long tickGap = 0;
		int fastest = 0;
		context.waitTicks(40);
		int clean = 0;
		for(int window = 0; window < 15; window++)
		{
			context.waitTicks(40);
			List<String> flags = server.takeFlags();
			PacketTrace.Stats s = PacketTrace.takeStats();
			joinFlags.addAll(flags);
			silence = Math.max(silence, s.serverSilenceMs());
			tickGap = Math.max(tickGap, s.tickGapMs());
			fastest = Math.max(fastest, s.fastestSecond());
			// two clean windows in a row: Grim's timer flags trail a long
			// stall by a second or more
			clean =
				flags.isEmpty() && s.serverSilenceMs() < 300 ? clean + 1 : 0;
			if(clean >= 2)
				break;
		}
		
		home = context.computeOnClient(mc -> mc.player.blockPosition());
		logger.info("Joined the {} server at {}", server.getName(), home);
		record("join", null, joinFlags,
			new PacketTrace.Stats(silence, tickGap, fastest));
	}
	
	private void connect()
	{
		String address = server.getAddress();
		context.runOnClient(mc -> {
			ServerData data = new ServerData(server.getName(), address,
				ServerData.Type.OTHER);
			// not from the server list: .sv and AutoReconnect have to work
			// for any join (ConnectScreenMixin remembers it)
			ConnectScreen.startConnecting(
				mc.screen != null ? mc.screen : new TitleScreen(), mc,
				ServerAddress.parseString(address), data, false, null);
		});
		context.waitFor(
			mc -> mc.player != null && mc.level != null && mc.screen == null,
			20 * 120);
	}
	
	/**
	 * Back on the server if a feature took the bot off it (.leave,
	 * AutoLeave, a kick). Returns whether it had to.
	 */
	boolean rejoinIfNeeded()
	{
		if(context.computeOnClient(mc -> mc.level != null && mc.player != null))
			return false;
		
		logger.info("Rejoining the {} server", server.getName());
		context.waitFor(mc -> mc.level == null, 20 * 30);
		context.runOnClient(mc -> mc.setScreen(new TitleScreen()));
		connect();
		// the server and the anticheat need a moment with a new player
		context.waitTicks(20);
		server.command(
			"effect give " + BOT + " minecraft:resistance infinite 4 true");
		server.command(
			"effect give " + BOT + " minecraft:saturation infinite 0 true");
		return true;
	}
	
	/**
	 * Leaves and joins again: a fresh start for the anticheat's idea of the
	 * player (after a feature left it rejecting every move).
	 */
	void reconnect()
	{
		logger.info("Reconnecting to the {} server", server.getName());
		context.runOnClient(mc -> {
			if(mc.level != null)
				mc.level.disconnect(Component.literal("Reconnecting"));
			mc.disconnectWithProgressScreen();
			mc.setScreen(new TitleScreen());
		});
		context.waitFor(mc -> mc.level == null, 20 * 30);
		rejoinIfNeeded();
	}
	
	AnticheatServer server()
	{
		return server;
	}
	
	BlockPos home()
	{
		return home;
	}
	
	private void leave()
	{
		PacketTrace.stop();
		context.runOnClient(mc -> {
			if(mc.level != null)
				mc.level.disconnect(Component.literal("Test done"));
			mc.disconnectWithProgressScreen();
			mc.setScreen(new TitleScreen());
		});
		context.waitFor(
			mc -> mc.level == null && mc.screen instanceof TitleScreen,
			20 * 30);
	}
	
	/** Back home, healed, empty-handed, hacks off, mobs gone. */
	private void reset()
	{
		server.command("kill @e[type=!player]");
		server.command("clear " + BOT);
		server.command("effect give " + BOT + " minecraft:instant_health 1 4");
		tp(home, 0, 0);
		context.waitTicks(20);
	}
	
	void tp(BlockPos pos, float yaw, float pitch)
	{
		server.command(
			String.format(Locale.ROOT, "tp %s %.1f %d %.1f %.1f %.1f", BOT,
				pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, pitch));
	}
	
	void item(String slot, String item)
	{
		server.command(
			"item replace entity " + BOT + " " + slot + " with " + item);
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
	}
	
	void summonHusk(BlockPos pos)
	{
		server.command(String.format(Locale.ROOT,
			"summon husk %.1f %d %.1f {NoAI:1b,Silent:1b,PersistenceRequired:1b,"
				+ "Health:1024f,attributes:[{id:\"minecraft:max_health\",base:1024}]}",
			pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
	}
	
	// ── Report ──
	
	private static final Pattern GRIM_CHECK = Pattern.compile("failed (\\S+)");
	private static final Pattern INTAVE_CHECK = Pattern.compile("on (\\S+)\\)");
	
	/** "Reach x2, Simulation x5" */
	static String summarize(List<String> flags)
	{
		Map<String, Integer> counts = new TreeMap<>();
		for(String flag : flags)
		{
			Matcher grim = GRIM_CHECK.matcher(flag);
			Matcher intave = INTAVE_CHECK.matcher(flag);
			String check = grim.find() ? grim.group(1)
				: intave.find() ? intave.group(1) : "?";
			counts.merge(check, 1, Integer::sum);
		}
		
		List<String> parts = new ArrayList<>();
		counts.forEach((check, n) -> parts.add(check + " x" + n));
		return "(" + String.join(", ", parts) + ")";
	}
	
	private void logReport()
	{
		// flags lag explains (see unexplainedFlags) are listed apart
		List<String> lines = new ArrayList<>();
		report.forEach((name, flags) -> {
			List<String> real = unexplainedFlags(name);
			int lag = flags.size() - real.size();
			lines.add(name + ": "
				+ (real.isEmpty() ? "clean"
					: real.size() + " flag(s) " + summarize(real))
				+ (lag > 0 ? " (+" + lag + " timer flag(s) from server lag)"
					: "")
				+ (notes.containsKey(name) ? " - " + notes.get(name) : ""));
		});
		
		// the whole table, every flag included, to a file
		Path file = server.getDir().toPath().getParent()
			.resolve(server.getName().toLowerCase(Locale.ROOT) + "-report.txt");
		List<String> full = new ArrayList<>(lines);
		full.add("");
		report.forEach((name, flags) -> flags
			.forEach(flag -> full.add(name + " | " + flag)));
		try
		{
			Files.write(file, full, StandardCharsets.UTF_8);
			
		}catch(IOException e)
		{
			logger.warn("Couldn't write {}", file, e);
		}
		
		// here: the scenarios, then only the features with something to say
		StringBuilder sb =
			new StringBuilder(server.getName() + " anticheat report:");
		int features = 0;
		int flagged = 0;
		int broken = 0;
		for(String name : report.keySet())
		{
			boolean feature =
				name.startsWith("hack ") || name.startsWith("cmd ");
			boolean flags = !unexplainedFlags(name).isEmpty();
			String note = notes.getOrDefault(name, "");
			boolean didntWork =
				note.startsWith("FAILED") || note.contains("DIDN'T WORK");
			if(feature)
			{
				features++;
				if(flags)
					flagged++;
				if(didntWork)
					broken++;
			}
			if(!feature || flags || didntWork
				|| note.contains("took the bot off"))
				sb.append("\n  ").append(
					lines.get(new ArrayList<>(report.keySet()).indexOf(name)));
		}
		if(features > 0)
			sb.append(String.format(Locale.ROOT,
				"\n  %d features tested (+%s above, not %s): %d raised flags,"
					+ " %d didn't work - full table: %s",
				features, AnticheatFeatureSweep.CORE,
				AnticheatFeatureSweep.SKIPPED, flagged, broken, file));
		
		for(String line : sb.toString().split("\n"))
			logger.info(line);
		ghSummary(sb.toString());
	}
}
