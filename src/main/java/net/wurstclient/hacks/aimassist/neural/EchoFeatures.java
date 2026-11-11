/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * The inputs ECHO (minecraftcombatai's V29; its public checkpoint newgen6
 * lists its 78 features) has and the network didn't (observation version
 * 12): the parts both the simulator and AimAssist work out the same way -
 * signed aim errors, whether their look ray is on you, the closing speed -
 * and the opponent's style over the fight (ECHO's m_aggression, m_spacing,
 * m_interval_ms, m_hit_range). One per side of a fight; no MC classes.
 */
public final class EchoFeatures
{
	/** Swings this recent count as "just swung" (ECHO's *_recent). */
	public static final int RECENT = 6;
	
	/** How the opponent fights, kept across fights against them. */
	public static final class OpponentModel
	{
		/** Share of ticks close by they walked in (0..1). */
		public double aggression = 0.5;
		/** The distance they keep close by, blocks. */
		public double spacing = 3;
		/** Ticks between their swings. */
		public double interval = 12;
		/** How far they hit you from, blocks. */
		public double hitRange = 2.9;
		
		/** Close enough to count: a fight, not a chase. */
		static final double CLOSE = 8;
		/** ~20 s of close fighting to move most of the way. */
		static final double SLOW = 1 / 400.0;
		static final double FAST = 0.15;
		
		private int sinceSwing = 1000;
		
		void tick(NeuralObservation o, boolean theyHit)
		{
			sinceSwing++;
			if(o.dist < CLOSE)
			{
				aggression += ((o.opAlong > 0.03 ? 1 : 0) - aggression) * SLOW;
				spacing += (o.dist - spacing) * SLOW;
			}
			if(o.opSwung)
			{
				if(sinceSwing < 60)
					interval += (sinceSwing - interval) * FAST;
				sinceSwing = 0;
			}
			if(theyHit)
				hitRange += (o.theirReach - hitRange) * 0.2;
		}
	}
	
	/**
	 * Every player's style, both ways (how they fight us, how we fought
	 * them), kept on disk so it's remembered from one session to the next.
	 */
	public static final class StyleBook
	{
		public final java.util.Map<java.util.UUID, OpponentModel> theirs =
			new java.util.HashMap<>(), ours = new java.util.HashMap<>();
		private final java.nio.file.Path file;
		
		public StyleBook(java.nio.file.Path file)
		{
			this.file = file;
			try(java.io.DataInputStream in =
				new java.io.DataInputStream(new java.io.BufferedInputStream(
					java.nio.file.Files.newInputStream(file))))
			{
				int n = in.readInt();
				for(int i = 0; i < n; i++)
				{
					java.util.UUID id =
						new java.util.UUID(in.readLong(), in.readLong());
					theirs.put(id, read(in));
					ours.put(id, read(in));
				}
			}catch(java.io.IOException e)
			{
				// none yet
			}
		}
		
		public OpponentModel theirs(java.util.UUID id)
		{
			return theirs.computeIfAbsent(id, k -> new OpponentModel());
		}
		
		public OpponentModel ours(java.util.UUID id)
		{
			return ours.computeIfAbsent(id, k -> new OpponentModel());
		}
		
		public void save()
		{
			try
			{
				java.nio.file.Files.createDirectories(file.getParent());
				try(java.io.DataOutputStream out = new java.io.DataOutputStream(
					new java.io.BufferedOutputStream(
						java.nio.file.Files.newOutputStream(file))))
				{
					out.writeInt(theirs.size());
					for(java.util.Map.Entry<java.util.UUID, OpponentModel> e : theirs
						.entrySet())
					{
						out.writeLong(e.getKey().getMostSignificantBits());
						out.writeLong(e.getKey().getLeastSignificantBits());
						write(out, e.getValue());
						write(out, ours(e.getKey()));
					}
				}
			}catch(java.io.IOException e)
			{
				e.printStackTrace();
			}
		}
		
		private static OpponentModel read(java.io.DataInputStream in)
			throws java.io.IOException
		{
			OpponentModel m = new OpponentModel();
			m.aggression = in.readDouble();
			m.spacing = in.readDouble();
			m.interval = in.readDouble();
			m.hitRange = in.readDouble();
			return m;
		}
		
