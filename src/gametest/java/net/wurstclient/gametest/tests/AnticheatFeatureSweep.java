/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.gametest.PacketTrace;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.ForceOpHack;
import net.wurstclient.navigator.NavigatorMainScreen;
import net.wurstclient.settings.FileSetting;

/**
 * Every hack and every command on the anticheat server, each in a test made
 * for what it does: its own scene (a pool for Jesus, a ladder for FastLadder,
 * a still husk for the auras, ores for VeinMiner...), its own playing (the
 * keys and clicks a player would use with it), and a check that it actually
 * did its thing (the husk lost health, the bot walked on the water, the vein
 * is gone, the screen looks different...). A clean report from a feature
 * that never did anything means nothing, so a failed check is reported as
 * "DIDN'T WORK" next to the flags.
 *
 * <p>
 * Built for speed: each test runs only until its effect shows (with a
 * timeout), builds only its own scene, and the next test undoes only what
 * this one changed ({@link FeatureTestKit}). A few ticks after each feature
 * catch late flags; the flags go to the feature that was on.
 *
 * <p>
 * Killaura, MaceAssist, ScaffoldWalk and Flight have their own scenarios in
 * {@link AnticheatTest}. Left out, because they'd send text to outside
 * services from a test run: ChatTranslator (Google Translate) and
 * AutoComplete (OpenRouter).
 */
final class AnticheatFeatureSweep extends FeatureTestKit
{
	/** Talk to outside services; not run from tests. */
	static final Set<String> SKIPPED = Set.of("ChatTranslator", "AutoComplete");
	
	/** Tested by AnticheatTest's own scenarios. */
	static final Set<String> CORE =
		Set.of("Killaura", "MaceAssist", "ScaffoldWalk", "Flight");
	
	private final Map<String, Supplier<String>> tests = new LinkedHashMap<>();
	
	/** {@code -Dwurst.test.features}: only these (empty: all). */
	static final List<String> FEATURES = java.util.Arrays
		.stream(System.getProperty("wurst.test.features", "")
			.toLowerCase(Locale.ROOT).split(","))
		.map(String::trim).filter(s -> !s.isEmpty()).toList();
	
	AnticheatFeatureSweep(AnticheatTest test, ClientGameTestContext context)
	{
		super(test, context, test.server().getName());
	}
	
	void run()
	{
		home = test.home();
		
		combat();
		movement();
		blocks();
		items();
		render();
		chatAndOther();
		commands();
		// these take the bot off the server
		lastOnes();
		
		// a hack without a test shows up as one
		List<String> hacks = context.computeOnClient(mc -> {
			List<String> names = new ArrayList<>();
			for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
				names.add(hack.getName());
			return names;
		});
		for(String hack : hacks)
			if(!tests.containsKey("hack " + hack) && !SKIPPED.contains(hack)
				&& !CORE.contains(hack))
				tests.put("hack " + hack,
					() -> FAIL + "no test written for it yet");
		Set<String> known = new HashSet<>(hacks);
		for(String name : tests.keySet())
			if(name.startsWith("hack ")
				&& !known.contains(name.substring(5).replaceAll(" \\(.*", "")))
				throw new IllegalStateException("No hack " + name);
			
		// -Pfeatures=blink,criticals: only the tests whose name contains one
		if(!FEATURES.isEmpty())
			tests.keySet().removeIf(name -> FEATURES.stream()
				.noneMatch(name.toLowerCase(Locale.ROOT)::contains));
		
		quietScreen();
		try
		{
			runAll();
			
		}finally
		{
			restoreScreen();
		}
	}
	
	private void runAll()
	{
		tests.forEach((name, body) -> test.featureScenario(name, () -> {
			begin();
			String before = probeNote;
			probeNote = null;
			lastTest = name;
			String note;
			String offNote;
			try
			{
				note = body.get();
				// what it looked like when it didn't work (render tests keep
				// their own screenshots)
				if(note != null && note.contains(FAIL)
					&& !note.contains("of the screen"))
					shot("fail_" + name.replace(' ', '_').replace(".", ""));
				
			}finally
			{
				offNote = finish();
			}
			String sent = sentSummary();
			List<String> setup = setupFlags();
			String setupNote = setup == null ? null
				: "set aside as setup: " + AnticheatTest.summarize(setup);
			List<String> parts = new ArrayList<>();
			for(String part : new String[]{note, offNote, sent, setupNote,
				before})
				if(part != null && !part.isEmpty())
					parts.add(part);
			return String.join("; ", parts);
		}));
	}
	
	private void hack(String name, Supplier<String> body)
	{
		tests.put("hack " + name, body);
	}
	
	private void cmd(String name, Supplier<String> body)
	{
		tests.put("cmd ." + name, body);
	}
	
	// ── Shared scenes ──
	
	private void sword()
	{
		give("hotbar.0", "diamond_sword");
		awaitItems();
	}
	
	private void pickaxe()
	{
		give("hotbar.0", "diamond_pickaxe");
		awaitItems();
	}
	
	/** A husk's body center, for looking at it. */
	private Vec3 body(double dx, double dz)
	{
		return Vec3.atBottomCenterOf(home).add(dx, 1, dz);
	}
	
	/** Water from z 2 to 12, this deep (1-3), cut into the floor. */
	private void pool(int depth)
	{
		fill(-3, -depth, 2, 3, -1, 12, "water");
		awaitBlock(0, -1, 7, Blocks.WATER);
	}
	
	/** A wall across the way south, at this z, this high. */
	private void wall(int z, int height)
	{
		fill(-2, 0, z, 2, height - 1, z, "stone");
		awaitBlock(0, height - 1, z, Blocks.STONE);
	}
	
	/** Holds W for this many ticks; returns the horizontal distance. */
	private double walk(int ticks)
	{
		Vec3 start = pos();
		holdFor(W, ticks);
		return horizontal(start, pos());
	}
	
	/** A husk somewhere, hit by the hack under test within maxTicks. */
	private String auraTest(String hack, double dx, double dz, int maxTicks)
	{
		sword();
		String husk = husk(dx, dz);
		awaitEntities(Husk.class, 1);
		if(!on(hack))
			return FAIL + "turned itself off at once";
		boolean hurt = awaitHurt(Husk.class, maxTicks);
		return check(hurt, fmt("hit the husk (%.1f damage)", damage(husk)),
			"never hit the husk");
	}
	
	/** One click at a husk right in front, after a full attack cooldown. */
	private String clickTest(String hack, String item, double dz,
		double minDamage, String what)
	{
		give("hotbar.0", item);
		awaitItems();
		String husk = husk(0, dz);
		awaitEntities(Husk.class, 1);
		lookAt(body(0, dz));
		if(!on(hack))
			return FAIL + "turned itself off at once";
		context.waitTicks(12);
		click(LEFT);
		boolean hurt = awaitHurt(Husk.class, 30);
		double damage = damage(husk);
		boolean attacked = sent().stream()
			.anyMatch(p -> p.getClass().getSimpleName().contains("Attack"));
		return check(hurt && damage >= minDamage,
			fmt("%s (%.1f damage)", what, damage),
			hurt ? fmt("only %.1f damage", damage) : attacked
				? "sent the hit, the server threw it out" : "never hit");
	}
	
	/**
	 * Criticals in one mode: a click at a husk 2 blocks ahead (aimed low, so
	 * it stays under the crosshair while jumping), then the damage it took.
	 */
	private String critTest(String mode)
	{
		sword();
		String husk = husk(0, 2);
		awaitEntities(Husk.class, 1);
		lookAt(Vec3.atBottomCenterOf(home).add(0, 0.6, 2));
		set("Criticals", "Mode", mode);
		on("Criticals");
		context.waitTicks(12);
		click(LEFT);
		double top = maxOver(20, mc -> rel(mc).y);
		awaitHurt(Husk.class, 5);
		double damage = damage(husk);
		boolean jumpMode = !mode.equals("Packet");
		// a diamond sword does 7, a critical hit 10.5
		return check(damage >= 10,
			fmt("%s: a critical hit for %.1f%s", mode, damage,
				jumpMode ? fmt(" (jumped %.2f)", top) : ""),
			fmt("%s: hit for %.1f%s", mode, damage,
				jumpMode ? fmt(", jumped %.2f", top) : ""));
	}
	
	/** Rotation packets' pitches (or yaws) sent so far. */
	private List<Float> sentRotations(boolean pitch)
	{
		List<Float> rots = new ArrayList<>();
		for(Object p : sent())
			if(p instanceof ServerboundMovePlayerPacket m && m.hasRotation())
				rots.add(pitch ? m.getXRot(0) : m.getYRot(0));
		return rots;
	}
	
