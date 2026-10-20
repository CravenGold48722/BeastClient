/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.SplittableRandom;

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
 * apart when they overlap.</li>
 * </ul>
 * Each fighter's camera follows the other with a lag (like AimAssist's aim
 * or a player's hand), and sees the other a few ticks late with smoothed
 * motion - what the game shows. Fighters with {@code onlyGoodHits} (the
 * network, like AimAssist) only hit with a sprint hit or a crit.
 */
public final class PvpSim
{
	public static final int NEVER = -1_000_000;
	static final double REACH = 3.0;
	static final double COOLDOWN = 12.5;
	
	public interface Policy
	{
		void reset(SplittableRandom random);
		
		void act(SimFighter me, NeuralObservation obs, NeuralAction out);
	}
	
	/** What a fighter showed at the end of a tick (for delayed views). */
	static final class Snapshot
	{
		double x, y, z;
		float yaw;
		boolean onGround, sprint, sneaking;
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
		
		// the camera
		double aimRate, aimNoise, aimLead;
		double lastNeedYaw = Double.NaN;
		float prevYaw, prevPitch;
		
		/** How many ticks late this fighter sees the other one. */
		int viewDelay;
		
		// the other fighter's motion as this one tracks it (AimAssist's
		// trackTargetMotion: half the last step, half what it had)
		double trackX, trackY, trackZ, motX, motY, motZ;
		boolean trackValid;
		
		/**
		 * Knockback from hits this tick: the victim's client gets it (as a
		 * velocity packet) before its next tick, so it can still react in
		 * that tick - a jump reset jumps off the ground with it.
		 */
		final double[][] pendingKnockback = new double[4][3];
		int pendingCount;
		
		final Snapshot[] history = new Snapshot[4];
		final NeuralObservation obs = new NeuralObservation();
		final NeuralAction action = new NeuralAction();
		
		// stats
		float damageDealt;
		int hits, sprintHits, crits, combo, maxCombo, swings;
		
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
			for(int t = 0; t < 40; t++)
				if(strength(t - 0.5) > 0.9F)
					return t;
			return 40;
		}
		
