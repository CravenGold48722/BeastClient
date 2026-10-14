/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.gametest.AnticheatServer;
import net.wurstclient.gametest.PacketTrace;
import net.wurstclient.gametest.WurstClientTestHelper;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IChatComponent;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.Rotation;

/**
 * What the per-feature anticheat tests are written with: setting a scene up
 * on the server (blocks, mobs, items, effects), playing like a player (keys,
 * mouse, looking), checking what happened (on the client, on the server, in
 * the packets sent, on the screen) - and undoing exactly what a test changed,
 * so the next one starts clean without rebuilding everything every time.
 *
 * <p>
 * Positions are relative to {@link #home}, the block the bot stands on after
 * joining: y 0 is the air above the floor, y -1 the grass, -2 and -3 dirt.
 * Facing yaw 0 is south (+z), where most scenes are built.
 */
abstract class FeatureTestKit
{
	static final String BOT = "Wurst-Bot";
	static final String FAIL = "DIDN'T WORK: ";
	
	/** Ticks after a feature is off for late flags (setbacks, verdicts). */
	private static final int SETTLE_TICKS = 8;
	
	// the arena that cleanup() rebuilds when a test built something
	private static final int AX1 = -9, AX2 = 9, AZ1 = -7, AZ2 = 15, AY2 = 16;
	
	final AnticheatTest test;
	final ClientGameTestContext context;
	final TestInput input;
	final AnticheatServer server;
	BlockPos home;
	
	// what the running test changed, undone by cleanup()
	private boolean arenaDirty;
	private boolean inventoryDirty;
	private boolean effectsDirty;
	private boolean gameModeDirty;
	private boolean timeDirty;
	private boolean weatherDirty;
	private boolean cameraDirty;
	private final Map<Setting, JsonElement> changedSettings =
		new LinkedHashMap<>();
	private final Set<Integer> heldKeys = new HashSet<>();
	private final Set<Integer> heldButtons = new HashSet<>();
	private final Map<Integer, String> expectedItems = new HashMap<>();
	private final List<PacketTrace.Sent> sent = new ArrayList<>();
	private int summoned;
	private final String name;
	private Path screenshotDir;
	
	FeatureTestKit(AnticheatTest test, ClientGameTestContext context,
		String name)
	{
		this.test = test;
		this.context = context;
		this.input = context.getInput();
		this.server = test.server();
		this.name = name;
	}
	
	// ── Running one test ──
	
	/**
	 * Before each test: undo what the last one changed, then stand at home,
	 * facing south, with an empty chat and a clean packet log.
	 */
	void begin()
	{
		// a clean record for each feature (see AnticheatTest.lastFlagged)
		if(!test.rejoinIfNeeded() && test.lastFlagged())
			test.reconnect();
		home = test.home();
		cleanup();
		at(0, 0, 0, 0, 0);
		probe();
		// right after a teleport the client isn't on the ground yet: jumps
		// and hacks that need the ground would do nothing
		// on the ground for a few ticks in a row: the anticheat's answer to
		// the teleport can come a tick or two later and lift the bot off it
		// for a moment (InstantBunker and .jump then said "mid-air")
		int grounded = 0;
		for(int i = 0; i < 15 && grounded < 3; i++)
		{
			context.waitTick();
			grounded = client(mc -> mc.player.onGround()) ? grounded + 1 : 0;
		}
		if(grounded < 3)
		{
			String state = context.computeOnClient(mc -> fmt(
				"y %.4f above the floor, moving %s, flying %s, noPhysics %s,"
					+ " riding %s",
				rel(mc).y, mc.player.getDeltaMovement(),
				mc.player.getAbilities().flying, mc.player.noPhysics,
				mc.player.isPassenger()));
			probeNote = (probeNote == null ? "" : probeNote + "; ")
				+ "not on the ground at the start (" + state + ")";
		}
		context.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
		PacketTrace.takeSent();
		sent.clear();
		setupFlags.clear();
		started = false;
	}
	
