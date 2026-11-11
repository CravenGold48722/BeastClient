/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * What the neural network sees of a sword fight, one tick at a time. Filled
 * in by AimAssist from the game and by the trainer's simulator from its own
 * state - the same fields, the same meaning, encoded by the same code - so
 * the net plays in the game what it learned in the simulator.
 *
 * <p>
 * Everything is relative to the line from you to the opponent ("along" =
 * toward them, "side" = to your left of that line), so the net doesn't have
 * to learn the same fight in every compass direction. Inputs are scaled to
 * about -1..1: unscaled inputs of very different sizes are the most common
 * reason a small network trains badly.
 */
public final class NeuralObservation
{
	/** Bumped whenever the inputs change; old weights then won't load. */
	public static final int VERSION = 14;
	
	/** After the terrain: your food and saturation. */
	public static final int TAIL = 2;
	
	/** After those: ECHO's inputs (version 12, see EchoFeatures). */
	public static final int ECHO = 33;
	
	/** Then: the pooled history (NeuralHistory). */
	public static final int CONTEXT = NeuralHistory.WIDTH;
	
	/** Then: far away - the distance, the height, the far terrain. */
	public static final int FAR = 2 + TerrainSense.INPUTS;
	
	/** Then: the pooled last two minutes (NeuralHistory's long ring). */
	public static final int LONG = NeuralHistory.WIDTH;
	
	/**
	 * Last (version 14): both players' enchantments - Knockback, Sharpness,
	 * Protection, Fire Aspect, yours and theirs (setGear).
	 */
	public static final int GEAR = 8;
	
	public static final int SIZE = 76 + NeuralAction.MEMORY + FocusMap.INPUTS
		+ TerrainSense.INPUTS + TAIL + ECHO + CONTEXT + FAR + LONG + GEAR;
	
	/** Your eyes to the nearest point of their hitbox (your reach). */
	public double dist;
	/** Their eyes to the nearest point of your hitbox (their reach). */
	public double theirReach;
	/** Their feet minus your feet, blocks. */
	public double dy;
	
	/** Your velocity: toward them, to your left, up (blocks/tick). */
	public double myAlong, mySide, myVy;
	/** Their velocity: toward you, to your left, up (blocks/tick). */
	public double opAlong, opSide, opVy;
	
	public boolean myGround, opGround;
	
	/** Ticks until a full (90%) hit, yours and theirs (estimated). */
	public int myChargeTicks, opChargeTicks;
	
	/** hurtTime, 10 right after a hit, counting down. */
	public int myHurt, opHurt;
	
	/** The sprint state the server has. */
	public boolean mySprint, opSprint;
	
	/** Health as a fraction of max health. */
	public double myHealth, opHealth;
	
	/**
	 * Your food (0..20) and saturation (0..food): full food with saturation
	 * heals fast, 18+ slowly, 6 or less can't sprint. Only your own - the
	 * server doesn't send anyone else's (the defaults stand in for theirs).
	 */
	public double myFood = 20, mySaturation = 5;
	
	/** A hit now would be a crit (falling, not sprinting). */
	public boolean critReady;
	
	/** A hit now would land (reach, crosshair, line of sight). */
	public boolean canHit;
	
	/** Degrees between where you look and their hitbox center. */
	public double aimError;
	
	/** How squarely they look at you: 1 = straight at you, -1 = away. */
	public double opFacing;
	
	/** Ticks since your last hit on them, and theirs on you. */
	public int sinceMyHit, sinceTheirHit;
	
	public boolean sneaking;
	
	// items (version 2)
	
	/** Stuck in a cobweb, you and them. */
	public boolean myInWeb, opInWeb;
	/** A raised shield that already blocks, yours and theirs. */
	public boolean myBlocking, opBlocking;
	/** A shield to raise, yours and theirs (their offhand shows it). */
	public boolean hasShield, opHasShield;
	/** How far along eating a golden apple is, 0..1 (0 = not eating). */
	public double myEating;
	/** They're eating or drinking (using an item that isn't a shield). */
	public boolean opUsing;
	/** What's left in your hotbar (potsLeft: Strength II splashes). */
	public int websLeft, potsLeft, gapplesLeft;
	/** Absorption hearts, in health points. */
	public double absorption;
	/** Regeneration is healing you. */
	public boolean regenerating;
	
