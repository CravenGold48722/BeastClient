/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.LongAdder;

import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/**
 * ECHO (minecraftcombatai.com) as an opponent in the simulator, to test the
 * network against (user, 2026-11-01). The newest public ECHO model is
 * sdffewe/minecraft-combat-MLP (2026-08-13, AGPL-3.0, by skork and CenterX;
 * newer than the newgen6 transformer): an MLP over ECHO's inputs at 9 past
 * ticks (56 x 9 = 504) that picks W/S, A/D, sprint and jump, plus 47
 * predictions ("risk": hit within 4 ticks, ...). Its clicks aren't in it
 * (ECHO's server clicks by rule), so it hits whenever it can with a full
 * charge. Compare only (user): it never trains our net. The file stays
 * outside the repo, in neural/trainer/echo.
 *
 * Its inputs are rebuilt from the simulator's state; the units were matched
 * to the means in the file, the signs that the file can't tell were picked
 * by {@link #calibrate} (train.ps1 has no action for it: java ... NeuralTrainer
 * echo &lt;neural folder&gt; [games]).
 *
 * Found 2026-11-01: the inputs fit (its "risk" against what happened in the
 * simulator: AUC 0.94; flipped signs 0.80-0.92), but standalone it doesn't
 * fight. Its W/S and A/D are how players moved (labels from velocity), not
 * keys, so it copies its own motion: from a standstill "none" 68%, after a
 * hit "back" 100% (the knockback), even 5+ blocks away W only 24%. Against
 * the shipped net (v3) it won 0.2-1.5% with every convention, sampling and
 * the knockback fix - a fake number, so it isn't in the round report. The
 * real ECHO (v32) is only on minecraftcombatai.com's paid API.
 */
final class EchoPolicy implements PvpSim.Policy
{
	static final String FILE = "echo/combat_move_model.json";
	
	/** The model's inputs, in its order (checked when it loads). */
	static final String[] NAMES = {"dist", "rel_dx", "rel_dy", "rel_dz",
		"d_dist", "d_rel_dx", "d_rel_dz", "s_pitch", "s_ground", "s_fall",
		"s_hurt", "s_hp", "s_weapon", "o_ground", "o_sprint", "o_fall",
		"o_block", "o_hurt", "o_hp", "align_self", "align_opp", "m_aggression",
		"m_spacing", "m_interval_ms", "m_hit_range", "ms_since_attack",
		"s_attacked", "aim_yaw_err", "aim_pitch_err", "aim_err", "on_target",
		"box_dist", "eye_dist", "opp_aim_yaw_err", "opp_aim_pitch_err",
		"opp_aim_err", "opp_on_us", "opp_box_dist", "opp_eye_dist",
		"aim_disadvantage", "s_ping", "o_ping", "ping_gap", "o_vx", "o_vz",
		"o_speed", "o_using", "o_sneak", "ms_since_opp_swing",
		"opp_swing_recent", "o_side_speed", "o_closing_speed", "o_side_abs",
		"s_attack_strength", "s_sneak", "o_attack_strength"};
	static final int N = NAMES.length;
	
	/** ms_since_* stop counting here (fits the file's 650 +- 356). */
	static final double MS_CAP = 1000;
	/** Close enough for the style inputs to count, blocks. */
	static final double CLOSE = 8;
	
	/** The conventions the file can't tell (see calibrate). */
	record Variant(int yawSign, int sideSign, int closeSign, int strafeSign,
		int activation, int deltaSign)
	{
		
		static final Variant BASE = new Variant(1, 1, 1, 1, 0, 1);
		
		Variant(int yawSign, int sideSign, int closeSign, int strafeSign,
			int activation)
		{
			this(yawSign, sideSign, closeSign, strafeSign, activation, 1);
		}
		
		@Override
		public String toString()
		{
			return String.format(
				"yaw %+d side %+d closing %+d strafe %+d deltas %+d %s",
				yawSign, sideSign, closeSign, strafeSign, deltaSign,
				new String[]{"relu", "tanh", "gelu"}[activation]);
		}
	}
	