	private static float spread(List<Float> values)
	{
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for(float v : values)
		{
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		return values.isEmpty() ? 0 : max - min;
	}
	
	// ── Combat ──
	
	private void combat()
	{
		hack("AimAssist", () -> {
			sword();
			String husk = husk(1.5, 2);
			awaitEntities(Husk.class, 1);
			at(0, 0, 0, 10, 0);
			on("AimAssist");
			boolean aimed =
				waitUntil(mc -> mc.crosshairPickEntity instanceof Husk, 60);
			boolean hit = aimed && awaitHurt(Husk.class, 40);
			return check(hit,
				fmt("turned onto the husk and hit it (%.1f damage)",
					damage(husk)),
				aimed ? "aimed at the husk but never hit it"
					: "never turned onto the husk");
		});
		
		// the combo: W/S taps, sprint resets, held-back hits
		hack("AimAssist (Auto combo)", () -> {
			sword();
			String husk = husk(1.5, 2);
			awaitEntities(Husk.class, 1);
			at(0, 0, 0, 10, 0);
			set("AimAssist", "Auto combo", true);
			set("AimAssist", "Dodging", false);
			on("AimAssist");
			boolean aimed =
				waitUntil(mc -> mc.crosshairPickEntity instanceof Husk, 60);
			boolean hit = aimed && awaitHurt(Husk.class, 40);
			// a few combo hits
			if(hit)
				context.waitTicks(60);
			return check(hit,
				fmt("comboed the husk (%.1f damage)", damage(husk)),
				aimed ? "aimed at the husk but never hit it"
					: "never turned onto the husk");
		});
		
		hack("AnchorAura", () -> {
			give("hotbar.0", "respawn_anchor 8");
			give("hotbar.1", "glowstone 16");
			awaitItems();
			String husk = husk(0, 3);
			awaitEntities(Husk.class, 1);
			// it only goes for players by default
			set("AnchorAura", "Filter hostile mobs", false);
			on("AnchorAura");
			boolean hurt = awaitHurt(Husk.class, 80);
			messy();
			return check(hurt,
				fmt("blew an anchor up at the husk (%.1f damage)",
					damage(husk)),
				"the husk was never hurt");
		});
		
		hack("ArrowDMG", () -> {
			give("hotbar.0", "bow");
			give("inventory.0", "arrow 64");
			awaitItems();
			String husk = husk(0, 5);
			awaitEntities(Husk.class, 1);
			lookAt(body(0, 5));
			on("ArrowDMG");
			holdMouse(RIGHT);
			context.waitTicks(22);
			releaseMouse(RIGHT);
			boolean hurt = awaitHurt(Husk.class, 20);
			return check(hurt,
				fmt("the arrow hit for %.1f (a full-power arrow does ~9)",
					damage(husk)),
				"the arrow never hit");
		});
		
		hack("AutoSword", () -> {
			give("hotbar.0", "diamond_pickaxe");
			give("hotbar.3", "diamond_sword");
			awaitItems();
			select(0);
			String husk = husk(0, 2);
			awaitEntities(Husk.class, 1);
			lookAt(body(0, 2));
			on("AutoSword");
			context.waitTicks(12);
			click(LEFT);
			boolean switched = waitUntil(
				mc -> mc.player.getInventory().getSelectedSlot() == 3, 10);
			boolean hurt = awaitHurt(Husk.class, 20);
			return check(switched && hurt,
				fmt("took the sword for the hit (%.1f damage)", damage(husk)),
				switched ? "took the sword, the hit never landed"
					: "stayed on the pickaxe");
		});
		
		hack("BowAimbot", () -> {
			give("hotbar.0", "bow");
			give("inventory.0", "arrow 64");
			awaitItems();
			String husk = husk(5, 7);
			awaitEntities(Husk.class, 1);
			on("BowAimbot");
			holdMouse(RIGHT);
			context.waitTicks(25);
			releaseMouse(RIGHT);
			boolean hurt = awaitHurt(Husk.class, 30);
			return check(hurt,
				fmt("aimed the bow 35 degrees over and hit (%.1f damage)",
					damage(husk)),
				"the arrow missed");
		});
		
		hack("ClickAura", () -> {
			sword();
			String husk = husk(2, 2);
			awaitEntities(Husk.class, 1);
			at(0, 0, 0, 180, 0);
			on("ClickAura");
			context.waitTicks(12);
			click(LEFT);
			boolean hurt = awaitHurt(Husk.class, 15);
			return check(hurt,
				fmt("a click facing away hit the husk (%.1f damage)",
					damage(husk)),
				"the click hit nothing");
		});
		
		// one test per mode: the jump modes are real jumps (no flags
		// expected), Packet fakes one (flags expected)
		hack("Criticals", () -> critTest("Full Jump"));
		hack("Criticals (Mini Jump)", () -> critTest("Mini Jump"));
		hack("Criticals (Packet)", () -> critTest("Packet"));
		
		hack("CrystalAura", () -> {
			give("hotbar.0", "end_crystal 8");
			awaitItems();
			fill(-1, -1, 2, 1, -1, 2, "obsidian");
			awaitBlock(0, -1, 2, Blocks.OBSIDIAN);
			String husk = husk(0, 4);
			awaitEntities(Husk.class, 1);
			set("CrystalAura", "Filter hostile mobs", false);
			on("CrystalAura");
			boolean hurt = awaitHurt(Husk.class, 80);
			messy();
			return check(hurt,
				fmt("blew a crystal up at the husk (%.1f damage)",
					damage(husk)),
				"the husk was never hurt");
		});
		
		hack("FightBot", () -> {
			sword();
			String husk = husk(0, 7);
			awaitEntities(Husk.class, 1);
			on("FightBot");
			boolean hurt = awaitHurt(Husk.class, 120);
			return check(hurt,
				fmt("walked 6 blocks to the husk and hit it (%.1f damage)",
					damage(husk)),
				"never got the husk");
		});
		
		hack("KillauraLegit", () -> auraTest("KillauraLegit", 1, 3, 80));
		
		hack("MaceDMG", () -> clickTest("MaceDMG", "mace", 2, 7,
			"mace hit with a faked fall (a plain hit does 6)"));
		
		hack("MultiAura", () -> {
			sword();
			String a = husk(2, 2);
			String b = husk(-2, 2);
			awaitEntities(Husk.class, 2);
			on("MultiAura");
			waitUntil(mc -> false, 30);
			double da = damage(a), db = damage(b);
			return check(da > 0 && db > 0,
				fmt("hit both husks (%.1f and %.1f damage)", da, db),
				fmt("husks lost %.1f and %.1f", da, db));
		});
		
		hack("Protect", () -> {
			sword();
			String villager = mob("villager", 0, 0, 2, "");
			String husk = husk(2, 4);
			awaitEntities(Husk.class, 1);
			awaitEntities(Villager.class, 1);
			on("Protect");
			boolean hurt = awaitHurt(Husk.class, 80);
			double villagerHealth = data(villager, "Health");
			return check(hurt && villagerHealth >= 20,
				fmt("guarded the villager, hit the husk (%.1f damage)",
					damage(husk)),
				hurt ? "hit the villager it was protecting"
					: "never went for the husk");
		});
		
		hack("Reach", () -> clickTest("Reach", "diamond_sword", 5, 1,
			"hit a husk 4.7 blocks away (vanilla reach: 3)"));
		
		hack("TP-Aura", () -> auraTest("TP-Aura", 3, 3, 60));
		
		hack("TriggerBot", () -> {
			sword();
			String husk = husk(0, 2);
			awaitEntities(Husk.class, 1);
			lookAt(body(0, 2));
			on("TriggerBot");
			boolean hurt = awaitHurt(Husk.class, 40);
			return check(hurt,
				fmt("hit the husk under the crosshair (%.1f damage)",
					damage(husk)),
				"never hit it");
		});
		
		hack("AutoArmor", () -> {
			give("inventory.0", "diamond_chestplate");
			give("inventory.1", "diamond_helmet");
			give("inventory.2", "diamond_boots");
			awaitItems();
			on("AutoArmor");
			boolean ok = waitUntil(
				mc -> !mc.player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
					&& !mc.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
					&& !mc.player.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
				80);
			return check(ok, "put on a helmet, chestplate and boots",
				"left armor in the inventory");
		});
		
		hack("AutoTotem", () -> {
			give("inventory.0", "totem_of_undying");
			awaitItems();
			set("AutoTotem", "Health", 0);
			on("AutoTotem");
			boolean ok = waitUntil(mc -> itemId(mc.player.getOffhandItem())
				.equals("totem_of_undying"), 40);
			return check(ok, "moved the totem to the offhand",
				"the offhand stayed empty");
		});
		
		hack("AutoPotion", () -> {
			give("hotbar.1",
				"splash_potion[potion_contents={potion:\"minecraft:healing\"}] 2");
			awaitItems();
			vulnerable();
			server.command("damage " + BOT + " 10");
			waitUntil(mc -> mc.player.getHealth() <= 10, 20);
			on("AutoPotion");
			boolean thrown =
				waitUntil(mc -> countItemOn(mc, "splash_potion") < 2, 40);
			boolean healed =
				thrown && waitUntil(mc -> mc.player.getHealth() > 10, 20);
			return check(healed,
				"threw a healing potion at 5 hearts and healed",
				thrown ? "threw it, didn't heal" : "never threw one");
		});
		
		hack("AutoSoup", () -> {
			give("hotbar.1", "mushroom_stew");
			awaitItems();
			hungry(16);
			vulnerable();
			server.command("damage " + BOT + " 10");
			waitUntil(mc -> mc.player.getHealth() <= 10, 20);
			on("AutoSoup");
			boolean ate = waitUntil(mc -> countItemOn(mc, "bowl") > 0, 60);
			return check(ate, "ate the soup at 5 hearts", "never ate the soup");
		});
		
		hack("AutoEat", () -> {
			give("hotbar.1", "cooked_beef 8");
			awaitItems();
			boolean hungry = hungry(12);
			on("AutoEat");
			boolean ate = hungry && waitUntil(
				mc -> mc.player.getFoodData().getFoodLevel() > 12, 80);
			return check(ate, "ate at 6 drumsticks",
				hungry ? "never ate" : "couldn't make the bot hungry");
		});
	}
	
	private static int countItemOn(net.minecraft.client.Minecraft mc, String id)
	{
		int n = 0;
		var inv = mc.player.getInventory();
		for(int i = 0; i < inv.getContainerSize(); i++)
			if(itemId(inv.getItem(i)).equals(id))
				n += inv.getItem(i).getCount();
		return n;
	}
	
	// ── Movement ──
	
	private void movement()
	{
		hack("AntiAFK", () -> {
			set("AntiAFK", "Wait time", 0);
			on("AntiAFK");
			Vec3 start = pos();
			boolean moved =
				waitUntil(mc -> horizontal(start, rel(mc)) > 0.5, 100);
			return check(moved, "walked around on its own", "never moved");
		});
		
		hack("AntiCactus", () -> {
			block(0, -1, 2, "sand");
			block(0, 0, 2, "cactus");
			awaitBlock(0, 0, 2, Blocks.CACTUS);
			on("AntiCactus");
			hold(W);
			double maxZ = maxOver(20, mc -> rel(mc).z);
			release(W);
			// the cactus' sides are 1/16 in: touching it puts you at 1.76
			return check(maxZ < 1.73,
				fmt("stopped a full block short of the cactus (z %.3f)", maxZ),
				fmt("walked into the cactus (z %.3f)", maxZ));
		});
		
		hack("AntiEntityPush", () -> {
			mob("cow", 0, 0, 0, "");
			awaitEntities(Cow.class, 1);
			on("AntiEntityPush");
			Vec3 start = pos();
			context.waitTicks(20);
			double moved = horizontal(start, pos());
			return check(moved < 0.05,
				fmt("stood still inside a cow (moved %.3f)", moved),
				fmt("got pushed %.2f blocks", moved));
		});
		
		hack("AntiHunger", () -> {
			on("AntiHunger");
			context.waitTicks(2);
			sent();
			walk(20);
			long spoofed = sent().stream()
				.filter(p -> p instanceof ServerboundMovePlayerPacket m
					&& !m.isOnGround())
				.count();
			return check(spoofed > 0,
				fmt("sent %d movement packets as \"not on the ground\" while"
					+ " walking", spoofed),
				"every packet said on the ground");
		});
		
		hack("AntiKnockback", () -> {
			String husk = husk(0, -1);
			awaitEntities(Husk.class, 1);
			on("AntiKnockback");
			Vec3 start = pos();
			server.command(
				"damage " + BOT + " 1 minecraft:mob_attack by " + husk);
			context.waitTicks(10);
			double moved = horizontal(start, pos());
			return check(moved < 0.1,
				fmt("took a hit without moving (%.3f)", moved),
				fmt("knocked back %.2f blocks", moved));
		});
		
		hack("AntiWaterPush", () -> {
			block(0, 0, 4, "water");
			boolean flowing = waitUntil(
				mc -> mc.level.getBlockState(p(0, 0, 0)).is(Blocks.WATER), 60);
			on("AntiWaterPush");
			Vec3 start = pos();
			context.waitTicks(20);
			double moved = horizontal(start, pos());
			return check(flowing && moved < 0.1,
				fmt("stood in flowing water without moving (%.3f)", moved),
				flowing ? fmt("pushed %.2f blocks", moved)
					: "the water never reached the bot");
		});
		
		hack("AutoSprint", () -> {
			on("AutoSprint");
			hold(W);
			double sprinted =
				maxOver(20, mc -> mc.player.isSprinting() ? 1.0 : 0.0);
			release(W);
			return check(sprinted > 0, "sprinted", "never sprinted");
		});
		
		hack("AutoSwim", () -> {
			pool(3);
			at(0, -3, 4, 0, 0);
			on("AutoSwim");
			hold(W);
			boolean swam = waitUntil(mc -> mc.player.isSwimming(), 30);
			release(W);
			return check(swam, "swam", "never started swimming");
		});
		
		hack("AutoWalk", () -> {
			on("AutoWalk");
			Vec3 start = pos();
			context.waitTicks(20);
			double moved = horizontal(start, pos());
			return check(moved > 2, fmt("walked %.1f blocks", moved),
				fmt("moved %.1f blocks", moved));
		});
		
		hack("Blink", () -> {
			on("Blink");
			context.waitTicks(2);
			long before = countSent(ServerboundMovePlayerPacket.class);
			walk(15);
			long during = countSent(ServerboundMovePlayerPacket.class) - before;
			Vec3 server = serverPos();
			Vec3 client = pos();
			off("Blink");
			// it lets the walk out on the next tick
			context.waitTicks(12);
			Vec3 after = serverPos();
			boolean held = during == 0 && server != null && horizontal(server,
				Vec3.atBottomCenterOf(net.minecraft.core.BlockPos.ZERO)) < 0.1;
			boolean released = after != null && horizontal(after, client) < 0.3;
			return check(held && released,
				fmt("held back %.1f blocks of walking, then sent it",
					horizontal(server == null ? client : server, client)),
				!held ? fmt("sent %d movement packets while on", during)
					: "the server never got the walk");
		});
		
		hack("BoatFly", () -> {
			String boat = mob("oak_boat", 0, 0, 2, "");
			context.waitTicks(2);
			server.command("ride " + BOT + " mount " + boat);
			boolean riding = waitUntil(mc -> mc.player.isPassenger(), 20);
			double y = pos().y;
			on("BoatFly");
			holdFor(SPACE, 20);
			double rose = pos().y - y;
			server.command("ride " + BOT + " dismount");
			return check(riding && rose > 1,
				fmt("the boat flew up %.1f blocks", rose),
				riding ? fmt("rose %.1f blocks", rose)
					: "couldn't get into the boat");
		});
		
		hack("BunnyHop", () -> {
			on("BunnyHop");
			hold(W);
			double top = maxOver(25, mc -> rel(mc).y);
			release(W);
			return check(top > 0.5, "jumped along while walking",
				"never jumped");
		});
		
		hack("CreativeFlight", () -> {
			on("CreativeFlight");
			// like creative mode: double-tap jump to fly, then hold it
			holdFor(SPACE, 2);
			context.waitTicks(2);
			hold(SPACE);
			double top = maxOver(15, mc -> rel(mc).y);
			boolean flying = client(mc -> mc.player.getAbilities().flying);
			release(SPACE);
			return check(top > 2,
				fmt("flew %.1f blocks up (flying: %s)", top, flying),
				fmt("rose %.1f blocks (flying: %s)", top, flying));
		});
		
		hack("Dolphin", () -> {
			pool(3);
			at(0, -3, 4, 0, 0);
			on("Dolphin");
			double top = maxOver(40, mc -> rel(mc).y);
			return check(top > -1.5,
				fmt("bobbed up from the bottom (to %.1f)", top),
				fmt("stayed down (%.1f)", top));
		});
		
		hack("ExtraElytra", () -> {
			give("armor.chest", "elytra");
			awaitItems();
			at(0, 15, 0, 0, 0);
			context.waitTicks(3);
			on("ExtraElytra");
			// held for 2 ticks: a tap inside one tick is never seen as held
			holdFor(SPACE, 2);
			boolean gliding = waitUntil(mc -> mc.player.isFallFlying(), 15);
			context.waitTicks(15);
			server.command(
				"item replace entity " + BOT + " armor.chest with air");
			return check(gliding, "started gliding with one jump",
				"never glided");
		});
		
		hack("FastLadder", () -> {
			fill(-1, 0, 2, 1, 9, 2, "stone");
			fill(0, 0, 1, 0, 9, 1, "ladder[facing=north]");
			awaitBlock(0, 9, 1, Blocks.LADDER);
			at(0, 0, 1, 0, 0);
			on("FastLadder");
			hold(W);
			double climbed = maxOver(20, mc -> rel(mc).y);
			release(W);
			// vanilla climbs about 2.4 blocks in a second
			return check(climbed > 3.5,
				fmt("climbed %.1f blocks in a second (vanilla ~2.4)", climbed),
				fmt("climbed %.1f blocks in a second", climbed));
		});
		
		hack("Fish", () -> {
			pool(3);
			at(0, -2, 4, 0, 0);
			on("Fish");
			double low = -maxOver(25, mc -> -rel(mc).y);
			return check(low > -2.4,
				fmt("hovered under water (lowest %.2f)", low),
				fmt("sank to %.2f", low));
		});
		
		hack("Follow", () -> {
			husk(0, 8);
			awaitEntities(Husk.class, 1);
			// it skips hostile mobs by default
			set("Follow", "Filter hostile mobs", false);
			on("Follow");
			boolean close = waitUntil(mc -> rel(mc).z > 5, 80);
			return check(close, "walked after the husk", "didn't follow it");
		});
		
		hack("Freecam", () -> {
			on("Freecam");
			Vec3 start = pos();
			Vec3 cam0 =
				client(mc -> mc.gameRenderer.getMainCamera().position());
			holdFor(W, 15);
			Vec3 cam1 =
				client(mc -> mc.gameRenderer.getMainCamera().position());
			double body = horizontal(start, pos());
			double cam = horizontal(cam0, cam1);
			return check(body < 0.05 && cam > 1,
				fmt("the camera flew %.1f blocks, the body stayed", cam),
				fmt("camera %.1f, body %.2f blocks", cam, body));
		});
		
		hack("Glide", () -> {
			at(0, 14, 0, 0, 0);
			on("Glide");
			context.waitTicks(2);
			double y = pos().y;
			context.waitTicks(20);
			double fell = y - pos().y;
			// a free fall goes about 6 blocks in a second
			return check(fell < 4,
				fmt("glided down %.1f blocks in a second", fell),
				fmt("fell %.1f blocks in a second", fell));
		});
		
		hack("HighJump", () -> {
			on("HighJump");
			// measured from the jump on: a setback a tick later puts the bot
			// back down before a measurement started after it
			hold(SPACE);
			double top = maxOver(30, mc -> rel(mc).y);
			release(SPACE);
			return check(top > 2, fmt("jumped %.1f blocks high", top),
				fmt("jumped %.1f blocks", top));
		});
		
		hack("Jesus", () -> {
			pool(2);
			on("Jesus");
			hold(W);
			List<Vec3> path = path(40);
			release(W);
			double low = path.stream().filter(v -> v.z > 3)
				.mapToDouble(v -> v.y).min().orElse(Double.NaN);
			return check(low > -0.5,
				fmt("walked over the water (lowest %.2f)", low),
				Double.isNaN(low) ? "never got onto the water"
					: fmt("sank to %.2f", low));
		});
		
		hack("Jetpack", () -> {
			on("Jetpack");
			hold(SPACE);
			double top = maxOver(15, mc -> rel(mc).y);
			release(SPACE);
			return check(top > 2, fmt("flew %.1f blocks up", top),
				fmt("rose %.1f blocks", top));
		});
		
		hack("NoClip", () -> {
			wall(2, 3);
			fill(-2, 0, 3, 2, 2, 3, "stone");
			on("NoClip");
			hold(W);
			boolean through = waitUntil(mc -> rel(mc).z > 4, 40);
			release(W);
			return check(through, "walked through a 2-block wall",
				"stopped at the wall");
		});
		
		hack("NoFall", () -> {
			vulnerable();
			at(0, 10, 0, 0, 0);
			on("NoFall");
			waitUntil(mc -> mc.player.onGround(), 60);
			context.waitTicks(4);
			double health = data("@a[name=" + BOT + ",limit=1]", "Health");
			return check(health >= 19.9,
				"fell 10 blocks without damage (vanilla: 7)",
				fmt("health %.1f after the fall", health));
		});
		
		hack("NoLevitation", () -> {
			on("NoLevitation");
			effect("levitation", 5, 3);
			double top = maxOver(25, mc -> rel(mc).y);
			return check(top < 0.5,
				fmt("stayed down with levitation IV (%.2f)", top),
				fmt("floated up %.1f blocks", top));
		});
		
		hack("NoSlowdown", () -> {
			give("hotbar.0", "bow");
			give("inventory.0", "arrow 64");
			awaitItems();
			on("NoSlowdown");
			holdMouse(RIGHT);
			context.waitTicks(2);
			double moved = walk(20);
			releaseMouse(RIGHT);
			// drawing a bow slows walking to ~0.9 blocks a second
			return check(moved > 2.5,
				fmt("walked %.1f blocks in a second drawing a bow", moved),
				fmt("walked %.1f blocks", moved));
		});
		
		hack("NoWeb", () -> {
			fill(-1, 0, 1, 1, 1, 9, "cobweb");
			awaitBlock(0, 0, 9, Blocks.COBWEB);
			on("NoWeb");
			double moved = walk(20);
			return check(moved > 2,
				fmt("walked %.1f blocks through cobwebs in a second "
					+ "(vanilla ~1)", moved),
				fmt("walked %.1f blocks", moved));
		});
		
		hack("Parkour", () -> {
			fill(-2, -3, 4, 2, -1, 4, "air");
			awaitBlock(0, -1, 4, Blocks.AIR);
			on("Parkour");
			hold(W);
			List<Vec3> path = path(30);
			release(W);
			double top = path.stream().mapToDouble(v -> v.y).max().orElse(0);
			Vec3 end = path.get(path.size() - 1);
			return check(top > 0.5 && end.z > 5 && end.y > -0.5,
				"jumped over the gap at the edge",
				end.y < -0.5 ? "fell into the gap" : "never jumped");
		});
		
		hack("SafeWalk", () -> {
			fill(-2, -3, 4, 2, -1, 4, "air");
			awaitBlock(0, -1, 4, Blocks.AIR);
			on("SafeWalk");
			hold(W);
			List<Vec3> path = path(30);
			release(W);
			double low = path.stream().mapToDouble(v -> v.y).min().orElse(0);
			double far = path.stream().mapToDouble(v -> v.z).max().orElse(0);
			return check(low > -0.1 && far > 3.5,
				fmt("stopped at the edge (z %.2f)", far),
				low <= -0.1 ? "fell into the gap" : "never got to the edge");
		});
		
		hack("Sneak", () -> {
			on("Sneak");
			hold(W);
			double sneaked =
				maxOver(10, mc -> mc.player.isShiftKeyDown() ? 1.0 : 0.0);
			release(W);
			boolean sent = sent().stream()
				.anyMatch(p -> p instanceof ServerboundPlayerInputPacket i
					&& i.input().shift());
			return check(sneaked > 0 || sent, "sneaked", "never sneaked");
		});
		
		hack("SnowShoe", () -> {
			fill(-2, -2, 2, 2, -1, 10, "powder_snow");
			awaitBlock(0, -1, 6, Blocks.POWDER_SNOW);
			on("SnowShoe");
			hold(W);
			List<Vec3> path = path(30);
			release(W);
			double low = path.stream().filter(v -> v.z > 3)
				.mapToDouble(v -> v.y).min().orElse(Double.NaN);
			return check(low > -0.3,
				fmt("walked on powder snow (lowest %.2f)", low),
				Double.isNaN(low) ? "never got onto the snow"
					: fmt("sank to %.2f", low));
		});
		
		hack("SpeedHack", () -> {
			on("SpeedHack");
			hold(GLFW.GLFW_KEY_LEFT_CONTROL);
			double moved = walk(20);
			release(GLFW.GLFW_KEY_LEFT_CONTROL);
			// sprinting: ~5.6 blocks a second
			return check(moved > 7,
				fmt("ran %.1f blocks in a second (sprinting: ~5.6)", moved),
				fmt("ran %.1f blocks", moved));
		});
		
		hack("Spider", () -> {
			wall(2, 9);
			on("Spider");
			hold(W);
			double top = maxOver(30, mc -> rel(mc).y);
			release(W);
			return check(top > 2, fmt("climbed the wall %.1f blocks", top),
				fmt("climbed %.1f blocks", top));
		});
		
		hack("Step", () -> {
			fill(-1, 0, 2, 1, 0, 7, "stone");
			awaitBlock(0, 0, 7, Blocks.STONE);
			on("Step");
			hold(W);
			boolean up =
				waitUntil(mc -> rel(mc).y > 0.9 && rel(mc).z > 2.5, 30);
			release(W);
			return check(up, "got up the step without the jump key",
				"stayed below the step");
		});
		
		hack("Timer", () -> {
			on("Timer");
			context.waitTicks(2);
			sent();
			holdFor(W, 30);
			List<Long> ticks = new ArrayList<>();
			for(PacketTrace.Sent s : sentTimed())
				if(s.packet() instanceof ServerboundClientTickEndPacket)
					ticks.add(s.nanos());
			double perSecond = ticks.size() < 2 ? 0 : (ticks.size() - 1) * 1e9
				/ (ticks.get(ticks.size() - 1) - ticks.get(0));
			return check(perSecond > 30,
				fmt("ticked %.0f times a second (vanilla 20)", perSecond),
				fmt("ticked %.0f times a second", perSecond));
		});
		
		hack("Derp", () -> {
			on("Derp");
			context.waitTicks(20);
			List<Float> pitches = sentRotations(true);
			return check(pitches.size() >= 10 && spread(pitches) > 60,
				fmt("sent %d random head turns", pitches.size()),
				fmt("%d rotations, spread %.0f", pitches.size(),
					spread(pitches)));
		});
		
		hack("HeadRoll", () -> {
			on("HeadRoll");
			context.waitTicks(25);
			List<Float> pitches = sentRotations(true);
			return check(spread(pitches) > 90,
				fmt("nodded %.0f degrees up and down", spread(pitches)),
				fmt("pitch moved %.0f degrees", spread(pitches)));
		});
		
		hack("Tired", () -> {
			on("Tired");
			context.waitTicks(25);
			List<Float> pitches = sentRotations(true);
			return check(spread(pitches) > 10,
				fmt("drooped its head %.0f degrees", spread(pitches)),
				fmt("pitch moved %.0f degrees", spread(pitches)));
		});
		
		hack("MileyCyrus", () -> {
			on("MileyCyrus");
			context.waitTicks(25);
			long toggles = sent().stream()
				.filter(p -> p instanceof ServerboundPlayerInputPacket).count();
			return check(toggles >= 4,
				fmt("twerked (%d sneak changes)", toggles),
				fmt("%d sneak changes", toggles));
		});
		
		hack("SkinDerp", () -> {
			Map<PlayerModelPart, Boolean> parts = client(mc -> {
				Map<PlayerModelPart, Boolean> m = new LinkedHashMap<>();
				for(PlayerModelPart part : PlayerModelPart.values())
					m.put(part, mc.options.isModelPartEnabled(part));
				return m;
			});
			on("SkinDerp");
			int local = 0;
			for(int i = 0; i < 25; i++)
			{
				context.waitTick();
				if(!client(mc -> {
					for(PlayerModelPart part : PlayerModelPart.values())
						if(mc.options.isModelPartEnabled(part) != parts
							.get(part))
							return false;
					return true;
				}))
					local++;
			}
			off("SkinDerp");
			context.runOnClient(mc -> parts
				.forEach((part, b) -> mc.options.setModelPart(part, b)));
			long sentToServer =
				countSent(ServerboundClientInformationPacket.class);
			// others only see skin parts the server was told about
			return check(sentToServer >= 2,
				fmt("toggled skin parts, told the server %d times",
					sentToServer),
				local > 0
					? "toggled skin parts on this screen only - the server"
						+ " was never told (Options.setModelPart doesn't send"
						+ " them in 26.1), so nobody else sees it"
					: "never toggled anything");
		});
		
		hack("PotionSaver", () -> {
			on("PotionSaver");
			context.waitTicks(2);
			long before = countSent(ServerboundMovePlayerPacket.class);
			context.waitTicks(30);
			long during = countSent(ServerboundMovePlayerPacket.class) - before;
			return check(during == 0,
				"sent no movement while standing still with effects",
				fmt("sent %d movement packets", during));
		});
		
		hack("RemoteView", () -> {
			husk(0, 5);
			awaitEntities(Husk.class, 1);
			cameraChanged();
			set("RemoteView", "Filter hostile mobs", false);
			on("RemoteView");
			boolean viewing = waitUntil(
				mc -> horizontal(rel(mc), new Vec3(0.5, 0, 5.5)) < 1, 20);
			context.waitTicks(20);
			return check(viewing, "watched through the husk's eyes",
				"the view stayed with the bot");
		});
		
		hack("InvWalk", () -> {
			on("InvWalk");
			input.pressKey(GLFW.GLFW_KEY_E);
			boolean open =
				waitUntil(mc -> mc.screen instanceof InventoryScreen, 10);
			double moved = walk(20);
			return check(open && moved > 2,
				fmt("walked %.1f blocks with the inventory open", moved),
				open ? fmt("moved %.1f blocks", moved)
					: "the inventory didn't open");
		});
		
		hack("PortalGUI", () -> {
			fill(-1, -1, 3, 2, 3, 3, "obsidian");
			fill(0, 0, 3, 1, 2, 3, "nether_portal[axis=x]");
			awaitBlock(0, 2, 3, Blocks.NETHER_PORTAL);
			at(0, 0, 3, 0, 0);
			on("PortalGUI");
			context.waitTicks(5);
			input.pressKey(GLFW.GLFW_KEY_E);
			context.waitTicks(20);
			boolean open = client(mc -> mc.screen instanceof InventoryScreen);
			at(0, 0, 0, 0, 0);
			return check(open, "kept the inventory open in a portal",
				"the portal closed the inventory");
		});
		
		hack("Instacart", () -> {
			give("hotbar.0", "bow[enchantments={flame:1}]");
			give("inventory.0", "arrow 64");
			give("inventory.1", "rail 8");
			// minecarts stack to 1 ("tnt_minecart 4" was refused)
			give("inventory.2", "tnt_minecart");
			awaitItems();
			lookAt(Vec3.atBottomCenterOf(p(0, 0, 5)));
			on("Instacart");
			holdMouse(RIGHT);
			boolean cart =
				waitUntil(mc -> countEntities(mc, MinecartTNT.class) > 0, 30);
			releaseMouse(RIGHT);
			messy();
			// the explosion belongs to this test
			waitUntil(mc -> countEntities(mc, MinecartTNT.class) == 0, 80);
			return check(cart, "fired and put a TNT minecart on a rail",
				"never placed the cart");
		});
	}
	
	// ── Blocks ──
	
	private void blocks()
	{
		hack("AirPlace", () -> {
			give("hotbar.0", "stone 16");
			awaitItems();
			on("AirPlace");
			context.waitTicks(2);
			click(RIGHT);
			context.waitTicks(5);
			int placed = count(Blocks.STONE, -3, 0, 1, 3, 4, 8);
			return check(placed > 0, "placed a block in mid-air",
				"placed nothing");
		});
		
		hack("AutoBuild", () -> buildTest("AutoBuild"));
		hack("InstaBuild", () -> buildTest("InstaBuild"));
		
		hack("TemplateTool", () -> {
			block(1, 0, 3, "stone");
			block(2, 0, 3, "stone");
			block(2, 1, 3, "stone");
			awaitBlock(2, 1, 3, Blocks.STONE);
			Path folder =
				client(mc -> ((FileSetting)setting("AutoBuild", "Template"))
					.getFolder());
			Path file = folder.resolve("ac_scan.json");
			try
			{
				Files.deleteIfExists(file);
			}catch(Exception e)
			{}
			on("TemplateTool");
			selectBlock(1, 0, 3);
			selectBlock(2, 1, 3);
			selectBlock(1, 0, 3);
			boolean naming = waitUntil(mc -> mc.screen != null, 60);
			if(naming)
			{
				// the Enter that confirmed the origin mustn't reach it
				context.waitTicks(3);
				input.typeChars("ac_scan");
				context.waitTick();
				input.pressKey(GLFW.GLFW_KEY_ENTER);
			}
			boolean saved = waitUntil(mc -> Files.exists(file), 60);
			return check(saved, "scanned 3 blocks into a template",
				naming ? "never saved the template" : "never got to naming it");
		});
		
		hack("AutoFarm", () -> {
			fill(-2, -1, 1, 2, -1, 3, "farmland");
			fill(-2, 0, 1, 2, 0, 3, "wheat[age=7]");
			awaitBlock(2, 0, 3, Blocks.WHEAT);
			give("hotbar.0", "wheat_seeds 16");
			awaitItems();
			on("AutoFarm");
			boolean farmed = waitUntil(
				mc -> mc.level.getBlockState(p(0, 0, 2)).is(Blocks.WHEAT)
					&& mc.level.getBlockState(p(0, 0, 2)).getValue(
						net.minecraft.world.level.block.CropBlock.AGE) == 0
					|| countItemOn(mc, "wheat") > 0,
				80);
			context.waitTicks(10);
			int replanted = countAge0Wheat();
			return check(farmed,
				fmt("harvested wheat (%d replanted)", replanted),
				"harvested nothing");
		});
		
		hack("BonemealAura", () -> {
			fill(-2, -1, 1, 2, -1, 3, "farmland");
			fill(-2, 0, 1, 2, 0, 3, "wheat[age=0]");
			awaitBlock(2, 0, 3, Blocks.WHEAT);
			give("hotbar.0", "bone_meal 16");
			awaitItems();
			on("BonemealAura");
			boolean used =
				waitUntil(mc -> countItemOn(mc, "bone_meal") < 16, 40);
			context.waitTicks(3);
			int grown = 15 - countAge0Wheat();
			return check(used && grown > 0,
				fmt("bone-mealed the wheat (%d plants grew)", grown),
				used ? "used bone meal, nothing grew" : "never used bone meal");
		});
		
		hack("Tillaura", () -> {
			give("hotbar.0", "diamond_hoe");
			awaitItems();
			on("Tillaura");
			boolean tilled =
				waitUntil(mc -> countIn(mc, Blocks.FARMLAND, 4) > 0, 30);
			context.waitTicks(5);
			messy();
			int n = client(mc -> countIn(mc, Blocks.FARMLAND, 4));
			return check(tilled, fmt("tilled %d blocks", n), "tilled nothing");
		});
		
		hack("BuildRandom", () -> {
			give("hotbar.0", "stone 64");
			awaitItems();
			on("BuildRandom");
			boolean placed =
				waitUntil(mc -> countIn(mc, Blocks.STONE, 6) > 0, 40);
			messy();
			int n = client(mc -> countIn(mc, Blocks.STONE, 6));
			return check(placed, fmt("placed %d blocks", n), "placed nothing");
		});
		
		hack("Excavator", () -> {
			pickaxe();
			fill(-1, 0, 2, 1, 1, 3, "stone");
			awaitBlock(1, 1, 3, Blocks.STONE);
			on("Excavator");
			selectExcavatorPos(-1, 0, 2);
			selectExcavatorPos(1, 1, 2);
			boolean gone = waitUntil(
				mc -> countBox(mc, Blocks.STONE, -1, 0, 2, 1, 1, 2) == 0, 200);
			int left = count(Blocks.STONE, -1, 0, 2, 1, 1, 2);
			return check(gone, "dug out the 3x2 area it was shown",
				fmt("%d of 6 blocks left", left));
		});
		
		hack("FastBreak", () -> {
			// default (Legit mode): no pause between blocks - a row of 3
			// dirt blocks at eye level with a shovel; vanilla waits 5 ticks
			// after each
			give("hotbar.0", "diamond_shovel");
			awaitItems();
			fill(0, 1, 2, 0, 1, 4, "dirt");
			awaitBlock(0, 1, 4, Blocks.DIRT);
			at(0, 0, 0, 0, 0);
			on("FastBreak");
			holdMouse(LEFT);
			int row = ticksUntil(
				mc -> countBox(mc, Blocks.DIRT, 0, 1, 2, 0, 1, 4) == 0, 60);
			releaseMouse(LEFT);
			
			// regular mode: faster breaking itself - an oak log by hand
			set("FastBreak", "Legit mode", false);
			select(1);
			block(0, 0, 2, "oak_log");
			awaitBlock(0, 0, 2, Blocks.OAK_LOG);
			lookAt(Vec3.atCenterOf(p(0, 0, 2)));
			holdMouse(LEFT);
			int log = ticksUntil(
				mc -> mc.level.getBlockState(p(0, 0, 2)).isAir(), 80);
			releaseMouse(LEFT);
			context.waitTicks(3);
			boolean kept = serverBlock(0, 0, 2, "air");
			return check(row < 14 && log < 50 && kept,
				fmt("3 dirt blocks in a row in %d ticks (vanilla ~18); a log "
					+ "by hand in %d ticks (vanilla 60)", row, log),
				fmt("row of 3: %d ticks, log: %d ticks%s", row, log,
					kept ? "" : ", the server kept the log"));
		});
		
		hack("FastPlace", () -> {
			give("hotbar.0", "stone 64");
			awaitItems();
			lookAt(Vec3.atBottomCenterOf(p(0, 0, 3)));
			on("FastPlace");
			context.waitTicks(2);
			sent();
			long before = countSent(ServerboundUseItemOnPacket.class);
			holdMouse(RIGHT);
			context.waitTicks(12);
			releaseMouse(RIGHT);
			long clicks = countSent(ServerboundUseItemOnPacket.class) - before;
			messy();
			// vanilla: every 4 ticks, so 3-4 in 12 ticks
			return check(clicks >= 7,
				fmt("%d block clicks in 12 ticks (vanilla 3-4)", clicks),
				fmt("%d block clicks in 12 ticks", clicks));
		});
		
		hack("HandNoClip", () -> {
			fill(-1, 0, 2, 1, 2, 2, "stone");
			block(0, 0, 3, "barrel");
			awaitBlock(0, 0, 3, Blocks.BARREL);
			lookAt(Vec3.atCenterOf(p(0, 0, 3)));
			on("HandNoClip");
			click(RIGHT);
			boolean open =
				waitUntil(mc -> mc.screen instanceof AbstractContainerScreen<?>
					&& !(mc.screen instanceof InventoryScreen), 15);
			return check(open, "opened a barrel through a wall",
				"couldn't reach the barrel");
		});
		
		hack("InstantBunker", () -> {
			give("hotbar.0", "stone 64");
			awaitItems();
			on("InstantBunker");
			boolean built =
				waitUntil(mc -> countIn(mc, Blocks.STONE, 3) >= 40, 100);
			messy();
			int n = client(mc -> countIn(mc, Blocks.STONE, 3));
			return check(built, fmt("built a bunker (%d blocks)", n),
				fmt("placed %d blocks", n));
		});
		
		hack("Kaboom", () -> {
			pickaxe();
			fill(-2, 0, 1, 2, 1, 3, "stone");
			awaitBlock(2, 1, 3, Blocks.STONE);
			on("Kaboom");
			context.waitTicks(10);
			messy();
			int left = count(Blocks.STONE, -2, 0, 1, 2, 1, 3);
			return check(left < 30,
				fmt("broke %d of 30 stone blocks at once", 30 - left),
				"the server kept every block (packet-spam breaking)");
		});
		
		hack("Liquids", () -> {
			pool(1);
			give("hotbar.0", "stone 16");
			awaitItems();
			lookAt(Vec3.atBottomCenterOf(p(0, 0, 3)));
			on("Liquids");
			context.waitTicks(2);
			click(RIGHT);
			context.waitTicks(5);
			int placed = count(Blocks.STONE, -3, -1, 2, 3, 0, 6);
			return check(placed > 0, "placed a block on water",
				"placed nothing");
		});
		
		for(String nuker : new String[]{"Nuker", "NukerLegit", "SpeedNuker"})
			hack(nuker, () -> {
				pickaxe();
				fill(-2, 0, 1, 2, 1, 3, "stone");
				awaitBlock(2, 1, 3, Blocks.STONE);
				set(nuker, "Flat mode", true);
				on(nuker);
				waitUntil(
					mc -> countBox(mc, Blocks.STONE, -2, 0, 1, 2, 1, 3) == 0,
					100);
				context.waitTicks(3);
				messy();
				int left = count(Blocks.STONE, -2, 0, 1, 2, 1, 3);
				return check(left < 30,
					fmt("broke %d of 30 stone blocks", 30 - left),
					"broke nothing");
			});
		
		hack("AutoMine", () -> {
			pickaxe();
			block(0, 0, 2, "stone");
			awaitBlock(0, 0, 2, Blocks.STONE);
			lookAt(Vec3.atCenterOf(p(0, 0, 2)));
			on("AutoMine");
			boolean mined =
				waitUntil(mc -> mc.level.getBlockState(p(0, 0, 2)).isAir(), 40);
			return check(mined, "mined the block it looked at",
				"never mined it");
		});
		
		hack("AutoTool", () -> {
			give("hotbar.0", "diamond_sword");
			give("hotbar.3", "diamond_pickaxe");
			awaitItems();
			block(0, 0, 2, "stone");
			awaitBlock(0, 0, 2, Blocks.STONE);
			lookAt(Vec3.atCenterOf(p(0, 0, 2)));
			on("AutoTool");
			holdMouse(LEFT);
			boolean switched = waitUntil(
				mc -> mc.player.getInventory().getSelectedSlot() == 3, 10);
			boolean mined =
				waitUntil(mc -> mc.level.getBlockState(p(0, 0, 2)).isAir(), 40);
			releaseMouse(LEFT);
			return check(switched && mined, "took the pickaxe and mined",
				switched ? "took the pickaxe, didn't mine"
					: "stayed on the sword");
		});
		
		hack("Tunneller", () -> {
			pickaxe();
			fill(-2, 0, 1, 2, 3, 10, "stone");
			awaitBlock(2, 3, 10, Blocks.STONE);
			on("Tunneller");
			boolean dug = waitUntil(
				mc -> countBox(mc, Blocks.STONE, -1, 0, 1, 1, 2, 1) == 0, 120);
			messy();
			int left = count(Blocks.STONE, -1, 0, 1, 1, 2, 1);
			return check(dug, "dug the first 3x3 of the tunnel",
				fmt("%d of its first 9 blocks left", left));
		});
		
		hack("TreeBot", () -> {
			give("hotbar.0", "diamond_axe");
			awaitItems();
			fill(-2, 3, 4, 2, 5, 8, "oak_leaves[persistent=true]");
			fill(0, 0, 6, 0, 4, 6, "oak_log");
			awaitBlock(0, 4, 6, Blocks.OAK_LOG);
			on("TreeBot");
			boolean chopped = waitUntil(
				mc -> countBox(mc, Blocks.OAK_LOG, 0, 0, 6, 0, 4, 6) == 0, 300);
			messy();
			int left = count(Blocks.OAK_LOG, 0, 0, 6, 0, 4, 6);
			return check(chopped, "chopped the whole tree",
				fmt("%d of 5 logs left", left));
		});
		
		hack("VeinMiner", () -> {
			pickaxe();
			fill(0, 0, 2, 1, 1, 2, "iron_ore");
			awaitBlock(1, 1, 2, Blocks.IRON_ORE);
			lookAt(Vec3.atCenterOf(p(0, 0, 2)));
			on("VeinMiner");
			holdMouse(LEFT);
			waitUntil(mc -> mc.level.getBlockState(p(0, 0, 2)).isAir(), 40);
			releaseMouse(LEFT);
			boolean all = waitUntil(
				mc -> countBox(mc, Blocks.IRON_ORE, 0, 0, 2, 1, 1, 2) == 0, 80);
			int left = count(Blocks.IRON_ORE, 0, 0, 2, 1, 1, 2);
			return check(all, "mined the whole 4-ore vein from one block",
				fmt("%d of 4 ores left", left));
		});
		
		hack("AutoSign", () -> {
			give("hotbar.0", "oak_sign 4");
			awaitItems();
			on("AutoSign");
			lookAt(Vec3.atBottomCenterOf(p(0, 0, 2)));
			click(RIGHT);
			boolean editing = waitUntil(
				mc -> mc.screen instanceof AbstractSignEditScreen, 20);
			if(editing)
			{
				input.typeChars("beast");
				input.pressKey(GLFW.GLFW_KEY_ESCAPE);
			}
			context.waitTicks(5);
			lookAt(Vec3.atBottomCenterOf(p(1, 0, 2)));
			click(RIGHT);
			context.waitTicks(10);
			boolean noScreen = client(mc -> mc.screen == null);
			String text = server.query(
				"data get block " + xyz(1, 0, 2) + " front_text.messages[0]",
				Pattern.compile("block data|no elements|not a block entity"));
			boolean written = text != null && text.contains("beast");
			return check(written && noScreen,
				"wrote the first sign's text on the second by itself",
				!editing ? "the first sign never opened"
					: fmt("second sign: %s", text));
		});
	}
	
	private String buildTest(String hack)
	{
		Path folder =
			client(mc -> ((FileSetting)setting(hack, "Template")).getFolder());
		try
		{
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("ac_test.json"),
				"{\"blocks\":[[0,0,0],[1,0,0],[2,0,0],[0,1,0],[1,1,0],[2,1,0]]}");
		}catch(Exception e)
		{
			throw new RuntimeException(e);
		}
		set(hack, "Template", "ac_test.json");
		give("hotbar.0", "stone 64");
		awaitItems();
		lookAt(Vec3.atBottomCenterOf(p(0, 0, 3)));
		on(hack);
		context.waitTicks(2);
		click(RIGHT);
		boolean built = waitUntil(mc -> countIn(mc, Blocks.STONE, 6) >= 6, 60);
		messy();
		int n = client(mc -> countIn(mc, Blocks.STONE, 6));
		return check(built, fmt("built the 6-block template (%d blocks)", n),
			fmt("%d of 6 blocks", n));
	}
	