	/**
	 * Can the bot still walk? A few ticks of W. Some features leave the
	 * anticheat (or the client) rejecting every move afterwards, and every
	 * later test would inherit that - so it's noted (on the test that comes
	 * next, naming the one before) and the bot reconnects for a fresh start.
	 */
	private void probe()
	{
		waitUntil(mc -> mc.player.onGround(), 10);
		server.takeFlags();
		Vec3 start = pos();
		holdFor(W, 4);
		double moved = horizontal(start, pos());
		// stopped before the teleport home: a teleport with momentum left
		// over was a Simulation flag (and a setback) at the next test's start
		waitUntil(
			mc -> mc.player.getDeltaMovement().horizontalDistance() < 0.003,
			10);
		List<String> flags = server.takeFlags();
		if(moved > 0.3)
		{
			at(0, 0, 0, 0, 0);
			return;
		}
		
		String state = context.computeOnClient(mc -> fmt(
			"client: sneaking %s, using an item %s, speed %.3f, effects %s",
			mc.player.isShiftKeyDown(), mc.player.isUsingItem(),
			mc.player.getSpeed(),
			mc.player.getActiveEffectsMap().keySet().stream().map(e -> e
				.unwrapKey().map(k -> k.identifier().getPath()).orElse("?"))
				.toList()));
		probeNote = fmt(
			"the bot couldn't walk after %s (moved %.2f in 4 ticks, %s; %s)"
				+ " - reconnected",
			lastTest, moved, flags.isEmpty() ? "no flags"
				: AnticheatTest.summarize(flags) + " flags",
			state);
		test.reconnect();
		home = test.home();
		at(0, 0, 0, 0, 0);
	}
	
	/** Set by {@link #probe()}, read (and cleared) by the sweep. */
	String probeNote;
	
	/** The test that ran last, for {@link #probe()}'s note. */
	String lastTest = "joining";
	
	/**
	 * Flags raised while the scene was set up (teleports, the last test's
	 * leftovers) aren't the feature's: they're set aside when the feature
	 * starts (its first {@link #on} or typed command) and shown apart.
	 */
	void start()
	{
		if(started)
			return;
		// a flag for the setup's last step arrives a tick or two later
		context.waitTicks(2);
		setupFlags.addAll(server.takeFlags());
		started = true;
	}
	
	/** Flags set aside by {@link #start()}, or null. */
	List<String> setupFlags()
	{
		return setupFlags.isEmpty() ? null : setupFlags;
	}
	
	private final List<String> setupFlags = new ArrayList<>();
	private boolean started;
	
	/**
	 * After each test: every hack off, keys let go, screens closed, back on
	 * the ground, then a few ticks for late flags. Returns a note if the
	 * test took the bot off the server.
	 */
	String finish()
	{
		heldKeys.forEach(input::releaseKey);
		heldKeys.clear();
		heldButtons.forEach(input::releaseMouse);
		heldButtons.clear();
		context.runOnClient(mc -> {
			for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
				if(hack.isEnabled())
					hack.setEnabled(false);
			KeyMapping.releaseAll();
			if(mc.screen != null && mc.level != null)
				mc.setScreen(null);
		});
		
		// a fall that a test started belongs to it
		waitUntil(mc -> mc.player == null || mc.player.onGround()
			|| mc.player.isInWater() || mc.player.isPassenger()
			|| mc.player.getAbilities().flying, 100);
		context.waitTicks(SETTLE_TICKS);
		
		if(test.rejoinIfNeeded())
			return "took the bot off the server (rejoined)";
		return null;
	}
	
	/** Puts back what the last test changed - only that. */
	private void cleanup()
	{
		server.command("kill @e[type=!player]");
		
		if(arenaDirty)
		{
			// not while standing on it: the anticheat's copy of the world
			// and the client's get the new floor a tick apart, and a tick of
			// falling that only one of them sees is a setback
			at(AX1 - 20, 0, 0, 0, 0);
			fill(AX1, 0, AZ1, AX2, AY2, AZ2, "air");
			fill(AX1, -3, AZ1, AX2, -2, AZ2, "dirt");
			fill(AX1, -1, AZ1, AX2, -1, AZ2, "grass_block");
			arenaDirty = false;
		}
		
		if(inventoryDirty)
		{
			server.command("clear " + BOT);
			expectedItems.clear();
			inventoryDirty = false;
		}
		
		// always, and told to the server: features switch slots (AutoSwitch,
		// AutoSword...), and a slot only the client changed means the
		// server's hand holds something else
		if(selectedSlot() != 0)
			select(0);
		
		if(effectsDirty)
		{
			server.command("effect clear " + BOT);
			server.command(
				"effect give " + BOT + " minecraft:resistance infinite 4 true");
			server.command(
				"effect give " + BOT + " minecraft:saturation infinite 0 true");
			effectsDirty = false;
		}
		
		if(gameModeDirty)
		{
			server.command("gamemode survival " + BOT);
			gameModeDirty = false;
		}
		
		if(timeDirty)
		{
			server.command("time set noon");
			timeDirty = false;
		}
		
		if(weatherDirty)
		{
			server.command("weather clear");
			// rain fades out over seconds otherwise, into the next tests'
			// screenshots
			waitUntil(mc -> !mc.level.isRaining(), 20);
			context.runOnClient(mc -> mc.level.setRainLevel(0));
			weatherDirty = false;
		}
		
		bareWorld = false;
		
		if(cameraDirty)
		{
			context.runOnClient(mc -> {
				mc.options.setCameraType(
					net.minecraft.client.CameraType.FIRST_PERSON);
				mc.setCameraEntity(mc.player);
			});
			cameraDirty = false;
		}
		
		if(!changedSettings.isEmpty())
		{
			context.runOnClient(mc -> changedSettings
				.forEach((setting, json) -> setting.fromJson(json)));
			changedSettings.clear();
		}
		
		if(context.computeOnClient(
			mc -> mc.player.getHealth() < mc.player.getMaxHealth()))
			server.command(
				"effect give " + BOT + " minecraft:instant_health 1 4");
	}
	
