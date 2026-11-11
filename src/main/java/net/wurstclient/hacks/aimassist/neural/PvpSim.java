/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.SplittableRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * A 1v1 sword fight on flat ground, without the game: ~400,000 ticks a
 * second per CPU core (the game runs 20), so the neural network can play
 * millions of fights in minutes.
 *
 * <p>
 * The rules are vanilla 26.1.2's, read from the decompiled classes
 * ({@code Player.attack}, {@code LivingEntity.knockback/hurtServer},
 * {@code LocalPlayer.modifyInput/aiStep}):
 * <ul>
 * <li>diamond sword: 7 damage x (0.2 + 0.8 x strength^2), strength =
 * (ticks since the last attack + 0.5) / 12.5;</li>
 * <li>crit: strength > 0.9, falling, not on the ground, not sprinting -
 * x1.5;</li>
 * <li>knockback 0.4 away from the attacker (velocity halved first; on the
 * ground y = min(0.4, vy/2 + 0.4)), plus 0.5 along the attacker's look for
 * a sprint hit (strength > 0.9 while the server has you sprinting), which
 * also slows the attacker to 60% and ends their sprint;</li>
 * <li>hit immunity: for 10 ticks after a hit only a bigger hit counts, for
 * the difference, with no knockback;</li>
 * <li>armor: damage x (1 - clamp(armor - damage / (2 + toughness / 4),
 * armor / 5, 20) / 25);</li>
 * <li>movement: keys x 0.98 (sneak x 0.3, diagonals rescaled to length 1),
 * ground speed 0.1 (sprint 0.13) with friction 0.546, air 0.02 (sprint
 * 0.026) with drag 0.91, gravity 0.08 and vertical drag 0.98, jump 0.42
 * plus 0.2 forward when sprinting;</li>
 * <li>reach 3.0 from the eyes, along the look; players push each other
 * apart when they overlap;</li>
 * <li>gear, each fighter's own ({@link Gear}; user, 2026-10-25/30): by
 * default diamond armor with Protection IV (damage x (1 - 16 / 25) after the
 * armor - fire and pearls skip the armor but not the Protection), a diamond
 * sword with Sharpness V (+3 x charge), no Knockback (Knockback I in 10% of
 * the trainer's fights: knockback 0.5 more, +0.5 for a sprint hit, and the
 * attacker slows to 60% and stops sprinting; nobody fights with II) and Fire
 * Aspect II (8 s on fire: 1 damage a second, which also restarts the hurt
 * cooldown - none with Fire Resistance), a diamond axe for shields; in game
 * the learner's fights use what both players actually wear;</li>
 * <li>the kit: 64 cobwebs, a shield, 9 splash Strength II (1:30, +6
 * damage), 3 splash Speed (8:00, +20% on the ground), 3 splash Fire
 * Resistance (8:00), 128 golden apples, 16 ender pearls, 64 wind charges, 2
 * water buckets and 32 bottles o' enchanting (nothing here wears out, so
 * they never come up). A raised shield blocks frontal melee hits once it
 * has been up 5 ticks (no damage, 0.4 knockback for the blocker, 0.5 back
 * for the attacker); an axe hit on it disables it for 5 s, at any charge -
 * AimAssist's Shield breaker switches to the axe by itself. Cobwebs placed
 * at the other one's feet (4.5 reach, the camera turns a tick first) scale
 * movement by (0.25, 0.05, 0.25), zero the velocity and the fall distance
 * every tick, and take 8 ticks to break with a sword - or a water bucket
 * placed at your feet washes them away 5 ticks later (FlowingFluid) and is
 * picked back up. Splash potions (thrown at your feet, all three a tick
 * apart) give x (1 - distance / 4) of their time to everyone within 4
 * blocks of where they land. A golden apple takes 32 ticks to eat and gives
 * 4 absorption and Regeneration II (1 HP per 25 ticks for 5 s). An ender
 * pearl (1.5 blocks a tick, gravity 0.03, drag 0.99, 1 s cooldown) takes you
 * where it lands, for 5 damage; a wind charge (1.5 a tick, straight, 0.5 s
 * cooldown) bursts where it hits (1 damage on a direct hit) and pushes
 * everyone within 2.4 blocks away from it by 1.22 x (1 - distance / 2.4)
 * - at your own feet a launch, with no fall damage. Using an item (shield
 * up, eating) means no attacks, movement x0.2 and no sprint; switching the
 * hotbar to an item and back restarts the attack charge.</li>
 * </ul>
 * Each fighter's camera follows the other with a lag (like AimAssist's aim
 * or a player's hand), and sees the other a few ticks late with smoothed
 * motion - what the game shows. Fighters with {@code onlyGoodHits} (the
 * network, like AimAssist) only hit with a sprint hit or a crit.
 */
public final class PvpSim
{
	public static final int NEVER = -1_000_000;
	/** Duels that start 14-28 blocks apart (the rest 3.5-9.5). */
	static final double FAR_START = 0.15;
	static final double REACH = 3.0;
	static final double COOLDOWN = 12.5;
	
	/**
	 * sqrt(a^2 + b^2): Math.hypot guards against overflow these numbers
	 * never reach, and was 1.2% of the trainer's time (last-bit rounding
	 * aside the same).
	 */
	static double length(double a, double b)
	{
		return Math.sqrt(a * a + b * b);
	}
	
	/** SimFighter.ticksUntilCharged() for tickers -64..63, computed once. */
	private static final int CHARGE_OFFSET = 64;
	private static final int[] CHARGE_TICKS = new int[128];
	static
	{
		for(int i = 0; i < CHARGE_TICKS.length; i++)
			CHARGE_TICKS[i] = SimFighter.ticksUntilCharged(i - CHARGE_OFFSET);
	}
	static final int SHIELD_DELAY = 5, EAT_TICKS = 32, WEB_BREAK_TICKS = 8;
	static final double ITEM_REACH = 4.5;
	/**
	 * Fitness for strafing STRAFE_SHARE of the time in range, less for more
	 * or less (user, 2026-10-25: it strafed all the time).
	 */
	public static final double STRAFE_BONUS = 0.1, STRAFE_SHARE = 0.6;
	/**
	 * Strafing counts for that only this many ticks into one direction: the
	 * bonus plus the cost of every key change paid for holding one way the
	 * whole fight (user, 2026-10-31: it circled one way 89-100% of the
	 * ticks; the scripted fighters switch every 5-25 ticks).
	 */
	static final int STRAFE_RUN = 25;
	/** Fitness cost per held-key change. */
	public static final double FLAP_COST = 0.0002;
	/**
	 * A won fight is worth this much (squashed candy: about e^10 = 22,000
	 * candy), a lost one costs it.
	 */
	public static final double WIN_SCORE = 10;
	/** A timeout's lead in the trade: up to this much of a win... */
	public static final double LEAD_WEIGHT = 0.8;
	/** ...about 3/4 of it at this many health points ahead. */
	public static final double LEAD_SCALE = 8;
	/** Ticks from deciding to pot to the first throw: stop, look down. */
	static final int POT_STOP_TICKS = 6;
	/**
	 * A small reward for a well-timed pearl (user, 2026-10-26: it never
	 * threw one): out on low health to far from everyone, or in from far
	 * to near them; at most PEARL_BONUS_MAX of them a fight.
	 */
	public static final double PEARL_BONUS = 1.5;
	static final int PEARL_BONUS_MAX = 2;
	/** The brute holds back from one who pearled out, until this close. */
	static final double BRUTE_WAIT = 20;
	static final int MAX_WEBS = 16;
	
	// the gear and the kit (user, 2026-10-25)
	static final int KIT_WEBS = 64, KIT_STRENGTH = 9, KIT_SPEED = 3,
		KIT_FIRE_RES = 3, KIT_GAPPLES = 128, KIT_PEARLS = 16, KIT_WIND = 64,
		KIT_WATER = 2, KIT_XP = 32;
	/**
	 * The share of fighters with a varied kit (varyKit) instead of the full
	 * one: 0 (the tests, the benchmarks' numbers), set by the trainer.
	 */
	public static volatile double kitVariety;
	/**
	 * With kitVariety: the share of fights in plain diamond armor and a
	 * diamond sword, nothing else (no enchantments, no items, no shield or
	 * axe). Every fight had Knockback II, Sharpness V, Protection IV and Fire
	 * Aspect II before, so the net learned hit-and-back-off (Knockback II
	 * makes the spacing) - and lost plain sword fights to a player who kept
	 * pressing, W 80% of the time and all (user, 2026-10-29). Half and half
	 * since 2026-10-31 (user): with the enchantment inputs the net can keep
	 * a plain-only trick without it costing the enchanted fights.
	 */
	static volatile double plainShare = 0.5;
	/** This fight's gear is plain (no enchantments, a sword only). */
	boolean plain;
	static final float SWORD_DAMAGE = 7, AXE_DAMAGE = 9, STRENGTH_II = 6;
	/** A netherite sword's attack damage (same speed as diamond). */
	public static final float NETHERITE_SWORD = 8, STRENGTH_III = 9;
	static final double NETHERITE_TOUGHNESS = 12;
	/** Diamond axe: attack speed 1, a full charge in 20 ticks. */
	static final double AXE_COOLDOWN = 20;
	static final float SHARPNESS_V = 3;
	/**
	 * Knockback I: (0 + 1) / 2 (LivingEntity.getKnockback). The trainer's
	 * fights have it 10% of the time, never II (user, 2026-10-30).
	 */
	public static final double KNOCKBACK_I = 0.5;
	static final double KNOCKBACK_SHARE = 0.1;
	static final int FIRE_ASPECT_II = 160;
	/** Protection IV on all four pieces. */
	static final double PROTECTION_IV = 16;
	public static final int SHIELD_DISABLE_TICKS = 100;
	/** Potions: Strength II, Speed, Fire Resistance - a bit each. */
	public static final int STRENGTH = 0, SPEED = 1, FIRE_RES = 2;
	public static final int[] POT_TICKS = {1800, 9600, 9600};
	/** A potion is thrown again once less than this is left of it. */
	public static final int[] REPOT_TICKS = {200, 600, 600};
	static final double PEARL_SPEED = 1.5, PEARL_GRAVITY = 0.03;
	public static final int PEARL_COOLDOWN = 20;
	static final float PEARL_DAMAGE = 5;
	/** Pearling in lands this far short of them; out, this far away. */
	public static final double PEARL_SHORT = 2.5, PEARL_AWAY = 12;
	static final double WIND_SPEED = 1.5, WIND_RADIUS = 1.2,
		WIND_KNOCKBACK = 1.22;
	public static final int WIND_COOLDOWN = 10;
	static final int WATER_FLOW_TICKS = 5;
	/** Every tick played to the end by {@link #run}, for the trainer's log. */
	public static final LongAdder SIMULATED_TICKS = new LongAdder();
	
	public interface Policy
	{
		void reset(SplittableRandom random);
		
		void act(SimFighter me, NeuralObservation obs, NeuralAction out);
	}
	
	/**
	 * A network policy whose forward pass can share one pass over the
	 * weights with another one's (the same weights, own biases): in a
	 * free-for-all the 19 opponents are the same net, two at a time is
	 * ~1.6x faster (user, 2026-10-27). act() = prepare, forward, finish.
	 */
	public interface Batchable extends Policy
	{
		/** Everything act() does before the network: its input. */
		float[] prepare(SimFighter me, NeuralObservation obs);
		
		NeuralNet batchNet();
		
		/** Everything act() does after it, from the network's output. */
		void finish(SimFighter me, NeuralObservation obs, NeuralAction out,
			float[] output);
	}
	
	/** What a fighter showed at the end of a tick (for delayed views). */
	static final class Snapshot
	{
		double x, y, z;
		float yaw, pitch;
		/** -sin(yaw), cos(yaw): where it faces on the ground. */
		double faceX, faceZ;
		/** The look vector. */
		double lookX, lookY, lookZ;
		double fallDistance;
		boolean onGround, sprint, sneaking, blocking, using, inWeb;
		int hurtTime, lastSwingTick;
	}
	
	public static final class SimFighter
	{
		final PvpSim sim;
		Policy policy;
		
		double x, y, z, vx, vy, vz;
		float yaw, pitch;
		boolean onGround = true;
		boolean sprinting, serverSprint, sneaking;
		int attackTicker = 20, noJumpDelay, hurtTime, invulnerableTime;
		float health = 20, lastHurt;
		double fallDistance;
		int lastSwingTick = NEVER, lastHitTick = NEVER, lastHurtTick = NEVER;
		
		/** Network fighters: only sprint hits and crits, like AimAssist. */
		boolean onlyGoodHits;
		
		/**
		 * How far this fighter's hits reach. 3.0 is vanilla; the online
		 * learner gives a copied opponent the reach it was seen hitting from
		 * (server-side bots and lag reach further).
		 */
		double reach = REACH;
		
		// the camera
		double aimRate, aimNoise, aimLead;
		double lastNeedYaw = Double.NaN;
		float prevYaw, prevPitch;
		
		// the look vector, worked out once per yaw/pitch (trig is most of a
		// tick's cost otherwise)
		private float lookYaw = Float.NaN, lookPitch = Float.NaN;
		double lookX, lookY, lookZ, sinYaw, cosYaw;
		private double kRate = Double.NaN, k;
		
