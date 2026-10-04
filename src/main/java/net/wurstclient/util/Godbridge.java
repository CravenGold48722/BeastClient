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
	
	/** Keeps clicks this far inside a face's top and bottom edges. */
	private static final double DEPTH_MARGIN = 0.1;
	
	/** ...and this far inside its sides. */
	private static final double SIDE_MARGIN = 0.06;
	
	/** How far ahead (ticks) a click is planned at most. */
	private static final int MAX_LEAD = 8;
	
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
	private double rescueYaw = Double.NaN;
	/** The yaw aimed with since the last plan. */
	private double lastYaw = Double.NaN;
	
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
	 * Whether placing this cell helps the bridge: on your way, within reach
	 * of the line.
	 */
	public boolean isBridgeCell(int cx, int cz, double x, double z)
	{
		double here = along(x, z);
		double cellAlong = along(cx + 0.5, cz + 0.5);
		return cellAlong > here - 1 && cellAlong < here + 3
			&& Math.abs(lateral(cx + 0.5, cz + 0.5)) < 1;
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
	
	/** Whether to sneak this tick (a click planned at the edge). */
	public boolean shouldSneak()
	{
		return sneak;
	}
	
	/**
	 * Plans the aim for the frames until the next tick.
	 *
	 * @param tick
	 *            a tick counter
	 * @param x
	 *            where this tick starts (your center)
	 * @param vx
	 *            your velocity of the last tick, before friction: vanilla's
	 *            deltaMovement / {@link #FRICTION} (not the distance moved -
	 *            sneaking at an edge cuts that short, the velocity not)
	 * @param moveX
	 *            the direction you walk in (unit), 0 when standing
	 * @param yaw
	 *            the camera yaw that will be used
	 * @return the pitch to aim with
	 */
	public double plan(long tick, double x, double z, double vx, double vz,
		double moveX, double moveZ, double eyeAboveTop, double yaw,
		double reach)
	{
		double pitch = planPitch(tick, x, z, vx, vz, moveX, moveZ, eyeAboveTop,
			yaw, reach);
		lastYaw = Double.isNaN(rescueYaw) ? yaw : rescueYaw;
		return pitch;
	}
	
	private double planPitch(long tick, double x, double z, double vx,
		double vz, double moveX, double moveZ, double eyeAboveTop, double yaw,
		double reach)
	{
		sneak = false;
		rescueYaw = Double.NaN;
		
		// where you'll be at the start of the next ticks, walking...
		double[][] walk = predict(x, z, vx, vz, moveX, moveZ, false);
		int lost = 0;
		for(int k = 1; k <= MAX_LEAD; k++)
			if(!supported(walk[k][0], walk[k][1], PLAN_HALF_WIDTH))
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
		List<int[]> targets = supportCells(walk[lost][0], walk[lost][1]);
		boolean stepping = targets.isEmpty();
		if(stepping)
			targets = steppingStones(walk[lost][0], walk[lost][1], x, z);
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
			double[][] path = plannedSneaking
				? predict(x, z, vx, vz, moveX, moveZ, true) : walk;
			int k = (int)(plannedTick - tick);
			if((plannedSneaking || k <= lost) && k <= MAX_LEAD
				&& placesTarget(path[k], eyeAboveTop, plannedYaw, plannedPitch,
					reach, targets) != null)
			{
				sneak = plannedSneaking;
				rescueYaw = plannedYaw;
				return lastPitch = plannedPitch;
			}
		}
		committed = false;
		
		// a click while walking on (not for a stepping stone: it doesn't hold
		// you where you'd walk to)...
		Click best = stepping ? null
			: search(tick, walk, lost, targets, eyeAboveTop, yaw, reach, x, z);
		boolean sneaking = false;
		
		// ...or else sneak to the edge (vanilla keeps you on it) and click
		// there, like a player ninja bridging
		if(best == null)
		{
			double[][] edge = predict(x, z, vx, vz, moveX, moveZ, true);
			best = search(tick, edge, MAX_LEAD + 1, targets, eyeAboveTop, yaw,
				reach, x, z);
			sneaking = true;
		}
		
		if(best == null)
		{
			// Not even from the edge at this yaw: once the edge is close, sneak
			// (vanilla keeps you on the block) and look straight at a face,
			// the way anyone would. Further out, a window may still come.
			if(lost > 2)
				return lastPitch;
			sneak = true;
			double[] look = rescueAim(x, z, targets, eyeAboveTop, reach);
			if(look == null)
				return lastPitch;
			rescueYaw = look[0];
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
	private double[] rescueAim(double x, double z, List<int[]> targets,
		double eyeAboveTop, double reach)
	{
		double[] best = null;
		double bestTurn = Double.MAX_VALUE;
		for(int[] cell : targets)
			for(Face face : sourceFaces(cell[0], cell[1]))
			{
				double px = face.x() + 0.5 + face.dx() * 0.5;
				double pz = face.z() + 0.5 + face.dz() * 0.5;
				double dx = px - x;
				double dz = pz - z;
				// in front of the face
				if(dx * face.dx() + dz * face.dz() >= -0.02)
					continue;
				
				double yaw = Math.toDegrees(Math.atan2(-dx, dz));
				double pitch = Math.toDegrees(
					Math.atan2(eyeAboveTop + 0.35, Math.hypot(dx, dz)));
				Face hit = raycast(x, z, eyeAboveTop, yaw, pitch, reach);
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
	
	/** Tick start positions: [0] = now, [k] = in k ticks. */
	private double[][] predict(double x, double z, double vx, double vz,
		double moveX, double moveZ, boolean sneaking)
	{
		double accel = WALK_ACCEL * (sneaking ? SNEAK_FACTOR : 1);
		double[][] path = new double[MAX_LEAD + 1][];
		path[0] = new double[]{x, z};
		for(int k = 1; k <= MAX_LEAD; k++)
		{
			vx = vx * FRICTION + moveX * accel;
			vz = vz * FRICTION + moveZ * accel;
			// sneaking: the step is cut short at the edge, the velocity isn't
			double[] stepXZ =
				sneaking ? protect(x, z, vx, vz) : new double[]{vx, vz};
			x += stepXZ[0];
			z += stepXZ[1];
			path[k] = new double[]{x, z};
		}
		return path;
	}
	
	/**
	 * The best click on the way: a tick before support runs out (or, worse,
	 * the one after, while your feet are still level), a pitch that lands
	 * on a face placing one of the target cells.
	 */
	private Click search(long tick, double[][] path, int lost,
		List<int[]> targets, double eyeAboveTop, double yaw, double reach,
		double x, double z)
	{
		Click best = null;
		for(int k = 1; k <= Math.min(MAX_LEAD, lost); k++)
		{
			// The click needs this rotation in the frames before it AND in the
			// one sent at the end of the tick before. One tick ahead, only the
			// rotation already aimed for can do that, yaw and pitch: it stays.
			// Further ahead, the (steered) yaw has time to be sent first.
			double kYaw = k == 1 ? lastYaw : yaw;
			if(Double.isNaN(kYaw))
				continue;
			List<Double> pitches = new ArrayList<>();
			if(k == 1)
				pitches.add(lastPitch);
			else
				for(int[] cell : targets)
					for(Face face : sourceFaces(cell[0], cell[1]))
						for(double p : pitchesFor(face, path[k][0], path[k][1],
							eyeAboveTop, yaw))
							pitches.add(p);
						
			for(double pitch : pitches)
			{
				Face hit = placesTarget(path[k], eyeAboveTop, kYaw, pitch,
					reach, targets);
				if(hit == null)
					continue;
					
				// a click while still standing on a block, rather than in
				// the last tick before falling; near the line rather than
				// off to the side
				double score = Math.abs(pitch - NOMINAL_PITCH) + 0.05 * k
					+ (k == lost ? 3 : 0) + Math
						.abs(lateral(hit.targetX() + 0.5, hit.targetZ() + 0.5));
				if(best == null || score < best.score())
					best = new Click(hit.targetX(), hit.targetZ(), k, kYaw,
						pitch, score);
			}
		}
		return best;
	}
	
	private Face placesTarget(double[] pos, double eyeAboveTop, double yaw,
		double pitch, double reach, List<int[]> targets)
	{
		Face hit = raycast(pos[0], pos[1], eyeAboveTop, yaw, pitch, reach);
		return hit != null && contains(targets, hit.targetX(), hit.targetZ())
			? hit : null;
	}
	
	private static boolean contains(List<int[]> cells, int x, int z)
	{
		for(int[] c : cells)
			if(c[0] == x && c[1] == z)
				return true;
		return false;
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
		int ticks, int sneakTicks, double minPitch, double maxPitch)
	{}
	
	/**
	 * Walks a bridge tick by tick with vanilla's ground movement (walking
	 * acceleration and friction; sneaking at 0.3x with edge protection),
	 * the feet staying level one tick past the last supported position, and
	 * a click only where the crosshair really is - with the rotation sent at
	 * the end of the tick before hitting the same face. The camera is
	 * assumed to reach each planned rotation within a tick.
	 *
	 * @param solid
	 *            the lane's blocks (keys from {@link #key}); gets the placed
	 *            ones added
	 */
	public static SimResult simulate(Set<Long> solid, double bridgeYaw,
		double stanceYaw, double anchorX, double anchorZ, double x, double z,
		double vx, double vz, double eyeAboveTop, double reach, double distance,
		int maxTicks)
	{
		Godbridge bridge =
			new Godbridge((cx, cz) -> solid.contains(key(cx, cz)), bridgeYaw,
				anchorX, anchorZ);
		double startAlong = bridge.along(x, z);
		double yawNow = stanceYaw;
		double yawSent = stanceYaw;
		double pitchNow = NOMINAL_PITCH;
		double pitchSent = pitchNow;
		double min = pitchNow;
		double max = pitchNow;
		int placed = 0;
		int sneakTicks = 0;
		
		int tick = 0;
		for(; tick < maxTicks; tick++)
		{
			if(bridge.along(x, z) - startAlong >= distance)
				break;
			
			// click what the crosshair is on (and the sent rotation too)
			Face now =
				bridge.raycast(x, z, eyeAboveTop, yawNow, pitchNow, reach);
			Face sent =
				bridge.raycast(x, z, eyeAboveTop, yawSent, pitchSent, reach);
			if(now != null && now.equals(sent)
				&& !solid.contains(key(now.targetX(), now.targetZ()))
				&& bridge.isBridgeCell(now.targetX(), now.targetZ(), x, z))
			{
				solid.add(key(now.targetX(), now.targetZ()));
				placed++;
			}
			
			// Vertical movement is resolved first, at where the tick started
			// and
			// after its click: nothing under you there and you start to fall.
			if(!bridge.supported(x, z))
				return new SimResult(true, bridge.along(x, z) - startAlong,
					placed, tick, sneakTicks, min, max);
				
			// aim for the next tick
			// keys: the one of the eight directions relative to the camera
			// closest to the bridge direction, like ScaffoldWalk's input
			double moveRad = Math.toRadians(
				yawNow + Math.round(wrap(bridgeYaw - yawNow) / 45) * 45);
			double moveX = -Math.sin(moveRad);
			double moveZ = Math.cos(moveRad);
			double pitchTarget =
				bridge.plan(tick, x, z, vx, vz, moveX, moveZ, eyeAboveTop,
					stanceYaw + yawCorrection(bridge.lateral(x, z)), reach);
			boolean sneak = bridge.shouldSneak();
			double yawTarget = Double.isNaN(bridge.yawOverride())
				? stanceYaw + yawCorrection(bridge.lateral(x, z))
				: bridge.yawOverride();
			if(sneak)
				sneakTicks++;
			
			// move
			double accel = WALK_ACCEL * (sneak ? SNEAK_FACTOR : 1);
			vx = vx * FRICTION + moveX * accel;
			vz = vz * FRICTION + moveZ * accel;
			double[] stepXZ =
				sneak ? bridge.protect(x, z, vx, vz) : new double[]{vx, vz};
			x += stepXZ[0];
			z += stepXZ[1];
			
			yawSent = yawNow;
			pitchSent = pitchNow;
			yawNow = yawTarget;
			pitchNow = pitchTarget;
			min = Math.min(min, pitchNow);
			max = Math.max(max, pitchNow);
		}
		
		return new SimResult(false, bridge.along(x, z) - startAlong, placed,
			tick, sneakTicks, min, max);
	}
	
	/**
	 * Picks the stance by trying them: simulates each candidate a dozen
	 * blocks ahead with the real blocks around you and takes the one that
	 * doesn't fall and sneaks least (then the shorter turn).
	 */
	public static double chooseStance(Set<Long> solid, double bridgeYaw,
		double anchorX, double anchorZ, double x, double z, double vx,
		double vz, double eyeAboveTop, double reach, double currentYaw)
	{
		double best = Double.NaN;
		double bestScore = Double.MAX_VALUE;
		for(double yaw : stanceCandidates(bridgeYaw))
		{
			SimResult r = simulate(new HashSet<>(solid), bridgeYaw, yaw,
				anchorX, anchorZ, x, z, vx, vz, eyeAboveTop, reach, 20, 300);
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