	// ── Hacks and settings ──
	
	/** Turns a hack on; false if it turned itself off at once. */
	boolean on(String hack)
	{
		start();
		return context.computeOnClient(mc -> {
			Hack h = WurstClient.INSTANCE.getHax().getHackByName(hack);
			h.setEnabled(true);
			return h.isEnabled();
		});
	}
	
	void off(String hack)
	{
		context.runOnClient(mc -> WurstClient.INSTANCE.getHax()
			.getHackByName(hack).setEnabled(false));
	}
	
	boolean isOn(String hack)
	{
		return context.computeOnClient(mc -> WurstClient.INSTANCE.getHax()
			.getHackByName(hack).isEnabled());
	}
	
	/**
	 * Changes a setting for this test only (put back by the next cleanup).
	 * The value is a number, a boolean or a mode's name.
	 */
	void set(String feature, String setting, Object value)
	{
		context.runOnClient(mc -> {
			Setting s = setting(feature, setting);
			changedSettings.putIfAbsent(s, s.toJson());
			JsonPrimitive json =
				value instanceof Number n ? new JsonPrimitive(n)
					: value instanceof Boolean b ? new JsonPrimitive(b)
						: new JsonPrimitive(value.toString());
			s.fromJson(json);
		});
	}
	
	/**
	 * Puts a setting back after the test, for tests where a command (the
	 * thing tested) changes it.
	 */
	void remember(String feature, String setting)
	{
		context.runOnClient(mc -> {
			Setting s = setting(feature, setting);
			changedSettings.putIfAbsent(s, s.toJson());
		});
	}
	
	String settingValue(String feature, String setting)
	{
		return context.computeOnClient(
			mc -> setting(feature, setting).toJson().toString());
	}
	
	static Setting setting(String feature, String setting)
	{
		Feature f = WurstClient.INSTANCE.getFeatureByName(feature);
		if(f == null)
			throw new IllegalArgumentException("No feature " + feature);
		Setting s = f.getSettings().get(setting.toLowerCase(Locale.ROOT));
		if(s == null)
			throw new IllegalArgumentException(
				"No setting " + feature + " " + setting);
		return s;
	}
	
	/** Runs a Wurst command without typing it (setup, not the test). */
	void wurst(String command)
	{
		context.runOnClient(
			mc -> WurstClient.INSTANCE.getCmdProcessor().process(command));
	}
	
	/** Types a Wurst command into chat like a player. */
	void type(String command)
	{
		start();
		WurstClientTestHelper.runWurstCommand(context, command);
	}
	
	// ── The scene ──
	
	BlockPos p(int dx, int dy, int dz)
	{
		return home.offset(dx, dy, dz);
	}
	