	// ── the model ────────────────────────────────────────────────────────
	
	static final class Model
	{
		int[] offsets;
		float[] mean, std;
		float[][] w = new float[3][], b = new float[3][];
		int[] width = new int[4];
		float[] forwardW, forwardB, strafeW, strafeB;
		float[] sprintW, jumpW, riskW;
		float sprintB, jumpB, riskB, sprintThr, jumpThr;
		int ring;
		
		@SuppressWarnings("unchecked")
		static Model read(Path file) throws IOException
		{
			Map<String, Object> d = (Map<String, Object>)new Json(
				Files.readString(file, StandardCharsets.UTF_8)).value();
			List<Object> names = (List<Object>)d.get("features");
			if(!Arrays.equals(names.toArray(), NAMES))
				throw new IOException("not the ECHO model this was written for"
					+ " (its inputs differ)");
			Model m = new Model();
			List<Object> offs = (List<Object>)d.get("offsets");
			m.offsets = new int[offs.size()];
			for(int i = 0; i < m.offsets.length; i++)
				m.offsets[i] = ((Number)offs.get(i)).intValue();
			m.ring = m.offsets[m.offsets.length - 1] + 1;
			m.mean = flat(d.get("mean"));
			m.std = flat(d.get("std"));
			for(int i = 0; i < m.std.length; i++)
				if(m.std[i] < 1e-6F)
					m.std[i] = 1;
			List<Object> trunk = (List<Object>)d.get("trunk");
			m.width[0] = N * m.offsets.length;
			for(int l = 0; l < 3; l++)
			{
				Map<String, Object> layer = (Map<String, Object>)trunk.get(l);
				m.w[l] = flat(layer.get("W"));
				m.b[l] = flat(layer.get("b"));
				m.width[l + 1] = m.b[l].length;
				if(m.w[l].length != m.width[l] * m.width[l + 1])
					throw new IOException(
						"trunk layer " + l + " has the wrong" + " size");
			}
			Map<String, Object> heads = (Map<String, Object>)d.get("heads");
			Map<String, Object> h = (Map<String, Object>)heads.get("forward");
			m.forwardW = flat(h.get("W"));
			m.forwardB = flat(h.get("b"));
			h = (Map<String, Object>)heads.get("strafe");
			m.strafeW = flat(h.get("W"));
			m.strafeB = flat(h.get("b"));
			h = (Map<String, Object>)heads.get("sprint");
			m.sprintW = flat(h.get("W"));
			m.sprintB = flat(h.get("b"))[0];
			m.sprintThr = ((Number)h.get("thr")).floatValue();
			h = (Map<String, Object>)heads.get("jump");
			m.jumpW = flat(h.get("W"));
			m.jumpB = flat(h.get("b"))[0];
			m.jumpThr = ((Number)h.get("thr")).floatValue();
			h = (Map<String, Object>)heads.get("risk");
			m.riskW = flat(h.get("W"));
			m.riskB = flat(h.get("b"))[0];
			return m;
		}
		
		/** Numbers, nested lists flattened row by row. */
		@SuppressWarnings("unchecked")
		private static float[] flat(Object o)
		{
			List<Float> out = new ArrayList<>();
			flatten(o, out);
			float[] a = new float[out.size()];
			for(int i = 0; i < a.length; i++)
				a[i] = out.get(i);
			return a;
		}
		
		@SuppressWarnings("unchecked")
		private static void flatten(Object o, List<Float> out)
		{
			if(o instanceof Number n)
				out.add(n.floatValue());
			else
				for(Object e : (List<Object>)o)
					flatten(e, out);
		}
		