	/** TemplateTool: look at a block, right-click it, press enter. */
	private void selectBlock(int dx, int dy, int dz)
	{
		lookAt(Vec3.atCenterOf(p(dx, dy, dz)));
		holdMouse(RIGHT);
		context.waitTicks(2);
		releaseMouse(RIGHT);
		holdFor(GLFW.GLFW_KEY_ENTER, 2);
		context.waitTicks(2);
	}
	
	private void selectExcavatorPos(int dx, int dy, int dz)
	{
		selectBlock(dx, dy, dz);
	}
	
	private int countAge0Wheat()
	{
		return client(mc -> {
			int n = 0;
			for(var pos : net.minecraft.core.BlockPos.betweenClosed(p(-2, 0, 1),
				p(2, 0, 3)))
			{
				var state = mc.level.getBlockState(pos);
				if(state.is(Blocks.WHEAT) && state.getValue(
					net.minecraft.world.level.block.CropBlock.AGE) == 0)
					n++;
			}
			return n;
		});
	}
	
	/** Blocks of a type within r blocks of home (y 0 up to r). */
	private int countIn(net.minecraft.client.Minecraft mc,
		net.minecraft.world.level.block.Block block, int r)
	{
		return countBox(mc, block, -r, -1, -r, r, r, r);
	}
	
