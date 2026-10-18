/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A pearl catch - your own wind charge hitting your own ender pearl in the
 * air, which puts you where the pearl was - worked out with vanilla's own
 * arithmetic, step for step (user, 2026-10-17: "calculates the EXACT
 * trajectory of the pearl ... and the angle you need to throw the
 * wind-charge"). From the 26.1.2 code:
 * <ul>
 * <li>Both are thrown with {@code Projectile.shootFromRotation}: the
 * direction from the rotation (float trig through {@link Mth}),
 * normalized, plus a random spread ({@code triangle(0, 0.0172275)} per axis
 * - the server's random, nobody else knows it), times 1.5, plus the
 * thrower's movement as the server knows it (the last movement packet's
 * position change; the vertical part only when not on the ground). The
 * server uses the rotation in the use packet itself.</li>
 * <li>The pearl starts at the thrower's feet x/z and eye height - 0.1F; each
 * tick: velocity - 0.03 in y, times 0.99F, then it moves.</li>
 * <li>The wind charge starts at the thrower's eye height and keeps its
 * velocity (no gravity, no drag).</li>
 * <li>The <b>pearl</b> does the hitting (wind charges are pickable, pearls
 * aren't): its move from one position to the next against the wind
 * charge's box (0.3125 wide, from 0.15 below its position) grown by
 * {@code clamp((age - 2) / 20, 0, 0.3)}, its age counted before the move.
 * The pearl - older - moves first in a tick, so at its move {@code a} a wind
 * charge thrown {@code f} ticks after it has moved {@code a - 1 - f}
 * times. You land where the pearl was before that move.</li>
 * </ul>
 * The one thing nobody can know beforehand is the wind charge's random
 * spread: a shot counts as sure when it still hits at all eight extremes of
 * it - within ~25 blocks that's every well-aimed shot.
 */
public enum PearlCatchSolver
{
	;
	
	public static final double PEARL_GRAVITY = 0.03;
	public static final double PEARL_INERTIA = 0.99F;
	public static final double PEARL_SPAWN_DROP = 0.1F;
	public static final float SHOOT_POWER = 1.5F;
	public static final double SPREAD = 0.0172275;
	public static final double WIND_WIDTH = 0.3125F;
	public static final double WIND_BOX_DROP = 0.15F;
	
	/** How many ticks ahead a catch is looked for. */
	public static final int HORIZON = 80;
	
	/** The direction vanilla throws in for a rotation, normalized. */
	public static Vec3 direction(float yaw, float pitch)
	{
		float rad = (float)(Math.PI / 180.0);
		float xd = -Mth.sin(yaw * rad) * Mth.cos(pitch * rad);
		float yd = -Mth.sin(pitch * rad);
		float zd = Mth.cos(yaw * rad) * Mth.cos(pitch * rad);
		return new Vec3(xd, yd, zd).normalize();
	}
	
	/**
	 * The launch velocity for a direction (normalized) and a spread, plus the
	 * thrower's known movement.
	 */
	public static Vec3 launch(Vec3 dir, Vec3 spread, Vec3 known,
		boolean onGround)
	{
		return dir.add(spread).scale(SHOOT_POWER).add(known.x,
			onGround ? 0 : known.y, known.z);
	}
	
	/**
	 * The pearl's positions: [0] where it is now, [k] after k more moves.
	 */
	public static Vec3[] pearlPath(Vec3 pos, Vec3 vel, int moves)
	{
		Vec3[] path = new Vec3[moves + 1];
		path[0] = pos;
		Vec3 v = vel;
		for(int k = 1; k <= moves; k++)
		{
			v = v.add(0, -PEARL_GRAVITY, 0).scale(PEARL_INERTIA);
			pos = pos.add(v);
			path[k] = pos;
		}
		return path;
	}
	
	/** The wind charge's box at a position. */
	public static AABB windBox(Vec3 pos)
	{
		double half = WIND_WIDTH / 2;
		return new AABB(pos.x - half, pos.y - WIND_BOX_DROP, pos.z - half,
			pos.x + half, pos.y - WIND_BOX_DROP + WIND_WIDTH, pos.z + half);
	}
	
	/** The pearl's hit margin at an age (vanilla's computeMargin). */
	public static double margin(int age)
	{
		return Math.max(0.0F, Math.min(0.3F, (age - 2) / 20.0F));
	}
	
	/**
	 * The pearl's move that a wind charge hits, or -1.
	 *
	 * @param path
	 *            the pearl's positions, [0] = now
	 * @param age
	 *            the pearl's age at path[0] (its moves so far)
	 * @param fire
	 *            the wind charge is thrown this many ticks from now (0 =
	 *            this tick)
	 * @param windStart
	 *            where it starts (thrower's x/z, eye height)
	 * @param windVel
	 *            its velocity
	 * @return k: the hit is in the move from path[k - 1] to path[k]
	 */
	public static int hitMove(Vec3[] path, int age, int fire, Vec3 windStart,
		Vec3 windVel)
	{
		Vec3 wind = windStart;
		int windMoves = 0;
		for(int k = 1; k < path.length; k++)
		{
			// at the pearl's move k (its age before it: age + k - 1) the
			// wind charge has moved k - 1 - fire times
			int due = k - 1 - fire;
			if(due < 0)
				continue;
			while(windMoves < due)
			{
				wind = wind.add(windVel);
				windMoves++;
			}
			// (its age during the move: vanilla counts tickCount up right
			// before tick())
			AABB box = windBox(wind).inflate(margin(age + k));
			// only a move that ENTERS the box hits: ProjectileUtil's
			// getEntityHitResult takes bb.clip(from, to) alone, and a move
			// starting inside never enters - counting "inside" too rated
			// slow, angled meetings 90%+ that then missed (server traces:
			// 5 of 5 throws predicted right with entering only)
			if(box.clip(path[k - 1], path[k]).isPresent())
				return k;
		}
		return -1;
	}
	
	/**
	 * Spread samples, vanilla's triangle(0, SPREAD) per axis, from a fixed
	 * seed - the same every solve.
	 */
	private static final Vec3[] SAMPLES = new Vec3[200];
	static
	{
		java.util.Random random = new java.util.Random(26_1_2);
		for(int i = 0; i < SAMPLES.length; i++)
			SAMPLES[i] =
				new Vec3(SPREAD * (random.nextDouble() - random.nextDouble()),
					SPREAD * (random.nextDouble() - random.nextDouble()),
					SPREAD * (random.nextDouble() - random.nextDouble()));
	}
	
	/**
	 * How often a shot hits, over vanilla's random spread (the server's - a
	 * triangle centred on the aim, so its extremes are rare: demanding a hit
	 * even there threw away a straight-up shot that hits 95-100% of the time).
	 */
	public static double hitRate(Vec3[] path, int age, int fire, Vec3 windStart,
		Vec3 dir, Vec3 known, boolean onGround)
	{
		return hitRate(path, age, fire, windStart, dir, known, onGround, 1);
	}
	
	/**
	 * Like {@link #hitRate(Vec3[], int, int, Vec3, Vec3, Vec3, boolean)}, but
	 * stops counting once the rate can't reach {@code enough} any more
	 * (returning what it has - an underestimate).
	 */
	private static double hitRate(Vec3[] path, int age, int fire,
		Vec3 windStart, Vec3 dir, Vec3 known, boolean onGround, double enough)
	{
		// Each sample with the server a tick early, on time and a tick late:
		// the time between the two throws is counted in client ticks, and
		// the server can see one more or less (packets jitter, the test
		// client can fall behind). A chase - both going up - barely notices;
		// a head-on meeting with the falling pearl (3 blocks a tick closer)
		// misses - those late shots were rated 90%+ and missed in the
		// server traces.
		int total = SAMPLES.length * 3;
		int maxMisses = (int)((1 - enough) * total);
		int hits = 0;
		int misses = 0;
		for(Vec3 spread : SAMPLES)
		{
			Vec3 vel = launch(dir, spread, known, onGround);
			for(int offset = -1; offset <= 1; offset++)
				if(hitMove(path, age, fire + offset, windStart, vel) >= 0)
					hits++;
				else if(++misses > maxMisses && enough < 1)
					return hits / (double)total;
		}
		return hits / (double)total;
	}
	
	/** A hit rate a catch has to have to count as sure. */
	public static final double MIN_RATE = 0.85;
	
	/** Whether a shot hits at least MIN_RATE of the time. */
	public static boolean isSure(Vec3[] path, int age, int fire, Vec3 windStart,
		Vec3 dir, Vec3 known, boolean onGround)
	{
		return hitRate(path, age, fire, windStart, dir, known,
			onGround) >= MIN_RATE;
	}
	
	/**
	 * Where the thrower is when firing, for a fire tick: eye position (x/z of
	 * the feet, eye height), known movement, on the ground.
	 */
	public record Shooter(Vec3 eyes, Vec3 known, boolean onGround)
	{}
	
	/** A planned catch, and how often it hits. */
	public record Plan(int fire, Vec3 dir, int hitMove, Vec3 landing,
		double rate)
	{
		public boolean sure()
		{
			return rate >= MIN_RATE;
		}
		
		/** The rotation to fire with. */
		public Rotation rotation()
		{
			return rotationOf(dir);
		}
	}
	
	/** The rotation that looks along a direction. */
	public static Rotation rotationOf(Vec3 dir)
	{
		return Rotation.wrapped(
			(float)Math.toDegrees(Math.atan2(-dir.x, dir.z)),
			(float)Math.toDegrees(
				-Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z))));
	}
	
	/**
	 * See {@link #solve(Vec3[], int, Shooter[], int, int, Rotation, double)}.
	 */
	public static Plan solve(Vec3[] path, int age, Shooter[] shooters,
		int earliest, int latest)
	{
		return solve(path, age, shooters, earliest, latest, null, 0);
	}
	
	/**
	 * Ticks for a camera at {@code from} to turn to {@code to} at this speed
	 * - in yaw and pitch, the way it turns (near straight up a few degrees of
	 * direction can be a big yaw swing) - plus a reaction for a big new
	 * turn.
	 */
	public static int turnTicks(Rotation from, Rotation to, double degPerTick)
	{
		double angle = Math.hypot(Mth.wrapDegrees(to.yaw() - from.yaw()),
			to.pitch() - from.pitch());
		if(angle < 1)
			return 0;
		int turning = (int)Math.ceil(angle / degPerTick);
		return angle < 10 ? turning : turning + 3;
	}
	
	private record Candidate(int fire, Vec3 dir, int hit, Vec3 landing,
		Shooter shooter)
	{}
	
	/**
	 * The highest catch that hits at least {@link #MIN_RATE} of the time (or,
	 * if none does, the one that hits most often): every fire tick from
	 * {@code earliest} to {@code latest}, every meeting point along the
	 * pearl's path - aimed with the wind charge's box centred on the pearl's
	 * move there, or straight up (near-vertical: yaw doesn't matter, so no
	 * turn), or at the camera's own yaw - and, given a camera, only shots it
	 * can turn to by their fire tick.
	 *
	 * @param shooters
	 *            the thrower for each fire tick ([f] = firing f ticks from
	 *            now), predicted
	 */
	public static Plan solve(Vec3[] path, int age, Shooter[] shooters,
		int earliest, int latest, Rotation camera, double degPerTick)
	{
		java.util.List<Candidate> candidates = new java.util.ArrayList<>();
		int last = Math.min(latest, shooters.length - 1);
		for(int fire = Math.max(0, earliest); fire <= last; fire++)
		{
			Shooter s = shooters[fire];
			Vec3 known = new Vec3(s.known().x, s.onGround() ? 0 : s.known().y,
				s.known().z);
			for(int k = fire + 2; k < path.length; k++)
			{
				int windMoves = k - 1 - fire;
				// the middle of the pearl's move, the box's middle on it
				Vec3 meet = path[k - 1].add(path[k]).scale(0.5).add(0,
					WIND_BOX_DROP - WIND_WIDTH / 2, 0);
				Vec3 perTick = meet.subtract(s.eyes()).scale(1.0 / windMoves)
					.subtract(known);
				// the wind charge flies 1.5 a tick: only a meeting it can
				// reach in exactly these moves (give or take its box)
				if(Math.abs(perTick.length() - SHOOT_POWER) * windMoves > 0.35)
					continue;
				Vec3 dir = perTick.normalize();
				addCandidate(candidates, path, age, fire, s, dir, camera,
					degPerTick);
				if(dir.y > 0.985)
					addCandidate(candidates, path, age, fire, s,
						new Vec3(0, 1, 0), camera, degPerTick);
				if(camera != null)
				{
					float pitch = rotationOf(dir).pitch();
					addCandidate(candidates, path, age, fire, s,
						direction(camera.yaw(), pitch), camera, degPerTick);
				}
			}
		}
		
		// highest first; the first that hits often enough wins
		candidates.sort((a, b) -> Double.compare(b.landing().y, a.landing().y));
		Plan bestRate = null;
		int checked = 0;
		for(Candidate c : candidates)
		{
			if(++checked > 150)
				break;
			Shooter s = c.shooter();
			// (stops early once it can't be sure - a fallback's rate is then
			// a little low, which only makes it less likely to be picked)
			double rate = hitRate(path, age, c.fire(), s.eyes(), c.dir(),
				s.known(), s.onGround(), MIN_RATE);
			Plan plan = new Plan(c.fire(), c.dir(), c.hit(), c.landing(), rate);
			if(plan.sure())
				return plan;
			if(bestRate == null || rate > bestRate.rate())
				bestRate = plan;
		}
		return bestRate;
	}
	
	private static void addCandidate(java.util.List<Candidate> candidates,
		Vec3[] path, int age, int fire, Shooter s, Vec3 dir, Rotation camera,
		double degPerTick)
	{
		if(camera != null
			&& turnTicks(camera, rotationOf(dir), degPerTick) > fire)
			return;
		int hit = hitMove(path, age, fire, s.eyes(),
			launch(dir, Vec3.ZERO, s.known(), s.onGround()));
		if(hit >= 0)
			candidates.add(new Candidate(fire, dir, hit, path[hit - 1], s));
	}
}