		void updateLook()
		{
			if(yaw == lookYaw && pitch == lookPitch)
				return;
			lookYaw = yaw;
			lookPitch = pitch;
			double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
			sinYaw = Math.sin(ry);
			cosYaw = Math.cos(ry);
			double cp = Math.cos(rp);
			lookX = -sinYaw * cp;
			lookY = -Math.sin(rp);
			lookZ = cosYaw * cp;
		}
		
		/** How much of the aim error the camera closes per tick. */
		double aimK()
		{
			if(aimRate != kRate)
			{
				kRate = aimRate;
				k = 1 - Math.exp(-aimRate * 0.05);
			}
			return k;
		}
		
		/** How many ticks late this fighter sees the other one. */
		int viewDelay;
		/** ECHO's inputs that need last tick's (and the fight's) values. */
		final EchoFeatures echo = new EchoFeatures();
		SimFighter echoTarget;
		
		// the kit (pots: Strength II)
		boolean hasShield, hasAxe;
		/** The sword's attack damage: diamond, or netherite. */
		float swordDamage = SWORD_DAMAGE;
		// the rest of the gear (Gear): armor points, toughness, Protection
		// levels (all pieces), Sharpness's damage, Knockback's strength and
		// Fire Aspect's ticks
		double armor = 20, toughness = 8, protection = PROTECTION_IV;
		float sharpness = SHARPNESS_V;
		double knockbackBonus;
		int fireAspectTicks = FIRE_ASPECT_II;
		/** Netherite armor: toughness 12, knockback resistance 0.4. */
		boolean netherite;
		/** What Strength adds while it's on (II: 6, III: 9). */
		float strengthBonus = STRENGTH_II;
		/** Resistance's level (II: damage x 0.6), 0 = none. */
		int resistance;
		/** Launched by a wind charge: no fall damage until landing. */
		boolean windLaunched;
		/** A totem of undying in the offhand; when the last one popped. */
		boolean totem;
		int totemTick = NEVER;
		/** Totems AutoTotem puts in the offhand after one pops. */
		int spareTotems;
		/**
		 * The brute (makeBrute): its effects come back after a totem pops,
		 * no Knockback enchantment.
		 */
		boolean brute;
		/** Speed's level all fight (the brute's V), 0 = only the potion. */
		int speedLevel;
		/** The brute holds back from this one (pearled out) until close. */
		SimFighter waitFor;
		/** The pearl in the air: thrown on low health, from how far. */
		boolean pearlLow;
		double pearlFrom;
		/**
		 * FoodData: food 0..20, saturation 0..food, exhaustion 0..40 and
		 * the natural regeneration timer.
		 */
		int food = 20;
		float saturation = 5, exhaustion;
		int foodTimer;
		/** rayHits' scratch {tMin, tMax}: no garbage every tick. */
		final double[] rayT = new double[2];
		/** Well-timed pearls this fight (PEARL_BONUS): in, out. */
		int goodPearlsIn, goodPearlsOut;
		/** What this fighter sees around it (FocusMap). */
		final FocusMap focusMap = new FocusMap();
		/** Absorption runs out after this (Absorption's effect). */
		int absorptionTicks;
		int webs, pots, speedPots, fireResPots, gapples, pearls, winds, waters,
			xpBottles;
		/** Ticks left of each potion's effect (STRENGTH, SPEED, FIRE_RES). */
		final int[] effectTicks = new int[3];
		int fireTicks;
		int pearlCooldown, windCooldown, shieldCooldown;
		/** The shield breaker: 1 holding the axe (hits next), 2 going back. */
		int axeStage, axeTick;
		/** Potions still to throw this round (bits by type). */
		int potQueue;
		/**
		 * Turning to throw a pearl (in or out) / wind charge (jump or at them).
		 */
		int pendingPearlTick = NEVER, pendingWindTick = NEVER;
		boolean pearlInPending, windJumpPending;
		/** No hit until then (the camera turned away for a pearl). */
		int busyUntil = NEVER;
		/**
		 * Hotbar slots run out with more in the inventory (a potion each - they
		 * don't stack - and a golden apple stack of 64), and the restock
		 * that has the inventory open until then.
		 */
		int restockPending, restockUntil = NEVER;
		boolean pearlFlying, windFlying;
		double pearlX, pearlY, pearlZ, pearlVx, pearlVy, pearlVz;
		double windX, windY, windZ, windVx, windVy, windVz;
		int windAge;
		/** Water out of a web: placing, washing, picking it back up. */
		int pendingWaterTick = NEVER, washTick = NEVER, pickupTick = NEVER;
		int waterTicks;
		/** A wind burst's push, arriving with the explosion packet. */
		boolean blastPending;
		double blastX, blastY, blastZ;
		/** Ticks the shield has been up; it blocks from SHIELD_DELAY. */
		int shieldTicks;
		/** Ticks spent eating a golden apple. */
		int eatTicks;
		float absorption;
		int regenTicks;
		/** No attack this tick: an item, a web being broken. */
		boolean busy;
		/**
		 * A web or potion the camera turned for: the tick it goes out (the
		 * click comes a tick after the turn, like AimAssist's).
		 */
		int pendingWebTick = NEVER, pendingPotTick = NEVER;
		int pendingWebX, pendingWebZ;
		final int[] potLandTick = {NEVER, NEVER, NEVER};
		final double[] potX = new double[3], potY = new double[3],
			potZ = new double[3];
		/** Stuck in a web: ticks so far, ticks breaking it, reaction. */
		int stuckTicks, breakTicks, webReact;
		/** Stats: ticks stuck in a web. */
		int stuckTotal;
		/** {@link PvpSim#webIndexOf}'s memo. */
		double webMemoX = Double.NaN, webMemoY, webMemoZ;
		int webMemoVersion, webMemoIndex = -1;
		
		// the other fighter's motion as this one tracks it (AimAssist's
		// trackTargetMotion: half the last step, half what it had)
		double trackX, trackY, trackZ, motX, motY, motZ;
		boolean trackValid;
		
		/**
		 * The server's own velocity of this player. The server never sees a
		 * player's input, so in its tick (LivingEntity.travel with no input,
		 * then snapped back to the client's position) it only falls and slows
		 * down: on the ground -0.0784 a tick (the 0.3608 of a knockback), in
		 * the air free fall from the tick the client left the ground.
		 * Knockback is computed from this, not from the client's velocity.
		 */
		double svx, svy = SERVER_GROUND_VY, svz;
		
		/**
		 * Knockback sent this tick (ClientboundSetEntityMotionPacket): it
		 * replaces the client's velocity at the start of its next tick, so a
		 * jump reset can still jump with it.
		 */
		boolean kbPending;
		double kbx, kby, kbz;
		
		/** The sprint state the client last sent (LocalPlayer.wasSprinting). */
		boolean sentSprint;
		
		/**
		 * The server turned this player's sprint off (a sprint hit); the
		 * entity data reaches the client at the start of its next tick.
		 */
		boolean sprintFlagCleared;
		
		/** Who this one fights (in FFA the nearest, sticky). */
		SimFighter target;
		final Candy candy = new Candy();
		/** An opponent is stuck in one of our webs this tick. */
		boolean trapping;
		/** The camera's last step (vector smoothing). */
		double aimStepYaw, aimStepPitch;
		
		boolean alive()
		{
			return health > 0;
		}
		
		final Snapshot[] history = new Snapshot[4];
		final NeuralObservation obs = new NeuralObservation();
		final NeuralAction action = new NeuralAction();
		
		// stats
		float damageDealt, healed;
		/**
		 * Everything that hit this fighter, absorption included (healing
		 * doesn't undo it): who took less decides a fight neither won.
		 */
		float taken;
		int hits, sprintHits, crits, combo, maxCombo, swings;
		int websPlaced, potsUsed, gapplesEaten, blocked, washes, totemsPopped;
		/**
		 * Ticks within 4.5 blocks, and strafing in them - the first
		 * STRAFE_RUN ticks of each direction only.
		 */
		int rangeTicks, strafeTicks;
		/** How often a held key changed (W/S, A/D, sprint, sneak). */
		int keyChanges;
		private int lastForward, lastStrafe, strafeRun;
		private boolean lastSprintKey, lastSneakKey;
		
		SimFighter(PvpSim sim)
		{
			this.sim = sim;
			for(int i = 0; i < history.length; i++)
				history[i] = new Snapshot();
		}
		
		double eyeHeight()
		{
			return sneaking ? 1.27 : 1.62;
		}
		
		static double height(boolean sneaking)
		{
			return sneaking ? 1.5 : 1.8;
		}
		
		float strength(double partial)
		{
			return (float)Math.min(1,
				Math.max(0, (attackTicker + partial) / COOLDOWN));
		}
		
		int ticksUntilCharged()
		{
			// it only depends on the (whole) ticker: looked up, the loop was
			// 2% of the trainer's time (2026-10-30)
			int i = attackTicker + CHARGE_OFFSET;
			if(i >= 0 && i < CHARGE_TICKS.length)
				return CHARGE_TICKS[i];
			return ticksUntilCharged(attackTicker);
		}
		
		static int ticksUntilCharged(int ticker)
		{
			for(int t = 0; t < 40; t++)
				if((float)Math.min(1,
					Math.max(0, (ticker + t - 0.5) / COOLDOWN)) > 0.9F)
					return t;
			return 40;
		}
		
		boolean critReady()
		{
			return fallDistance > 0 && !onGround && !serverSprint;
		}
		
		boolean blocking()
		{
			return shieldTicks >= SHIELD_DELAY;
		}
		
		boolean usingItem()
		{
			return shieldTicks > 0 || eatTicks > 0;
		}
		
		void giveKit()
		{
			hasShield = hasAxe = true;
			webs = KIT_WEBS;
			pots = KIT_STRENGTH;
			speedPots = KIT_SPEED;
			fireResPots = KIT_FIRE_RES;
			gapples = KIT_GAPPLES;
			pearls = KIT_PEARLS;
			winds = KIT_WIND;
			waters = KIT_WATER;
			xpBottles = KIT_XP;
		}
		
		/**
		 * A kit like real fights have: any item or none, a few or many, a
		 * shield or not, a totem or not. With the full kit in every fight,
		 * these inputs never changed in training; in real fights they did
		 * (no wind charges, no pots, the opponent without a shield but with a
		 * totem) and the net walked backwards 62-84% of the time (user's
		 * fights, 2026-10-29).
		 */
		void varyKit(SplittableRandom r)
		{
			webs = some(r, 0.6, KIT_WEBS);
			pots = some(r, 0.5, KIT_STRENGTH);
			speedPots = some(r, 0.5, KIT_SPEED);
			fireResPots = some(r, 0.4, KIT_FIRE_RES);
			gapples = some(r, 0.8, KIT_GAPPLES);
			pearls = some(r, 0.6, KIT_PEARLS);
			winds = some(r, 0.4, KIT_WIND);
			waters = some(r, 0.5, KIT_WATER);
			hasShield = r.nextDouble() < 0.7;
			if(r.nextDouble() < 0.4)
			{
				totem = true;
				spareTotems = r.nextInt(4);
			}
		}
		
		/** This fighter's gear as it is now (the brute's hits push no more). */
		Gear gear()
		{
			return new Gear(armor, toughness, protection, swordDamage,
				sharpness, brute ? 0 : knockbackBonus, fireAspectTicks);
		}
		
		/**
		 * Plain diamond: the sword and the armor, nothing else (the items;
		 * Gear.PLAIN takes the enchantments).
		 */
		void bareKit()
		{
			webs = pots = speedPots = fireResPots = 0;
			gapples = pearls = winds = waters = xpBottles = 0;
			hasShield = hasAxe = false;
		}
		
		/** None (1 - p), else 1..max - often only a few. */
		private static int some(SplittableRandom r, double p, int max)
		{
			if(r.nextDouble() >= p)
				return 0;
			return 1 + r.nextInt(r.nextBoolean() ? Math.min(4, max) : max);
		}
		
		Snapshot seen(int delay)
		{
			return history[Math.floorMod(sim.tick - 1 - delay, history.length)];
		}
		
		void record()
		{
			Snapshot s = history[Math.floorMod(sim.tick, history.length)];
			s.x = x;
			s.y = y;
			s.z = z;
			s.yaw = yaw;
			updateLook();
			s.faceX = -sinYaw;
			s.faceZ = cosYaw;
			s.pitch = pitch;
			s.lookX = lookX;
			s.lookY = lookY;
			s.lookZ = lookZ;
			s.fallDistance = fallDistance;
			s.onGround = onGround;
			s.sprint = serverSprint;
			s.sneaking = sneaking;
			s.hurtTime = hurtTime;
			s.lastSwingTick = lastSwingTick;
			s.blocking = blocking();
			s.using = usingItem();
			s.inWeb = sim.inWeb(this);
		}
	}
	
	final SplittableRandom random;
	/** All fighters; a and b are the first two (a duel has only those). */
	final SimFighter[] fighters;
	final SimFighter a, b;
	int tick;
	final int maxTicks;
	