	private int countBox(net.minecraft.client.Minecraft mc,
		net.minecraft.world.level.block.Block block, int x1, int y1, int z1,
		int x2, int y2, int z2)
	{
		int n = 0;
		for(var pos : net.minecraft.core.BlockPos.betweenClosed(p(x1, y1, z1),
			p(x2, y2, z2)))
			if(mc.level.getBlockState(pos).is(block))
				n++;
		return n;
	}
	
	// ── Items ──
	
	private void items()
	{
		hack("AutoDrop", () -> {
			give("inventory.0", "allium 4");
			awaitItems();
			on("AutoDrop");
			boolean dropped =
				waitUntil(mc -> countItemOn(mc, "allium") == 0, 30);
			return check(dropped, "dropped the alliums", "kept them");
		});
		
		hack("AutoSteal", () -> {
			block(0, 0, 2,
				"chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5}]}");
			awaitBlock(0, 0, 2, Blocks.CHEST);
			lookAt(Vec3.atCenterOf(p(0, 0, 2)));
			on("AutoSteal");
			click(RIGHT);
			boolean stolen =
				waitUntil(mc -> countItemOn(mc, "diamond") >= 5, 40);
			inventoryChanged();
			return check(stolen, "emptied the chest into the inventory",
				"took nothing");
		});
		
		hack("Restock", () -> {
			give("inventory.5", "minecart");
			awaitItems();
			on("Restock");
			boolean restocked =
				waitUntil(mc -> itemId(mc.player.getInventory().getItem(0))
					.equals("minecart"), 30);
			return check(restocked, "brought the minecart to slot 1",
				"left the hand empty");
		});
		
		hack("AutoSwitch", () -> {
			on("AutoSwitch");
			Set<Integer> slots = new HashSet<>();
			for(int i = 0; i < 10; i++)
			{
				slots.add(selectedSlot());
				context.waitTick();
			}
			return check(slots.size() >= 3,
				fmt("switched through %d slots in 10 ticks", slots.size()),
				fmt("%d slot(s) in 10 ticks", slots.size()));
		});
		
		hack("Throw", () -> {
			give("hotbar.0", "snowball 16");
			awaitItems();
			on("Throw");
			context.waitTicks(2);
			// it acts while the use key is down, so a tap shorter than a
			// tick does nothing
			holdMouse(RIGHT);
			context.waitTicks(2);
			releaseMouse(RIGHT);
			context.waitTicks(3);
			int thrown = 16 - countItem("snowball");
			return check(thrown > 1,
				fmt("threw %d snowballs with one click", thrown),
				fmt("threw %d", thrown));
		});
		
		hack("AutoFish", () -> {
			pool(2);
			give("hotbar.0", "fishing_rod[enchantments={lure:5}]");
			awaitItems();
			lookAt(Vec3.atBottomCenterOf(p(0, 0, 6)));
			on("AutoFish");
			boolean cast =
				waitUntil(mc -> mc.player.fishing instanceof FishingHook, 40);
			boolean caught = cast && waitUntil(mc -> {
				var inv = mc.player.getInventory();
				for(int i = 1; i < inv.getContainerSize(); i++)
					if(!inv.getItem(i).isEmpty())
						return true;
				return false;
			}, 240);
			inventoryChanged();
			return check(caught, "cast the rod and caught something",
				cast ? "cast, caught nothing in 12 s" : "never cast the rod");
		});
		
		hack("FeedAura", () -> {
			give("hotbar.0", "wheat 16");
			awaitItems();
			mob("cow", 1.5, 0, 2, "");
			mob("cow", -1.5, 0, 2, "");
			awaitEntities(Cow.class, 2);
			on("FeedAura");
			boolean fed = waitUntil(mc -> countItemOn(mc, "wheat") < 16, 40);
			return check(fed, "fed the cows", "fed nothing");
		});
		
		hack("AutoLibrarian", () -> {
			give("hotbar.0", "diamond_axe");
			give("hotbar.1", "lectern");
			awaitItems();
			mob("villager", 0, 0, 3, "VillagerData:{profession:"
				+ "\"minecraft:librarian\",level:1,type:\"minecraft:plains\"}");
			block(1, 0, 3, "lectern");
			awaitBlock(1, 0, 3, Blocks.LECTERN);
			awaitEntities(Villager.class, 1);
			on("AutoLibrarian");
			boolean working = waitUntil(
				mc -> mc.screen instanceof MerchantScreen
					|| !mc.level.getBlockState(p(1, 0, 3)).is(Blocks.LECTERN),
				100);
			messy();
			String error = chatError();
			return check(working,
				"opened the librarian's trades / broke its lectern",
				error != null ? error : "never started");
		});
		
		hack("ItemGenerator", () -> {
			creative();
			on("ItemGenerator");
			boolean items =
				waitUntil(mc -> countEntities(mc, ItemEntity.class) > 0, 20);
			off("ItemGenerator");
			return check(items, "dropped generated items", "no items appeared");
		});
		
		for(String[] potion : new String[][]{{"CrashChest", "chest"},
			{"KillPotion", "potion"}, {"TrollPotion", "potion"}})
			hack(potion[0], () -> {
				creative();
				inventoryChanged();
				on(potion[0]);
				boolean got = waitUntil(mc -> {
					var inv = mc.player.getInventory();
					for(int i = 0; i < inv.getContainerSize(); i++)
						if(itemId(inv.getItem(i)).contains(potion[1]))
							return true;
					return false;
				}, 20);
				return check(got, "made the item in the inventory",
					"nothing appeared");
			});
	}
	