		private static void write(java.io.DataOutputStream out, OpponentModel m)
			throws java.io.IOException
		{
			out.writeDouble(m.aggression);
			out.writeDouble(m.spacing);
			out.writeDouble(m.interval);
			out.writeDouble(m.hitRange);
		}
	}
	
	private OpponentModel model = new OpponentModel();
	private double lastDist = Double.NaN;
	private int lastSinceTheirHit = Integer.MAX_VALUE;
	
	/** A new fight; the model stays unless a new one is given. */
	public void reset(OpponentModel model)
	{
		if(model != null)
			this.model = model;
		lastDist = Double.NaN;
		lastSinceTheirHit = Integer.MAX_VALUE;
	}
	
	public OpponentModel model()
	{
		return model;
	}
	
	/**
	 * After everything else of this tick's observation is filled in: the
	 * closing speed, then the opponent model (which writes its m_ inputs).
	 */
	public void update(NeuralObservation o)
	{
		o.dDist = Double.isNaN(lastDist) ? 0 : o.dist - lastDist;
		lastDist = o.dist;
		boolean theyHit = o.sinceTheirHit < lastSinceTheirHit;
		lastSinceTheirHit = o.sinceTheirHit;
		model.tick(o, theyHit);
		o.mAggression = model.aggression;
		o.mSpacing = model.spacing;
		o.mInterval = model.interval;
		o.mHitRange = model.hitRange;
	}
	
	// ── geometry, the same in the simulator and in game ─────────────────
	
	/** The look vector of yaw and pitch (degrees), into out. */
	public static void look(double yaw, double pitch, double[] out)
	{
		double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
		double cp = Math.cos(rp);
		out[0] = -Math.sin(ry) * cp;
		out[1] = -Math.sin(rp);
		out[2] = Math.cos(ry) * cp;
	}
	
	/**
	 * Degrees to turn right (+) or left (-) to face (tx, tz) from (x, z)
	 * looking at yaw (Minecraft's: 0 = +z, 90 = -x).
	 */
	public static double yawError(double x, double z, double yaw, double tx,
		double tz)
	{
		double need = Math.toDegrees(FastTrig.atan2(tz - z, tx - x)) - 90;
		return wrap(need - yaw);
	}
	
	/** Degrees to look down (+) or up (-) at ty from an eye at ey. */
	public static double pitchError(double x, double ey, double z, double pitch,
		double tx, double ty, double tz)
	{
		double hx = tx - x, hz = tz - z;
		double need = -Math
			.toDegrees(FastTrig.atan2(ty - ey, Math.sqrt(hx * hx + hz * hz)));
		return need - pitch;
	}
	
	/**
	 * Degrees between a look vector (lx, ly, lz; unit length) and the
	 * direction from the eye to t.
	 */
	public static double aimAngle(double x, double ey, double z, double lx,
		double ly, double lz, double tx, double ty, double tz)
	{
		double dx = tx - x, dy = ty - ey, dz = tz - z;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if(len < 1e-6)
			return 0;
		double dot = (lx * dx + ly * dy + lz * dz) / len;
		return Math.toDegrees(FastTrig.acos(Math.max(-1, Math.min(1, dot))));
	}
	
	/**
	 * Whether the look ray (unit vector dx, dy, dz) from the eye hits a
	 * player's hitbox (feet at x, y, z; 0.6 wide) within reach - "on
	 * target", for either side.
	 */
	public static boolean rayHitsBox(double ex, double ey, double ez, double dx,
		double dy, double dz, double x, double y, double z, double height,
		double reach)
	{
		// slabs: where the ray is between each pair of faces (no allocation:
		// the simulator asks every tick)
		double near = 0, far = reach;
		double lo = x - 0.3 - ex, hi = x + 0.3 - ex;
		if(Math.abs(dx) < 1e-9)
		{
			if(lo > 0 || hi < 0)
				return false;
		}else
		{
			double t1 = lo / dx, t2 = hi / dx;
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
		}
		lo = y - ey;
		hi = y + height - ey;
		if(Math.abs(dy) < 1e-9)
		{
			if(lo > 0 || hi < 0)
				return false;
		}else
		{
			double t1 = lo / dy, t2 = hi / dy;
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
		}
		lo = z - 0.3 - ez;
		hi = z + 0.3 - ez;
		if(Math.abs(dz) < 1e-9)
		{
			if(lo > 0 || hi < 0)
				return false;
		}else
		{
			double t1 = lo / dz, t2 = hi / dz;
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
		}
		return near <= far;
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