	// the full kit (version 3)
	
	/** Strength / Speed left on you, 0..1 of a potion's 90 s. */
	public double myStrength, mySpeed;
	/** They have Strength / Speed (their effect particles show it). */
	public boolean opStrength, opSpeed;
	/** Speed II splashes, pearls, wind charges, water buckets left. */
	public int speedPotsLeft, pearlsLeft, windLeft, waterLeft;
	/** Ticks until a pearl / wind charge can be thrown again. */
	public int pearlCooldown, windCooldown;
	/** Your pearl is in the air. */
	public boolean pearlFlying;
	
	// context (version 4)
	/** On fire, you and them (it shows on them). */
	public boolean myOnFire, opOnFire;
	/** Fire Resistance left on you, 0..1 of a potion's 8 min. */
	public double myFireRes;
	/** Ticks until your shield can go up again (an axe disabled it). */
	public int myShieldCooldown;
	/** An item has your hands (aiming it, eating, out of a web...). */
	public boolean itemBusy;
	/**
	 * What you pressed last tick (the network's only memory of what it
	 * just did): W/S and A/D as 1 / 0 / -1, jump, sprint, attack.
	 */
	public int lastForward, lastStrafe;
	public boolean lastJump, lastSprint, lastAttack;
	
	// space (version 5): where things are, "along" toward them and "side"
	// to your left, in blocks
	/** How far things around you are looked for, blocks. */
	public static final double SPACE_RANGE = 6, OTHER_RANGE = 12;
	/** The nearest cobweb within SPACE_RANGE. */
	public boolean webNear;
	public double webAlong, webSide;
	/** A wind charge in the air that isn't yours. */
	public boolean windIncoming;
	public double windAlong, windSide;
	/** Their ender pearl in the air: where it will land. */
	public boolean opPearlFlying;
	public double opPearlAlong, opPearlSide;
	/** The nearest other player (not them) within OTHER_RANGE. */
	public boolean otherNear;
	public double otherAlong, otherSide;
	/** How high above the ground your feet are. */
	public double myHeight;
	
	// memory (version 6)
	/** What the network wrote to its memory last tick (NeuralAction). */
	public final float[] memory = new float[NeuralAction.MEMORY];
	
	// version 7
	/**
	 * Their weapon's attack damage minus yours (a netherite sword against
	 * a diamond one: +1).
	 */
	public double opWeapon;
	
	// version 8
	/** Their armor toughness minus yours (netherite against diamond: 4). */
	public double opToughness;
	/** They hold a totem of undying (it shows in their hand). */
	public boolean opTotem;
	
	// version 9
	/** Everything around you, as FocusMap's cells (already scaled). */
	public final float[] focus = new float[FocusMap.INPUTS];
	
	// version 10
	/** The ground around you, as TerrainSense's inputs (already scaled). */
	public final float[] terrain = new float[TerrainSense.INPUTS];
	
	// version 12: what ECHO (minecraftcombatai's V29) sees that this didn't
	/** Where you and they look up/down, degrees (down positive). */
	public double myPitch, opPitch;
	/**
	 * Signed aim errors, degrees: to turn right (+) and down (+) onto their
	 * hitbox center - yours, and theirs onto yours.
	 */
	public double aimYawErr, aimPitchErr, opAimYawErr, opAimPitchErr;
	/** Degrees between their look and your hitbox center. */
	public double opAimError;
	/** How far you and they have fallen (a crit needs some). */
	public double myFall, opFall;
	/** A weapon in your hand (not busy with an item). */
	public boolean myWeapon = true;
	/** They're sneaking (a smaller hitbox). */
	public boolean opSneaking;
	/** Their swing showed this tick. */
	public boolean opSwung;
	/** Their look ray is on your hitbox within their reach. */
	public boolean opOnUs;
	/** Ticks since your swing and since theirs showed. */
	public int sinceMySwing = 1000, sinceTheirSwing = 1000;
	/** Eye to eye, blocks. */
	public double eyeDist;
	/** Your latency and theirs (ms), and the server's tick (ms). */
	public double myPing, opPing, tickMs = 50;
	/** How much nearer they got since last tick (box distance, blocks). */
	public double dDist;
	/** Their style so far (EchoFeatures.OpponentModel). */
	public double mAggression = 0.5, mSpacing = 3, mInterval = 12,
		mHitRange = 2.9;
	