	// ── Render (only changes what you see) ──
	
	private void render()
	{
		hack("BarrierESP", () -> {
			fill(-1, 0, 5, 1, 1, 5, "barrier");
			awaitBlock(1, 1, 5, Blocks.BARRIER);
			return screenChange("BarrierESP", 20, null);
		});
		
		hack("BaseFinder", () -> {
			fill(-2, 0, 6, 2, 2, 6, "oak_planks");
			block(0, 0, 5, "crafting_table");
			awaitBlock(0, 0, 5, Blocks.CRAFTING_TABLE);
			return screenChange("BaseFinder", 60, null);
		});
		
		hack("CameraDistance", () -> {
			thirdPerson();
			return screenChange("CameraDistance", 3, null);
		});
		
		hack("CameraNoClip", () -> {
			fill(-2, 0, -2, 2, 3, -2, "stone");
			awaitBlock(2, 3, -2, Blocks.STONE);
			thirdPerson();
			return screenChange("CameraNoClip", 3, null);
		});
		
		hack("CaveFinder", () -> {
			fill(-3, -3, 4, 3, -2, 8, "cave_air");
			awaitBlock(0, -2, 6, Blocks.CAVE_AIR);
			return screenChange("CaveFinder", 30, null);
		});
		
		hack("ChestESP", () -> {
			block(1, 0, 5, "chest");
			awaitBlock(1, 0, 5, Blocks.CHEST);
			return screenChange("ChestESP", 5, null);
		});
		
		hack("Fullbright", () -> {
			time("midnight");
			context.waitTicks(5);
			return screenChange("Fullbright", 25, null);
		});
		
		hack("HealthTags", () -> {
			on("HealthTags");
			context.waitTicks(5);
			return "on; it only changes other players' name tags - there are"
				+ " none here, so nothing to see";
		});
		
		hack("ItemESP", () -> {
			// a few, spread out: one box is a few dozen pixels
			for(int i = -2; i <= 2; i++)
			{
				Vec3 at = Vec3.atBottomCenterOf(p(i * 2, 0, 4));
				server.command(fmt(
					"summon item %.1f %.1f %.1f {Item:{id:"
						+ "\"minecraft:diamond\",count:1},PickupDelay:32767}",
					at.x, at.y, at.z));
			}
			awaitEntities(ItemEntity.class, 5);
			bareWorld = true;
			return screenChange("ItemESP", 5, null);
		});
		
		hack("LSD", () -> screenChange("LSD", 10, null));
		
		hack("MobESP", () -> {
			husk(1, 5);
			awaitEntities(Husk.class, 1);
			return screenChange("MobESP", 5, null);
		});
		
		hack("MobSpawnESP", () -> screenChange("MobSpawnESP", 30, null));
		
		hack("NameTags", () -> {
			// far away, where a normal name tag is too small to read
			mob("husk", 0, 0, 40, "CustomName:\"Bob\",CustomNameVisible:1b");
			awaitEntities(Husk.class, 1);
			return screenChange("NameTags", 3, null);
		});
		
		hack("NewChunks", () -> {
			// it marks chunks as they arrive, so: on, then somewhere the
			// server has never generated, looking down the new chunks
			// it draws at a set height (default y 0: 60 above this floor)
			set("NewChunks", "Altitude", home.getY() - 1);
			boolean enabled = on("NewChunks");
			at(240, 0, 0, 90, 20);
			waitUntil(
				mc -> mc.level.getChunkSource().getLoadedChunksCount() > 50,
				60);
			context.waitTicks(40);
			java.nio.file.Path onShot = shot("NewChunks_on");
			off("NewChunks");
			context.waitTicks(3);
			java.nio.file.Path off1 = shot("NewChunks_off1");
			context.waitTicks(3);
			java.nio.file.Path off2 = shot("NewChunks_off2");
			return verdictOf(enabled, off1, off2, onShot);
		});
		
		hack("NoBackground", () -> screenChange("NoBackground", 2, () -> {
			context.runOnClient(mc -> {
				if(!(mc.screen instanceof InventoryScreen))
					mc.setScreen(new InventoryScreen(mc.player));
			});
		}));
		
		hack("NoFireOverlay", () -> {
			String result = removesEffect("NoFireOverlay", () -> {
				block(0, 0, 0, "fire");
				waitUntil(mc -> mc.player.isOnFire(), 20);
			}, null);
			block(0, 0, 0, "water");
			context.waitTicks(3);
			return result;
		});
		
		hack("NoFog", () -> screenChange("NoFog", 3, null));
		
		hack("NoHurtcam", () -> {
			vulnerable();
			return removesEffect("NoHurtcam", () -> {},
				() -> server.command("damage " + BOT + " 1"));
		});
		
		hack("NoOverlay", () -> {
			pool(3);
			at(0, -3, 4, 0, 0);
			return screenChange("NoOverlay", 2, null);
		});
		
		hack("NoPumpkin", () -> {
			give("armor.head", "carved_pumpkin");
			awaitItems();
			return screenChange("NoPumpkin", 2, null);
		});
		
		hack("NoShieldOverlay", () -> {
			give("weapon.offhand", "shield");
			awaitItems();
			holdMouse(RIGHT);
			context.waitTicks(5);
			return screenChange("NoShieldOverlay", 2, null);
		});
		
		hack("NoVignette", () -> {
			// the vignette darkens with the dark; at noon outside it's gone
			time("midnight");
			context.waitTicks(5);
			return screenChange("NoVignette", 2, null);
		});
		
		hack("OpenWaterESP", () -> {
			pool(2);
			give("hotbar.0", "fishing_rod");
			awaitItems();
			lookAt(Vec3.atBottomCenterOf(p(0, 0, 6)));
			click(RIGHT);
			waitUntil(mc -> mc.player.fishing != null
				&& mc.player.fishing.isInWater(), 40);
			return screenChange("OpenWaterESP", 3, null);
		});
		
		hack("Overlay", () -> {
			block(0, 0, 2, "obsidian");
			awaitBlock(0, 0, 2, Blocks.OBSIDIAN);
			lookAt(Vec3.atCenterOf(p(0, 0, 2)));
			holdMouse(LEFT);
			context.waitTicks(5);
			bareWorld = true;
			return screenChange("Overlay", 2, null);
		});
		
		hack("PlayerESP", () -> {
			on("PlayerESP");
			context.waitTicks(5);
			return "on; it only shows other players - there are none here,"
				+ " so nothing to see";
		});
		
		hack("PortalESP", () -> {
			fill(-1, -1, 7, 2, 3, 7, "obsidian");
			fill(0, 0, 7, 1, 2, 7, "nether_portal[axis=x]");
			awaitBlock(0, 2, 7, Blocks.NETHER_PORTAL);
			return screenChange("PortalESP", 30, null);
		});
		
		hack("ProphuntESP", () -> {
			// Prophunt's fake blocks are invisible mobs
			mob("husk", 1, 0, 5,
				"active_effects:[{id:\"minecraft:invisibility\","
					+ "duration:-1,amplifier:0b,show_particles:0b}]");
			awaitEntities(Husk.class, 1);
			return screenChange("ProphuntESP", 5, null);
		});
		
		hack("Radar", () -> screenChange("Radar", 2, this::openClickGui));
		
		hack("RainbowUI",
			() -> screenChange("RainbowUI", 2, this::openClickGui));
		
		hack("Search", () -> {
			block(2, 0, 6, "diamond_ore");
			awaitBlock(2, 0, 6, Blocks.DIAMOND_ORE);
			return screenChange("Search", 30, null);
		});
		
		hack("Trajectories", () -> {
			give("hotbar.0", "bow");
			give("inventory.0", "arrow 64");
			awaitItems();
			holdMouse(RIGHT);
			context.waitTicks(25);
			bareWorld = true;
			return screenChange("Trajectories", 2, null);
		});
		
		hack("TrueSight", () -> {
			mob("husk", 0, 0, 4,
				"active_effects:[{id:\"minecraft:invisibility\","
					+ "duration:-1,amplifier:0b,show_particles:0b}]");
			awaitEntities(Husk.class, 1);
			return screenChange("TrueSight", 3, null);
		});
		
		hack("X-Ray", () -> {
			block(2, -2, 6, "diamond_ore");
			return screenChange("X-Ray", 15, null);
		});
		
		hack("AntiBlind", () -> {
			effect("blindness", 30, 0);
			context.waitTicks(25);
			return screenChange("AntiBlind", 3, null);
		});
		
		hack("AntiWobble", () -> {
			return removesEffect("AntiWobble", () -> {
				effect("nausea", 30, 0);
				context.waitTicks(60);
			}, null);
		});
		
		// last: the rain takes a while to go
		hack("NoWeather", () -> {
			weather("rain");
			waitUntil(mc -> mc.level.getRainLevel(1) > 0.9, 100);
			return screenChange("NoWeather", 3, null);
		});
	}
	