	/**
	 * A fighter's gear, as worn: armor points, toughness, Protection levels
	 * (all pieces together), the sword's attack damage, Sharpness's bonus,
	 * Knockback's strength (a level = 0.5), Fire Aspect's ticks (a level =
	 * 80). The in-game learner fights in both players' real gear.
	 */
	public record Gear(double armor, double toughness, double protection,
		float swordDamage, float sharpness, double knockback, int fireTicks)
	{
		
		/** Plain diamond: no enchantments. */
		public static final Gear PLAIN =
			new Gear(20, 8, 0, SWORD_DAMAGE, 0, 0, 0);
		
		void applyTo(SimFighter f)
		{
			f.armor = armor;
			f.toughness = toughness;
			f.protection = protection;
			f.swordDamage = swordDamage;
			f.sharpness = sharpness;
			f.knockbackBonus = knockback;
			f.fireAspectTicks = fireTicks;
		}
	}
	
	/** Enchantments each there or not, Knockback I 10% of the time. */
	private Gear randomGear()
	{
		return new Gear(20, 8, random.nextDouble() < 0.3 ? 0 : PROTECTION_IV,
			SWORD_DAMAGE, random.nextDouble() < 0.3 ? 0 : SHARPNESS_V,
			random.nextDouble() < KNOCKBACK_SHARE ? KNOCKBACK_I : 0,
			random.nextDouble() < 0.4 ? 0 : FIRE_ASPECT_II);
	}
	
	/** Both fighters' gear (null: leave that one's as it is). */
	public void setGear(Gear forA, Gear forB)
	{
		if(forA != null)
			forA.applyTo(a);
		if(forB != null)
			forB.applyTo(b);
	}
	
	/** Cobwebs on the ground (block x, z; all at y = 0). */
	final int[] webX = new int[MAX_WEBS], webZ = new int[MAX_WEBS];
	final SimFighter[] webOwner = new SimFighter[MAX_WEBS];
	/** Each web's height: the top of the ground it was put on. */
	final int[] webY = new int[MAX_WEBS];
	/** The arena (flat unless useTerrain). */
	Terrain terrain = Terrain.flat();
	private final double[] moveOut = new double[6];
	int webCount;
	/** Bumped whenever a web comes or goes. */
	int webVersion;
	/** All webs' columns lie in this box (as of webBoxVersion). */
	private int webMinX, webMaxX, webMinZ, webMaxZ, webBoxVersion = -1;
	
	public PvpSim(long seed, Policy pa, Policy pb, int maxTicks)
	{
		this(seed, new Policy[]{pa, pb}, maxTicks);
	}
	
	/**
	 * Two policies = a duel, more = a free-for-all (docs/TRAINING.MD): no
	 * teams, each one fights the nearest, last one standing wins.
	 */
	public PvpSim(long seed, Policy[] policies, int maxTicks)
	{
		random = new SplittableRandom(seed);
		this.maxTicks = maxTicks;
		fighters = new SimFighter[policies.length];
		for(int i = 0; i < fighters.length; i++)
		{
			fighters[i] = new SimFighter(this);
			fighters[i].policy = policies[i];
		}
		a = fighters[0];
		b = fighters[1];
		a.target = b;
		b.target = a;
		
		// the gear (trainer only, see kitVariety): plain diamond and a sword
		// now and then, else each enchantment there or not - the same for
		// both, or in a fifth of the fights each their own
		if(kitVariety > 0)
		{
			plain = random.nextDouble() < plainShare;
			Gear gear = plain ? Gear.PLAIN : randomGear();
			setGear(gear,
				!plain && random.nextDouble() < 0.2 ? randomGear() : gear);
		}
		
		// now and then from across the arena (user, 2026-10-27: it should
		// see and play an opponent far away too - the far inputs)
		double dist = random.nextDouble() < FAR_START
			? 14 + random.nextDouble() * 14 : 3.5 + random.nextDouble() * 6;
		double angle = random.nextDouble() * Math.PI * 2;
		// around the middle, so a far start stays in the arena
		a.x = -Math.cos(angle) * dist / 2;
		a.z = -Math.sin(angle) * dist / 2;
		b.x = Math.cos(angle) * dist / 2;
		b.z = Math.sin(angle) * dist / 2;
		if(fighters.length > 2)
		{
			// a ring, about 2.5 blocks apart
			double radius = fighters.length * 2.5 / (2 * Math.PI);
			for(int i = 0; i < fighters.length; i++)
			{
				double an = angle + i * 2 * Math.PI / fighters.length;
				fighters[i].x = Math.cos(an) * radius;
				fighters[i].z = Math.sin(an) * radius;
			}
			updateTargets();
		}
		for(SimFighter f : fighters)
		{
			SimFighter o = f.target;
			f.yaw = needYaw(f, o.x, o.z)
				+ (float)((random.nextDouble() - 0.5) * 40);
			f.pitch = 0;
			f.prevYaw = f.yaw;
			f.aimRate = 12 + random.nextDouble() * 16;
			f.aimNoise = 0.1 + random.nextDouble() * 0.6;
			f.aimLead = 0.5 + random.nextDouble() * 0.5;
			f.viewDelay = random.nextInt(3);
			f.attackTicker = 5 + random.nextInt(20);
			f.vy = -0.0784;
			f.giveKit();
			if(plain)
				f.bareKit();
			else if(kitVariety > 0 && random.nextDouble() < kitVariety)
				f.varyKit(random);
			f.webReact = 3 + random.nextInt(4);
			// fed before the fight: full food, saturation anything from
			// just respawned to topped up
			f.saturation = 2 + random.nextInt(19);
		}
		// a full history, so the first delayed views aren't empty
		for(tick = -3; tick <= 0; tick++)
			for(SimFighter f : fighters)
				f.record();
		tick = 0;
		
		for(SimFighter f : fighters)
			f.policy.reset(random.split());
	}
	
	/** Fights on this ground: everyone stands on it from the start. */
	public void useTerrain(Terrain t)
	{
		terrain = t;
		for(SimFighter f : fighters)
			f.y = t.groundUnder(f.x, f.z);
		int now = tick;
		for(tick = now - 3; tick <= now; tick++)
			for(SimFighter f : fighters)
				f.record();
		tick = now;
	}
	
	/**
	 * FFA: everyone fights the nearest one still alive, switching only to
	 * one at least 2 blocks closer (no flip-flopping between two).
	 */
	void updateTargets()
	{
		if(fighters.length == 2)
			return;
		for(SimFighter f : fighters)
		{
			if(!f.alive())
				continue;
			SimFighter best = null;
			double bestD = Double.MAX_VALUE;
			for(SimFighter g : fighters)
			{
				if(g == f || !g.alive())
					continue;
				double d = horizDistSq(f, g);
				if(d < bestD)
				{
					bestD = d;
					best = g;
				}
			}
			if(best != null && best != f.target && (f.target == null
				|| !f.target.alive()
				|| Math.sqrt(horizDistSq(f, f.target)) - Math.sqrt(bestD) > 2))
				f.target = best;
		}
	}
	
	static double horizDistSq(SimFighter f, SimFighter g)
	{
		double dx = g.x - f.x, dz = g.z - f.z;
		return dx * dx + dz * dz;
	}
	
	int aliveCount()
	{
		int n = 0;
		for(SimFighter f : fighters)
			if(f.alive())
				n++;
		return n;
	}
	
	/** Plays the fight to the end. Returns 1 if A won, -1 if B, else 0. */
	public int run()
	{
		while(tick < maxTicks)
		{
			step();
			if(aliveCount() <= 1)
				break;
		}
		SIMULATED_TICKS.add((long)tick * fighters.length / 2);
		if(fighters.length > 2)
			return !a.alive() ? -1 : aliveCount() == 1 ? 1 : 0;
		if(a.health <= 0 && b.health > 0)
			return -1;
		if(b.health <= 0 && a.health > 0)
			return 1;
		return 0;
	}
	
	/**
	 * A duel's result with a timeout decided by the trade: 1 if A took less
	 * than B over the fight, -1 if more. With 128 golden apples, totems and
	 * potions nearly every even fight times out (297 of 300 champion
	 * mirror fights), and the health left at that moment (0.4 apart on
	 * average) said more about who ate last than who was winning.
	 */
	public int outcome(int result)
	{
		if(result != 0 || fighters.length > 2)
			return result;
		return Float.compare(b.taken, a.taken);
	}
	
	/**
	 * A fight's worth to fighter A, the same for every trainer: damage
	 * traded (healing undoes the hits it healed), the win, the sword PvP
	 * tactics (sprint hits, crits, combos held and taken), a gentle pull
	 * toward strafing while in range and a small cost for flapping keys.
	 */
	public double score(int result)
	{
		// the user's candy (docs/TRAINING.MD), squashed like AimAssist's in
		// real fights: a sprint streak doubles every hit, and unsquashed one
		// long streak against a weak player outweighed everything else (the
		// trainer learned to farm those and lost to everyone else)
		double score = Candy.squash(a.candy.total());
		// and the fight itself: the candy rewards how it's fought, this
		// what the champion duel asks for
		score += WIN_SCORE * result;
		// a timeout: most of a win for a clear lead in the trade, the same
		// thing the champion duel decides it by (outcome) - with only the
		// win above, self-play saw nearly no win signal at all
		if(result == 0 && fighters.length == 2)
			score += WIN_SCORE * LEAD_WEIGHT
				* Math.tanh((b.taken - a.taken) / LEAD_SCALE);
		// only for pearls that served the fight: running off to pearl back
		// in farmed it and lost to everyone (2026-10-26, SprintCombo 89% ->
		// 27% in two rounds) - in counts in a win, out in a fight not lost
		int pearls = (result > 0 ? a.goodPearlsIn : 0)
			+ (result >= 0 ? a.goodPearlsOut : 0);
		score += PEARL_BONUS * Math.min(pearls, PEARL_BONUS_MAX);
		// a gentle pull toward strafing and a cost for flapping keys
		double share = a.strafeTicks / (double)Math.max(1, a.rangeTicks);
		score += 10 * STRAFE_BONUS * (1 - 2 * Math.abs(share - STRAFE_SHARE));
		score -= 10 * FLAP_COST * a.keyChanges;
		return score;
	}
	
	public void step()
	{
		tick++;
		
		if(fighters.length > 2)
		{
			stepFfa();
			return;
		}
		
		// 0. last tick's knockback arrives
		applyKnockback(a);
		applyKnockback(b);
		
		// 1. decide, from what each one sees
		observe(a, b);
		observe(b, a);
		a.policy.act(a, a.obs, a.action);
		b.policy.act(b, b.obs, b.action);
		holdBack(a);
		holdBack(b);
		stats(a);
		stats(b);
		
		// 2. items: shields, eating, webs, potions
		items(a);
		items(b);
		
		// 3. attacks go out before the tick's movement
		boolean aFirst = random.nextBoolean();
		attackPhase(aFirst ? a : b);
		attackPhase(aFirst ? b : a);
		
		// 4. movement
		move(a);
		move(b);
		push(a, b);
		
		// 5. end of tick
		endTick(a);
		endTick(b);
		aim(a, b);
		aim(b, a);
		a.record();
		b.record();
		candyTick();
	}
	
	/** The same tick with everyone, in FFA. */
	void stepFfa()
	{
		updateTargets();
		for(SimFighter f : fighters)
			if(f.alive())
				applyKnockback(f);
		// everyone sees last tick's world (snapshots), so all observe
		// first; then the networks that share weights decide two at a time
		for(SimFighter f : fighters)
			if(f.alive())
				observe(f, f.target);
		SimFighter waiting = null;
		float[] waitingIn = null;
		for(SimFighter f : fighters)
		{
			if(!f.alive())
				continue;
			if(!(f.policy instanceof Batchable bp)
				|| bp.batchNet().sharedWeights == null)
			{
				f.policy.act(f, f.obs, f.action);
				continue;
			}
			float[] in = bp.prepare(f, f.obs);
			Batchable wp = waiting == null ? null : (Batchable)waiting.policy;
			if(wp != null
				&& wp.batchNet().sharedWeights == bp.batchNet().sharedWeights)
			{
				NeuralNet.forwardPair(wp.batchNet(), waitingIn, bp.batchNet(),
					in);
				finishBatched(waiting);
				finishBatched(f);
				waiting = null;
				continue;
			}
			if(waiting != null)
				decideAlone(waiting, waitingIn);
			waiting = f;
			waitingIn = in;
		}
		if(waiting != null)
			decideAlone(waiting, waitingIn);
		for(SimFighter f : fighters)
			if(f.alive())
			{
				holdBack(f);
				stats(f);
			}
		for(SimFighter f : fighters)
			if(f.alive())
				items(f);
		int n = fighters.length;
		int first = random.nextInt(n);
		for(int i = 0; i < n; i++)
		{
			SimFighter f = fighters[(first + i) % n];
			if(f.alive() && f.target.alive())
				attackPhase(f);
		}
		for(SimFighter f : fighters)
			if(f.alive())
				move(f);
		for(int i = 0; i < n; i++)
			for(int j = i + 1; j < n; j++)
				if(fighters[i].alive() && fighters[j].alive())
					push(fighters[i], fighters[j]);
		for(SimFighter f : fighters)
			if(f.alive())
			{
				endTick(f);
				aim(f, f.target);
				f.record();
			}
		candyTick();
	}
	
	private static void decideAlone(SimFighter f, float[] in)
	{
		((Batchable)f.policy).batchNet().forward(in);
		finishBatched(f);
	}
	
	private static void finishBatched(SimFighter f)
	{
		Batchable bp = (Batchable)f.policy;
		NeuralNet net = bp.batchNet();
		bp.finish(f, f.obs, f.action,
			net.getActivations(net.sizesView().length - 1));
	}
	