	/** The pooled last ticks (NeuralHistory), filled before encoding. */
	public final float[] context = new float[CONTEXT];
	
	/**
	 * The ground far around you (TerrainSense.senseFar, already scaled): an
	 * arena across, not just the next blocks (user, 2026-10-27).
	 */
	public final float[] terrainFar = new float[TerrainSense.INPUTS];
	
	/**
	 * The pooled last two minutes (NeuralHistory's long ring; in game it
	 * carries over to the next fight against the same player).
	 */
	public final float[] memoryLong = new float[LONG];
	
	/**
	 * Both players' enchantments (user, 2026-10-30: a fight with Knockback I
	 * plays differently from one without, and the net has to tell which it's
	 * in): Knockback's strength (I = 0.5), Sharpness's bonus, Protection
	 * levels (all pieces), Fire Aspect's ticks.
	 */
	public double myKnockback, opKnockback, mySharpness, opSharpness,
		myProtection, opProtection, myFireAspect, opFireAspect;
	
	/** The enchantment inputs from both players' gear. */
	public void setGear(PvpSim.Gear mine, PvpSim.Gear theirs)
	{
		myKnockback = mine.knockback();
		opKnockback = theirs.knockback();
		mySharpness = mine.sharpness();
		opSharpness = theirs.sharpness();
		myProtection = mine.protection();
		opProtection = theirs.protection();
		myFireAspect = mine.fireTicks();
		opFireAspect = theirs.fireTicks();
	}
	
	/**
	 * Encode the mirror image: left and right swapped everywhere. The game
	 * is the same mirrored, so this is as good a fight - AimAssist flips it
	 * now and then, so a net that learned to circle one way circles both
	 * (the trained one strafed left 100% of the time, 2026-10-25). The
	 * network's strafe is then flipped back too.
	 */
	public boolean mirrored;
	
	private int lastSpaceTick = Integer.MIN_VALUE, nextSpaceTick;
	private boolean farDue;
	
	/**
	 * Whether {@link #terrainFar} is due too (after isSpaceDue said yes):
	 * every other time (~7 ticks) - a 6-block grid out to 48 blocks barely
	 * changes in a third of a second, and it was a tenth of the trainer's
	 * time.
	 */
	public boolean isFarDue()
	{
		return farDue;
	}
	
	private boolean spaceShort;
	
	/**
	 * Whether {@link #focus} and {@link #terrain} are due again this tick:
	 * every 3rd, then 4th tick (user, 2026-10-25) - they change slowly and
	 * were 40% of the simulator's time. Also when the clock went back (a
	 * new fight). Kept as they are in between.
	 */
	public boolean isSpaceDue(int tick)
	{
		if(tick >= lastSpaceTick && tick < nextSpaceTick)
			return false;
		// the far terrain: every other time, and first thing in a fight
		farDue = tick < lastSpaceTick || !farDue;
		lastSpaceTick = tick;
		spaceShort = !spaceShort;
		nextSpaceTick = tick + (spaceShort ? 3 : 4);
		return true;
	}
	