	private void openClickGui()
	{
		context.runOnClient(mc -> {
			if(!(mc.screen instanceof ClickGuiScreen))
				mc.setScreen(new ClickGuiScreen(WurstClient.INSTANCE.getGui()));
		});
	}
	
	// ── Chat, GUIs and other ──
	
	private void chatAndOther()
	{
		hack("FancyChat", () -> {
			on("FancyChat");
			input.pressKey(GLFW.GLFW_KEY_T);
			input.typeChars("hello there");
			input.pressKey(GLFW.GLFW_KEY_ENTER);
			context.waitTicks(3);
			String sentText =
				sent().stream().filter(ServerboundChatPacket.class::isInstance)
					.map(p -> ((ServerboundChatPacket)p).message()).findFirst()
					.orElse(null);
			return check(sentText != null && !sentText.equals("hello there"),
				"sent \"" + sentText + "\" for \"hello there\"",
				sentText == null ? "sent no chat message"
					: "sent the text unchanged");
		});
		
		hack("InfiniChat", () -> {
			on("InfiniChat");
			input.pressKey(GLFW.GLFW_KEY_T);
			input.typeChars("a".repeat(300));
			int length = client(mc -> {
				if(!(mc.screen instanceof ChatScreen chat))
					return -1;
				try
				{
					Field f = ChatScreen.class.getDeclaredField("input");
					f.setAccessible(true);
					return ((EditBox)f.get(chat)).getValue().length();
				}catch(ReflectiveOperationException e)
				{
					return -2;
				}
			});
			input.pressKey(GLFW.GLFW_KEY_ESCAPE);
			return check(length == 300,
				"took 300 characters in the chat box (vanilla 256)",
				fmt("the chat box held %d characters", length));
		});
		
		hack("MassTPA", () -> {
			on("MassTPA");
			context.waitTicks(10);
			long tpas = sent().stream()
				.filter(p -> p instanceof ServerboundChatCommandPacket c
					&& c.command().startsWith("tpa"))
				.count();
			boolean alone = chatContains("Couldn't find any players");
			return tpas > 0 ? fmt("sent %d /tpa", tpas)
				: alone ? "nobody else online to send /tpa to (correct)"
					: FAIL + "sent no /tpa";
		});
		
		hack("NameProtect", () -> {
			// it swaps the name where text is drawn, so: the chat line on
			// screen, with and without it
			server.command("tellraw " + BOT + " \"" + BOT + " was here\"");
			boolean got = awaitChat("was here", 20);
			String screen = screenChange("NameProtect", 2, null);
			on("NameProtect");
			boolean renamed =
				client(mc -> !WurstClient.INSTANCE.getHax().nameProtectHack
					.protect(BOT + " was here").contains(BOT));
			return !got ? FAIL + "the message never came"
				: !screen.startsWith("visible") ? screen
					: check(renamed,
						"hid the bot's name in chat (" + screen + ")",
						"the text it draws still has the name");
		});
		
		hack("AntiSpam", () -> {
			on("AntiSpam");
			for(int i = 0; i < 3; i++)
				server.command("tellraw " + BOT + " \"spam spam\"");
			boolean merged = awaitChat("[x3]", 20);
			return check(merged, "merged 3 copies into one \"[x3]\" line",
				"didn't merge them");
		});
		
		hack("ForceOP", () -> {
			on("ForceOP");
			// what its window does when you press Start, without the window
			context.runOnClient(mc -> {
				try
				{
					Method m = ForceOpHack.class
						.getDeclaredMethod("messageFromDialog", String.class);
					m.setAccessible(true);
					m.invoke(WurstClient.INSTANCE.getHax().forceOpHack,
						"start 300 false");
				}catch(ReflectiveOperationException e)
				{
					throw new RuntimeException(e);
				}
			});
			context.waitTicks(30);
			long logins = sent().stream()
				.filter(p -> p instanceof ServerboundChatCommandPacket c
					&& c.command().startsWith("login"))
				.count();
			return check(logins > 0, fmt("tried %d /login passwords", logins),
				"sent no /login");
		});
		
		hack("Panic", () -> {
			on("Fullbright");
			on("Sneak");
			on("Panic");
			context.waitTicks(2);
			boolean allOff = !isOn("Fullbright") && !isOn("Sneak");
			return check(allOff, "turned every hack off", "left hacks on");
		});
		
		hack("TooManyHax", () -> {
			context.runOnClient(
				mc -> WurstClient.INSTANCE.getHax().tooManyHaxHack.setBlocked(
					WurstClient.INSTANCE.getHax().fullbrightHack, true));
			try
			{
				on("TooManyHax");
				boolean blocked = !on("Fullbright");
				return check(blocked, "kept a blocked hack from turning on",
					"let the blocked hack on");
				
			}finally
			{
				context.runOnClient(
					mc -> WurstClient.INSTANCE.getHax().tooManyHaxHack
						.setBlocked(
							WurstClient.INSTANCE.getHax().fullbrightHack,
							false));
			}
		});
		
		hack("ClickGUI", () -> {
			on("ClickGUI");
			boolean open =
				waitUntil(mc -> mc.screen instanceof ClickGuiScreen, 10);
			return check(open, "opened the ClickGUI", "nothing opened");
		});
		
		hack("Navigator", () -> {
			on("Navigator");
			boolean open =
				waitUntil(mc -> mc.screen instanceof NavigatorMainScreen, 10);
			return check(open, "opened the Navigator", "nothing opened");
		});
		
		hack("AutoRespawn", () -> {
			on("AutoRespawn");
			effectsChanged();
			inventoryChanged();
			server.command("kill " + BOT);
			boolean died = waitUntil(mc -> mc.player.isDeadOrDying()
				|| mc.screen instanceof DeathScreen, 20);
			boolean back = died && waitUntil(mc -> !mc.player.isDeadOrDying()
				&& !(mc.screen instanceof DeathScreen), 40);
			return check(back, "respawned right after dying",
				died ? "stayed on the death screen" : "didn't die");
		});
	}
	