		boolean critReady()
		{
			return fallDistance > 0 && !onGround && !sprinting && !serverSprint;
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
			s.onGround = onGround;
			s.sprint = serverSprint;
			s.sneaking = sneaking;
			s.hurtTime = hurtTime;
			s.lastSwingTick = lastSwingTick;
		}
	}
	
	final SplittableRandom random;
	final SimFighter a, b;
	int tick;
	final int maxTicks;
	double armor, toughness;
	
	public PvpSim(long seed, Policy pa, Policy pb, int maxTicks)
	{
		random = new SplittableRandom(seed);
		this.maxTicks = maxTicks;
		a = new SimFighter(this);
		b = new SimFighter(this);
		a.policy = pa;
		b.policy = pb;
		
		// equal gear, varied: no armor, iron, diamond
		switch(random.nextInt(3))
		{
			case 0 -> armor = toughness = 0;
			case 1 ->
			{
				armor = 15;
				toughness = 0;
			}
			default ->
			{
				armor = 20;
				toughness = 8;
			}
		}
		
		double dist = 3.5 + random.nextDouble() * 6;
		double angle = random.nextDouble() * Math.PI * 2;
		a.x = 0;
		a.z = 0;
		b.x = Math.cos(angle) * dist;
		b.z = Math.sin(angle) * dist;
		for(SimFighter f : new SimFighter[]{a, b})
		{
			SimFighter o = f == a ? b : a;
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
		}
		// a full history, so the first delayed views aren't empty
		for(tick = -3; tick <= 0; tick++)
		{
			a.record();
			b.record();
		}
		tick = 0;
		
		pa.reset(random.split());
		pb.reset(random.split());
	}
	
	/** Plays the fight to the end. Returns 1 if A won, -1 if B, else 0. */
	public int run()
	{
		while(tick < maxTicks)
		{
			step();
			if(a.health <= 0 || b.health <= 0)
				break;
		}
		if(a.health <= 0 && b.health > 0)
			return -1;
		if(b.health <= 0 && a.health > 0)
			return 1;
		return 0;
	}
	
	public void step()
	{
		tick++;
		
		// 0. last tick's knockback arrives
		applyKnockback(a);
		applyKnockback(b);
		
		// 1. decide, from what each one sees
		observe(a, b);
		observe(b, a);
		a.policy.act(a, a.obs, a.action);
		b.policy.act(b, b.obs, b.action);
		
		// 2. attacks go out before the tick's movement
		boolean aFirst = random.nextBoolean();
		attackPhase(aFirst ? a : b);
		attackPhase(aFirst ? b : a);
		
		// 3. movement
		move(a);
		move(b);
		push();
		
		// 4. end of tick
		for(SimFighter f : new SimFighter[]{a, b})
		{
			if(f.hurtTime > 0 && f.lastHurtTick != tick)
				f.hurtTime--;
			if(f.invulnerableTime > 0)
				f.invulnerableTime--;
			f.attackTicker++;
			f.serverSprint = f.sprinting;
		}
		aim(a, b);
		aim(b, a);
		a.record();
		b.record();
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
		o.opHealth = op.health / 20;
		o.critReady = me.critReady();
		o.canHit = rayHits(me, s.x, s.y, s.z, opH, 0.03);
		
		double cy = s.y + opH / 2 - eyeY;
		double lookX = -Math.sin(Math.toRadians(me.yaw))
			* Math.cos(Math.toRadians(me.pitch));
		double lookY = -Math.sin(Math.toRadians(me.pitch));
		double lookZ = Math.cos(Math.toRadians(me.yaw))
			* Math.cos(Math.toRadians(me.pitch));
		double tx = s.x - me.x, tz = s.z - me.z;
		double tl = Math.sqrt(tx * tx + cy * cy + tz * tz);
		double dot =
			tl < 1e-6 ? 1 : (lookX * tx + lookY * cy + lookZ * tz) / tl;
		o.aimError = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
		
		double opLookX = -Math.sin(Math.toRadians(s.yaw));
		double opLookZ = Math.cos(Math.toRadians(s.yaw));
		o.opFacing = len < 1e-4 ? 1 : -(opLookX * ax + opLookZ * az);
		
		o.sinceMyHit = Math.min(1000, tick - me.lastHitTick);
		o.sinceTheirHit = Math.min(1000, tick - me.lastHurtTick - 1);
		o.sneaking = me.sneaking;
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
		double ex = me.x, ey = me.y + me.eyeHeight(), ez = me.z;
		double yaw = Math.toRadians(me.yaw), pitch = Math.toRadians(me.pitch);
		double dx = -Math.sin(yaw) * Math.cos(pitch);
		double dy = -Math.sin(pitch);
		double dz = Math.cos(yaw) * Math.cos(pitch);
		double w = 0.3 - inset;
		double[] lo = {bx - w, by + inset, bz - w};
		double[] hi = {bx + w, by + h - inset, bz + w};
		double[] o = {ex, ey, ez};
		double[] d = {dx, dy, dz};
		double tMin = 0, tMax = REACH;
		for(int i = 0; i < 3; i++)
		{
			if(Math.abs(d[i]) < 1e-9)
			{
				if(o[i] < lo[i] || o[i] > hi[i])
					return false;
				continue;
			}
			double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
			if(t1 > t2)
			{
				double t = t1;
				t1 = t2;
				t2 = t;
			}
			tMin = Math.max(tMin, t1);
			tMax = Math.min(tMax, t2);
			if(tMin > tMax)
				return false;
		}
		return true;
	}
	
	// ── combat ───────────────────────────────────────────────────────────
	
	void attackPhase(SimFighter f)
	{
		if(!f.action.attack || !f.obs.canHit)
			return;
		if(f.onlyGoodHits && !f.serverSprint && !f.critReady())
			return;
		
		SimFighter t = f == a ? b : a;
		f.swings++;
		f.lastSwingTick = tick;
		float s = f.strength(0.5);
		f.attackTicker = 0;
		
		// the server checks the real position (a little lenient)
		double eyeY = f.y + f.eyeHeight();
		if(boxDistance(f.x, eyeY, f.z, t.x, t.y, t.z,
			SimFighter.height(t.sneaking)) > REACH + 0.1)
			return;
		
		float damage = 7 * (0.2F + s * s * 0.8F);
		boolean charged = s > 0.9F;
		boolean sprintHit = charged && f.serverSprint;
		boolean crit = charged && f.critReady();
		if(crit)
			damage *= 1.5F;
		
		if(!hurt(t, f, damage))
			return;
		
		f.hits++;
		f.lastHitTick = tick;
		f.combo++;
		f.maxCombo = Math.max(f.maxCombo, f.combo);
		t.combo = 0;
		if(crit)
			f.crits++;
		
		if(sprintHit)
		{
			f.sprintHits++;
			queueKnockback(t, 0.5, Math.sin(Math.toRadians(f.yaw)),
				-Math.cos(Math.toRadians(f.yaw)));
			f.vx *= 0.6;
			f.vz *= 0.6;
			f.sprinting = false;
		}
	}
	
	/** LivingEntity.hurtServer. Returns whether it was a full hit. */
	boolean hurt(SimFighter t, SimFighter from, float damage)
	{
		if(t.invulnerableTime > 10)
		{
			if(damage <= t.lastHurt)
				return false;
			float dealt = afterArmor(damage - t.lastHurt);
			t.lastHurt = damage;
			t.health -= dealt;
			from.damageDealt += dealt;
			return false;
		}
		
		t.lastHurt = damage;
		t.invulnerableTime = 20;
		float dealt = afterArmor(damage);
		t.health -= dealt;
		from.damageDealt += dealt;
		t.hurtTime = 10;
		t.lastHurtTick = tick;
		queueKnockback(t, 0.4, from.x - t.x, from.z - t.z);
		return true;
	}
	
	float afterArmor(float damage)
	{
		if(armor <= 0)
			return damage;
		double f = 2 + toughness / 4;
		double g = Math.min(20, Math.max(armor / 5, armor - damage / f));
		return (float)(damage * (1 - g / 25));
	}
	
	static void queueKnockback(SimFighter t, double strength, double x,
		double z)
	{
		if(t.pendingCount == t.pendingKnockback.length)
			return;
		double[] k = t.pendingKnockback[t.pendingCount++];
		k[0] = strength;
		k[1] = x;
		k[2] = z;
	}
	
	static void applyKnockback(SimFighter t)
	{
		for(int i = 0; i < t.pendingCount; i++)
		{
			double[] k = t.pendingKnockback[i];
			knockback(t, k[0], k[1], k[2]);
		}
		t.pendingCount = 0;
	}
	
	/** LivingEntity.knockback. */
	static void knockback(SimFighter t, double strength, double x, double z)
	{
		double len = Math.sqrt(x * x + z * z);
		if(len < 1e-5)
			return;
		double kx = x / len * strength, kz = z / len * strength;
		t.vx = t.vx / 2 - kx;
		t.vz = t.vz / 2 - kz;
		if(t.onGround)
			t.vy = Math.min(0.4, t.vy / 2 + strength);
	}
	
	// ── movement ─────────────────────────────────────────────────────────
	
	void move(SimFighter f)
	{
		NeuralAction act = f.action;
		f.sneaking = act.sneak;
		
		// LocalPlayer.aiStep: sprint
		if(f.sprinting && (act.forward <= 0 || f.sneaking))
			f.sprinting = false;
		else if(!f.sprinting && act.sprint && act.forward > 0 && !f.sneaking)
			f.sprinting = true;
		
		// modifyInput: x0.98, sneak x0.3, square movement
		double ix = act.strafe, iz = act.forward;
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
		if(act.jump)
		{
			if(f.onGround && f.noJumpDelay == 0)
			{
				f.vy = 0.42;
				if(f.sprinting)
				{
					double r = Math.toRadians(f.yaw);
					f.vx += -Math.sin(r) * 0.2;
					f.vz += Math.cos(r) * 0.2;
				}
				f.noJumpDelay = 10;
			}
		}else
			f.noJumpDelay = 0;
		
		// travel: moveRelative with the friction of the last move
		boolean ground = f.onGround;
		double speed =
			ground ? f.sprinting ? 0.13 : 0.1 : f.sprinting ? 0.026 : 0.02;
		if(len > 0)
		{
			double r = Math.toRadians(f.yaw);
			double sin = Math.sin(r), cos = Math.cos(r);
			f.vx += (ix * cos - iz * sin) * speed;
			f.vz += (iz * cos + ix * sin) * speed;
		}
		
		double oldY = f.y;
		f.x += f.vx;
		f.z += f.vz;
		f.y += f.vy;
		if(f.y <= 0)
		{
			f.y = 0;
			f.vy = 0;
			f.onGround = true;
		}else
			f.onGround = false;
		
		if(f.onGround)
			f.fallDistance = 0;
		else if(f.y < oldY)
			f.fallDistance += oldY - f.y;
		
		double h = ground ? 0.546 : 0.91;
		f.vx *= h;
		f.vz *= h;
		f.vy = (f.vy - 0.08) * 0.98;
	}
	
	/** Players overlapping push each other apart (Entity.push). */
	void push()
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
		return (float)(Math.toDegrees(Math.atan2(tz - f.z, tx - f.x)) - 90);
	}
	
	/** Follows the other one like a hand on a mouse: lag, lead, noise. */
	void aim(SimFighter f, SimFighter op)
	{
		Snapshot s = op.seen(f.viewDelay);
		double eyeY = f.y + f.eyeHeight();
		double h = SimFighter.height(s.sneaking);
		double ty = Math.max(s.y + 0.2, Math.min(s.y + h - 0.2, eyeY));
		double need = needYaw(f, s.x, s.z);
		double horiz = Math.hypot(s.x - f.x, s.z - f.z);
		double needPitch = -Math.toDegrees(Math.atan2(ty - eyeY, horiz));
		
		double lead = 0;
		if(!Double.isNaN(f.lastNeedYaw))
			lead = wrap(need - f.lastNeedYaw) * f.aimLead;
		f.lastNeedYaw = need;
		
		double k = 1 - Math.exp(-f.aimRate * 0.05);
		double errYaw = wrap(need + lead - f.yaw);
		double stepYaw = Math.max(-45, Math.min(45, errYaw * k));
		f.prevYaw = f.yaw;
		f.prevPitch = f.pitch;
		f.yaw = (float)wrap(f.yaw + stepYaw + gaussian() * f.aimNoise);
		f.pitch = (float)Math.max(-90, Math.min(90, f.pitch
			+ (needPitch - f.pitch) * k + gaussian() * f.aimNoise * 0.5));
	}
	
	double gaussian()
	{
		// sum of uniforms - plenty for camera noise, and fast
		return random.nextDouble() + random.nextDouble() + random.nextDouble()
			- 1.5;
	}
	
	static double wrap(double deg)
	{
		deg %= 360;
		if(deg >= 180)
			deg -= 360;
		if(deg < -180)
			deg += 360;
		return deg;
	}
}