		/** One layer: out = act(W in + b); W is rows of in's length. */
		static void layer(float[] w, float[] b, float[] in, int n, float[] out,
			int activation)
		{
			for(int r = 0; r < b.length; r++)
			{
				float s = b[r];
				int base = r * n;
				for(int c = 0; c < n; c++)
					s += w[base + c] * in[c];
				out[r] = switch(activation)
				{
					case 1 -> (float)Math.tanh(s);
					case 2 -> (float)(0.5 * s * (1 + Math
						.tanh(0.7978845608 * (s + 0.044715 * s * s * s))));
					default -> Math.max(0, s);
				};
			}
		}
		
		static float dot(float[] w, int row, float[] h)
		{
			float s = 0;
			int base = row * h.length;
			for(int i = 0; i < h.length; i++)
				s += w[base + i] * h[i];
			return s;
		}
	}
	
	private static volatile Model model;
	private static volatile boolean missing;
	
	/** The model in neural/trainer, or null (said once) if it isn't there. */
	static Model model(Path trainerDir)
	{
		Model m = model;
		if(m != null || missing)
			return m;
		synchronized(EchoPolicy.class)
		{
			if(model != null || missing)
				return model;
			Path file = trainerDir.resolve(FILE);
			try
			{
				model = Model.read(file);
			}catch(IOException | RuntimeException e)
			{
				missing = true;
				ContinuousTrainer.log("No ECHO test: %s (%s)", file, e);
			}
			return model;
		}
	}
	
	// ── the fighter ──────────────────────────────────────────────────────
	
	private final Model m;
	private final Variant v;
	private final double[][] frames;
	private int head, count, freeForward;
	/** hurtTime from which its "back" is the knockback, not a choice. */
	static final int KNOCKBACK_HURT = 7;
	private SplittableRandom random = new SplittableRandom();
	private final float[] in;
	private final float[][] act;
	private double lastDist, lastRx, lastRz, lastSx, lastSz;
	private double aggression, spacing;
	private int lastHurt;
	/** Predictions of "risk" still waiting for their 4 ticks. */
	private final float[] pendP = new float[RISK_AHEAD];
	private final int[] pendT = new int[RISK_AHEAD];
	private int pendN;
	
	EchoPolicy(Model m, Variant v)
	{
		this.m = m;
		this.v = v;
		frames = new double[m.ring][N];
		in = new float[m.width[0]];
		act = new float[][]{new float[m.width[1]], new float[m.width[2]],
			new float[m.width[3]]};
	}
	
	@Override
	public void reset(SplittableRandom random)
	{
		this.random = random;
		count = 0;
		freeForward = 1;
		pendN = 0;
		lastHurt = PvpSim.NEVER;
		// the file's means: an opponent it knows nothing about
		aggression = 0.015;
		spacing = 3.28;
	}
	
	@Override
	public void act(SimFighter me, NeuralObservation o, NeuralAction out)
	{
		out.clear();
		SimFighter op = me.target;
		if(op == null)
			return;
		PvpSim.Snapshot s = op.seen(me.viewDelay);
		int tick = me.sim.tick;
		
		head = (head + 1) % m.ring;
		double[] f = frames[head];
		fill(me, op, s, o, f);
		if(count++ == 0)
			for(double[] g : frames)
				System.arraycopy(f, 0, g, 0, N);
			
		for(int k = 0; k < m.offsets.length; k++)
		{
			double[] g = frames[Math.floorMod(head - m.offsets[k], m.ring)];
			for(int i = 0; i < N; i++)
			{
				int j = k * N + i;
				in[j] = (float)((g[i] - m.mean[j]) / m.std[j]);
			}
		}
		Model.layer(m.w[0], m.b[0], in, m.width[0], act[0], v.activation);
		Model.layer(m.w[1], m.b[1], act[0], m.width[1], act[1], v.activation);
		Model.layer(m.w[2], m.b[2], act[1], m.width[2], act[2], v.activation);
		float[] h = act[2];
		
		// classes: back / none / forward (closing in -> forward 81%, backing
		// off -> back 86%), and the same for A/D. Sampled, not the likeliest:
		// a cloned player mostly keeps doing what its motion shows, so the
		// likeliest from a standstill is to stand (68%) - forever
		out.forward = sample3(m.forwardW, m.forwardB, h) - 1;
		// its classes are how the player moved, not the keys: right after a
		// hit that's the knockback (back 100%, 90%, 46%, 32% at hurt 10..7,
		// probed), which nobody presses S for - keep the keys from before
		if(me.hurtTime >= KNOCKBACK_HURT)
			out.forward = freeForward;
		else
			freeForward = out.forward;
		out.strafe = v.strafeSign * (sample3(m.strafeW, m.strafeB, h) - 1);
		out.sprint =
			sigmoid(Model.dot(m.sprintW, 0, h) + m.sprintB) > m.sprintThr;
		out.jump = sigmoid(Model.dot(m.jumpW, 0, h) + m.jumpB) > m.jumpThr;
		out.attack = o.canHit && o.myChargeTicks == 0 && !me.busy;
		
		if(CALIBRATING)
			record(me, f, tick, sigmoid(Model.dot(m.riskW, 0, h) + m.riskB),
				out);
	}
	