	/** The per-tick candy: near a fight, stuck in a web, trapping one. */
	void candyTick()
	{
		for(SimFighter f : fighters)
			f.trapping = false;
		for(SimFighter f : fighters)
		{
			if(!f.alive() || f.stuckTicks == 0)
				continue;
			int web = webIndexOf(f);
			SimFighter owner = web < 0 ? null : webOwner[web];
			if(owner != null && owner != f)
				owner.trapping = true;
		}
		for(SimFighter f : fighters)
		{
			if(!f.alive())
				continue;
			double nearest = Double.MAX_VALUE, webbed = Double.MAX_VALUE;
			for(SimFighter g : fighters)
				if(g != f && g.alive())
				{
					double d = horizDistSq(f, g);
					nearest = Math.min(nearest, d);
					if(g.stuckTicks > 0)
						webbed = Math.min(webbed, d);
				}
			boolean falling = !f.onGround && f.vy < 0;
			f.candy.tick(tick, Math.sqrt(nearest), f.stuckTicks > 0, f.trapping,
				falling && webbed <= Candy.JUMP_REACH * Candy.JUMP_REACH,
				f.health);
		}
	}
	
	void endTick(SimFighter f)
	{
		if(f.hurtTime > 0 && f.lastHurtTick != tick)
			f.hurtTime--;
		if(f.invulnerableTime > 0)
			f.invulnerableTime--;
		f.attackTicker++;
		
		// LocalPlayer.sendIsSprintingIfNeeded: only a change is sent, so after
		// a sprint hit the server stays off until the client stops and starts
		// again (a W-tap) - re-sprinting in the same tick sends nothing
		if(f.sprinting != f.sentSprint)
		{
			f.sentSprint = f.sprinting;
			f.serverSprint = f.sprinting;
		}
		serverTravel(f);
		foodTick(f);
		
		// Regeneration: a heal every 50 >> amplifier ticks (II: 25, the
		// brute's III all fight: 12)
		if(f.brute)
		{
			if(tick % 12 == 0)
				heal(f, 1);
			if(f.regenTicks > 0)
				f.regenTicks--;
		}else if(f.regenTicks > 0)
		{
			if(f.regenTicks % 25 == 0)
				heal(f, 1);
			f.regenTicks--;
		}
		for(int i = 0; i < 3; i++)
		{
			if(f.effectTicks[i] > 0)
				f.effectTicks[i]--;
			if(f.potLandTick[i] == tick)
				splash(f, i);
		}
		if(f.absorptionTicks > 0 && --f.absorptionTicks == 0)
			f.absorption = 0;
		if(f.pearlCooldown > 0)
			f.pearlCooldown--;
		if(f.windCooldown > 0)
			f.windCooldown--;
		if(f.shieldCooldown > 0)
			f.shieldCooldown--;
		if(f.waterTicks > 0)
		{
			f.waterTicks--;
			f.fireTicks = 0;
		}
		
		// Entity.baseTick: on fire, 1 damage a second (not with Fire
		// Resistance, which rejects it before the hurt cooldown)
		if(f.fireTicks > 0)
		{
			if(f.fireTicks % 20 == 0 && f.effectTicks[FIRE_RES] == 0)
				hurtPlain(f, 1);
			f.fireTicks--;
		}
		
		if(f.pearlFlying)
			stepPearl(f);
		if(f.windFlying)
			stepWind(f);
	}
	
	void stats(SimFighter f)
	{
		NeuralAction act = f.action;
		f.strafeRun = act.strafe == 0 ? 0
			: act.strafe == f.lastStrafe ? f.strafeRun + 1 : 1;
		if(f.obs.dist < ITEM_REACH)
		{
			f.rangeTicks++;
			if(act.strafe != 0 && f.strafeRun <= STRAFE_RUN)
				f.strafeTicks++;
		}
		if(act.forward != f.lastForward)
			f.keyChanges++;
		if(act.strafe != f.lastStrafe)
			f.keyChanges++;
		if(act.sprint != f.lastSprintKey)
			f.keyChanges++;
		if(act.sneak != f.lastSneakKey)
			f.keyChanges++;
		f.lastForward = act.forward;
		f.lastStrafe = act.strafe;
		f.lastSprintKey = act.sprint;
		f.lastSneakKey = act.sneak;
	}
	
	SimFighter other(SimFighter f)
	{
		return f.target;
	}
	
	// ── items ────────────────────────────────────────────────────────────
	
	boolean webAt(int cx, int cz)
	{
		for(int i = 0; i < webCount; i++)
			if(webX[i] == cx && webZ[i] == cz)
				return true;
		return false;
	}
	
	/** Whether a player standing at x, y, z touches a web. */
	boolean inWeb(double x, double y, double z)
	{
		return webIndex(x, y, z) >= 0;
	}
	
	/** Whether this fighter touches a web where it stands now. */
	boolean inWeb(SimFighter f)
	{
		return webIndexOf(f) >= 0;
	}
	
	/**
	 * {@link #webIndex} at the fighter's position, remembered until it moves
	 * or a web comes or goes (asked up to 4 times a tick).
	 */
	private int webIndexOf(SimFighter f)
	{
		if(f.webMemoVersion != webVersion || f.webMemoX != f.x
			|| f.webMemoY != f.y || f.webMemoZ != f.z)
		{
			f.webMemoIndex = webIndex(f.x, f.y, f.z);
			f.webMemoX = f.x;
			f.webMemoY = f.y;
			f.webMemoZ = f.z;
			f.webMemoVersion = webVersion;
		}
		return f.webMemoIndex;
	}
	
	private int webIndex(double x, double y, double z)
	{
		if(webCount == 0)
			return -1;
		// the hitbox x - 0.3..x + 0.3 overlaps block wx..wx + 1 exactly when
		// floor(x - 0.3) <= wx <= ceil(x + 0.3) - 1 - whole numbers only
		int x0 = (int)Math.floor(x - 0.3), x1 = (int)Math.ceil(x + 0.3) - 1;
		int z0 = (int)Math.floor(z - 0.3), z1 = (int)Math.ceil(z + 0.3) - 1;
		// nowhere near any web: no scan (asked every tick by every fighter)
		if(webBoxVersion != webVersion)
		{
			webMinX = webMinZ = Integer.MAX_VALUE;
			webMaxX = webMaxZ = Integer.MIN_VALUE;
			for(int i = 0; i < webCount; i++)
			{
				webMinX = Math.min(webMinX, webX[i]);
				webMaxX = Math.max(webMaxX, webX[i]);
				webMinZ = Math.min(webMinZ, webZ[i]);
				webMaxZ = Math.max(webMaxZ, webZ[i]);
			}
			webBoxVersion = webVersion;
		}
		if(x1 < webMinX || x0 > webMaxX || z1 < webMinZ || z0 > webMaxZ)
			return -1;
		for(int i = 0; i < webCount; i++)
		{
			int wx = webX[i], wz = webZ[i];
			if(wx >= x0 && wx <= x1 && wz >= z0 && wz <= z1 && y < webY[i] + 1
				&& y + 1.8 > webY[i])
				return i;
		}
		return -1;
	}
	
	void items(SimFighter f)
	{
		NeuralAction act = f.action;
		SimFighter op = f.target;
		f.busy = tick < f.busyUntil;
		
		// restocking like AimAssist (NeuralItems): the inventory open, an
		// item a tick into the hotbar, closed - 3 ticks for one, no keys and
		// no hands meanwhile
		if(tick < f.restockUntil)
		{
			f.busy = true;
			return;
		}
		if(f.restockPending > 0 && f.potQueue == 0 && f.axeStage == 0
			&& !f.usingItem() && !inWeb(f))
		{
			f.restockUntil = tick + 2 + f.restockPending;
			f.restockPending = 0;
			f.busy = true;
			return;
		}
		
		// the shield breaker (AimAssist's, by itself): to the axe, the hit
		// next tick (attackPhase), then back to the sword - each switch
		// restarts the charge
		if(f.axeStage == 2)
		{
			f.axeStage = 0;
			f.attackTicker = -1;
			f.busy = true;
			return;
		}
		if(f.axeStage == 1)
		{
			if(tick - f.axeTick > 3)
				f.axeStage = 2;
			return;
		}
		
		// an item the camera turned for last tick goes out now; to the item
		// and back restarts the charge (-1: the switch back is a tick later)
		if(f.pendingWebTick == tick)
		{
			f.pendingWebTick = NEVER;
			if(f.webs > 0 && webCount < MAX_WEBS
				&& !webAt(f.pendingWebX, f.pendingWebZ))
			{
				webX[webCount] = f.pendingWebX;
				webY[webCount] = terrain.at(f.pendingWebX, f.pendingWebZ);
				webZ[webCount] = f.pendingWebZ;
				webOwner[webCount] = f;
				webCount++;
				webVersion++;
				f.webs--;
				f.websPlaced++;
			}
			f.attackTicker = -1;
			f.busy = true;
			return;
		}
		if(f.pendingPotTick == tick)
		{
			throwPot(f);
			return;
		}
		if(f.pendingPearlTick == tick)
		{
			throwPearl(f);
			return;
		}
		if(f.pendingWindTick == tick)
		{
			throwWind(f);
			return;
		}
		if(f.pendingWaterTick == tick)
		{
			// looking down from inside the web: the source goes in the cell
			// above it and flows down into it
			f.pendingWaterTick = NEVER;
			f.waters--;
			f.washTick = tick + WATER_FLOW_TICKS;
			f.attackTicker = -1;
			f.busy = true;
			return;
		}
		if(f.washTick != NEVER)
		{
			f.busy = true;
			if(tick >= f.washTick)
			{
				f.washTick = NEVER;
				wash(f);
				f.pickupTick = tick + 1;
			}
			return;
		}
		if(f.pickupTick == tick)
		{
			// the source back into the bucket
			f.pickupTick = NEVER;
			f.waters++;
			f.attackTicker = -1;
			f.busy = true;
			return;
		}
		if(f.pendingWebTick != NEVER || f.pendingPotTick != NEVER
			|| f.pendingPearlTick != NEVER || f.pendingWindTick != NEVER
			|| f.pendingWaterTick != NEVER)
		{
			f.busy = true;
			return;
		}
		
		// stuck in a web: out of it after a moment - water if the network
		// wants, else hitting it
		int web = webIndexOf(f);
		if(web >= 0)
			f.stuckTotal++;
		if(web < 0)
			f.stuckTicks = f.breakTicks = 0;
		else if(++f.stuckTicks == 1 && webOwner[web] != null
			&& webOwner[web] != f && webOwner[web].alive())
			webOwner[web].candy.trapped(tick, webOwner[web].health);
		if(web >= 0 && f.stuckTicks > f.webReact)
		{
			stopUsing(f);
			f.busy = true;
			if(act.water && f.waters > 0)
			{
				f.pendingWaterTick = tick + 1;
				return;
			}
			// Player.getDestroySpeed: a fifth as fast off the ground (and
			// with the head in water) - a web caught mid-jump takes 40 ticks
			f.breakTicks += f.onGround ? 5 : 1;
			if(f.breakTicks >= WEB_BREAK_TICKS * 5)
			{
				removeWeb(web);
				f.stuckTicks = f.breakTicks = 0;
			}
			return;
		}
		
		// their shield is up toward us, in reach: the axe
		if(f.hasAxe && f.obs.opBlocking && f.obs.opFacing > 0 && f.obs.canHit)
		{
			stopUsing(f);
			f.axeStage = 1;
			f.axeTick = tick;
			f.attackTicker = 0;
			f.busy = true;
			return;
		}
		
		// eating: held for 32 ticks
		if(act.eat && f.gapples > 0 && f.shieldTicks == 0)
		{
			f.busy = true;
			if(++f.eatTicks >= EAT_TICKS)
			{
				f.gapples--;
				if(f.gapples > 0 && f.gapples % 64 == 0)
					f.restockPending++;
				f.gapplesEaten++;
				// FoodData.eat(4, 1.2): +4 food, +9.6 saturation (to the food)
				f.food = Math.min(f.food + 4, 20);
				f.saturation = Math.min(f.saturation + 9.6F, f.food);
				f.absorption = 4;
				f.absorptionTicks = 2400;
				f.regenTicks = 100;
				f.eatTicks = 0;
				f.attackTicker = 0;
			}
			return;
		}
		
		// the shield (offhand, no switch)
		if(act.shield && f.hasShield && f.shieldCooldown == 0
			&& f.eatTicks == 0)
		{
			f.shieldTicks++;
			f.busy = true;
			return;
		}
		
		// letting go of an item takes the tick (vanilla skips the clicks)
		if(f.usingItem())
		{
			stopUsing(f);
			f.busy = true;
			return;
		}
		
		if(act.web && f.webs > 0)
		{
			Snapshot s = op.seen(f.viewDelay);
			int cx = (int)Math.floor(s.x), cz = (int)Math.floor(s.z);
			double dx = cx + 0.5 - f.x, dz = cz + 0.5 - f.z,
				dy = terrain.at(cx, cz) + 0.5 - (f.y + f.eyeHeight());
			if(s.onGround && !webAt(cx, cz)
				&& dx * dx + dy * dy + dz * dz <= ITEM_REACH * ITEM_REACH)
			{
				f.pendingWebTick = tick + 1;
				f.pendingWebX = cx;
				f.pendingWebZ = cz;
				f.busy = true;
				return;
			}
		}
		
		if(act.pot)
		{
			int need = potsNeeded(f);
			if(need != 0)
			{
				f.potQueue = need;
				// standing still first, then the camera straight down
				// (AimAssist: user, 2026-10-26 - turning to pot mid-sprint
				// looked weird)
				f.pendingPotTick = tick + POT_STOP_TICKS;
				f.busy = true;
				return;
			}
		}
		
		// pearling in only from further than it lands short
		if((act.pearlIn && f.obs.dist > PEARL_SHORT + 2 || act.pearlOut)
			&& f.pearls > 0 && f.pearlCooldown == 0 && !f.pearlFlying)
		{
			// out: the camera turns away (2 ticks at most 90 degrees a tick)
			// and back
			f.pearlInPending = act.pearlIn;
			f.pendingPearlTick = tick + (act.pearlIn ? 1 : 2);
			f.busy = true;
			return;
		}
		
		if((act.windJump || act.windHit) && f.winds > 0 && f.windCooldown == 0
			&& !f.windFlying)
		{
			f.windJumpPending = act.windJump;
			f.pendingWindTick = tick + 1;
			f.busy = true;
		}
	}
	