	public void encode(float[] out)
	{
		// mirrored: left and right swapped (see mirrored)
		float m = mirrored ? -1 : 1;
		int i = 0;
		out[i++] = clamp(dist / 4, 0, 2);
		out[i++] = clamp(theirReach / 4, 0, 2);
		out[i++] = clamp(dy / 2, -1.5, 1.5);
		out[i++] = clamp(myAlong * 4, -2, 2);
		out[i++] = clamp(m * mySide * 4, -2, 2);
		out[i++] = clamp(myVy * 2, -2, 2);
		out[i++] = clamp(opAlong * 4, -2, 2);
		out[i++] = clamp(m * opSide * 4, -2, 2);
		out[i++] = clamp(opVy * 2, -2, 2);
		out[i++] = myGround ? 1 : -1;
		out[i++] = opGround ? 1 : -1;
		out[i++] = clamp(myChargeTicks / 12.0, 0, 1);
		out[i++] = clamp(opChargeTicks / 12.0, 0, 1);
		out[i++] = clamp(myHurt / 10.0, 0, 1);
		out[i++] = clamp(opHurt / 10.0, 0, 1);
		out[i++] = mySprint ? 1 : -1;
		out[i++] = opSprint ? 1 : -1;
		out[i++] = clamp(myHealth, 0, 1);
		out[i++] = clamp(opHealth, 0, 1);
		out[i++] = critReady ? 1 : -1;
		out[i++] = canHit ? 1 : -1;
		out[i++] = clamp(aimError / 20, 0, 2);
		out[i++] = clamp(opFacing, -1, 1);
		out[i++] = clamp(sinceMyHit / 20.0, 0, 1);
		out[i++] = clamp(sinceTheirHit / 20.0, 0, 1);
		out[i++] = sneaking ? 1 : -1;
		out[i++] = myInWeb ? 1 : -1;
		out[i++] = opInWeb ? 1 : -1;
		out[i++] = myBlocking ? 1 : -1;
		out[i++] = opBlocking ? 1 : -1;
		out[i++] = hasShield ? 1 : -1;
		out[i++] = opHasShield ? 1 : -1;
		out[i++] = clamp(myEating, 0, 1);
		out[i++] = opUsing ? 1 : -1;
		out[i++] = clamp(websLeft / 4.0, 0, 1);
		out[i++] = clamp(potsLeft / 2.0, 0, 1);
		out[i++] = clamp(gapplesLeft, 0, 1);
		out[i++] = clamp(absorption / 4, 0, 1);
		out[i++] = regenerating ? 1 : -1;
		out[i++] = clamp(myStrength, 0, 1);
		out[i++] = clamp(mySpeed, 0, 1);
		out[i++] = opStrength ? 1 : -1;
		out[i++] = opSpeed ? 1 : -1;
		out[i++] = clamp(speedPotsLeft / 2.0, 0, 1);
		out[i++] = clamp(pearlsLeft / 4.0, 0, 1);
		out[i++] = clamp(windLeft / 8.0, 0, 1);
		out[i++] = clamp(waterLeft / 2.0, 0, 1);
		out[i++] = clamp(pearlCooldown / 20.0, 0, 1);
		out[i++] = clamp(windCooldown / 10.0, 0, 1);
		out[i++] = pearlFlying ? 1 : -1;
		out[i++] = myOnFire ? 1 : -1;
		out[i++] = opOnFire ? 1 : -1;
		out[i++] = clamp(myFireRes, 0, 1);
		out[i++] = clamp(myShieldCooldown / 100.0, 0, 1);
		out[i++] = itemBusy ? 1 : -1;
		out[i++] = clamp(lastForward, -1, 1);
		out[i++] = clamp(m * lastStrafe, -1, 1);
		out[i++] = lastJump ? 1 : -1;
		out[i++] = lastSprint ? 1 : -1;
		out[i++] = lastAttack ? 1 : -1;
		out[i++] = webNear ? 1 : -1;
		out[i++] = clamp(webAlong / SPACE_RANGE, -1, 1);
		out[i++] = clamp(m * webSide / SPACE_RANGE, -1, 1);
		out[i++] = windIncoming ? 1 : -1;
		out[i++] = clamp(windAlong / OTHER_RANGE, -1, 1);
		out[i++] = clamp(m * windSide / OTHER_RANGE, -1, 1);
		out[i++] = opPearlFlying ? 1 : -1;
		out[i++] = clamp(opPearlAlong / OTHER_RANGE, -2, 2);
		out[i++] = clamp(m * opPearlSide / OTHER_RANGE, -2, 2);
		out[i++] = otherNear ? 1 : -1;
		out[i++] = clamp(otherAlong / OTHER_RANGE, -1, 1);
		out[i++] = clamp(m * otherSide / OTHER_RANGE, -1, 1);
		out[i++] = clamp(myHeight / 4, 0, 2);
		for(float mem : memory)
			out[i++] = clamp(mem, -1, 1);
		out[i++] = clamp(opWeapon / 4, -2, 2);
		out[i++] = clamp(opToughness / 4, -2, 2);
		out[i++] = opTotem ? 1 : -1;
		// (feature 1 of each focus cell is sideways)
		for(int k = 0; k < focus.length; k += FocusMap.FEATURES)
		{
			out[i++] = clamp(focus[k], -3, 3);
			out[i++] = clamp(m * focus[k + 1], -3, 3);
			for(int f = 2; f < FocusMap.FEATURES; f++)
				out[i++] = clamp(focus[k + f], -3, 3);
		}
		int[] from = mirrored ? MIRROR_TERRAIN : SAME_TERRAIN;
		for(int k = 0; k < terrain.length; k++)
			out[i++] = clamp(terrain[from[k]], -2, 2);
		// last: added to a trained net (2026-10-26), deaf to them at first
		out[i++] = clamp(myFood / 10 - 1, -1, 1);
		out[i++] = clamp(mySaturation / 10 - 1, -1, 1);
		// ECHO's (version 12)
		out[i++] = clamp(myPitch / 45, -2, 2);
		out[i++] = clamp(opPitch / 45, -2, 2);
		out[i++] = clamp(m * aimYawErr / 30, -2, 2);
		out[i++] = clamp(aimPitchErr / 30, -2, 2);
		out[i++] = clamp(m * opAimYawErr / 30, -2, 2);
		out[i++] = clamp(opAimPitchErr / 30, -2, 2);
		out[i++] = clamp(opAimError / 20, 0, 2);
		out[i++] = clamp(myFall / 3, 0, 2);
		out[i++] = clamp(opFall / 3, 0, 2);
		out[i++] = myWeapon ? 1 : -1;
		out[i++] = opSneaking ? 1 : -1;
		out[i++] = opSwung ? 1 : -1;
		out[i++] = opOnUs ? 1 : -1;
		out[i++] = clamp(sinceMySwing / 20.0, 0, 1);
		out[i++] = sinceMySwing <= EchoFeatures.RECENT ? 1 : -1;
		out[i++] = clamp(sinceTheirSwing / 20.0, 0, 1);
		out[i++] = sinceTheirSwing <= EchoFeatures.RECENT ? 1 : -1;
		out[i++] = clamp(eyeDist / 4, 0, 2);
		out[i++] = clamp(myPing / 200, 0, 2);
		out[i++] = clamp(opPing / 200, 0, 2);
		out[i++] = clamp((opPing - myPing) / 200, -2, 2);
		out[i++] = clamp(tickMs / 50 - 1, -1, 2);
		out[i++] = clamp(dDist * 4, -2, 2);
		double mySpeedH = Math.sqrt(myAlong * myAlong + mySide * mySide);
		double opSpeedH = Math.sqrt(opAlong * opAlong + opSide * opSide);
		out[i++] = clamp(mySpeedH * 4, 0, 2);
		out[i++] = clamp(opSpeedH * 4, 0, 2);
		// which way you / they move: toward the other (1) or away (-1)
		out[i++] = mySpeedH < 1e-3 ? 0 : clamp(myAlong / mySpeedH, -1, 1);
		out[i++] = opSpeedH < 1e-3 ? 0 : clamp(opAlong / opSpeedH, -1, 1);
		out[i++] = clamp(Math.abs(opSide) * 4, 0, 2);
		out[i++] = clamp((aimError - opAimError) / 30, -2, 2);
		out[i++] = clamp(mAggression * 2 - 1, -1, 1);
		out[i++] = clamp(mSpacing / 4, 0, 2);
		out[i++] = clamp(mInterval / 20, 0, 2);
		out[i++] = clamp(mHitRange / 4, 0, 2);
		// (averages of tanh: already -1..1)
		System.arraycopy(context, 0, out, i, context.length);
		i += context.length;
		// far: the distance on a log scale (dist above stops at 8 blocks;
		// this one tells 10 from 60), the height difference to 32 blocks
		out[i++] = clamp(Math.log(1 + dist) / LOG_64, 0, 1);
		out[i++] = clamp(dy / 16, -2, 2);
		for(int k = 0; k < terrainFar.length; k++)
			out[i++] = clamp(terrainFar[from[k]], -2, 2);
		System.arraycopy(memoryLong, 0, out, i, memoryLong.length);
		i += memoryLong.length;
		// the enchantments: Knockback I = 1, Sharpness V = 1, Protection IV
		// on all four = 1, Fire Aspect II = 1
		out[i++] = clamp(myKnockback * 2, 0, 2);
		out[i++] = clamp(opKnockback * 2, 0, 2);
		out[i++] = clamp(mySharpness / 3, 0, 2);
		out[i++] = clamp(opSharpness / 3, 0, 2);
		out[i++] = clamp(myProtection / 16, 0, 2);
		out[i++] = clamp(opProtection / 16, 0, 2);
		out[i++] = clamp(myFireAspect / 160, 0, 2);
		out[i++] = clamp(opFireAspect / 160, 0, 2);
	}
	