	// ── Commands, typed into chat like a player ──
	
	private String typed(String command, String expected)
	{
		type(command);
		boolean shown = awaitChat(expected, 20);
		String error = chatError();
		return check(shown && error == null, "showed \"" + expected + "\"",
			error != null ? error : "no \"" + expected + "\" in chat");
	}
	
	private void commands()
	{
		cmd("addalt", () -> {
			type("addalt AcTestAlt");
			context.waitTicks(2);
			boolean added = client(mc -> {
				var alts = WurstClient.INSTANCE.getAltManager();
				boolean has = alts.contains("AcTestAlt");
				alts.getList().stream()
					.filter(a -> a.getName().equals("AcTestAlt")).findFirst()
					.ifPresent(alts::remove);
				return has;
			});
			return check(added, "added the alt (then removed it again)",
				"no alt added");
		});
		
		cmd("annoy", () -> {
			String on = typed("annoy AcSomeone", "Now annoying AcSomeone");
			type("annoy");
			return on;
		});
		
		cmd("author", () -> {
			creative();
			give("hotbar.0", "written_book[written_book_content={title:\"t\","
				+ "author:\"a\",pages:[]}]");
			awaitItems();
			type("author AcAuthor");
			boolean changed = waitUntil(mc -> {
				var book = mc.player.getMainHandItem()
					.get(DataComponents.WRITTEN_BOOK_CONTENT);
				return book != null && book.author().equals("AcAuthor");
			}, 20);
			return onServerToo(changed, 0, "AcAuthor",
				"changed the book's author", "the author stayed");
		});
		
		cmd("bind", () -> {
			type("bind F12 .say bound");
			context.waitTicks(2);
			boolean bound = client(mc -> WurstClient.INSTANCE.getKeybinds()
				.getCommands("key.keyboard.f12") != null);
			wurst("unbind F12");
			return check(bound, "bound F12", "no keybind added");
		});
		
		cmd("binds", () -> typed("binds list", "Keybind list"));
		
		cmd("blink", () -> {
			type("blink");
			boolean on = waitUntil(mc -> isBlinkOn(), 5);
			walk(10);
			type("blink");
			boolean off = waitUntil(mc -> !isBlinkOn(), 5);
			return check(on && off, "turned Blink on, then off",
				"Blink didn't toggle");
		});
		
		cmd("blocklist", () -> {
			type("blocklist X-Ray Ores list");
			context.waitTicks(3);
			String error = chatError();
			return check(error == null && chat().size() > 1,
				"listed X-Ray's ores", error != null ? error : "no list");
		});
		
		cmd("clear", () -> {
			server.command("tellraw " + BOT + " \"something to clear\"");
			awaitChat("something to clear", 20);
			type("clear");
			context.waitTicks(2);
			boolean cleared = !chatContains("something to clear");
			return check(cleared, "cleared the chat", "the chat stayed");
		});
		
		cmd("copyitem", () -> {
			creative();
			sword();
			type("copyitem " + BOT + " hand");
			boolean copied =
				waitUntil(mc -> countItemOn(mc, "diamond_sword") >= 2, 20);
			return onServerToo(copied, -1, "Slot: 1b", "copied the held sword",
				"nothing copied");
		});
		
		cmd("damage", () -> {
			vulnerable();
			type("damage 4");
			context.waitTicks(10);
			double health = data("@a[name=" + BOT + ",limit=1]", "Health");
			return check(health <= 18.1,
				fmt("took 2 hearts of fall damage (health %.1f)", health),
				fmt("health %.1f", health));
		});
		
		cmd("dig", () -> {
			pickaxe();
			fill(-3, 0, 1, 3, 2, 6, "stone");
			awaitBlock(3, 2, 6, Blocks.STONE);
			type("dig 2 2 2");
			// it digs from the front-left-top corner, floor included
			waitUntil(mc -> countBox(mc, Blocks.AIR, -3, -3, 1, 3, 2, 6) >= 8,
				200);
			type("dig stop");
			messy();
			int dug = count(Blocks.AIR, -3, -3, 1, 3, 2, 6);
			return check(dug >= 8, fmt("dug out the 2x2x2 (%d blocks)", dug),
				fmt("dug %d of 8", dug));
		});
		
		cmd("drop", () -> {
			give("hotbar.0", "stone 16");
			give("hotbar.1", "dirt 16");
			awaitItems();
			type("drop");
			boolean empty = waitUntil(mc -> countItemOn(mc, "stone") == 0
				&& countItemOn(mc, "dirt") == 0, 20);
			return check(empty, "dropped everything", "kept items");
		});
		
		cmd("enabledhax",
			() -> commandWithoutError("enabledhax list-profiles"));
		
		cmd("enchant", () -> {
			creative();
			sword();
			type("enchant");
			boolean enchanted =
				waitUntil(mc -> mc.player.getMainHandItem().isEnchanted(), 20);
			return onServerToo(enchanted, 0, "enchantments",
				"enchanted the sword", "the sword stayed plain");
		});
		
		cmd("excavate", () -> {
			pickaxe();
			fill(-1, 0, 2, 1, 1, 3, "stone");
			awaitBlock(1, 1, 3, Blocks.STONE);
			type("excavate " + xyz(-1, 0, 2) + " " + xyz(1, 1, 3));
			boolean gone = waitUntil(
				mc -> countBox(mc, Blocks.STONE, -1, 0, 2, 1, 1, 3) == 0, 200);
			messy();
			return check(gone, "dug out the 12 blocks",
				fmt("%d left", count(Blocks.STONE, -1, 0, 2, 1, 1, 3)));
		});
		
		cmd("features", () -> typed("features", "All features:"));
		
		cmd("follow", () -> {
			husk(0, 8);
			awaitEntities(Husk.class, 1);
			set("Follow", "Filter hostile mobs", false);
			type("follow Husk");
			boolean close = waitUntil(mc -> rel(mc).z > 5, 80);
			return check(close, "followed the husk", "didn't follow it");
		});
		
		cmd("friends", () -> {
			String added = typed("friends add AcFriend", "Added friend");
			type("friends remove AcFriend");
			return added;
		});
		
		cmd("getpos", () -> typed("getpos", "Position:"));
		
		cmd("give", () -> {
			creative();
			inventoryChanged();
			type("give diamond 3");
			boolean got = waitUntil(mc -> countItemOn(mc, "diamond") >= 3, 20);
			return onServerToo(got, -1, "minecraft:diamond\"",
				"gave 3 diamonds", "nothing given");
		});
		
		cmd("gm", () -> {
			type("gm creative");
			context.waitTicks(3);
			boolean sentCmd = sent().stream()
				.anyMatch(p -> p instanceof ServerboundChatCommandPacket c
					&& c.command().equals("gamemode creative"));
			return check(sentCmd,
				"sent /gamemode creative (refused: the bot isn't op)",
				"sent no /gamemode");
		});
		
		cmd("goto", () -> {
			type("goto " + xyz(0, 0, 6));
			boolean there = waitUntil(mc -> rel(mc).z > 5.5, 200);
			type("goto");
			return check(there, "walked to the spot", "never got there");
		});
		
		cmd("help", () -> typed("help", "Command list"));
		
		cmd("invsee", () -> {
			// it only looks at other players (RemotePlayer) - there are none
			// here, so what it can show is its answer for a missing one
			type("invsee " + BOT);
			boolean answered = awaitChat("Player not found", 20);
			return answered
				? "answered \"Player not found\" (it only shows other"
					+ " players' inventories - none here)"
				: FAIL + "no answer";
		});
		
		cmd("ip", () -> typed("ip", "IP:"));
		
		cmd("itemlist",
			() -> commandWithoutError("itemlist AutoDrop Items list"));
		
		cmd("jump", () -> {
			type("jump");
			double top = maxOver(15, mc -> rel(mc).y);
			return check(top > 0.5, "jumped", "didn't jump");
		});
		
		cmd("modify", () -> {
			creative();
			sword();
			type("modify set custom_name {\"text\":\"AcName\"}");
			boolean named = waitUntil(mc -> mc.player.getMainHandItem()
				.getHoverName().getString().equals("AcName"), 20);
			return onServerToo(named, 0, "AcName",
				"renamed the sword through its components",
				"the item didn't change");
		});
		
		cmd("path", () -> {
			type("path " + xyz(0, 0, 8));
			context.waitTicks(20);
			String error = chatError();
			type("path");
			return check(error == null, "found and showed a path", error);
		});
		
		cmd("potion", () -> {
			creative();
			give("hotbar.0",
				"potion[potion_contents={potion:\"minecraft:swiftness\"}]");
			awaitItems();
			type("potion add strength 1 60");
			boolean changed = waitUntil(mc -> {
				var contents = mc.player.getMainHandItem()
					.get(DataComponents.POTION_CONTENTS);
				return contents != null && !contents.customEffects().isEmpty();
			}, 20);
			return onServerToo(changed, 0, "strength",
				"added strength to the potion", "the potion didn't change");
		});
		
		cmd("protect", () -> {
			sword();
			mob("villager", 0, 0, 2, "");
			String husk = husk(2, 4);
			awaitEntities(Villager.class, 1);
			awaitEntities(Husk.class, 1);
			type("protect Villager");
			boolean hurt = awaitHurt(Husk.class, 80);
			return check(hurt,
				fmt("protected the villager from the husk (%.1f damage)",
					damage(husk)),
				"never attacked the husk");
		});
		
		cmd("rename", () -> {
			creative();
			sword();
			type("rename AcRenamed");
			boolean named = waitUntil(mc -> mc.player.getMainHandItem()
				.getHoverName().getString().equals("AcRenamed"), 20);
			return onServerToo(named, 0, "AcRenamed", "renamed the sword",
				"the name stayed");
		});
		
		cmd("repair", () -> {
			creative();
			give("hotbar.0", "diamond_sword[damage=100]");
			awaitItems();
			type("repair");
			boolean repaired = waitUntil(
				mc -> mc.player.getMainHandItem().getDamageValue() == 0, 20);
			if(!repaired)
				return FAIL + "still damaged";
			String server = serverSlot(0);
			return check(!server.contains("minecraft:damage"),
				"repaired the sword",
				"repaired on this screen only - the server's sword is still"
					+ " damaged");
		});
		
		cmd("rv", () -> {
			husk(0, 5);
			awaitEntities(Husk.class, 1);
			cameraChanged();
			set("RemoteView", "Filter hostile mobs", false);
			type("rv Husk");
			boolean viewing = waitUntil(
				mc -> horizontal(rel(mc), new Vec3(0.5, 0, 5.5)) < 1, 20);
			return check(viewing, "watched through the husk",
				"the view stayed with the bot");
		});
		
		cmd("say", () -> {
			type("say hello from beast");
			context.waitTicks(3);
			boolean said = sent().stream()
				.anyMatch(p -> p instanceof ServerboundChatPacket c
					&& c.message().equals("hello from beast"));
			return check(said, "sent the chat message", "sent nothing");
		});
		
		cmd("setblock", () -> settingCmd("Nuker", "ID",
			"setblock Nuker ID dirt", "minecraft:dirt"));
		cmd("setcheckbox", () -> settingCmd("MaceAssist", "Mace trigger bot",
			"setcheckbox MaceAssist Mace_trigger_bot toggle", null));
		cmd("setcolor", () -> settingCmd("AirPlace", "Guide color",
			"setcolor AirPlace Guide_color #00FF00", "00FF00"));
		cmd("setmode", () -> settingCmd("Sneak", "Mode",
			"setmode Sneak Mode Packet", "Packet"));
		cmd("setslider",
			() -> settingCmd("Timer", "Speed", "setslider Timer Speed 3", "3"));
		
		cmd("settings", () -> commandWithoutError("settings list-profiles"));
		
		cmd("sv", () -> typed("sv", "Server version:"));
		
		cmd("t", () -> {
			type("t Fullbright on");
			boolean on = waitUntil(mc -> isHackOn("Fullbright"), 5);
			type("t Fullbright off");
			boolean off = waitUntil(mc -> !isHackOn("Fullbright"), 5);
			String error = chatError();
			return check(on && off, "turned Fullbright on, then off",
				fmt("on: %s, off: %s%s", on, off,
					error == null ? "" : " - " + error));
		});
		
		cmd("taco", () -> {
			type("taco");
			context.waitTicks(10);
			String error = chatError();
			type("taco");
			return check(error == null, "the taco danced", error);
		});
		
		cmd("toomanyhax", () -> {
			type("toomanyhax block Fullbright");
			boolean blocked = waitUntil(
				mc -> WurstClient.INSTANCE.getHax().tooManyHaxHack
					.isBlocked(WurstClient.INSTANCE.getHax().fullbrightHack),
				5);
			type("toomanyhax unblock Fullbright");
			return check(blocked, "blocked Fullbright (then unblocked it)",
				"nothing blocked");
		});
		
		cmd("tp", () -> {
			type("tp " + xyz(0, 0, 8));
			boolean there = waitUntil(mc -> rel(mc).z > 7.5, 10);
			context.waitTicks(5);
			Vec3 server = serverPos();
			boolean kept = server != null && server.z > 7.5;
			return check(there && kept, "teleported 8 blocks",
				there ? "the server set it back" : "didn't move");
		});
		
		cmd("unbind", () -> {
			context.runOnClient(mc -> WurstClient.INSTANCE.getKeybinds()
				.add("key.keyboard.f11", "say unbound"));
			type("unbind F11");
			context.waitTicks(2);
			boolean gone = client(mc -> WurstClient.INSTANCE.getKeybinds()
				.getCommands("key.keyboard.f11") == null);
			return check(gone, "removed the F11 keybind", "it stayed");
		});
		
		cmd("vclip", () -> {
			fill(-1, 2, -1, 1, 2, 1, "stone");
			awaitBlock(0, 2, 0, Blocks.STONE);
			type("vclip 3");
			boolean up = waitUntil(mc -> rel(mc).y > 2.9, 10);
			return check(up, "clipped up through the ceiling",
				"stayed below it");
		});
		
		cmd("viewcomp", () -> {
			sword();
			return commandWithoutError("viewcomp");
		});
		
		cmd("viewnbt", () -> {
			sword();
			return commandWithoutError("viewnbt");
		});
		
		cmd("xray", () -> commandWithoutError("xray list"));
	}
	