	/**
	 * The potions that are worth throwing now (bits by type): running low
	 * and none of that kind in the air.
	 */
	int potsNeeded(SimFighter f)
	{
		int[] left = {f.pots, f.speedPots, f.fireResPots};
		int need = 0;
		for(int i = 0; i < 3; i++)
			if(left[i] > 0 && f.effectTicks[i] < REPOT_TICKS[i]
				&& f.potLandTick[i] < tick)
				need |= 1 << i;
		return need;
	}
	
	/** One potion of the round, at the feet; the next one next tick. */
	private void throwPot(SimFighter f)
	{
		int type = Integer.numberOfTrailingZeros(f.potQueue);
		f.potQueue &= f.potQueue - 1;
		f.pendingPotTick = f.potQueue != 0 ? tick + 1 : NEVER;
		if(type == STRENGTH)
			f.pots--;
		else if(type == SPEED)
			f.speedPots--;
		else
			f.fireResPots--;
		if((type == STRENGTH ? f.pots
			: type == SPEED ? f.speedPots : f.fireResPots) > 0)
			f.restockPending++;
		f.potsUsed++;
		// pitch 90, 20 degrees up: lands 3 ticks later, carried by the
		// thrower's motion
		f.updateLook();
		f.potLandTick[type] = tick + 3;
		f.potX[type] = f.x + (f.vx - f.sinYaw * 0.17) * 3;
		f.potY[type] = terrain.heightAt(f.potX[type], f.potZ[type], f.y);
		f.potZ[type] = f.z + (f.vz + f.cosYaw * 0.17) * 3;
		f.attackTicker = -1;
		f.busy = true;
	}
	
	private final double[] pearlPlayersX = new double[64],
		pearlPlayersZ = new double[64];
	
	/** ThrownEnderpearl from the eyes (-0.1), toward or away from them. */
	private void throwPearl(SimFighter f)
	{
		f.pendingPearlTick = NEVER;
		SimFighter op = f.target;
		double dx = op.x - f.x, dz = op.z - f.z;
		double d = Math.sqrt(dx * dx + dz * dz);
		if(d < 1e-3)
		{
			dx = 1;
			dz = 0;
			d = 1;
		}
		// TerrainSense plans it over the arena: in, just short of them; out
		// (to heal), as far from everyone as it gets, never into a pit
		if(!f.pearlInPending)
			f.busyUntil = tick + 3;
		int n = 0;
		for(SimFighter g : fighters)
			if(g != f && g.alive())
			{
				pearlPlayersX[n] = g.x;
				pearlPlayersZ[n++] = g.z;
			}
		double eyeY = f.y + f.eyeHeight();
		double fvy = f.onGround ? 0 : f.vy;
		double[] aim = TerrainSense.planPearl(terrain, f.x, eyeY, f.z, f.y,
			f.vx, fvy, f.vz, f.pearlInPending, op.x, op.z, pearlPlayersX,
			pearlPlayersZ, n);
		double ry = Math.toRadians(aim[0]), rp = Math.toRadians(aim[1]);
		double cp = Math.cos(rp) * PEARL_SPEED;
		f.pearlX = f.x;
		f.pearlY = eyeY - 0.1;
		f.pearlZ = f.z;
		f.pearlVx = -Math.sin(ry) * cp + f.vx;
		f.pearlVy = -Math.sin(rp) * PEARL_SPEED + fvy;
		f.pearlVz = Math.cos(ry) * cp + f.vz;
		f.pearlFlying = true;
		f.pearlLow = f.health < 6;
		f.pearlFrom = d;
		f.pearls--;
		f.pearlCooldown = PEARL_COOLDOWN;
		f.attackTicker = -1;
		f.busy = true;
	}
	
	/**
	 * The pitch (degrees, up negative) that throws an ender pearl this far
	 * over flat ground {@code -drop} blocks below where it starts - the
	 * lowest one that reaches. Shared with AimAssist (NeuralItems).
	 */
	public static double pearlPitch(double horiz, double drop)
	{
		for(int p = 45; p > -45; p--)
		{
			if(pearlRange(p, drop) < horiz)
				continue;
			// between this degree and the one above it
			double lo = p, hi = p + 1;
			for(int i = 0; i < 12; i++)
			{
				double mid = (lo + hi) / 2;
				if(pearlRange(mid, drop) >= horiz)
					lo = mid;
				else
					hi = mid;
			}
			return lo;
		}
		return -45;
	}
	
	/** ThrowableProjectile.tick: gravity, drag 0.99, then the move. */
	static double pearlRange(double pitch, double drop)
	{
		double r = Math.toRadians(pitch);
		double vx = Math.cos(r) * PEARL_SPEED, vy = -Math.sin(r) * PEARL_SPEED;
		double x = 0, y = 0;
		for(int t = 0; t < 200; t++)
		{
			vy = (vy - PEARL_GRAVITY) * 0.99;
			vx *= 0.99;
			double ny = y + vy;
			if(ny <= drop)
				return x + vx * (y - drop) / (y - ny);
			x += vx;
			y = ny;
		}
		return x;
	}
	
	/** WindCharge from the eyes: straight down, or at their feet. */
	private void throwWind(SimFighter f)
	{
		f.pendingWindTick = NEVER;
		double eyeY = f.y + f.eyeHeight();
		double dx = 0, dy = -1, dz = 0;
		if(!f.windJumpPending)
		{
			Snapshot s = f.target.seen(f.viewDelay);
			dx = s.x - f.x;
			dy = s.y + 0.1 - eyeY;
			dz = s.z - f.z;
			double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if(l < 1e-6)
				return;
			dx /= l;
			dy /= l;
			dz /= l;
		}
		f.windX = f.x;
		f.windY = eyeY;
		f.windZ = f.z;
		f.windVx = dx * WIND_SPEED + f.vx;
		f.windVy = dy * WIND_SPEED + (f.onGround ? 0 : f.vy);
		f.windVz = dz * WIND_SPEED + f.vz;
		f.windFlying = true;
		f.windAge = 0;
		f.winds--;
		f.windCooldown = WIND_COOLDOWN;
		f.attackTicker = -1;
		f.busy = true;
	}
	
	/**
	 * The pearl's tick; landed, it takes its owner there. A player in its
	 * way stops it (ThrownEnderpearl.onHitEntity: no damage to them) and
	 * the owner lands there instead.
	 */
	void stepPearl(SimFighter f)
	{
		f.pearlVy = (f.pearlVy - PEARL_GRAVITY) * 0.99;
		f.pearlVx *= 0.99;
		f.pearlVz *= 0.99;
		double ny = f.pearlY + f.pearlVy;
		double nx = f.pearlX + f.pearlVx, nz = f.pearlZ + f.pearlVz;
		for(SimFighter g : fighters)
		{
			if(g == f || !g.alive())
				continue;
			double t = segmentHitsBox(f.pearlX, f.pearlY, f.pearlZ, nx, ny, nz,
				g, 0.125);
			if(t < 0)
				continue;
			f.pearlFlying = false;
			if(f.alive())
				teleport(f, f.pearlX + f.pearlVx * t,
					f.pearlY + (ny - f.pearlY) * t, f.pearlZ + f.pearlVz * t);
			return;
		}
		double ground = terrain.heightAt(nx, nz, ny);
		if(ny > ground)
		{
			f.pearlX = nx;
			f.pearlY = ny;
			f.pearlZ = nz;
			return;
		}
		f.pearlFlying = false;
		if(!f.alive())
			return;
		// into a wall's side: there, in the air; else onto the ground
		if(ground - f.pearlY > 0.5)
		{
			teleport(f, f.pearlX, f.pearlY, f.pearlZ);
			return;
		}
		double k = (f.pearlY - ground) / (f.pearlY - ny);
		teleport(f, f.pearlX + f.pearlVx * k, ground, f.pearlZ + f.pearlVz * k);
	}
	
	/** ThrownEnderpearl.onHit: there, the fall reset, 5 damage. */
	void teleport(SimFighter f, double x, double y, double z)
	{
		double nearest = Double.MAX_VALUE;
		for(SimFighter g : fighters)
			if(g != f && g.alive())
				nearest = Math.min(nearest, length(g.x - x, g.z - z));
		if(f.pearlInPending && f.pearlFrom > 12 && nearest < 5)
			f.goodPearlsIn++;
		if(!f.pearlInPending && f.pearlLow && nearest >= PEARL_AWAY)
			f.goodPearlsOut++;
		if(!f.pearlInPending && f.pearlLow)
			for(SimFighter g : fighters)
				if(g.brute && g != f)
					g.waitFor = f;
		f.x = x;
		f.y = y;
		f.z = z;
		f.vx = f.vy = f.vz = 0;
		f.svx = f.svz = 0;
		// standing on what's under the box (an edge, a wall's top)
		double floor = terrain.groundUnder(x, z);
		if(f.y < floor)
			f.y = floor;
		f.onGround = f.y <= floor + 1e-6;
		f.svy = f.onGround ? SERVER_GROUND_VY : 0;
		f.fallDistance = 0;
		hurtPlain(f, PEARL_DAMAGE);
	}
	
	/** The wind charge's tick: a fighter in its way, or the ground. */
	void stepWind(SimFighter f)
	{
		double nx = f.windX + f.windVx, ny = f.windY + f.windVy,
			nz = f.windZ + f.windVz;
		for(SimFighter g : fighters)
		{
			// never the thrower: it starts inside its box and never left it
			if(g == f || !g.alive())
				continue;
			double t = segmentHitsBox(f.windX, f.windY, f.windZ, nx, ny, nz, g,
				0.15625);
			if(t < 0)
				continue;
			f.windFlying = false;
			int r = hurt(g, f, 1);
			if(r != HURT_NONE)
			{
				g.candy.hurt(tick);
				f.candy.active(tick);
			}
			windBurst(f.windX + (nx - f.windX) * t,
				f.windY + (ny - f.windY) * t, f.windZ + (nz - f.windZ) * t);
			return;
		}
		double ground = terrain.heightAt(nx, nz, ny);
		if(ny <= ground)
		{
			f.windFlying = false;
			// a wall's side: where it is; else on the ground (+0.25)
			if(ground - f.windY > 0.5)
			{
				windBurst(f.windX, f.windY, f.windZ);
				return;
			}
			double k = (f.windY - ground) / (f.windY - ny);
			windBurst(f.windX + (nx - f.windX) * k, ground + 0.25,
				f.windZ + (nz - f.windZ) * k);
			return;
		}
		f.windX = nx;
		f.windY = ny;
		f.windZ = nz;
		if(++f.windAge > 40)
			f.windFlying = false;
	}
	
	/**
	 * Where along a step (0..1) a projectile (half as wide as given) first
	 * touches a standing fighter's box, or -1.
	 */
	static double segmentHitsBox(double x0, double y0, double z0, double x1,
		double y1, double z1, SimFighter g, double half)
	{
		double r = 0.3 + half, h = SimFighter.height(g.sneaking);
		double[] lo = {g.x - r, g.y - half, g.z - r};
		double[] hi = {g.x + r, g.y + h + half, g.z + r};
		double[] p = {x0, y0, z0}, d = {x1 - x0, y1 - y0, z1 - z0};
		double tMin = 0, tMax = 1;
		for(int i = 0; i < 3; i++)
		{
			if(Math.abs(d[i]) < 1e-9)
			{
				if(p[i] < lo[i] || p[i] > hi[i])
					return -1;
				continue;
			}
			double t1 = (lo[i] - p[i]) / d[i], t2 = (hi[i] - p[i]) / d[i];
			tMin = Math.max(tMin, Math.min(t1, t2));
			tMax = Math.min(tMax, Math.max(t1, t2));
			if(tMin > tMax)
				return -1;
		}
		return tMin;
	}
	