	/** A class drawn by the softmax's probabilities. */
	private int sample3(float[] w, float[] b, float[] h)
	{
		double z0 = b[0] + Model.dot(w, 0, h), z1 = b[1] + Model.dot(w, 1, h),
			z2 = b[2] + Model.dot(w, 2, h);
		double max = Math.max(z0, Math.max(z1, z2));
		double e0 = Math.exp(z0 - max), e1 = Math.exp(z1 - max),
			e2 = Math.exp(z2 - max);
		double r = random.nextDouble() * (e0 + e1 + e2);
		return r < e0 ? 0 : r < e0 + e1 ? 1 : 2;
	}
	
	private static float sigmoid(float z)
	{
		return (float)(1 / (1 + Math.exp(-z)));
	}
	
	/** ECHO's 56 inputs of this tick, from what this fighter sees. */
	private void fill(SimFighter me, SimFighter op, PvpSim.Snapshot s,
		NeuralObservation o, double[] f)
	{
		double rx = s.x - me.x, ry = s.y - me.y, rz = s.z - me.z;
		double dist = Math.sqrt(rx * rx + rz * rz);
		boolean first = count == 0;
		double ovx = first ? 0 : s.x - lastSx, ovz = first ? 0 : s.z - lastSz;
		lastSx = s.x;
		lastSz = s.z;
		// their velocity toward us (+) and to the side, on the line between
		double ux = dist < 1e-4 ? 1 : rx / dist,
			uz = dist < 1e-4 ? 0 : rz / dist;
		double closing = -(ovx * ux + ovz * uz);
		double side = ovx * -uz + ovz * ux;
		if(dist < CLOSE)
		{
			double sign = closing > 0.03 ? 1 : closing < -0.03 ? -1 : 0;
			aggression += (sign - aggression) * 0.05;
			spacing += (dist - spacing) * 0.02;
		}
		double aimYaw = v.yawSign * o.aimYawErr;
		double opAimYaw = v.yawSign * o.opAimYawErr;
		int sinceTheirSwing = o.sinceTheirSwing;
		
		int i = 0;
		f[i++] = dist;
		f[i++] = rx;
		f[i++] = ry;
		f[i++] = rz;
		f[i++] = first ? 0 : v.deltaSign * (dist - lastDist);
		f[i++] = first ? 0 : v.deltaSign * (rx - lastRx);
		f[i++] = first ? 0 : v.deltaSign * (rz - lastRz);
		f[i++] = me.pitch;
		f[i++] = me.onGround ? 1 : 0;
		f[i++] = me.fallDistance;
		f[i++] = me.hurtTime;
		f[i++] = me.health;
		f[i++] = 1;
		f[i++] = s.onGround ? 1 : 0;
		f[i++] = s.sprint ? 1 : 0;
		f[i++] = s.fallDistance;
		f[i++] = s.blocking ? 1 : 0;
		f[i++] = s.hurtTime;
		f[i++] = op.health;
		f[i++] = Math.cos(Math.toRadians(o.aimYawErr));
		f[i++] = Math.cos(Math.toRadians(o.opAimYawErr));
		f[i++] = aggression;
		f[i++] = spacing;
		f[i++] = o.mInterval * 50;
		f[i++] = o.mHitRange;
		f[i++] = Math.min(MS_CAP, o.sinceMySwing * 50.0);
		f[i++] = o.sinceMySwing <= 2 ? 1 : 0;
		f[i++] = aimYaw;
		f[i++] = o.aimPitchErr;
		f[i++] = o.aimError;
		f[i++] = o.canHit ? 1 : 0;
		f[i++] = Math.max(-3, Math.min(3, o.dist - PvpSim.REACH));
		f[i++] = o.eyeDist;
		f[i++] = opAimYaw;
		f[i++] = o.opAimPitchErr;
		f[i++] = o.opAimError;
		f[i++] = o.opOnUs ? 1 : 0;
		f[i++] = Math.max(-3, Math.min(3, o.theirReach - PvpSim.REACH));
		f[i++] = o.eyeDist;
		f[i++] = o.aimError - o.opAimError;
		f[i++] = o.myPing;
		f[i++] = o.opPing;
		f[i++] = o.myPing - o.opPing;
		f[i++] = ovx;
		f[i++] = ovz;
		f[i++] = Math.sqrt(ovx * ovx + ovz * ovz);
		f[i++] = s.using ? 1 : 0;
		f[i++] = s.sneaking ? 1 : 0;
		f[i++] = Math.min(MS_CAP, sinceTheirSwing * 50.0);
		f[i++] = sinceTheirSwing < EchoFeatures.RECENT ? 1 : 0;
		f[i++] = v.sideSign * side;
		f[i++] = v.closeSign * closing;
		f[i++] = Math.abs(side);
		f[i++] =
			Math.min(1, Math.max(0, (me.attackTicker + 0.5) / PvpSim.COOLDOWN));
		f[i++] = me.sneaking ? 1 : 0;
		f[i++] = Math.min(1, (sinceTheirSwing + 0.5) / PvpSim.COOLDOWN);
		if(i != N)
			throw new AssertionError(i);
		lastDist = dist;
		lastRx = rx;
		lastRz = rz;
	}
	