	private static final double LOG_64 = Math.log(64);
	/** Where each terrain input comes from, as is and mirrored. */
	private static final int[] SAME_TERRAIN = new int[TerrainSense.INPUTS],
		MIRROR_TERRAIN = new int[TerrainSense.INPUTS];
	static
	{
		for(int k = 0; k < TerrainSense.INPUTS; k++)
		{
			SAME_TERRAIN[k] = k;
			MIRROR_TERRAIN[k] = mirrorTerrain(k);
		}
	}
	
	/**
	 * Where terrain input k comes from in the mirror image: the patch's
	 * side columns swapped, direction d (45 degrees each, toward the left)
	 * swapped with 8 - d.
	 */
	private static int mirrorTerrain(int k)
	{
		int p = TerrainSense.PATCH, patch = p * p;
		if(k < patch)
			return k / p * p + p - 1 - k % p;
		int d = (k - patch) / 2;
		return patch
			+ (TerrainSense.DIRECTIONS - d) % TerrainSense.DIRECTIONS * 2
			+ (k - patch) % 2;
	}
	
	/**
	 * The space inputs, from positions relative to you (dx, dz) with the
	 * unit vector toward them (ax, az): the same in the simulator and in
	 * game. NaN = nothing there.
	 */
	public void setSpace(double ax, double az, double webDx, double webDz,
		double windDx, double windDz, double pearlDx, double pearlDz,
		double otherDx, double otherDz, double height)
	{
		double sx = -az, sz = ax;
		webNear = !Double.isNaN(webDx);
		webAlong = webNear ? webDx * ax + webDz * az : 0;
		webSide = webNear ? webDx * sx + webDz * sz : 0;
		windIncoming = !Double.isNaN(windDx);
		windAlong = windIncoming ? windDx * ax + windDz * az : 0;
		windSide = windIncoming ? windDx * sx + windDz * sz : 0;
		opPearlFlying = !Double.isNaN(pearlDx);
		opPearlAlong = opPearlFlying ? pearlDx * ax + pearlDz * az : 0;
		opPearlSide = opPearlFlying ? pearlDx * sx + pearlDz * sz : 0;
		otherNear = !Double.isNaN(otherDx);
		otherAlong = otherNear ? otherDx * ax + otherDz * az : 0;
		otherSide = otherNear ? otherDx * sx + otherDz * sz : 0;
		myHeight = height;
	}
	
	private static float clamp(double v, double min, double max)
	{
		return (float)(v < min ? min : v > max ? max : v);
	}
	
	/**
	 * Their ticks until a 90% hit from their last swing (players with a
	 * sword: 12.5 ticks a full charge) - what AimAssist estimates in game.
	 */
	public static int opChargeFromSwing(int ticksSinceSwing, double cooldown)
	{
		return Math.max(0,
			(int)Math.ceil(0.9 * cooldown - 0.5 - ticksSinceSwing));
	}
}