	/**
	 * ServerExplosion.hurtEntities for a wind charge: everyone within 2.4
	 * blocks (feet) is pushed from the burst toward their eyes by 1.22 x (1 -
	 * distance / 2.4). The server pushes its copy; the player gets the push
	 * in the explosion packet and adds it to its own velocity.
	 */
	void windBurst(double cx, double cy, double cz)
	{
		for(SimFighter g : fighters)
		{
			if(!g.alive())
				continue;
			double dx = g.x - cx, dy = g.y - cy, dz = g.z - cz;
			double dist =
				Math.sqrt(dx * dx + dy * dy + dz * dz) / (2 * WIND_RADIUS);
			if(dist > 1)
				continue;
			double ey = g.y + g.eyeHeight() - cy;
			double len = Math.sqrt(dx * dx + ey * ey + dz * dz);
			if(len < 1e-6)
				continue;
			double k = (1 - dist) * WIND_KNOCKBACK / len;
			g.svx += dx * k;
			g.svy += ey * k;
			g.svz += dz * k;
			g.blastPending = true;
			// ServerPlayer.onExplosionHit: no fall damage from this launch
			g.windLaunched = true;
			g.blastX += dx * k;
			g.blastY += ey * k;
			g.blastZ += dz * k;
		}
	}
	
	/** The water reached the web: every web this fighter is in is gone. */
	void wash(SimFighter f)
	{
		int web;
		while((web = webIndexOf(f)) >= 0)
			removeWeb(web);
		f.washes++;
		f.stuckTicks = f.breakTicks = 0;
		f.fireTicks = 0;
		// in the water until it's picked up and has drained away
		f.waterTicks = 3;
	}
	
	void removeWeb(int web)
	{
		webCount--;
		webX[web] = webX[webCount];
		webZ[web] = webZ[webCount];
		webOwner[web] = webOwner[webCount];
		webY[web] = webY[webCount];
		webOwner[webCount] = null;
		webVersion++;
	}
	
	private static void stopUsing(SimFighter f)
	{
		if(f.eatTicks > 0)
			f.attackTicker = 0;
		f.eatTicks = 0;
		f.shieldTicks = 0;
	}
	
	/**
	 * A buff potion landing (ThrownSplashPotion.applySplash): x (1 -
	 * distance / 4) of its time, if that's over a second, replacing a
	 * shorter one.
	 */
	void splash(SimFighter thrower, int type)
	{
		for(SimFighter g : fighters)
		{
			if(!g.alive())
				continue;
			double dx = g.x - thrower.potX[type], dy = g.y - thrower.potY[type],
				dz = g.z - thrower.potZ[type];
			double d2 = dx * dx + dy * dy + dz * dz;
			if(d2 >= 16)
				continue;
			int ticks = (int)((1 - Math.sqrt(d2) / 4) * POT_TICKS[type] + 0.5);
			if(ticks > 20)
				g.effectTicks[type] = Math.max(g.effectTicks[type], ticks);
		}
	}
	
	/** Player.causeFoodExhaustion. */
	static void exhaust(SimFighter f, float amount)
	{
		f.exhaustion = Math.min(f.exhaustion + amount, 40);
	}
	
	/**
	 * FoodData.tick (difficulty Normal, natural regeneration on): every 4
	 * exhaustion costs a saturation point, else a food point; full food with
	 * saturation heals up to 1 every 10 ticks (6 exhaustion per health
	 * point), 18+ food 1 every 80 ticks; no food starves down to 1.
	 */
	void foodTick(SimFighter f)
	{
		if(f.health <= 0)
			return;
		if(f.exhaustion > 4)
		{
			f.exhaustion -= 4;
			if(f.saturation > 0)
				f.saturation = Math.max(f.saturation - 1, 0);
			else
				f.food = Math.max(f.food - 1, 0);
		}
		boolean hurt = f.health < 20;
		if(f.saturation > 0 && hurt && f.food >= 20)
		{
			if(++f.foodTimer >= 10)
			{
				float used = Math.min(f.saturation, 6);
				heal(f, used / 6);
				exhaust(f, used);
				f.foodTimer = 0;
			}
		}else if(f.food >= 18 && hurt)
		{
			if(++f.foodTimer >= 80)
			{
				heal(f, 1);
				exhaust(f, 6);
				f.foodTimer = 0;
			}
		}else if(f.food <= 0)
		{
			if(++f.foodTimer >= 80)
			{
				if(f.health > 1)
					hurtPlain(f, 1);
				f.foodTimer = 0;
			}
		}else
			f.foodTimer = 0;
	}
	
	static void heal(SimFighter f, float amount)
	{
		if(amount <= 0 || f.health <= 0)
			return;
		float h = Math.min(20, f.health + amount);
		f.healed += h - f.health;
		f.health = h;
	}
	
	// ── observation ──────────────────────────────────────────────────────
	
	void observe(SimFighter me, SimFighter op)
	{
		Snapshot s = op.seen(me.viewDelay);
		NeuralObservation o = me.obs;
		
		if(!me.trackValid)
		{
			me.motX = me.motY = me.motZ = 0;
			me.trackValid = true;
		}else
		{
			double sx = s.x - me.trackX, sy = s.y - me.trackY,
				sz = s.z - me.trackZ;
			me.motX = me.motX * 0.5 + sx * 0.5;
			me.motY = me.motY * 0.5 + sy * 0.5;
			me.motZ = me.motZ * 0.5 + sz * 0.5;
		}
		me.trackX = s.x;
		me.trackY = s.y;
		me.trackZ = s.z;
		
		double eyeY = me.y + me.eyeHeight();
		double opH = SimFighter.height(s.sneaking);
		o.dist = boxDistance(me.x, eyeY, me.z, s.x, s.y, s.z, opH);
		double opEyeY = s.y + (s.sneaking ? 1.27 : 1.62);
		o.theirReach = boxDistance(s.x, opEyeY, s.z, me.x, me.y, me.z,
			SimFighter.height(me.sneaking));
		o.dy = s.y - me.y;
		
		double ax = s.x - me.x, az = s.z - me.z;
		double len = Math.sqrt(ax * ax + az * az);
		if(len < 1e-4)
		{
			ax = 1;
			az = 0;
		}else
		{
			ax /= len;
			az /= len;
		}
		double sx = -az, sz = ax;
		o.myAlong = me.vx * ax + me.vz * az;
		o.mySide = me.vx * sx + me.vz * sz;
		o.myVy = me.vy;
		o.opAlong = -(me.motX * ax + me.motZ * az);
		o.opSide = me.motX * sx + me.motZ * sz;
		o.opVy = me.motY;
		observeSpace(me, op, o, ax, az);
		o.myGround = me.onGround;
		o.opGround = s.onGround;
		o.myChargeTicks = me.ticksUntilCharged();
		o.opChargeTicks = s.lastSwingTick == NEVER ? 0 : NeuralObservation
			.opChargeFromSwing(tick - s.lastSwingTick, COOLDOWN);
		o.myHurt = me.hurtTime;
		o.opHurt = s.hurtTime;
		o.mySprint = me.serverSprint;
		o.opSprint = s.sprint;
		o.myHealth = me.health / 20;
		o.myFood = me.food;
		o.mySaturation = me.saturation;
		o.opHealth = op.health / 20;
		o.critReady = me.critReady();
		o.canHit = rayHits(me, s.x, s.y, s.z, opH, 0.03);
		
		double cy = s.y + opH / 2 - eyeY;
		me.updateLook();
		double lookX = me.lookX, lookY = me.lookY, lookZ = me.lookZ;
		double tx = s.x - me.x, tz = s.z - me.z;
		double tl = Math.sqrt(tx * tx + cy * cy + tz * tz);
		double dot =
			tl < 1e-6 ? 1 : (lookX * tx + lookY * cy + lookZ * tz) / tl;
		o.aimError =
			Math.toDegrees(FastTrig.acos(Math.max(-1, Math.min(1, dot))));
		
		o.opFacing = len < 1e-4 ? 1 : -(s.faceX * ax + s.faceZ * az);
		
		o.sinceMyHit = Math.min(1000, tick - me.lastHitTick);
		o.sinceTheirHit = Math.min(1000, tick - me.lastHurtTick - 1);
		o.sneaking = me.sneaking;
		
		o.myInWeb = inWeb(me);
		o.opInWeb = s.inWeb;
		o.myBlocking = me.blocking();
		o.opBlocking = s.blocking;
		o.hasShield = me.hasShield;
		o.opHasShield = op.hasShield;
		o.myEating = me.eatTicks / (double)EAT_TICKS;
		o.opUsing = s.using;
		o.websLeft = me.webs;
		o.potsLeft = me.pots;
		o.gapplesLeft = me.gapples;
		o.absorption = me.absorption;
		o.regenerating = me.regenTicks > 0;
		o.myStrength = me.effectTicks[STRENGTH] / (double)POT_TICKS[STRENGTH];
		o.mySpeed = me.effectTicks[SPEED] / (double)POT_TICKS[SPEED];
		// the client never learns another player's effects: not here either
		o.opStrength = o.opSpeed = false;
		o.speedPotsLeft = me.speedPots;
		o.pearlsLeft = me.pearls;
		o.windLeft = me.winds;
		o.waterLeft = me.waters;
		o.pearlCooldown = me.pearlCooldown;
		o.windCooldown = me.windCooldown;
		o.pearlFlying = me.pearlFlying;
		o.myOnFire = me.fireTicks > 0;
		o.opOnFire = op.fireTicks > 0;
		o.myFireRes = me.effectTicks[FIRE_RES] / (double)POT_TICKS[FIRE_RES];
		o.myShieldCooldown = me.shieldCooldown;
		o.itemBusy = me.busy;
		// what the policy chose last tick (act() overwrites it next)
		NeuralAction last = me.action;
		o.lastForward = last.forward;
		o.lastStrafe = last.strafe;
		o.lastJump = last.jump;
		o.lastSprint = last.sprint;
		o.lastAttack = last.attack;
		System.arraycopy(last.memory, 0, o.memory, 0, NeuralAction.MEMORY);
		o.opWeapon = op.swordDamage - me.swordDamage;
		o.opToughness = toughnessOf(op) - toughnessOf(me);
		o.setGear(me.gear(), op.gear());
		o.opTotem = op.totem;
		
		// ECHO's (version 12), from the same delayed view of them
		o.myPitch = me.pitch;
		o.opPitch = s.pitch;
		double myH = SimFighter.height(me.sneaking);
		o.aimYawErr = EchoFeatures.yawError(me.x, me.z, me.yaw, s.x, s.z);
		o.aimPitchErr = EchoFeatures.pitchError(me.x, eyeY, me.z, me.pitch, s.x,
			s.y + opH / 2, s.z);
		o.opAimYawErr = EchoFeatures.yawError(s.x, s.z, s.yaw, me.x, me.z);
		o.opAimPitchErr = EchoFeatures.pitchError(s.x, opEyeY, s.z, s.pitch,
			me.x, me.y + myH / 2, me.z);
		o.opAimError = EchoFeatures.aimAngle(s.x, opEyeY, s.z, s.lookX, s.lookY,
			s.lookZ, me.x, me.y + myH / 2, me.z);
		o.opOnUs = EchoFeatures.rayHitsBox(s.x, opEyeY, s.z, s.lookX, s.lookY,
			s.lookZ, me.x, me.y, me.z, myH, REACH);
		o.myFall = me.fallDistance;
		o.opFall = s.fallDistance;
		o.myWeapon = !me.busy;
		o.opSneaking = s.sneaking;
		// their swing shows the first tick their snapshot with it is seen
		int seenTick = tick - 1 - me.viewDelay;
		o.sinceTheirSwing = s.lastSwingTick == NEVER ? 1000
			: Math.min(1000, seenTick - s.lastSwingTick);
		o.opSwung = o.sinceTheirSwing == 0;
		o.sinceMySwing = me.lastSwingTick == NEVER ? 1000
			: Math.min(1000, tick - me.lastSwingTick);
		double ex = s.x - me.x, ey = opEyeY - eyeY, ez = s.z - me.z;
		o.eyeDist = Math.sqrt(ex * ex + ey * ey + ez * ez);
		// the view delay is the latency: ~half a round trip a tick late
		o.myPing = 25 + 50 * me.viewDelay;
		o.opPing = 25 + 50 * op.viewDelay;
		o.tickMs = 50;
		if(me.echoTarget != op)
		{
			me.echoTarget = op;
			me.echo.reset(new EchoFeatures.OpponentModel());
		}
		me.echo.update(o);
	}
	
	double toughnessOf(SimFighter f)
	{
		return f.armor <= 0 ? 0
			: f.netherite ? NETHERITE_TOUGHNESS : f.toughness;
	}
	