	// ── calibration: how well the rebuilt inputs fit the model ───────────
	
	static final int RISK_AHEAD = 4;
	static volatile boolean CALIBRATING;
	private static final LongAdder[] RISK_POS = adders(1001),
		RISK_NEG = adders(1001), FWD = adders(3), FWD_FAR = adders(3),
		STRAFE = adders(3);
	private static final LongAdder SPRINTS = new LongAdder(),
		JUMPS = new LongAdder(), TICKS = new LongAdder(),
		FAR_TICKS = new LongAdder();
	private static final double[] SUM = new double[N], SUM2 = new double[N];
	private static long samples;
	
	private static LongAdder[] adders(int n)
	{
		LongAdder[] a = new LongAdder[n];
		for(int i = 0; i < n; i++)
			a[i] = new LongAdder();
		return a;
	}
	
	private void record(SimFighter me, double[] f, int tick, float risk,
		NeuralAction out)
	{
		// "risk": hit within the next 4 ticks
		boolean hurt =
			me.lastHurtTick != lastHurt && me.lastHurtTick != PvpSim.NEVER;
		lastHurt = me.lastHurtTick;
		int keep = 0;
		for(int k = 0; k < pendN; k++)
			if(hurt)
				RISK_POS[(int)(pendP[k] * 1000)].increment();
			else if(tick - pendT[k] >= RISK_AHEAD)
				RISK_NEG[(int)(pendP[k] * 1000)].increment();
			else
			{
				pendP[keep] = pendP[k];
				pendT[keep++] = pendT[k];
			}
		pendN = keep;
		if(pendN < RISK_AHEAD)
		{
			pendP[pendN] = risk;
			pendT[pendN++] = tick;
		}
		
		TICKS.increment();
		FWD[out.forward + 1].increment();
		STRAFE[v.strafeSign * out.strafe + 1].increment();
		if(f[0] > 5)
		{
			FAR_TICKS.increment();
			FWD_FAR[out.forward + 1].increment();
		}
		if(out.sprint)
			SPRINTS.increment();
		if(out.jump)
			JUMPS.increment();
		synchronized(SUM)
		{
			samples++;
			for(int i = 0; i < N; i++)
			{
				SUM[i] += f[i];
				SUM2[i] += f[i] * f[i];
			}
		}
	}
	