	private boolean isBlinkOn()
	{
		return WurstClient.INSTANCE.getHax().blinkHack.isEnabled();
	}
	
	private static boolean isHackOn(String name)
	{
		return WurstClient.INSTANCE.getHax().getHackByName(name).isEnabled();
	}
	
	private String commandWithoutError(String command)
	{
		type(command);
		context.waitTicks(3);
		String error = chatError();
		int lines = chat().size();
		return check(error == null && lines > 0,
			fmt("answered with %d line(s)", lines),
			error != null ? error : "no answer");
	}
	
	/** A setting command; expected null = any change. */
	private String settingCmd(String feature, String setting, String command,
		String expected)
	{
		remember(feature, setting);
		String before = settingValue(feature, setting);
		type(command);
		context.waitTicks(2);
		String after = settingValue(feature, setting);
		boolean ok = !after.equals(before) && (expected == null
			|| after.toLowerCase().contains(expected.toLowerCase()));
		return check(ok, "set " + feature + " " + setting + " to " + after,
			"the setting stayed " + after);
	}
	
	// ── Last: they take the bot off the server ──
	
	private void lastOnes()
	{
		hack("AutoLeave", () -> {
			on("AutoLeave");
			vulnerable();
			server.command("damage " + BOT + " 13");
			boolean left = waitUntilAnywhere(mc -> mc.level == null, 40);
			return check(left, "left the server at 3.5 hearts",
				"stayed on the server");
		});
		
		hack("AutoReconnect", () -> {
			set("AutoReconnect", "Wait time", 0.5);
			on("AutoReconnect");
			server.command("kick " + BOT);
			boolean kicked = waitUntilAnywhere(mc -> mc.level == null, 40);
			boolean back = kicked && waitUntilAnywhere(mc -> mc.level != null
				&& mc.player != null && mc.screen == null, 400);
			return check(back, "reconnected on its own after a kick",
				kicked ? "stayed disconnected" : "the kick never came");
		});
		
		cmd("leave", () -> {
			type("leave");
			boolean left = waitUntilAnywhere(mc -> mc.level == null, 40);
			return check(left, "left the server", "stayed");
		});
	}
}