	/** Webs, wind charges, their pearl and other fighters around you. */
	void observeSpace(SimFighter me, SimFighter op, NeuralObservation o,
		double ax, double az)
	{
		double webDx = Double.NaN, webDz = Double.NaN;
		double best =
			NeuralObservation.SPACE_RANGE * NeuralObservation.SPACE_RANGE;
		for(int i = 0; i < webCount; i++)
		{
			double dx = webX[i] + 0.5 - me.x, dz = webZ[i] + 0.5 - me.z;
			double d2 = dx * dx + dz * dz;
			if(d2 < best)
			{
				best = d2;
				webDx = dx;
				webDz = dz;
			}
		}
		
		double windDx = Double.NaN, windDz = Double.NaN;
		double otherDx = Double.NaN, otherDz = Double.NaN;
		double bestWind = Double.MAX_VALUE, bestOther =
			NeuralObservation.OTHER_RANGE * NeuralObservation.OTHER_RANGE;
		for(SimFighter g : fighters)
		{
			if(g == me)
				continue;
			if(g.windFlying)
			{
				double dx = g.windX - me.x, dz = g.windZ - me.z;
				double d2 = dx * dx + dz * dz;
				if(d2 < bestWind)
				{
					bestWind = d2;
					windDx = dx;
					windDz = dz;
				}
			}
			if(g != op && g.alive())
			{
				double dx = g.x - me.x, dz = g.z - me.z;
				double d2 = dx * dx + dz * dz;
				if(d2 < bestOther)
				{
					bestOther = d2;
					otherDx = dx;
					otherDz = dz;
				}
			}
		}
		
		double pearlDx = Double.NaN, pearlDz = Double.NaN;
		if(op.pearlFlying)
		{
			double[] land = TerrainSense.pearlLanding(terrain, op.pearlX,
				op.pearlY, op.pearlZ, op.pearlVx, op.pearlVy, op.pearlVz, op.y);
			if(land != null)
			{
				pearlDx = land[0] - me.x;
				pearlDz = land[2] - me.z;
			}
		}
		o.setSpace(ax, az, webDx, webDz, windDx, windDz, pearlDx, pearlDz,
			otherDx, otherDz, me.y - terrain.groundUnder(me.x, me.z));
		// the terrain and the focus map: every 3rd-4th tick, like in game
		if(!o.isSpaceDue(tick))
			return;
		TerrainSense.sense(terrain, me.x, me.y, me.z, ax, az, o.terrain, 0);
		if(o.isFarDue())
			TerrainSense.senseFar(terrain, me.x, me.y, me.z, ax, az,
				o.terrainFar, 0);
			
		// and everything as FocusMap's cells: players, projectiles, webs,
		// totem pops in the arena
		FocusMap m = me.focusMap;
		m.clear(me.x, me.z, ax, az, FOCUS_RANGE);
		for(SimFighter g : fighters)
		{
			if(g != me && g.alive())
				m.add(g.x, g.z, FocusMap.PLAYER, g.health / 20);
			if(g.pearlFlying)
				m.add(g.pearlX, g.pearlZ, FocusMap.PROJECTILE, 0);
			if(g.windFlying)
				m.add(g.windX, g.windZ, FocusMap.PROJECTILE, 0);
			for(int i = 0; i < 3; i++)
				if(g.potLandTick[i] >= tick)
					m.add(g.potX[i], g.potZ[i], FocusMap.PROJECTILE, 0);
			if(tick - g.totemTick < FocusMap.TOTEM_TICKS)
				m.add(g.x, g.z, FocusMap.TOTEM, 0);
		}
		// the webs AimAssist sees: around your feet (NeuralSpace)
		int fx = (int)Math.floor(me.x), fy = (int)Math.floor(me.y),
			fz = (int)Math.floor(me.z);
		for(int i = 0; i < webCount; i++)
			if(Math.abs(webX[i] - fx) <= FocusMap.WEB_SCAN
				&& Math.abs(webZ[i] - fz) <= FocusMap.WEB_SCAN
				&& Math.abs(webY[i] - fy) <= 1)
				m.add(webX[i] + 0.5, webZ[i] + 0.5, FocusMap.WEB, 0);
		m.write(o.focus, 0);
	}
	
	/** How far FocusMap looks in the arena (2 chunks across). */
	static final double FOCUS_RANGE = 32;
	
	/**
	 * Where an ender pearl at x, y, z moving vx, vy, vz comes down to
	 * groundY (ThrowableProjectile's tick): {x, z}.
	 */
	public static double[] pearlLanding(double x, double y, double z, double vx,
		double vy, double vz, double groundY)
	{
		for(int t = 0; t < 200 && y > groundY; t++)
		{
			vy = (vy - PEARL_GRAVITY) * 0.99;
			vx *= 0.99;
			vz *= 0.99;
			x += vx;
			y += vy;
			z += vz;
		}
		return new double[]{x, z};
	}
	