	String xyz(int dx, int dy, int dz)
	{
		BlockPos pos = p(dx, dy, dz);
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
	
	void block(int dx, int dy, int dz, String block)
	{
		server.command("setblock " + xyz(dx, dy, dz) + " " + block);
		arenaDirty = true;
	}
	
	void fill(int dx1, int dy1, int dz1, int dx2, int dy2, int dz2,
		String block)
	{
		server.command("fill " + xyz(dx1, dy1, dz1) + " " + xyz(dx2, dy2, dz2)
			+ " " + block);
		arenaDirty = true;
	}
	
	/** The test changed the arena some other way (explosion, mining...). */
	void messy()
	{
		arenaDirty = true;
	}
	
	/** The test changed the bot's effects (death, milk...). */
	void effectsChanged()
	{
		effectsDirty = true;
	}
	
	/** The test changed the inventory without give() (creative items...). */
	void inventoryChanged()
	{
		inventoryDirty = true;
	}
	
	/** Waits until the client has the block (at most a second). */
	boolean awaitBlock(int dx, int dy, int dz, Block block)
	{
		BlockPos pos = p(dx, dy, dz);
		return waitUntil(mc -> mc.level.getBlockState(pos).is(block), 20);
	}
	
	/** Blocks of this type in the box, as the client has them. */
	int count(Block block, int dx1, int dy1, int dz1, int dx2, int dy2, int dz2)
	{
		BlockPos a = p(dx1, dy1, dz1);
		BlockPos b = p(dx2, dy2, dz2);
		return context.computeOnClient(mc -> {
			if(mc.level == null)
				return -1;
			int n = 0;
			for(BlockPos pos : BlockPos.betweenClosed(a, b))
				if(mc.level.getBlockState(pos).is(block))
					n++;
			return n;
		});
	}
	
	/** Whether the server has this block there (not just the client). */
	boolean serverBlock(int dx, int dy, int dz, String block)
	{
		String answer =
			server.query("execute if block " + xyz(dx, dy, dz) + " " + block,
				Pattern.compile("Test (passed|failed)"));
		return answer != null && answer.contains("passed");
	}
	
	/**
	 * Summons a mob at block (dx, dz)'s center, standing still and unhurt by
	 * anything but the bot. Returns a selector for it.
	 */
	String mob(String type, double dx, double dy, double dz, String extraNbt)
	{
		String tag = "ac" + ++summoned;
		Vec3 pos = Vec3.atBottomCenterOf(p(0, 0, 0)).add(dx, dy, dz);
		server.command(String.format(Locale.ROOT,
			"summon %s %.2f %.2f %.2f {NoAI:1b,Silent:1b,PersistenceRequired:1b,"
				+ "Tags:[\"%s\"]%s}",
			type, pos.x, pos.y, pos.z, tag,
			extraNbt.isEmpty() ? "" : "," + extraNbt));
		return "@e[tag=" + tag + ",limit=1]";
	}
	
	/** A husk with 1024 health, so it never dies. */
	String husk(double dx, double dz)
	{
		return mob("husk", dx, 0, dz, "Health:1024f,attributes:[{id:"
			+ "\"minecraft:max_health\",base:1024}]");
	}
	
	/** Waits until the client sees at least n entities of this kind. */
	boolean awaitEntities(Class<? extends Entity> type, int n)
	{
		return waitUntil(mc -> countEntities(mc, type) >= n, 20);
	}
	
	static int countEntities(Minecraft mc, Class<? extends Entity> type)
	{
		int n = 0;
		// a mob the last test's cleanup killed is still there, dying
		for(Entity e : mc.level.entitiesForRendering())
			if(type.isInstance(e) && e != mc.player && e.isAlive())
				n++;
		return n;
	}
	
	/** A number from an entity's data on the server, or NaN. */
	double data(String selector, String path)
	{
		String answer = server.query("data get entity " + selector + " " + path,
			Pattern.compile("entity data: |found no elements|No entity"));
		if(answer == null)
			return Double.NaN;
		
		Matcher m = Pattern.compile("entity data: (-?[0-9.]+)").matcher(answer);
		return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
	}
	
	/** How much health a 1024-health husk lost, from the server. */
	double damage(String selector)
	{
		double health = data(selector, "Health");
		return Double.isNaN(health) ? 0 : 1024 - health;
	}
	
	/** Waits until the husk is hurt (seen on the client: its hurt time). */
	boolean awaitHurt(Class<? extends Entity> type, int maxTicks)
	{
		return waitUntil(mc -> {
			for(Entity e : mc.level.entitiesForRendering())
				if(type.isInstance(e)
					&& e instanceof net.minecraft.world.entity.LivingEntity le
					&& !le.isDeadOrDying() && le.hurtTime > 0)
					return true;
			return false;
		}, maxTicks);
	}
	
	// ── Inventory ──
	
	/**
	 * Puts an item in a slot (hotbar.N, inventory.N, weapon.offhand,
	 * armor.head/chest/legs/feet), e.g. "stone 64" or
	 * "bow[enchantments={flame:1}]".
	 */
	void give(String slot, String item)
	{
		server.command(
			"item replace entity " + BOT + " " + slot + " with " + item);
		String id = item.split("[\\[ ]")[0];
		expectedItems.put(slotIndex(slot), id);
		inventoryDirty = true;
	}
	
	/** Waits until the client has everything given so far. */
	boolean awaitItems()
	{
		return waitUntil(mc -> {
			for(Map.Entry<Integer, String> e : expectedItems.entrySet())
				if(!itemId(mc.player.getInventory().getItem(e.getKey()))
					.equals(e.getValue()))
					return false;
			return true;
		}, 20);
	}
	
	static String itemId(ItemStack stack)
	{
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}
	
	static int slotIndex(String slot)
	{
		return switch(slot)
		{
			case "weapon.offhand" -> 40;
			case "armor.head" -> 39;
			case "armor.chest" -> 38;
			case "armor.legs" -> 37;
			case "armor.feet" -> 36;
			default ->
			{
				int n = Integer.parseInt(slot.substring(slot.indexOf('.') + 1));
				yield slot.startsWith("hotbar.") ? n : 9 + n;
			}
		};
	}
	
	/** The server's copy of an inventory slot (0-35), as SNBT, or "". */
	String serverSlot(int slot)
	{
		String answer = server.query(
			"data get entity " + BOT
				+ (slot < 0 ? " Inventory"
					: " Inventory[{Slot:" + slot + "b}]"),
			Pattern.compile("entity data: |found no elements"));
		return answer == null ? "" : answer;
	}
	
	/**
	 * For things that change an item on the client: whether the server got
	 * it too (in creative, only what the client sends counts).
	 */
	String onServerToo(boolean clientOk, int slot, String expected, String did,
		String didnt)
	{
		if(!clientOk)
			return FAIL + didnt;
		String server = serverSlot(slot);
		return server.contains(expected) ? did
			: FAIL + did + " on this screen only - the server's item is"
				+ " unchanged (" + server.replaceAll(".*entity data: ", "")
				+ ")";
	}
	
	/** How many of this item the client's inventory holds. */
	int countItem(String id)
	{
		return context.computeOnClient(mc -> {
			int n = 0;
			var inv = mc.player.getInventory();
			for(int i = 0; i < inv.getContainerSize(); i++)
				if(itemId(inv.getItem(i)).equals(id))
					n += inv.getItem(i).getCount();
			return n;
		});
	}
	
	int selectedSlot()
	{
		return context
			.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
	}
	
	void select(int slot)
	{
		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(slot);
			WurstClient.IMC.getInteractionManager().syncSelectedSlot();
		});
	}
	
	// ── The bot ──
	
	/** Teleports to block (dx, dy, dz)'s center, looking this way. */
	void at(int dx, int dy, int dz, float yaw, float pitch)
	{
		Vec3 pos = Vec3.atBottomCenterOf(p(dx, dy, dz));
		long accepted = countSent(ServerboundAcceptTeleportationPacket.class);
		server.command(
			String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f %.1f", BOT,
				pos.x, pos.y, pos.z, yaw, pitch));
		// done once the client confirms it (in the air it falls right away,
		// so its position never matches for long)
		for(int i = 0; i < 40 && countSent(
			ServerboundAcceptTeleportationPacket.class) <= accepted; i++)
			context.waitTick();
	}
	
	/**
	 * Turns to look at a point by moving the mouse, like a player. (The
	 * server's /rotate made the client answer with an extra rotation packet
	 * inside the tick - Grim AimDuplicateLook/TickTimer, Intave "2 packets
	 * without client tick end" - flags that then landed on the feature.)
	 */
	void lookAt(Vec3 target)
	{
		double[] counts = context.computeOnClient(mc -> {
			Vec3 d = target.subtract(mc.player.getEyePosition());
			double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
			double pitch = -Math
				.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			double step = Rotation.getMouseStep();
			return new double[]{
				Mth.wrapDegrees(yaw - mc.player.getYRot()) / step,
				(pitch - mc.player.getXRot()) / step, yaw, pitch, step};
		});
		input.moveCursor(Math.round(counts[0]), Math.round(counts[1]));
		double tolerance = counts[4] * 1.5;
		boolean turned =
			waitUntil(
				mc -> Math
					.abs(Mth.wrapDegrees(
						mc.player.getYRot() - (float)counts[2])) < tolerance
					&& Math.abs(mc.player.getXRot() - counts[3]) < tolerance,
				10);
		// the mouse movement sometimes never arrives (the first one of a
		// run): then the same turn in whole mouse steps, like Wurst's aim
		if(!turned)
			context.runOnClient(
				mc -> new Rotation((float)counts[2], (float)counts[3])
					.applyToClientPlayer());
		// one tick for the movement packet with the new rotation
		context.waitTick();
	}
	
	/** Looks at the center of a block's face toward the bot. */
	void lookAtBlock(int dx, int dy, int dz)
	{
		lookAt(Vec3.atCenterOf(p(dx, dy, dz)));
	}
	
	/** Where the bot is, relative to home's block corner. */
	Vec3 pos()
	{
		Vec3 base = Vec3.atLowerCornerOf(home);
		return context.computeOnClient(mc -> mc.player.position())
			.subtract(base);
	}
	
	/** {@link #pos()} for code already on the client thread (lambdas). */
	Vec3 rel(Minecraft mc)
	{
		return mc.player.position().subtract(Vec3.atLowerCornerOf(home));
	}
	
	/** Where the server has the bot, relative to home's block corner. */
	Vec3 serverPos()
	{
		String answer = server.query("data get entity " + BOT + " Pos",
			Pattern.compile("entity data: \\["));
		if(answer == null)
			return null;
		Matcher m =
			Pattern.compile("\\[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d\\]")
				.matcher(answer);
		if(!m.find())
			return null;
		return new Vec3(Double.parseDouble(m.group(1)),
			Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)))
				.subtract(Vec3.atLowerCornerOf(home));
	}
	
	void effect(String effect, int seconds, int amplifier)
	{
		server.command("effect give " + BOT + " minecraft:" + effect + " "
			+ seconds + " " + amplifier + " true");
		effectsDirty = true;
	}
	
	/** Takes away the infinite resistance (for tests about damage). */
	void vulnerable()
	{
		server.command("effect clear " + BOT + " minecraft:resistance");
		effectsDirty = true;
	}
	
	/** Hungry enough to eat: food at most this much (of 20). */
	boolean hungry(int food)
	{
		server.command("effect clear " + BOT + " minecraft:saturation");
		server.command("effect give " + BOT + " minecraft:hunger 10 255 true");
		effectsDirty = true;
		boolean ok = waitUntil(
			mc -> mc.player.getFoodData().getFoodLevel() <= food, 200);
		server.command("effect clear " + BOT + " minecraft:hunger");
		return ok;
	}
	
	void creative()
	{
		server.command("gamemode creative " + BOT);
		gameModeDirty = true;
		waitUntil(mc -> mc.player.getAbilities().instabuild, 20);
	}
	
	void time(String time)
	{
		server.command("time set " + time);
		timeDirty = true;
	}
	
	void weather(String weather)
	{
		server.command("weather " + weather);
		weatherDirty = true;
	}
	
	void thirdPerson()
	{
		context.runOnClient(mc -> mc.options
			.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
		cameraDirty = true;
	}
	
	void cameraChanged()
	{
		cameraDirty = true;
	}
	
	// ── Input ──
	
	void hold(int key)
	{
		input.holdKey(key);
		heldKeys.add(key);
	}
	
	void release(int key)
	{
		input.releaseKey(key);
		heldKeys.remove(key);
	}
	
	void holdFor(int key, int ticks)
	{
		hold(key);
		context.waitTicks(ticks);
		release(key);
	}
	
	void holdMouse(int button)
	{
		input.holdMouse(button);
		heldButtons.add(button);
	}
	
	void releaseMouse(int button)
	{
		input.releaseMouse(button);
		heldButtons.remove(button);
	}
	
	void click(int button)
	{
		input.pressMouse(button);
	}
	
	static final int W = GLFW.GLFW_KEY_W;
	static final int S = GLFW.GLFW_KEY_S;
	static final int SPACE = GLFW.GLFW_KEY_SPACE;
	static final int SHIFT = GLFW.GLFW_KEY_LEFT_SHIFT;
	static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
	static final int RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
	
	// ── Watching ──
	
	/**
	 * Waits until the condition holds (checked once per tick); false after
	 * maxTicks without it.
	 */
	boolean waitUntil(Predicate<Minecraft> condition, int maxTicks)
	{
		for(int i = 0;; i++)
		{
			if(context.computeOnClient(mc -> mc.player != null
				&& mc.level != null && condition.test(mc)))
				return true;
			if(i >= maxTicks)
				return false;
			context.waitTick();
		}
	}
	
	/** Ticks until the condition held (maxTicks + 1 if it never did). */
	int ticksUntil(Predicate<Minecraft> condition, int maxTicks)
	{
		for(int i = 0; i <= maxTicks; i++)
		{
			if(context.computeOnClient(mc -> mc.player != null
				&& mc.level != null && condition.test(mc)))
				return i;
			context.waitTick();
		}
		return maxTicks + 1;
	}
	
	/** Like waitUntil, also while not in a world. */
	boolean waitUntilAnywhere(Predicate<Minecraft> condition, int maxTicks)
	{
		for(int i = 0;; i++)
		{
			if(context.computeOnClient(condition::test))
				return true;
			if(i >= maxTicks)
				return false;
			context.waitTick();
		}
	}
	
	/** The bot's path, relative to home's corner, once per tick. */
	List<Vec3> path(int ticks)
	{
		List<Vec3> path = new ArrayList<>();
		for(int i = 0; i < ticks; i++)
		{
			path.add(pos());
			context.waitTick();
		}
		path.add(pos());
		return path;
	}
	
	static double horizontal(Vec3 a, Vec3 b)
	{
		return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z));
	}
	
	<T> T client(Function<Minecraft, T> f)
	{
		return context.computeOnClient(f::apply);
	}
	
	/**
	 * Runs for this many ticks and returns the extreme of a value seen in
	 * them (max, or min with a minus sign in front of the function).
	 */
	double maxOver(int ticks, Function<Minecraft, Double> value)
	{
		double max = Double.NEGATIVE_INFINITY;
		for(int i = 0; i < ticks; i++)
		{
			max = Math.max(max, context.computeOnClient(value::apply));
			context.waitTick();
		}
		return max;
	}
	
	/** Every packet the client sent in this test so far. */
	List<Object> sent()
	{
		PacketTrace.takeSent().forEach(sent::add);
		List<Object> packets = new ArrayList<>();
		for(PacketTrace.Sent s : sent)
			packets.add(s.packet());
		return packets;
	}
	
	List<PacketTrace.Sent> sentTimed()
	{
		sent();
		return sent;
	}
	
	long countSent(Class<?> type)
	{
		return sent().stream().filter(type::isInstance).count();
	}
	
	/** The packet types sent beyond walking around, e.g. "Swing x3". */
	String sentSummary()
	{
		Map<String, Integer> counts = new TreeMap<>();
		for(Object packet : sent())
		{
			String type = packet.getClass().getSimpleName()
				.replace("Serverbound", "").replace("Packet", "");
			if(!IDLE.contains(type))
				counts.merge(type, 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((type, n) -> parts.add(type + " x" + n));
		return parts.isEmpty() ? "" : "sent " + String.join(", ", parts);
	}
	
	/** What a player standing or walking around sends anyway. */
	private static final Set<String> IDLE = Set.of("ClientTickEnd", "Pos",
		"PosRot", "Rot", "StatusOnly", "PlayerInput", "KeepAlive", "Pong",
		"ChunkBatchReceived", "AcceptTeleportation", "PlayerLoaded");
	
	/** The chat messages since the test began, oldest first. */
	List<String> chat()
	{
		return context.computeOnClient(mc -> {
			List<GuiMessage> all =
				IChatComponent.get(mc.gui.getChat()).getAllMessages();
			List<String> lines = new ArrayList<>();
			for(int i = all.size() - 1; i >= 0; i--)
				lines.add(all.get(i).content().getString());
			return lines;
		});
	}
	
	boolean chatContains(String text)
	{
		return chat().stream().anyMatch(l -> l.contains(text));
	}
	
	/** A Wurst error or syntax error in chat since the test began. */
	String chatError()
	{
		for(String line : chat())
			if(line.contains("[ERROR]") || line.contains("Syntax error:"))
				return line;
		return null;
	}
	
	boolean awaitChat(String text, int maxTicks)
	{
		return waitUntil(mc -> {
			for(GuiMessage m : IChatComponent.get(mc.gui.getChat())
				.getAllMessages())
				if(m.content().getString().contains(text))
					return true;
			return false;
		}, maxTicks);
	}
	
	// ── The screen ──
	
	/**
	 * Hides what changes on the screen without the feature: the hack list,
	 * toggle messages, clouds. Done once for the whole sweep.
	 */
	void quietScreen()
	{
		context.runOnClient(mc -> {
			Setting mode = setting("HackList", "Mode");
			Setting messages = setting("ToggleMessages", "Enabled");
			screenSettings.put(mode, mode.toJson());
			screenSettings.put(messages, messages.toJson());
			clouds = mc.options.cloudStatus().get();
			mode.fromJson(new JsonPrimitive("Hidden"));
			messages.fromJson(new JsonPrimitive(false));
			mc.options.cloudStatus().set(CloudStatus.OFF);
		});
	}
	
	/** Undoes {@link #quietScreen()}. */
	void restoreScreen()
	{
		context.runOnClient(mc -> {
			screenSettings.forEach((s, json) -> s.fromJson(json));
			if(clouds != null)
				mc.options.cloudStatus().set(clouds);
		});
	}
	
	private final Map<Setting, JsonElement> screenSettings =
		new LinkedHashMap<>();
	private CloudStatus clouds;
	
	/**
	 * Whether turning the hack on changes the picture: a screenshot with it
	 * off, one with it on, one with it off again. The two "off" ones show
	 * how much changes anyway (animations); the "on" one has to differ from
	 * the first clearly more than that. Before each screenshot,
	 * {@code beforeShot} (if any) sets up what the hack works on, e.g. a
	 * hurt for NoHurtcam.
	 */
	String screenChange(String hack, int scanTicks, Runnable beforeShot)
	{
		// the scene was just built: let its chunks re-render first
		context.waitTicks(5);
		if(beforeShot != null)
			beforeShot.run();
		context.waitTicks(3);
		Path off1 = shot(hack + "_off1");
		if(beforeShot != null)
			beforeShot.run();
		context.waitTicks(3);
		Path off2 = shot(hack + "_off2");
		
		// both "off" pictures before the hack: some fade out after it's off
		boolean enabled = on(hack);
		context.waitTicks(scanTicks);
		if(beforeShot != null)
			beforeShot.run();
		context.waitTicks(3);
		Path on = shot(hack + "_on");
		off(hack);
		
		return verdictOf(enabled, off1, off2, on);
	}
	
	/**
	 * The verdict for two "off" screenshots and one "on" screenshot: the
	 * "on" one has to differ from both clearly more than they differ from
	 * each other (animations, flicker).
	 */
	String verdictOf(boolean enabled, Path off1, Path off2, Path on)
	{
		double changed = Math.min(difference(off1, on), difference(off2, on));
		double noise = difference(off1, off2);
		boolean ok = enabled && changed > noise * 1.3 + 0.003;
		String numbers = String.format(Locale.ROOT,
			"%.1f%% of the screen changed (%.1f%% without it)", changed * 100,
			noise * 100);
		if(ok)
			for(Path p : new Path[]{off1, on, off2})
				try
				{
					Files.deleteIfExists(p);
				}catch(Exception e)
				{}
		return ok ? "visible: " + numbers : FAIL + (enabled
			? "no visible change - " + numbers : "turned itself off at once");
	}
	
	Path shot(String fileName)
	{
		// toasts (advancements, recipes) and particles (the last test's mobs
		// dying) aren't the hack's
		context.runOnClient(mc -> {
			mc.getToastManager().clear();
			mc.particleEngine.clearParticles();
			if(bareWorld)
				mc.options.hideGui = true;
		});
		context.waitTick();
		try
		{
			return context.takeScreenshot(
				"ac_" + name.toLowerCase(Locale.ROOT) + "_" + fileName);
			
		}finally
		{
			if(bareWorld)
				context.runOnClient(mc -> mc.options.hideGui = false);
		}
	}
	
	/**
	 * Screenshots without the HUD and the hand (F1), for things drawn in the
	 * world: a swinging or drawing hand changes more of the picture than a
	 * thin box does. Reset by the next test.
	 */
	boolean bareWorld;
	
	/**
	 * For hacks that take an effect away (fire overlay, nausea, hurt shake):
	 * a screenshot without the effect, one with it, one with it and the
	 * hack on. With the hack, the picture has to be clearly closer to the
	 * one without the effect. {@code eachShot} (if any) runs before every
	 * screenshot with the effect, e.g. a fresh hurt.
	 */
	String removesEffect(String hack, Runnable addEffect, Runnable eachShot)
	{
		context.waitTicks(5);
		Path without = shot(hack + "_without");
		addEffect.run();
		context.waitTicks(10);
		if(eachShot != null)
			eachShot.run();
		context.waitTicks(3);
		Path with = shot(hack + "_with");
		boolean enabled = on(hack);
		context.waitTicks(3);
		if(eachShot != null)
			eachShot.run();
		context.waitTicks(3);
		Path on = shot(hack + "_on");
		off(hack);
		
		double effect = difference(without, with);
		double left = difference(without, on);
		boolean ok = enabled && effect > 0.01 && left < effect * 0.5;
		String numbers =
			fmt("the effect changes %.1f%% of the screen, %.1f%% with the hack",
				effect * 100, left * 100);
		return ok ? "took the effect away: " + numbers
			: FAIL + (enabled ? numbers : "turned itself off at once");
	}
	
	/** Share of pixels (every 2nd in each direction) that clearly differ. */
	private static double difference(Path a, Path b)
	{
		try(NativeImage x = WurstClientTestHelper.loadImageFile(a);
			NativeImage y = WurstClientTestHelper.loadImageFile(b))
		{
			int w = Math.min(x.getWidth(), y.getWidth());
			int h = Math.min(x.getHeight(), y.getHeight());
			int differ = 0, total = 0;
			for(int py = 0; py < h; py += 2)
				for(int px = 0; px < w; px += 2)
				{
					int c1 = x.getPixel(px, py);
					int c2 = y.getPixel(px, py);
					int d = Math.max(Math.abs((c1 & 0xFF) - (c2 & 0xFF)),
						Math.max(Math.abs((c1 >> 8 & 0xFF) - (c2 >> 8 & 0xFF)),
							Math.abs((c1 >> 16 & 0xFF) - (c2 >> 16 & 0xFF))));
					// low: a TrueSight husk is a faint shape against the sky
					if(d > 10)
						differ++;
					total++;
				}
			return total == 0 ? 0 : (double)differ / total;
		}
	}
	
	// ── Verdicts ──
	
	/** The note for a check: what it did, or why it didn't work. */
	static String check(boolean ok, String did, String didnt)
	{
		return ok ? did : FAIL + didnt;
	}
	
	static String fmt(String format, Object... args)
	{
		return String.format(Locale.ROOT, format, args);
	}
}