	private static void clearStats()
	{
		for(LongAdder[] a : new LongAdder[][]{RISK_POS, RISK_NEG, FWD, FWD_FAR,
			STRAFE})
			for(LongAdder x : a)
				x.reset();
		for(LongAdder x : new LongAdder[]{SPRINTS, JUMPS, TICKS, FAR_TICKS})
			x.reset();
		synchronized(SUM)
		{
			Arrays.fill(SUM, 0);
			Arrays.fill(SUM2, 0);
			samples = 0;
		}
	}
	
	/** Area under the ROC curve of "risk" (0.5 = no better than a coin). */
	private static double riskAuc()
	{
		double pos = 0, neg = 0, area = 0, negBelow = 0;
		for(int b = 0; b <= 1000; b++)
		{
			pos += RISK_POS[b].sum();
			neg += RISK_NEG[b].sum();
		}
		for(int b = 0; b <= 1000; b++)
		{
			long p = RISK_POS[b].sum(), n = RISK_NEG[b].sum();
			area += p * (negBelow + n / 2.0);
			negBelow += n;
		}
		return pos == 0 || neg == 0 ? Double.NaN : area / (pos * neg);
	}
	
	private static String shares(LongAdder[] a, LongAdder total)
	{
		double t = Math.max(1, total.sum());
		return String.format("%.0f/%.0f/%.0f%%", a[0].sum() * 100 / t,
			a[1].sum() * 100 / t, a[2].sum() * 100 / t);
	}
	
	/**
	 * Win rate (a draw counts half) of ECHO against the network, and how
	 * well it fits: its "risk" prediction against what happened (ECHO's own
	 * data: well above 0.5), how it moves.
	 */
	static double rate(Model model, Variant v, int[] sizes, float[] params,
		int games, long seed) throws Exception
	{
		return 1 - netWinRate(model, v, sizes, params, games, seed);
	}
	
	/** The network's win rate against ECHO (a draw counts half). */
	static double netWinRate(Model model, Variant v, int[] sizes,
		float[] params, int games, long seed) throws Exception
	{
		NeuralTrainer.Spec net = NeuralTrainer.netSpec(sizes, params);
		return NeuralTrainer.winRate(net, () -> new EchoPolicy(model, v), false,
			games, seed);
	}
	
	/**
	 * args: echo &lt;neural folder&gt; [games]: ECHO against the shipped
	 * network with each convention the file can't tell, how well its inputs
	 * fit (risk AUC, inputs far from the file's means), and how it moves.
	 */
	static void calibrate(String[] args) throws Exception
	{
		Path dir = Path.of(args[1]).resolve("trainer");
		int games = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
		Model model = model(dir);
		if(model == null)
			return;
		NeuralPolicy shipped = NeuralPolicy.get();
		int[] sizes = shipped.getSizes();
		float[] params = shipped.getParams();
		CALIBRATING = true;
		Variant b = Variant.BASE;
		Variant[] variants = {b, new Variant(-1, 1, 1, -1, 0),
			// settled 2026-11-01 (risk AUC 0.956 base, 0.916 closing -1,
			// 0.936 tanh, 0.955 gelu), kept to recheck
			new Variant(1, b.sideSign, -1, b.strafeSign, 0)};
		for(Variant v : variants)
		{
			clearStats();
			double r = rate(model, v, sizes, params, games, 0xEC40L);
			ContinuousTrainer.log(
				"ECHO %s: wins %.1f%% vs the shipped net, risk AUC %.3f,"
					+ " S/-/W %s (far: %s), right/-/left %s, sprint %.0f%%,"
					+ " jump %.0f%%",
				v, r * 100, riskAuc(), shares(FWD, TICKS),
				shares(FWD_FAR, FAR_TICKS), shares(STRAFE, TICKS),
				SPRINTS.sum() * 100.0 / Math.max(1, TICKS.sum()),
				JUMPS.sum() * 100.0 / Math.max(1, TICKS.sum()));
			if(v == b)
				logFit(model);
		}
	}
	
