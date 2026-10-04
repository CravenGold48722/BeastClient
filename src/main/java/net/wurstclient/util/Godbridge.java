/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A godbridge in any direction, the way players do it: walking backward along
 * the bridge, clicking the side face of a block the moment the crosshair is
 * on it, no sneaking while that works.
 *
 * <ul>
 * <li><b>Blocks:</b> blocks only join face to face, so a bridge at an angle
 * is a staircase (at 45 degrees the zigzag players build diagonals with).
 * Instead of a fixed path, a block goes wherever your support is about to
 * run out: the planner predicts where your hitbox stands on no block any
 * more, and any block that would hold you there will do - near a corner
 * either of the blocks around it.</li>
 * <li><b>Stance:</b> a face can only be clicked from in front of it, so the
 * look has to point back along both axes the staircase steps along, and
 * keys only move in 45 degree steps relative to the camera: the camera
 * faces bridge + 180 + 45k. Straight bridges at 45 degrees with S plus A or
 * D (the classic godbridge), diagonals straight back with S only (the
 * diagonal godbridge). Which one works also depends on where in the block
 * you stand, so {@link #chooseStance} simulates them and takes the best.</li>
 * <li><b>Line:</b> looking straight back along a line, the crosshair crosses
 * each face where the line does; through block corners it is never cleanly
 * on one. {@link #chooseAnchor} shifts the line sideways so its crossings
 * stay clear of corners (exact diagonals: through the faces' middles).</li>
 * <li><b>Pitch:</b> the crosshair only clears a block's top edge once you
 * are ~0.3 past it, about where you start to fall: roughly one tick per
 * block to click in (why players jump every 8-10 blocks). The planner
 * predicts the next tick positions with vanilla's movement, raycasts
 * candidate pitches against the blocks and picks the click closest to the
 * nominal ~75.4 (players: 75.0-75.8), committed early - a click is only
 * made when the rotation the server already has hits too - and re-checked
 * every tick.</li>
 * <li><b>No window:</b> sneak to the edge and click there, like a player
 * ninja bridging.</li>
 * </ul>
 *
 * <p>
 * Works on one lane (a y level): cell x, z is the block at x, laneY, z, its
 * top is height 0. Distances in blocks, yaw/pitch in Minecraft's degrees.
 */
public final class Godbridge
{
	/** The middle of the 75.0-75.8 range players use. */
	public static final double NOMINAL_PITCH = 75.4;
	
	/** Half the player's width. */
	public static final double HALF_WIDTH = 0.3;
	
	/** What planning counts on: a block's last 0.05 under you doesn't. */
	private static final double PLAN_HALF_WIDTH = 0.25;
	
	/** Vanilla ground movement: the step left after friction (0.6 * 0.91). */
	public static final double FRICTION = 0.546;
	
	/** Walking acceleration per tick (0.1 speed * 0.98 input). */
	public static final double WALK_ACCEL = 0.098;
	
	public static final double SNEAK_FACTOR = 0.3;
	
	/** Air acceleration per tick (0.02 flying speed * 0.98 input). */
	public static final double AIR_ACCEL = 0.0196;
	
	public static final double AIR_FRICTION = 0.91;
	public static final double JUMP_POWER = 0.42;
	public static final double GRAVITY = 0.08;
	public static final double DRAG = 0.98;
	public static final double EYE_STANDING = 1.62;
	public static final double EYE_CROUCHING = 1.27;
	
	/**
	 * How quickly the smooth aim settles (1/s, critically damped): smooth,
	 * but still on the planned pitch before the click.
	 */
	public static final double AIM_OMEGA = 50;
	
	/** Keeps clicks this far inside a face's top and bottom edges. */
	private static final double DEPTH_MARGIN = 0.1;
	
	/** ...and this far inside its sides. */
	private static final double SIDE_MARGIN = 0.06;
	
	/** How far ahead (ticks) a click is planned at most... */
	private static final int LEAD = 8;
	
	/** ...and while jumping: the whole jump, to its landing. */
	private static final int MAX_LEAD = 14;
	
	public interface Lane
	{
		boolean isSolid(int x, int z);
	}
	
	/** A face of a cell: the cell and the direction it faces (dx, dz). */
	public record Face(int x, int z, int dx, int dz)
	{
		public int targetX()
		{
			return x + dx;
		}
		
		public int targetZ()
		{
			return z + dz;
		}
	}
	
	private record Click(int cellX, int cellZ, int lead, double yaw,
		double pitch, double score)
	{}
	
	private final Lane lane;
	private final double dirX;
	private final double dirZ;
	private final double anchorX;
	private final double anchorZ;
	
	// the committed click plan
	private boolean committed;
	private int plannedX;
	private int plannedZ;
	private long plannedTick;
	private double plannedPitch;
	private double plannedYaw;
	private boolean plannedSneaking;
	private double lastPitch = NOMINAL_PITCH;
	private boolean sneak;
	private int horizon = LEAD;
	private boolean rescuing;
	private Cam cam = new Cam(0, NOMINAL_PITCH, 0, 0);
	private double rescueYaw = Double.NaN;
	
	/**
	 * @param bridgeYaw
	 *            the direction to bridge in
	 * @param anchorX
	 *            a point on the line to walk along
	 */
	public Godbridge(Lane lane, double bridgeYaw, double anchorX,
		double anchorZ)
	{
		this.lane = lane;
		double rad = Math.toRadians(bridgeYaw);
		dirX = -Math.sin(rad);
		dirZ = Math.cos(rad);
		this.anchorX = anchorX;
		this.anchorZ = anchorZ;
	}
	
	// ── Geometry ───────────────────────────────────────────────────────
	
	/** Signed distance from the bridge line, + to the bridge's right. */
	public double lateral(double x, double z)
	{
		// right of the bridge direction = Minecraft yaw + 90
		return (x - anchorX) * -dirZ + (z - anchorZ) * dirX;
	}
	
	public double along(double x, double z)
	{
		return (x - anchorX) * dirX + (z - anchorZ) * dirZ;
	}
	
	/**
	 * Whether placing this cell helps the bridge: under you right now, or on
	 * your way within reach of the line.
	 */
	public boolean isBridgeCell(int cx, int cz, double x, double z)
	{
		if(overlaps(cx, cz, x, z, HALF_WIDTH))
			return true;
		double here = along(x, z);
		double cellAlong = along(cx + 0.5, cz + 0.5);
		return cellAlong > here - 1.5 && cellAlong < here + 3
			&& Math.abs(lateral(cx + 0.5, cz + 0.5)) < 1.5;
	}
	
	/** Whether a player centered here stands on any block of the lane. */
	public boolean supported(double x, double z)
	{
		return supported(x, z, HALF_WIDTH);
	}
	
	/**
	 * Whether a hitbox this wide (half width) centered here overlaps a block
	 * of the lane. Planning uses a slightly narrower one than the real
	 * {@link #HALF_WIDTH}: standing on a block's last 0.05 is too close to
	 * count on.
	 */
	private boolean supported(double x, double z, double half)
	{
		for(int cx =
			(int)Math.floor(x - half); cx <= (int)Math.floor(x + half); cx++)
			for(int cz = (int)Math.floor(z - half); cz <= (int)Math
				.floor(z + half); cz++)
				if(overlaps(cx, cz, x, z, half) && lane.isSolid(cx, cz))
					return true;
		return false;
	}
	
	/**
	 * Strict overlap of a cell with a hitbox centered here, like collisions.
	 */
	private static boolean overlaps(int cx, int cz, double x, double z,
		double half)
	{
		return cx < x + half && cx + 1 > x - half && cz < z + half
			&& cz + 1 > z - half;
	}
	
	/**
	 * The free cells under a hitbox centered here that could be placed (have
	 * a block next to them) - any of them would hold you there.
	 */
	private List<int[]> supportCells(double x, double z)
	{
		List<int[]> cells = new ArrayList<>();
		double half = PLAN_HALF_WIDTH;
		for(int cx =
			(int)Math.floor(x - half); cx <= (int)Math.floor(x + half); cx++)
			for(int cz = (int)Math.floor(z - half); cz <= (int)Math
				.floor(z + half); cz++)
				if(overlaps(cx, cz, x, z, half) && !lane.isSolid(cx, cz)
					&& !sourceFaces(cx, cz).isEmpty())
					cells.add(new int[]{cx, cz});
		return cells;
	}
	
	/**
	 * Free cells next to the ones under a hitbox centered at x, z that could
	 * be placed now and are on your way (within a block of the line, not
	 * behind {@code fromX, fromZ}).
	 */
	private List<int[]> steppingStones(double x, double z, double fromX,
		double fromZ)
	{
		List<int[]> cells = new ArrayList<>();
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for(int cx = (int)Math.floor(x - HALF_WIDTH); cx <= (int)Math
			.floor(x + HALF_WIDTH); cx++)
			for(int cz = (int)Math.floor(z - HALF_WIDTH); cz <= (int)Math
				.floor(z + HALF_WIDTH); cz++)
			{
				if(!overlaps(cx, cz, x, z, HALF_WIDTH))
					continue;
				for(int[] d : dirs)
				{
					int nx = cx + d[0];
					int nz = cz + d[1];
					if(lane.isSolid(nx, nz) || sourceFaces(nx, nz).isEmpty()
						|| !isBridgeCell(nx, nz, fromX, fromZ)
						|| contains(cells, nx, nz))
						continue;
					cells.add(new int[]{nx, nz});
				}
			}
		return cells;
	}
	
	/** The faces of solid neighbors that a click on would place this cell. */
	public List<Face> sourceFaces(int cx, int cz)
	{
		List<Face> faces = new ArrayList<>();
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for(int[] d : dirs)
			if(lane.isSolid(cx - d[0], cz - d[1]))
				faces.add(new Face(cx - d[0], cz - d[1], d[0], d[1]));
		return faces;
	}
	
	// ── Rays ────────────────────────────────────────────────────────────
	
	/**
	 * The first lane face a ray from the eye hits, null for none (or the top
	 * of a block). {@code eyeAboveTop}: eye height above the lane's top.
	 */
	public Face raycast(double ex, double ez, double eyeAboveTop, double yaw,
		double pitch, double reach)
	{
		double yr = Math.toRadians(yaw);
		double pr = Math.toRadians(pitch);
		double lx = -Math.sin(yr) * Math.cos(pr);
		double ly = -Math.sin(pr);
		double lz = Math.cos(yr) * Math.cos(pr);
		
		// only blocks the ray can reach before it's below the lane
		double horizontal = ly < -1e-6
			? Math.min(reach, (eyeAboveTop + 1) / -ly) * Math.cos(pr) : reach;
		int r = (int)Math.ceil(horizontal) + 1;
		
		double bestT = reach;
		Face best = null;
		boolean bestTop = false;
		int ox = (int)Math.floor(ex);
		int oz = (int)Math.floor(ez);
		for(int cx = ox - r; cx <= ox + r; cx++)
			for(int cz = oz - r; cz <= oz + r; cz++)
			{
				if(!lane.isSolid(cx, cz))
					continue;
				
				// slab test against [cx, cx+1] x [-1, 0] x [cz, cz+1]
				double[] tx = slab(ex, lx, cx, cx + 1);
				double[] ty = slab(eyeAboveTop, ly, -1, 0);
				double[] tz = slab(ez, lz, cz, cz + 1);
				double near = Math.max(tx[0], Math.max(ty[0], tz[0]));
				double far = Math.min(tx[1], Math.min(ty[1], tz[1]));
				if(near > far || near < 0 || near >= bestT)
					continue;
				
				bestT = near;
				bestTop = near == ty[0];
				if(bestTop)
					best = null;
				else if(near == tx[0])
					best = new Face(cx, cz, lx > 0 ? -1 : 1, 0);
				else
					best = new Face(cx, cz, 0, lz > 0 ? -1 : 1);
			}
		return bestTop ? null : best;
	}
	
	private static double[] slab(double origin, double dir, double min,
		double max)
	{
		if(Math.abs(dir) < 1e-12)
			return origin >= min && origin <= max
				? new double[]{-Double.MAX_VALUE, Double.MAX_VALUE}
				: new double[]{Double.MAX_VALUE, -Double.MAX_VALUE};
		double a = (min - origin) / dir;
		double b = (max - origin) / dir;
		return new double[]{Math.min(a, b), Math.max(a, b)};
	}
	
	/**
	 * Pitches that land a ray from the eye on the face: the one closest to
	 * nominal, and the middle of the range. Empty when the face can't be
	 * reached at this yaw from here.
	 */
	private static double[] pitchesFor(Face face, double ex, double ez,
		double eyeAboveTop, double yaw)
	{
		double yr = Math.toRadians(yaw);
		double lx = -Math.sin(yr);
		double lz = Math.cos(yr);
		
		// horizontal distance from the eye to the face's plane
		double h = -(lx * face.dx() + lz * face.dz());
		if(h <= 0.05)
			return new double[0];
		double planeX = face.x() + 0.5 + face.dx() * 0.5;
		double planeZ = face.z() + 0.5 + face.dz() * 0.5;
		double q = (ex - planeX) * face.dx() + (ez - planeZ) * face.dz();
		if(q <= 0)
			return new double[0];
		double dist = q / h;
		
		// where along the face the ray crosses it
		double crossX = ex + lx * dist;
		double crossZ = ez + lz * dist;
		double side = face.dx() != 0 ? crossZ - face.z() : crossX - face.x();
		if(side < SIDE_MARGIN || side > 1 - SIDE_MARGIN)
			return new double[0];
		
		double minTan = (eyeAboveTop + DEPTH_MARGIN) / dist;
		double maxTan = (eyeAboveTop + 1 - DEPTH_MARGIN) / dist;
		double nominalTan = Math.tan(Math.toRadians(NOMINAL_PITCH));
		double clamped = Math.max(minTan, Math.min(maxTan, nominalTan));
		double mid = (minTan + maxTan) / 2;
		return new double[]{Math.toDegrees(Math.atan(clamped)),
			Math.toDegrees(Math.atan(mid))};
	}
	
	// ── Planning ─────────────────────────────────────────────────────────
	
	public void reset()
	{
		committed = false;
		lastPitch = NOMINAL_PITCH;
		sneak = false;
	}
	
	/**
	 * The yaw to aim with instead of the one passed to {@link #plan}, NaN
	 * for that one: a planned click keeps the yaw it was planned with (the
	 * sent rotation and the current one must hit the same face, and near a
	 * corner a fraction of a degree of steering decides which), and the
	 * rescue looks straight at a face.
	 */
	public double yawOverride()
	{
		return rescueYaw;
	}
	
	/** Whether it's looking straight at a block (no stance click works). */
	public boolean isRescuing()
	{
		return rescuing;
	}
	
	/** Whether a click is planned (for the jump: one that catches you). */
	public boolean hasPlan()
	{
		return committed;
	}
	
	/** Whether to sneak this tick (a click planned at the edge). */
	public boolean shouldSneak()
	{
		return sneak;
	}
	
	// ── Movement ───────────────────────────────────────────────────────
	
	/**
	 * Your movement state at the start of a tick, relative to the lane, and
	 * vanilla's movement for one tick (LivingEntity.travel) - one model for
	 * the planner and the simulation, so they predict the same thing.
	 */
	public static final class Body
	{
		public double x;
		public double z;
		/** Feet height above the lane's top. */
		public double h;
		/** deltaMovement as vanilla stores it (after friction and gravity). */
		public double vx;
		public double vy;
		public double vz;
		public boolean onGround;
		/** Crouching this tick (sneak held last tick): eyes at 1.27. */
		public boolean crouching;
		
		public Body(double x, double z, double h, double vx, double vy,
			double vz, boolean onGround, boolean crouching)
		{
			this.x = x;
			this.z = z;
			this.h = h;
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.onGround = onGround;
			this.crouching = crouching;
		}
		
		/** Standing on the lane, still. */
		public static Body standing(double x, double z)
		{
			return new Body(x, z, 0, 0, -GRAVITY * DRAG, 0, true, false);
		}
		
		public Body copy()
		{
			return new Body(x, z, h, vx, vy, vz, onGround, crouching);
		}
		
		public double eyeAboveTop()
		{
			return h + (crouching ? EYE_CROUCHING : EYE_STANDING);
		}
		
		/** Whether this tick's vertical move needs a block under you. */
		public boolean needsSupport()
		{
			return onGround ? vy <= 0 : h + vy <= 1e-9;
		}
		
		/**
		 * One tick: the jump, acceleration from the keys (ground 0.098, air
		 * 0.0196, sneaking 0.3x), sneaking's edge protection (the step only),
		 * the vertical move before the horizontal one (landing and falling
		 * are decided where the tick starts), then friction and gravity.
		 */
		public void step(Godbridge lane, double moveX, double moveZ,
			boolean sneak, boolean jump)
		{
			boolean ground = onGround;
			if(jump && ground)
				vy = JUMP_POWER;
			
			double accel =
				(ground ? WALK_ACCEL : AIR_ACCEL) * (sneak ? SNEAK_FACTOR : 1);
			double dx = vx + moveX * accel;
			double dz = vz + moveZ * accel;
			double mx = dx;
			double mz = dz;
			if(sneak && ground && vy <= 0)
			{
				double[] step = lane.protect(x, z, dx, dz);
				mx = step[0];
				mz = step[1];
			}
			
			boolean landed = false;
			double dy = vy;
			if(h + dy <= 0 && lane.supported(x, z))
			{
				dy = -h;
				landed = true;
			}
			h += dy;
			x += mx;
			z += mz;
			onGround = landed;
			vy = ((landed ? 0 : vy) - GRAVITY) * DRAG;
			double friction = ground ? FRICTION : AIR_FRICTION;
			vx = dx * friction;
			vz = dz * friction;
			crouching = sneak;
		}
	}
	
	/** Tick start states: [0] = now, [k] = in k ticks. */
	private Body[] predict(Body start, double moveX, double moveZ,
		boolean sneaking, boolean jumpFirst)
	{
		Body[] path = new Body[MAX_LEAD + 1];
		path[0] = start.copy();
		for(int k = 1; k <= MAX_LEAD; k++)
		{
			Body b = path[k - 1].copy();
			b.step(this, moveX, moveZ, sneaking, jumpFirst && k == 1);
			path[k] = b;
		}
		return path;
	}
	
	// ── Planning ─────────────────────────────────────────────────────────
	
	/**
	 * Plans the aim for the frames until the next tick.
	 *
	 * @param tick
	 *            a tick counter
	 * @param body
	 *            your state at the start of this tick
	 * @param cam
	 *            the camera now, and how fast it turns ({@link #spring})
	 * @param moveX
	 *            the direction your keys walk in (unit), 0 when not walking
	 * @param jump
	 *            whether you jump this tick
	 * @param yaw
	 *            the camera yaw that will be used
	 * @return the pitch to aim with
	 */
	public double plan(long tick, Body body, Cam cam, double moveX,
		double moveZ, boolean jump, double yaw, double reach)
	{
		this.cam = cam;
		return planPitch(tick, body, moveX, moveZ, jump, yaw, reach);
	}
	
	private double planPitch(long tick, Body body, double moveX, double moveZ,
		boolean jump, double yaw, double reach)
	{
		sneak = false;
		rescueYaw = Double.NaN;
		rescuing = false;
		
		// where you'll be at the start of the next ticks, walking on...
		Body[] walk = predict(body, moveX, moveZ, false, jump);
		horizon = jump || !body.onGround ? MAX_LEAD : LEAD;
		int lost = 0;
		for(int k = 1; k <= horizon; k++)
			if(walk[k].needsSupport()
				&& !supported(walk[k].x, walk[k].z, PLAN_HALF_WIDTH))
			{
				lost = k;
				break;
			}
		
		// still standing on blocks for a while: nothing to place yet
		if(lost == 0)
		{
			committed = false;
			return lastPitch = NOMINAL_PITCH;
		}
		
		// any of the cells that would hold you where you'd lose support - or,
		// when none of those touches a block yet (moving away from a block's
		// corner), a stepping stone next to them first
		Body end = walk[lost];
		List<int[]> targets = supportCells(end.x, end.z);
		boolean stepping = targets.isEmpty();
		if(stepping)
			targets = steppingStones(end.x, end.z, body.x, body.z);
		if(targets.isEmpty())
		{
			committed = false;
			sneak = lost <= 2;
			return lastPitch;
		}
		
		// keep the plan while it still works from where you'll really be
		if(committed && tick < plannedTick
			&& contains(targets, plannedX, plannedZ))
		{
			Body[] path = plannedSneaking
				? predict(body, moveX, moveZ, true, false) : walk;
			int k = (int)(plannedTick - tick);
			if((plannedSneaking || k <= lost) && k <= horizon
				&& clickHits(path[k], k, plannedYaw, plannedPitch, reach,
					targets) != null)
			{
				sneak = plannedSneaking;
				rescueYaw = plannedYaw;
				return lastPitch = plannedPitch;
			}
		}
		committed = false;
		
		// a click while walking on (not for a stepping stone on the ground: it
		// doesn't hold you where you'd walk to - in the air nothing has to
		// hold you until you land, so there it can go in first)...
		boolean inAir = jump || !body.onGround;
		Click best =
			stepping && !inAir ? null : search(walk, lost, targets, yaw, reach);
		boolean sneaking = false;
		
		// ...or else sneak to the edge (vanilla keeps you on it) and click
		// there, like a player ninja bridging
		if(best == null && !jump)
		{
			Body[] edge = predict(body, moveX, moveZ, true, false);
			best = search(edge, horizon + 1, targets, yaw, reach);
			sneaking = true;
		}
		
		if(best == null)
		{
			// Not even from the edge at this yaw: once the edge is close, sneak
			// (vanilla keeps you on the block) and look straight at a face,
			// the way anyone would. Further out, a window may still come.
			if(lost > 2 || jump)
				return lastPitch;
			sneak = true;
			double[] look = rescueAim(body, targets, reach);
			if(look == null)
				return lastPitch;
			rescueYaw = look[0];
			rescuing = true;
			return lastPitch = look[1];
		}
		
		committed = true;
		plannedX = best.cellX();
		plannedZ = best.cellZ();
		plannedTick = tick + best.lead();
		plannedPitch = best.pitch();
		plannedYaw = best.yaw();
		if(Math.abs(wrap(best.yaw() - yaw)) > 1e-9)
			rescueYaw = best.yaw();
		plannedSneaking = sneaking;
		sneak = sneaking;
		return lastPitch = best.pitch();
	}
	
	/**
	 * Looking straight at a face that places one of the targets, from where
	 * you stand: its middle, 0.35 below the top edge. Null when none is in
	 * sight yet (you're still behind all of them).
	 *
	 * @return {yaw, pitch}
	 */
	private double[] rescueAim(Body body, List<int[]> targets, double reach)
	{
		double eye = body.eyeAboveTop();
		double[] best = null;
		double bestTurn = Double.MAX_VALUE;
		for(int[] cell : targets)
			for(Face face : sourceFaces(cell[0], cell[1]))
			{
				double px = face.x() + 0.5 + face.dx() * 0.5;
				double pz = face.z() + 0.5 + face.dz() * 0.5;
				double dx = px - body.x;
				double dz = pz - body.z;
				// in front of the face
				if(dx * face.dx() + dz * face.dz() >= -0.02)
					continue;
				
				double yaw = Math.toDegrees(Math.atan2(-dx, dz));
				double pitch =
					Math.toDegrees(Math.atan2(eye + 0.35, Math.hypot(dx, dz)));
				Face hit = raycast(body.x, body.z, eye, yaw, pitch, reach);
				if(hit == null || hit.targetX() != cell[0]
					|| hit.targetZ() != cell[1])
					continue;
				
				double turn = Math.abs(pitch - NOMINAL_PITCH);
				if(turn < bestTurn)
				{
					bestTurn = turn;
					best = new double[]{yaw, pitch};
				}
			}
		return best;
	}
	
	/**
	 * Vanilla's edge protection while sneaking (Player.maybeBackOffFromEdge):
	 * shortens the step in 0.05 steps per axis, then both, until you'd still
	 * stand on a block. Only the step - your velocity keeps its momentum,
	 * which is what slides you off the moment you stop sneaking.
	 */
	public double[] protect(double x, double z, double dx, double dz)
	{
		while(dx != 0 && !supported(x + dx, z))
			dx = Math.abs(dx) <= 0.05 ? 0 : dx - Math.signum(dx) * 0.05;
		while(dz != 0 && !supported(x, z + dz))
			dz = Math.abs(dz) <= 0.05 ? 0 : dz - Math.signum(dz) * 0.05;
		while(dx != 0 && dz != 0 && !supported(x + dx, z + dz))
		{
			dx = Math.abs(dx) <= 0.05 ? 0 : dx - Math.signum(dx) * 0.05;
			dz = Math.abs(dz) <= 0.05 ? 0 : dz - Math.signum(dz) * 0.05;
		}
		return new double[]{dx, dz};
	}
	
	/**
	 * The best click on the way: a tick before support runs out (or, worse,
	 * that tick itself), with your feet not below the lane's top, a rotation
	 * that lands on a face placing one of the target cells.
	 */
	private Click search(Body[] path, int lost, List<int[]> targets, double yaw,
		double reach)
	{
		Click best = null;
		for(int k = 1; k <= Math.min(horizon, lost); k++)
		{
			Body b = path[k];
			// the block would be inside you
			if(b.h < -1e-4)
				break;
				
			// Aim targets to try: the yaw to aim with and pitches that land
			// on a face from there - and holding the camera where it is.
			// Each is judged with the camera's real lag (clickHits).
			List<double[]> aims = new ArrayList<>();
			aims.add(new double[]{cam.yaw(), cam.pitch()});
			for(int[] cell : targets)
				for(Face face : sourceFaces(cell[0], cell[1]))
					for(double p : pitchesFor(face, b.x, b.z, b.eyeAboveTop(),
						yaw))
						aims.add(new double[]{yaw, p});
					
			for(double[] aim : aims)
			{
				Face hit = clickHits(b, k, aim[0], aim[1], reach, targets);
				if(hit == null)
					continue;
					
				// a click while still standing on a block, rather than in
				// the last tick before falling; near the line rather than
				// off to the side; little turning
				double score = Math.abs(aim[1] - NOMINAL_PITCH) + 0.05 * k
					+ (k == lost ? 3 : 0) + Math
						.abs(lateral(hit.targetX() + 0.5, hit.targetZ() + 0.5));
				if(best == null || score < best.score())
					best = new Click(hit.targetX(), hit.targetZ(), k, aim[0],
						aim[1], score);
			}
		}
		return best;
	}
	
	/**
	 * Whether aiming at this target from now on gets a click in at tick k:
	 * the camera (lagging behind, see {@link #spring}) must be on a face that
	 * places one of the targets at tick k AND one tick before - that's the
	 * rotation the server will already have.
	 */
	private Face clickHits(Body b, int k, double targetYaw, double targetPitch,
		double reach, List<int[]> targets)
	{
		Cam now = cam.at(targetYaw, targetPitch, 0.05 * k);
		Cam sent = cam.at(targetYaw, targetPitch, 0.05 * (k - 1));
		Face hit = placesTarget(b, now.yaw(), now.pitch(), reach, targets);
		if(hit == null)
			return null;
		Face sentHit =
			raycast(b.x, b.z, b.eyeAboveTop(), sent.yaw(), sent.pitch(), reach);
		return hit.equals(sentHit) ? hit : null;
	}
	
	private Face placesTarget(Body b, double yaw, double pitch, double reach,
		List<int[]> targets)
	{
		Face hit = raycast(b.x, b.z, b.eyeAboveTop(), yaw, pitch, reach);
		return hit != null && contains(targets, hit.targetX(), hit.targetZ())
			? hit : null;
	}
	
	/**
	 * The camera: where it points and how fast it turns (degrees per
	 * second), following its aim target with the {@link #spring} smooth aim.
	 */
	public record Cam(double yaw, double pitch, double yawVel, double pitchVel)
	{
		/** Where it is after t seconds of following this target. */
		public Cam at(double targetYaw, double targetPitch, double t)
		{
			if(t <= 0)
				return this;
			double[] y =
				spring(yaw, yawVel, yaw + wrap(targetYaw - yaw), t, AIM_OMEGA);
			double[] p = spring(pitch, pitchVel, targetPitch, t, AIM_OMEGA);
			return new Cam(y[0], p[0], y[1], p[1]);
		}
	}
	
	private static boolean contains(List<int[]> cells, int x, int z)
	{
		for(int[] c : cells)
			if(c[0] == x && c[1] == z)
				return true;
		return false;
	}
	
	public double anchorX()
	{
		return anchorX;
	}
	
	public double anchorZ()
	{
		return anchorZ;
	}
	
	// ── Aim ─────────────────────────────────────────────────────────────
	
	/**
	 * Smooth aim: one step of a critically damped spring toward the target
	 * (no overshoot, no jerk when the target moves), exact for any frame
	 * time. A 2 degree nudge settles in well under two ticks.
	 *
	 * @return {angle, velocity}
	 */
	public static double[] spring(double angle, double velocity, double target,
		double dt, double omega)
	{
		double e = angle - target;
		double c = velocity + omega * e;
		double decay = Math.exp(-omega * dt);
		return new double[]{target + (e + c * dt) * decay,
			(velocity - omega * c * dt) * decay};
	}
	
	// ── Stance and line ─────────────────────────────────────────────────
	
	/** Steering back onto the line: degrees of yaw for a lateral offset. */
	public static double yawCorrection(double lateral)
	{
		return Math.max(-5, Math.min(5, -lateral * 25));
	}
	
	/** The camera yaws a bridge toward {@code bridgeYaw} could use. */
	public static List<Double> stanceCandidates(double bridgeYaw)
	{
		double rad = Math.toRadians(bridgeYaw);
		double dx = -Math.sin(rad);
		double dz = Math.cos(rad);
		List<Double> list = new ArrayList<>();
		for(int k = -1; k <= 1; k++)
		{
			double yaw = bridgeYaw + 180 + 45 * k;
			double r = Math.toRadians(yaw);
			// must look back along every axis the staircase steps along
			if(Math.abs(dx) > 1e-6 && -Math.sin(r) * Math.signum(dx) > -0.3
				|| Math.abs(dz) > 1e-6 && Math.cos(r) * Math.signum(dz) > -0.3)
				continue;
			list.add(yaw);
		}
		return list;
	}
	
	/**
	 * Shifts the line sideways (up to 0.3 from where you stand) so that it
	 * crosses the faces between its cells well clear of their corners - the
	 * worst 20% of the next 16 crossings as far from corners as possible.
	 * Exact diagonals end up through the faces' middles.
	 *
	 * @return the anchor {x, z}
	 */
	public static double[] chooseAnchor(double bridgeYaw, double x, double z)
	{
		double rad = Math.toRadians(bridgeYaw);
		double dx = -Math.sin(rad);
		double dz = Math.cos(rad);
		// straight lines cross no side faces
		if(Math.abs(dx) < 1e-6 || Math.abs(dz) < 1e-6)
			return new double[]{x, z};
		
		double rx = -dz;
		double rz = dx;
		double bestScore = -1;
		double[] best = {x, z};
		for(int i = -6; i <= 6; i++)
		{
			double off = i * 0.05;
			double ax = x + rx * off;
			double az = z + rz * off;
			List<Double> clear = new ArrayList<>();
			// crossings of x = integer and z = integer planes ahead
			for(double t = 0.05; t < 12; t += 0.05)
			{
				double px = ax + dx * t;
				double pz = az + dz * t;
				double nx = px + dx * 0.05;
				double nz = pz + dz * 0.05;
				if(Math.floor(px) != Math.floor(nx))
					clear.add(edgeDistance(pz));
				if(Math.floor(pz) != Math.floor(nz))
					clear.add(edgeDistance(px));
				if(clear.size() >= 16)
					break;
			}
			clear.sort(null);
			double score = clear.isEmpty() ? 0 : clear.get(clear.size() / 5);
			// prefer staying close to where you are
			score -= Math.abs(off) * 0.05;
			if(score > bestScore)
			{
				bestScore = score;
				best = new double[]{ax, az};
			}
		}
		return best;
	}
	
	private static double edgeDistance(double v)
	{
		double f = v - Math.floor(v);
		return Math.min(f, 1 - f);
	}
	
	// ── Simulation ─────────────────────────────────────────────────────
	
	public static long key(int x, int z)
	{
		return (long)x << 32 ^ z & 0xffffffffL;
	}
	
	public record SimResult(boolean fell, double travelled, int placed,
		int ticks, int sneakTicks, int jumps, double minPitch, double maxPitch,
		double maxAimSpeed)
	{}
	
	/** Frames per tick the simulated camera is turned in (60 fps). */
	private static final int SIM_FRAMES = 3;
	
	/**
	 * Walks a bridge tick by tick: {@link Body}'s vanilla movement, a camera
	 * that follows the plan with the {@link #spring} smooth aim (three frames
	 * per tick), a click only where the crosshair really is and the rotation
	 * sent at the end of the tick before hits the same face, and optionally
	 * a godbridger's jump every 8-10 blocks (when {@link #jumpSafe}).
	 *
	 * @param solid
	 *            the lane's blocks (keys from {@link #key}); gets the placed
	 *            ones added
	 * @param jumpFirst
	 *            jump in the first tick
	 * @param jumps
	 *            randomness for the rhythm jumps, null for none
	 */
	public static SimResult simulate(Set<Long> solid, double bridgeYaw,
		double stanceYaw, double anchorX, double anchorZ, Body body,
		double camYaw, double camPitch, double reach, double distance,
		int maxTicks, boolean jumpFirst, Random jumps)
	{
		Godbridge bridge =
			new Godbridge((cx, cz) -> solid.contains(key(cx, cz)), bridgeYaw,
				anchorX, anchorZ);
		Body b = body.copy();
		double startAlong = bridge.along(b.x, b.z);
		double yawNow = camYaw;
		double pitchNow = camPitch;
		double yawSent = yawNow;
		double pitchSent = pitchNow;
		double velYaw = 0;
		double velPitch = 0;
		double min = pitchNow;
		double max = pitchNow;
		double maxSpeed = 0;
		int placed = 0;
		int sneakTicks = 0;
		int jumpCount = 0;
		int sinceJump = 0;
		int sinceSneak = 99;
		int jumpAt = jumps == null ? 0 : 8 + jumps.nextInt(3);
		
		int tick = 0;
		for(; tick < maxTicks; tick++)
		{
			if(bridge.along(b.x, b.z) - startAlong >= distance && b.onGround)
				break;
			
			// click what the crosshair is on (and the sent rotation too)
			double eye = b.eyeAboveTop();
			Face now = bridge.raycast(b.x, b.z, eye, yawNow, pitchNow, reach);
			Face sent =
				bridge.raycast(b.x, b.z, eye, yawSent, pitchSent, reach);
			boolean clicked = false;
			if(b.h >= -1e-4 && now != null && now.equals(sent)
				&& !solid.contains(key(now.targetX(), now.targetZ()))
				&& bridge.isBridgeCell(now.targetX(), now.targetZ(), b.x, b.z))
			{
				solid.add(key(now.targetX(), now.targetZ()));
				placed++;
				sinceJump++;
				clicked = true;
			}
			
			// falling below the lane: nothing held you where the tick started
			if(b.needsSupport() && !bridge.supported(b.x, b.z))
				return new SimResult(true, bridge.along(b.x, b.z) - startAlong,
					placed, tick, sneakTicks, jumpCount, min, max, maxSpeed);
				
			// keys: the one of the eight directions relative to the camera
			// closest to the bridge direction, like ScaffoldWalk's input
			double moveRad = Math.toRadians(
				yawNow + Math.round(wrap(bridgeYaw - yawNow) / 45) * 45);
			double moveX = -Math.sin(moveRad);
			double moveZ = Math.cos(moveRad);
			double steerYaw =
				stanceYaw + yawCorrection(bridge.lateral(b.x, b.z));
			
			// a godbridger's jump: right after a block, every 8-10 of them
			boolean jump = tick == 0 && jumpFirst;
			if(jumps != null && clicked && sinceJump >= jumpAt && b.onGround
				&& sinceSneak > 6 && jumpSafe(solid, bridgeYaw, stanceYaw,
					anchorX, anchorZ, b, yawNow, pitchNow, reach))
			{
				jump = true;
				sinceJump = 0;
				jumpAt = 8 + jumps.nextInt(3);
			}
			
			double pitchTarget = bridge.plan(tick, b,
				new Cam(yawNow, pitchNow, velYaw, velPitch), moveX, moveZ, jump,
				steerYaw, reach);
			boolean sneak = bridge.shouldSneak();
			if(jump && (sneak || !bridge.hasPlan()))
			{
				// no jumping off a sneak plan, nor without a click that
				// catches the landing
				jump = false;
				pitchTarget = bridge.plan(tick, b,
					new Cam(yawNow, pitchNow, velYaw, velPitch), moveX, moveZ,
					false, steerYaw, reach);
				sneak = bridge.shouldSneak();
			}
			if(jump)
				jumpCount++;
			double yawTarget = Double.isNaN(bridge.yawOverride()) ? steerYaw
				: bridge.yawOverride();
			if(sneak)
				sneakTicks++;
			sinceSneak = sneak || bridge.isRescuing() ? 0 : sinceSneak + 1;
			
			b.step(bridge, moveX, moveZ, sneak, jump);
			
			// the camera follows during the frames until the next tick
			yawSent = yawNow;
			pitchSent = pitchNow;
			double target = yawNow + wrap(yawTarget - yawNow);
			for(int f = 0; f < SIM_FRAMES; f++)
			{
				double dt = 0.05 / SIM_FRAMES;
				double[] y = spring(yawNow, velYaw, target, dt, AIM_OMEGA);
				double[] p =
					spring(pitchNow, velPitch, pitchTarget, dt, AIM_OMEGA);
				yawNow = y[0];
				velYaw = y[1];
				pitchNow = p[0];
				velPitch = p[1];
				maxSpeed = Math.max(maxSpeed, Math.hypot(velYaw, velPitch));
			}
			min = Math.min(min, pitchNow);
			max = Math.max(max, pitchNow);
		}
		
		return new SimResult(false, bridge.along(b.x, b.z) - startAlong, placed,
			tick, sneakTicks, jumpCount, min, max, maxSpeed);
	}
	
	/**
	 * Whether jumping now works out: a short simulation from here, jumping
	 * right away - landing on blocks placed in the air, without falling and
	 * with at most a moment of sneaking to get back into the rhythm.
	 */
	public static boolean jumpSafe(Set<Long> solid, double bridgeYaw,
		double stanceYaw, double anchorX, double anchorZ, Body body,
		double camYaw, double camPitch, double reach)
	{
		SimResult r = simulate(new HashSet<>(solid), bridgeYaw, stanceYaw,
			anchorX, anchorZ, body, camYaw, camPitch, reach, 3, 40, true, null);
		return !r.fell() && r.sneakTicks() <= 3;
	}
	
	/**
	 * Picks the stance by trying them: simulates each candidate a dozen
	 * blocks ahead with the real blocks around you and takes the one that
	 * doesn't fall and sneaks least (then the shorter turn).
	 */
	public static double chooseStance(Set<Long> solid, double bridgeYaw,
		double anchorX, double anchorZ, Body body, double reach,
		double currentYaw)
	{
		double best = Double.NaN;
		double bestScore = Double.MAX_VALUE;
		for(double yaw : stanceCandidates(bridgeYaw))
		{
			SimResult r =
				simulate(new HashSet<>(solid), bridgeYaw, yaw, anchorX, anchorZ,
					body, yaw, NOMINAL_PITCH, reach, 20, 300, false, null);
			double score = (r.fell() ? 1000 : 0) + r.sneakTicks()
				+ Math.abs(wrap(yaw - currentYaw)) / 1000;
			if(score < bestScore)
			{
				bestScore = score;
				best = yaw;
			}
		}
		return best;
	}
	
	private static double wrap(double angle)
	{
		angle %= 360;
		if(angle >= 180)
			angle -= 360;
		if(angle < -180)
			angle += 360;
		return angle;
	}
}