	/** From a point to the nearest point of a standing player's box. */
	static double boxDistance(double px, double py, double pz, double bx,
		double by, double bz, double h)
	{
		double dx = Math.max(Math.max(bx - 0.3 - px, px - (bx + 0.3)), 0);
		double dy = Math.max(Math.max(by - py, py - (by + h)), 0);
		double dz = Math.max(Math.max(bz - 0.3 - pz, pz - (bz + 0.3)), 0);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
	
	/**
	 * Whether the look ray hits the box within reach - shrunk by
	 * {@code inset} at the sides, like HitCheck's margin.
	 */
	static boolean rayHits(SimFighter me, double bx, double by, double bz,
		double h, double inset)
	{
		me.updateLook();
		double w = 0.3 - inset;
		double[] t = me.rayT;
		t[0] = 0;
		t[1] = me.reach;
		return slab(t, me.x, me.lookX, bx - w, bx + w) && slab(t,
			me.y + me.eyeHeight(), me.lookY, by + inset, by + h - inset)
			&& slab(t, me.z, me.lookZ, bz - w, bz + w);
	}
	
	/** One axis of the ray-box test; t = {tMin, tMax}, narrowed. */
	private static boolean slab(double[] t, double o, double d, double lo,
		double hi)
	{
		if(Math.abs(d) < 1e-9)
			return o >= lo && o <= hi;
		double t1 = (lo - o) / d, t2 = (hi - o) / d;
		if(t1 > t2)
		{
			double tmp = t1;
			t1 = t2;
			t2 = tmp;
		}
		t[0] = Math.max(t[0], t1);
		t[1] = Math.min(t[1], t2);
		return t[0] <= t[1];
	}
	
	// ── combat ───────────────────────────────────────────────────────────
	
	void attackPhase(SimFighter f)
	{
		// the shield breaker hits by itself, at any charge
		boolean axe = f.axeStage == 1;
		if(axe)
		{
			if(!f.obs.canHit)
				return;
		}else
		{
			if(!f.action.attack || !f.obs.canHit || f.busy)
				return;
			// a sprint hit or a crit - both only count charged (> 90%),
			// like AimAssist (it never checked: uncharged hits every 1-4
			// ticks in real plain fights, 2026-10-31)
			if(f.onlyGoodHits && (f.strength(0.5) <= 0.9F
				|| !f.serverSprint && !f.critReady()))
				return;
		}
		
		SimFighter t = f.target;
		f.swings++;
		f.lastSwingTick = tick;
		float s = axe
			? (float)Math.min(1,
				Math.max(0, (f.attackTicker + 0.5) / AXE_COOLDOWN))
			: f.strength(0.5);
		f.attackTicker = 0;
		if(axe)
			f.axeStage = 2;
		
		// the server checks the real position (a little lenient)
		double eyeY = f.y + f.eyeHeight();
		if(boxDistance(f.x, eyeY, f.z, t.x, t.y, t.z,
			SimFighter.height(t.sneaking)) > f.reach + 0.1)
			return;
			
		// a raised shield facing the attacker blocks it all: blockedByItem
		// and the hurt knockback only change the server's velocity (no
		// markHurt, so no velocity packet), the attacker gets nothing, and the
		// defender still gets its hurt cooldown (lastHurt 0)
		if(t.blocking())
		{
			t.updateLook();
			if(-t.sinYaw * (f.x - t.x) + t.cosYaw * (f.z - t.z) > 0)
			{
				t.blocked++;
				serverKnockback(t, 0.5, t.x - f.x, t.z - f.z);
				if(axe)
				{
					// BlocksAttacks.disable: on cooldown, lowered
					t.shieldCooldown = SHIELD_DISABLE_TICKS;
					t.shieldTicks = 0;
				}
				if(t.invulnerableTime <= 10)
				{
					t.lastHurt = 0;
					t.invulnerableTime = 20;
					serverKnockback(t, 0.4F, f.x - t.x, f.z - t.z);
				}
				return;
			}
		}
		
		// Player.attack: the attribute damage scaled by the charge, x1.5 for
		// a crit, then Sharpness x the charge
		float base = (axe ? AXE_DAMAGE : f.swordDamage)
			+ (f.effectTicks[STRENGTH] > 0 ? f.strengthBonus : 0);
		float damage = base * (0.2F + s * s * 0.8F);
		boolean charged = s > 0.9F;
		boolean sprintHit = charged && f.serverSprint;
		boolean crit = charged && f.critReady();
		if(crit)
			damage *= 1.5F;
		if(!axe)
			damage += f.sharpness * s;
			
		// Player.attack: the target's server velocity before the hit, restored
		// after the velocity packet is sent
		double oldX = t.svx, oldY = t.svy, oldZ = t.svz;
		int result = hurt(t, f, damage);
		if(result != HURT_NONE)
		{
			// Player.attack: 0.1 for a hit that did damage
			exhaust(f, 0.1F);
			t.candy.hurt(tick);
			if(result == HURT_FULL && charged)
				f.candy.hit(
					crit ? Candy.CRIT
						: sprintHit ? Candy.SPRINT : Candy.REGULAR,
					tick, f.health, inWeb(t));
			else
				f.candy.active(tick);
		}
		if(result == HURT_NONE)
			return;
		if(!axe)
			t.fireTicks = Math.max(t.fireTicks, f.fireAspectTicks);
		if(sprintHit)
			f.sprintHits++;
			
		// causeExtraKnockback: Knockback (I: 0.5), +0.5 for a sprint hit (on
		// the
		// server only - the attacker's client runs hurtClient, which does
		// nothing: no slowdown, sprint stays on until the server's sprint
		// flag arrives)
		double extra =
			(axe || f.brute ? 0 : f.knockbackBonus) + (sprintHit ? 0.5 : 0);
		if(extra > 0)
		{
			f.updateLook();
			serverKnockback(t, extra, f.sinYaw, -f.cosYaw);
			f.svx *= 0.6;
			f.svz *= 0.6;
			f.serverSprint = false;
			f.sprintFlagCleared = true;
		}
		if(result == HURT_FULL)
		{
			// markHurt: the velocity goes to the victim, the server's own is
			// put back
			t.kbPending = true;
			t.kbx = t.svx;
			t.kby = t.svy;
			t.kbz = t.svz;
			t.svx = oldX;
			t.svy = oldY;
			t.svz = oldZ;
		}
		if(result != HURT_FULL)
			return;
		
		f.hits++;
		f.lastHitTick = tick;
		f.combo++;
		f.maxCombo = Math.max(f.maxCombo, f.combo);
		t.combo = 0;
		if(crit)
			f.crits++;
	}
	
	static final int HURT_NONE = 0, HURT_PARTIAL = 1, HURT_FULL = 2;
	
	/**
	 * LivingEntity.hurtServer: nothing, a partial hit during the hurt
	 * cooldown (damage above the last hit's, no knockback, no velocity
	 * packet) or a full hit.
	 */
	int hurt(SimFighter t, SimFighter from, float damage)
	{
		if(t.invulnerableTime > 10)
		{
			if(damage <= t.lastHurt)
				return HURT_NONE;
			float hit = afterProtection(t, afterArmor(t, damage - t.lastHurt));
			t.taken += hit;
			float dealt = absorb(t, hit);
			// Player.actuallyHurt: 0.1 when it got past the absorption
			if(dealt > 0)
				exhaust(t, 0.1F);
			t.lastHurt = damage;
			t.health -= dealt;
			from.damageDealt += dealt;
			checkTotem(t);
			return HURT_PARTIAL;
		}
		
		t.lastHurt = damage;
		t.invulnerableTime = 20;
		float hit = afterProtection(t, afterArmor(t, damage));
		t.taken += hit;
		float dealt = absorb(t, hit);
		if(dealt > 0)
			exhaust(t, 0.1F);
		t.health -= dealt;
		from.damageDealt += dealt;
		checkTotem(t);
		t.hurtTime = 10;
		t.lastHurtTick = tick;
		serverKnockback(t, 0.4F, from.x - t.x, from.z - t.z);
		return HURT_FULL;
	}
	
	/**
	 * Damage with no attacker behind it (fire, a pearl - both skip the
	 * armor): the hurt cooldown like any other, no knockback.
	 */
	void hurtPlain(SimFighter t, float damage)
	{
		if(t.invulnerableTime > 10)
		{
			if(damage <= t.lastHurt)
				return;
			float hit = afterProtection(t, damage - t.lastHurt);
			t.taken += hit;
			t.health -= absorb(t, hit);
			t.lastHurt = damage;
			checkTotem(t);
			return;
		}
		t.lastHurt = damage;
		t.invulnerableTime = 20;
		float hit = afterProtection(t, damage);
		t.taken += hit;
		t.health -= absorb(t, hit);
		checkTotem(t);
		t.hurtTime = 10;
	}
	
	/**
	 * LivingEntity.checkTotemDeathProtection: at 1 health, every effect
	 * gone, then Regeneration II 45 s, Absorption II 5 s, Fire Resistance
	 * 40 s.
	 */
	static void checkTotem(SimFighter t)
	{
		if(t.health > 0 || !t.totem)
			return;
		t.totem = false;
		t.health = 1;
		java.util.Arrays.fill(t.effectTicks, 0);
		t.strengthBonus = STRENGTH_II;
		t.resistance = 0;
		t.regenTicks = 900;
		t.absorption = 8;
		t.absorptionTicks = 100;
		t.effectTicks[FIRE_RES] = 800;
		t.totemsPopped++;
		t.totemTick = t.sim.tick;
		// AutoTotem: the next one into the offhand
		if(t.spareTotems > 0)
		{
			t.spareTotems--;
			t.totem = true;
		}
		if(t.brute)
			t.sim.bruteEffects(t);
	}
	
	/**
	 * The strong opponent (user, 2026-10-25/26), one to run from: a maxed
	 * netherite sword (no Knockback) and armor, Strength III, Resistance
	 * III, Regeneration III and Speed V all fight (back after a totem
	 * pops), 2 totems with AutoTotem (so no shield), no pearls. The trainer
	 * gives it the candidate's own skill.
	 */
	void makeBrute(SimFighter f)
	{
		f.brute = true;
		f.swordDamage = NETHERITE_SWORD;
		f.netherite = true;
		bruteEffects(f);
		f.totem = true;
		f.spareTotems = 1;
		f.hasShield = false;
		f.pearls = 0;
	}
	
	void bruteEffects(SimFighter f)
	{
		f.strengthBonus = STRENGTH_III;
		f.effectTicks[STRENGTH] = maxTicks + 1;
		f.resistance = 3;
		f.speedLevel = 5;
	}
	
	/**
	 * The brute doesn't come after one who pearled away on low health
	 * until they're within BRUTE_WAIT again (user, 2026-10-26).
	 */
	void holdBack(SimFighter f)
	{
		SimFighter w = f.waitFor;
		if(w == null)
			return;
		if(!w.alive() || length(w.x - f.x, w.z - f.z) <= BRUTE_WAIT)
		{
			f.waitFor = null;
			return;
		}
		if(f.target == w)
			f.action.clear();
	}
	
	/**
	 * LivingEntity.getDamageAfterMagicAbsorb: Resistance (x (1 - 0.2 a
	 * level)), then Protection (CombatRules.getDamageAfterMagicAbsorb).
	 */
	float afterProtection(SimFighter t, float damage)
	{
		damage *= 1 - 0.2F * t.resistance;
		return (float)(damage * (1 - Math.min(20, t.protection) / 25));
	}
	
	/** Absorption hearts take damage first; returns what's left. */
	static float absorb(SimFighter t, float damage)
	{
		float a = Math.min(t.absorption, damage);
		t.absorption -= a;
		return damage - a;
	}
	
	float afterArmor(SimFighter t, float damage)
	{
		if(t.armor <= 0)
			return damage;
		double f = 2 + (t.netherite ? NETHERITE_TOUGHNESS : t.toughness) / 4;
		double g = Math.min(20, Math.max(t.armor / 5, t.armor - damage / f));
		return (float)(damage * (1 - g / 25));
	}
	
	/** The server's velocity of a player standing on the ground. */
	static final double SERVER_GROUND_VY = (0 - 0.08) * 0.98F;
	
	/**
	 * LivingEntity.knockback on the server's velocity (onGround is the
	 * client's last movement packet's).
	 */
	static void serverKnockback(SimFighter t, double strength, double x,
		double z)
	{
		// knockback resistance (netherite armor: 0.1 a piece)
		strength *= 1 - (t.netherite ? 0.4 : 0);
		double len = Math.sqrt(x * x + z * z);
		if(len < 1e-5 || strength <= 0)
			return;
		double kx = x / len * strength, kz = z / len * strength;
		t.svx = t.svx / 2 - kx;
		t.svz = t.svz / 2 - kz;
		if(t.onGround)
			t.svy = Math.min(0.4, t.svy / 2 + strength);
	}
	
	/** The velocity packet arrives before the victim's tick. */
	static void applyKnockback(SimFighter t)
	{
		if(t.kbPending)
		{
			t.kbPending = false;
			t.vx = t.kbx;
			t.vy = t.kby;
			t.vz = t.kbz;
		}
		if(t.blastPending)
		{
			t.blastPending = false;
			t.vx += t.blastX;
			t.vy += t.blastY;
			t.vz += t.blastZ;
			t.blastX = t.blastY = t.blastZ = 0;
		}
		if(t.sprintFlagCleared)
		{
			t.sprintFlagCleared = false;
			t.sprinting = false;
		}
	}
	
	/**
	 * The server's own tick of a player: travel with no input from the
	 * client's position (aiStep's 0.003 cut-off, ground friction 0.6 x 0.91,
	 * air 0.91, gravity, x0.98 - on the ground the fall is stopped first).
	 */
	static void serverTravel(SimFighter f)
	{
		if(Math.abs(f.svx) < 0.003)
			f.svx = 0;
		if(Math.abs(f.svz) < 0.003)
			f.svz = 0;
		if(Math.abs(f.svy) < 0.003)
			f.svy = 0;
		if(f.onGround && f.svy < 0)
			f.svy = 0;
		double h = f.onGround ? GROUND_FRICTION : AIR_FRICTION;
		f.svx *= h;
		f.svz *= h;
		f.svy = (f.svy - 0.08) * 0.98F;
	}
	
	static final double GROUND_FRICTION = 0.6F * 0.91F;
	static final double AIR_FRICTION = 0.91F;
	
	// ── movement ─────────────────────────────────────────────────────────
	
	void move(SimFighter f)
	{
		NeuralAction act = f.action;
		f.sneaking = act.sneak;
		boolean using = f.usingItem();
		boolean web = inWeb(f);
		// throwing potions or restocking: no keys at all, like AimAssist
		boolean potting = f.potQueue != 0 || tick < f.restockUntil;
		
		// LocalPlayer.aiStep: sprint
		// (isSprintingPossible: more than 6 food)
		boolean fed = f.food > 6;
		if(f.sprinting
			&& (act.forward <= 0 || f.sneaking || using || potting || !fed))
			f.sprinting = false;
		else if(!f.sprinting && act.sprint && act.forward > 0 && !f.sneaking
			&& !using && !potting && fed)
			f.sprinting = true;
		
		// modifyInput: x0.98, sneak x0.3, square movement
		double ix = potting ? 0 : act.strafe, iz = potting ? 0 : act.forward;
		double len = Math.sqrt(ix * ix + iz * iz);
		if(len > 0)
		{
			ix *= 0.98;
			iz *= 0.98;
			if(f.sneaking)
			{
				ix *= 0.3;
				iz *= 0.3;
			}
			if(using)
			{
				ix *= 0.2;
				iz *= 0.2;
			}
			double l = Math.sqrt(ix * ix + iz * iz);
			double ux = ix / l, uz = iz / l;
			double ax = Math.abs(ux), az = Math.abs(uz);
			double ratio = ax > az ? az / ax : ax / az;
			double scale = Math.min(l * Math.sqrt(ratio * ratio + 1), 1);
			ix = ux * scale;
			iz = uz * scale;
		}
		
		// LivingEntity.aiStep: jump
		if(f.noJumpDelay > 0)
			f.noJumpDelay--;
		boolean water = f.waterTicks > 0 && !web;
		if(act.jump && water && !potting)
			f.vy += 0.04;
		else if(act.jump && !potting)
		{
			if(f.onGround && f.noJumpDelay == 0)
			{
				f.vy = 0.42;
				exhaust(f, f.sprinting ? 0.2F : 0.05F);
				if(f.sprinting)
				{
					f.updateLook();
					f.vx += -f.sinYaw * 0.2;
					f.vz += f.cosYaw * 0.2;
				}
				f.noJumpDelay = 10;
			}
		}else
			f.noJumpDelay = 0;
		
		// travel: moveRelative with the friction of the last move
		boolean ground = f.onGround;
		double speed =
			ground ? f.sprinting ? 0.13 : 0.1 : f.sprinting ? 0.026 : 0.02;
		if(ground)
			speed *= 1 + 0.2
				* Math.max(f.speedLevel, f.effectTicks[SPEED] > 0 ? 1 : 0);
		if(water)
			speed = 0.02;
		if(len > 0)
		{
			f.updateLook();
			double sin = f.sinYaw, cos = f.cosYaw;
			f.vx += (ix * cos - iz * sin) * speed;
			f.vz += (iz * cos + ix * sin) * speed;
		}
		
		// Entity.move: a web scales this move and stops all motion
		double mx = f.vx, my = f.vy, mz = f.vz;
		if(web)
		{
			mx *= 0.25;
			my *= 0.05;
			mz *= 0.25;
			f.vx = f.vy = f.vz = 0;
		}
		double oldY = f.y, oldX = f.x, oldZ = f.z;
		double[] out = moveOut;
		terrain.move(f.x, f.y, f.z, mx, my, mz, out);
		f.x = out[0];
		f.y = out[1];
		f.z = out[2];
		// Player.checkMovementStatistics: sprinting on the ground, 0.1 a block
		if(f.sprinting && out[3] != 0)
			exhaust(f, 0.1F * (float)length(f.x - oldX, f.z - oldZ));
		// a wall stops that axis' motion
		if(out[4] != 0)
			f.vx = 0;
		if(out[5] != 0)
			f.vz = 0;
		boolean wasOnGround = f.onGround;
		f.onGround = out[3] != 0;
		if(f.onGround)
		{
			f.vy = 0;
			// LivingEntity.causeFallDamage: ceil(fall - 3), past the armor;
			// none after a wind charge's launch
			if(!wasOnGround && f.fallDistance > 3 && !f.windLaunched && !web)
				hurtPlain(f, (float)Math.ceil(f.fallDistance - 3));
			f.windLaunched = false;
		}
		
		if(f.onGround || web)
			f.fallDistance = 0;
		else if(f.y < oldY)
			f.fallDistance += oldY - f.y;
		
		if(water)
		{
			f.vx *= 0.8;
			f.vz *= 0.8;
			f.vy = f.vy * 0.8 - 0.02;
			return;
		}
		double h = ground ? GROUND_FRICTION : AIR_FRICTION;
		f.vx *= h;
		f.vz *= h;
		f.vy = (f.vy - 0.08) * 0.98F;
	}
	
	/** Players overlapping push each other apart (Entity.push). */
	void push(SimFighter a, SimFighter b)
	{
		double dx = b.x - a.x, dz = b.z - a.z;
		if(Math.abs(dx) >= 0.6 || Math.abs(dz) >= 0.6)
			return;
		if(a.y + SimFighter.height(a.sneaking) <= b.y
			|| b.y + SimFighter.height(b.sneaking) <= a.y)
			return;
		double m = Math.max(Math.abs(dx), Math.abs(dz));
		if(m < 0.01)
			return;
		m = Math.sqrt(m);
		dx /= m;
		dz /= m;
		double g = Math.min(1, 1 / m);
		dx *= g * 0.05;
		dz *= g * 0.05;
		a.vx -= dx;
		a.vz -= dz;
		b.vx += dx;
		b.vz += dz;
	}
	
	// ── the camera ───────────────────────────────────────────────────────
	
	static float needYaw(SimFighter f, double tx, double tz)
	{
		return (float)(Math.toDegrees(FastTrig.atan2(tz - f.z, tx - f.x)) - 90);
	}
	
	/** Follows the other one like a hand on a mouse: lag, lead, noise. */
	void aim(SimFighter f, SimFighter op)
	{
		Snapshot s = op.seen(f.viewDelay);
		double eyeY = f.y + f.eyeHeight();
		double h = SimFighter.height(s.sneaking);
		double ty = Math.max(s.y + 0.2, Math.min(s.y + h - 0.2, eyeY));
		double need = needYaw(f, s.x, s.z);
		double hx = s.x - f.x, hz = s.z - f.z;
		double horiz = Math.sqrt(hx * hx + hz * hz);
		double needPitch = -Math.toDegrees(FastTrig.atan2(ty - eyeY, horiz));
		
		double lead = 0;
		if(!Double.isNaN(f.lastNeedYaw))
			lead = wrap(need - f.lastNeedYaw) * f.aimLead;
		f.lastNeedYaw = need;
		
		double k = f.aimK();
		double errYaw = wrap(need + lead - f.yaw);
		double stepYaw =
			Math.max(-45, Math.min(45, errYaw * k)) + gaussian() * f.aimNoise;
		double stepPitch =
			(needPitch - f.pitch) * k + gaussian() * f.aimNoise * 0.5;
		
		// vector smoothing on top of the humanized step, as in game: part of
		// the last step carries over; faster than 1800 deg/s = candy reset
		f.aimStepYaw =
			f.aimStepYaw * VECTOR_SMOOTHING + stepYaw * (1 - VECTOR_SMOOTHING);
		f.aimStepPitch = f.aimStepPitch * VECTOR_SMOOTHING
			+ stepPitch * (1 - VECTOR_SMOOTHING);
		f.candy.turned(length(f.aimStepYaw, f.aimStepPitch) * 20);
		
		f.prevYaw = f.yaw;
		f.prevPitch = f.pitch;
		f.yaw = (float)wrap(f.yaw + f.aimStepYaw);
		f.pitch = (float)Math.max(-90, Math.min(90, f.pitch + f.aimStepPitch));
	}
	
	/** Share of the camera's last step kept in the next (0 = none). */
	public static final double VECTOR_SMOOTHING = 0.3;
	
	double gaussian()
	{
		// sum of uniforms - plenty for camera noise, and fast
		return random.nextDouble() + random.nextDouble() + random.nextDouble()
			- 1.5;
	}
	
	static double wrap(double deg)
	{
		if(deg >= -180 && deg < 180)
			return deg;
		deg %= 360;
		if(deg >= 180)
			deg -= 360;
		if(deg < -180)
			deg += 360;
		return deg;
	}
}