	/** The inputs whose simulator values are far from ECHO's own data. */
	private static void logFit(Model model)
	{
		StringBuilder sb = new StringBuilder();
		synchronized(SUM)
		{
			for(int i = 0; i < N; i++)
			{
				double mean = SUM[i] / samples;
				double sd =
					Math.sqrt(Math.max(0, SUM2[i] / samples - mean * mean));
				double z = (mean - model.mean[i]) / model.std[i];
				double ratio = sd / model.std[i];
				if(Math.abs(z) > 0.75 || ratio < 0.33 || ratio > 3)
					sb.append(String.format(" %s %.3g+-%.3g (ECHO %.3g+-%.3g)",
						NAMES[i], mean, sd, model.mean[i], model.std[i]));
			}
		}
		ContinuousTrainer.log("ECHO inputs unlike its data:%s",
			sb.length() == 0 ? " none" : sb);
	}
	
	// ── a small JSON reader (the trainer jar has no libraries) ──────────
	
	private static final class Json
	{
		private final String s;
		private int p;
		
		Json(String s)
		{
			this.s = s;
		}
		
		Object value() throws IOException
		{
			skip();
			char c = s.charAt(p);
			if(c == '{')
			{
				Map<String, Object> map = new HashMap<>();
				p++;
				skip();
				if(s.charAt(p) == '}')
				{
					p++;
					return map;
				}
				while(true)
				{
					skip();
					String key = (String)value();
					skip();
					expect(':');
					map.put(key, value());
					skip();
					if(s.charAt(p++) == '}')
						return map;
				}
			}
			if(c == '[')
			{
				List<Object> list = new ArrayList<>();
				p++;
				skip();
				if(s.charAt(p) == ']')
				{
					p++;
					return list;
				}
				while(true)
				{
					list.add(value());
					skip();
					if(s.charAt(p++) == ']')
						return list;
				}
			}
			if(c == '"')
			{
				StringBuilder sb = new StringBuilder();
				p++;
				while(s.charAt(p) != '"')
				{
					char ch = s.charAt(p++);
					if(ch == '\\')
					{
						char e = s.charAt(p++);
						if(e == 'u')
						{
							ch = (char)Integer.parseInt(s.substring(p, p + 4),
								16);
							p += 4;
						}else
							ch = switch(e)
							{
								case 'n' -> '\n';
								case 't' -> '\t';
								case 'r' -> '\r';
								case 'b' -> '\b';
								case 'f' -> '\f';
								default -> e;
							};
					}
					sb.append(ch);
				}
				p++;
				return sb.toString();
			}
			if(s.startsWith("true", p))
			{
				p += 4;
				return Boolean.TRUE;
			}
			if(s.startsWith("false", p))
			{
				p += 5;
				return Boolean.FALSE;
			}
			if(s.startsWith("null", p))
			{
				p += 4;
				return null;
			}
			int start = p;
			while(p < s.length() && "+-.eE0123456789".indexOf(s.charAt(p)) >= 0)
				p++;
			if(start == p)
				throw new IOException("bad JSON at " + p);
			return Double.parseDouble(s.substring(start, p));
		}
		
		private void skip()
		{
			while(p < s.length() && Character.isWhitespace(s.charAt(p)))
				p++;
		}
		
		private void expect(char c) throws IOException
		{
			if(s.charAt(p++) != c)
				throw new IOException("bad JSON at " + (p - 1));
		}
	}
}
