/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A real fight, tick by tick, as the server showed it (ECHO's "pvpnewset":
 * it learned from recorded server packets): your side - what you saw, the
 * keys pressed and whether you pressed them or the network did - and the
 * opponent's - what they saw and the keys their movement, flags and swings
 * give away (OpponentRecorder) - plus the damage both ways, so the trainer
 * can tell which moves paid off. One gzip file per fight in
 * {@code .minecraft/wurst/neural/fights}; inputs as 16-bit floats.
 * No MC classes: AimAssist writes, the trainer reads.
 */
public final class FightLog
{
	static final int MAGIC = 0x42464C31; // "BFL1"
	static final byte OURS = 0, THEIRS = 1, END = 2, EVENT = 3;
	
	/**
	 * Raw packet events (ECHO grounded its labels in the server's packets):
	 * their position as the server sent it (x, y, z, on ground 1/0), their
	 * swing (an animate packet), their knockback (a velocity packet: vx, vy,
	 * vz), the keys your client sent (ServerboundPlayerInputPacket: bits W
	 * S A D jump sneak sprint), your attack packet.
	 */
	public static final byte THEIR_POS = 1, THEIR_SWING = 2,
		THEIR_KNOCKBACK = 3, OUR_INPUT = 4, OUR_ATTACK = 5;
	/**
	 * Why the network's hit didn't go out (a: AimAssist's NO_HIT_* bits, b:
	 * the attack charge, c: the hitbox distance, d: the selected slot), and
	 * a tick AimAssist skipped the network (a: its SKIP_* code) - so a fight
	 * without hits says why (user, 2026-10-31: 230 ticks it wanted to and
	 * could hit, 0 hits).
	 */
	public static final byte NO_HIT = 6, SKIPPED = 7;
	/** A fight shorter than this isn't kept (someone walking past). */
	static final int MIN_TICKS = 100;
	
	/** One tick or sample of a fight. */
	public static final class Row
	{
		public final int tick;
		public final float[] x;
		public final byte[] y;
		/** Ours only: you pressed the keys (not the network). */
		public final boolean human;
		/** Ours only: damage dealt and taken so far this fight. */
		public final float dealt, taken;
		
		Row(int tick, float[] x, byte[] y, boolean human, float dealt,
			float taken)
		{
			this.tick = tick;
			this.x = x;
			this.y = y;
			this.human = human;
			this.dealt = dealt;
			this.taken = taken;
		}
	}
	
	/** A raw packet event (EVENT's kinds). */
	public record Event(int tick, byte kind, float a, float b, float c, float d)
	{}
	
	/** A whole fight read back. */
	public static final class Fight
	{
		public UUID opponent;
		public String name;
		public long start;
		/** 1 you won, -1 they did, 0 neither (it just ended). */
		public int result;
		public final List<Row> ours = new ArrayList<>(),
			theirs = new ArrayList<>();
		public final List<Event> events = new ArrayList<>();
	}
	
	// ── writing (client thread) ─────────────────────────────────────────
	
	private static final int FLUSH_ROWS = 100;
	
	private final Path folder;
	private DataOutputStream out;
	private Path file;
	private int rows;
	
	public FightLog(Path folder)
	{
		this.folder = folder;
	}
	
	public boolean isOpen()
	{
		return out != null;
	}
	
	/** Starts a fight's file (ends any open one first). */
	public void begin(UUID opponent, String name)
	{
		end(0);
		try
		{
			Files.createDirectories(folder);
			String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
			file = folder
				.resolve(System.currentTimeMillis() + "-" + safe + ".fight.gz");
			out = new DataOutputStream(new BufferedOutputStream(
				new GZIPOutputStream(Files.newOutputStream(file), true),
				1 << 16));
			out.writeInt(MAGIC);
			out.writeInt(NeuralObservation.VERSION);
			out.writeInt(NeuralObservation.SIZE);
			out.writeLong(System.currentTimeMillis());
			out.writeLong(opponent.getMostSignificantBits());
			out.writeLong(opponent.getLeastSignificantBits());
			out.writeUTF(name);
			rows = 0;
		}catch(IOException e)
		{
			close();
			e.printStackTrace();
		}
	}
	
	/** Your side of this tick (y: Backprop's labels, -1 = unknown). */
	public void ours(int tick, float[] x, byte[] y, boolean human, float dealt,
		float taken)
	{
		if(out == null)
			return;
		try
		{
			out.writeByte(OURS);
			out.writeInt(tick);
			writeRow(x, y);
			out.writeBoolean(human);
			out.writeFloat(dealt);
			out.writeFloat(taken);
			// on disk every 5 s (sync flush: readable without the end), so
			// closing the game mid-fight loses at most that
			if(++rows % FLUSH_ROWS == 0)
				out.flush();
		}catch(IOException e)
		{
			close();
		}
	}
	
	/** A sample of theirs, as OpponentRecorder makes it. */
	public void theirs(int tick, float[] x, byte[] y)
	{
		if(out == null)
			return;
		try
		{
			out.writeByte(THEIRS);
			out.writeInt(tick);
			writeRow(x, y);
		}catch(IOException e)
		{
			close();
		}
	}
	
	/** A raw packet event (see EVENT). */
	public void event(int tick, byte kind, float a, float b, float c, float d)
	{
		if(out == null)
			return;
		try
		{
			out.writeByte(EVENT);
			out.writeInt(tick);
			out.writeByte(kind);
			out.writeFloat(a);
			out.writeFloat(b);
			out.writeFloat(c);
			out.writeFloat(d);
		}catch(IOException e)
		{
			close();
		}
	}
	
	/** Ends the fight: 1 you won, -1 they did, 0 neither. */
	public void end(int result)
	{
		if(out == null)
			return;
		try
		{
			out.writeByte(END);
			out.writeByte(result);
		}catch(IOException e)
		{
			// closed below
		}
		boolean keep = rows >= MIN_TICKS;
		close();
		if(!keep && file != null)
			try
			{
				Files.deleteIfExists(file);
			}catch(IOException e)
			{
				// left behind: the reader skips short fights too
			}
	}
	
	private void writeRow(float[] x, byte[] y) throws IOException
	{
		out.writeShort(x.length);
		for(float v : x)
			out.writeShort(Float.floatToFloat16(v));
		out.write(y);
	}
	
	private void close()
	{
		try
		{
			if(out != null)
				out.close();
		}catch(IOException e)
		{
			// nothing to do
		}
		out = null;
	}
	
	// ── reading (trainer) ───────────────────────────────────────────────
	
	/** Every fight in the folder recorded with the current inputs. */
	public static List<Fight> readAll(Path folder)
	{
		List<Fight> fights = new ArrayList<>();
		if(!Files.isDirectory(folder))
			return fights;
		try(var files = Files.list(folder))
		{
			for(Path p : (Iterable<Path>)files.sorted()::iterator)
			{
				if(!p.toString().endsWith(".fight.gz"))
					continue;
				Fight f = read(p);
				if(f != null && f.ours.size() >= MIN_TICKS)
					fights.add(f);
			}
		}catch(IOException e)
		{
			e.printStackTrace();
		}
		return fights;
	}
	
	/** One fight, or null if it's from other inputs or unreadable. */
	public static Fight read(Path file)
	{
		try(InputStream raw = Files.newInputStream(file);
			DataInputStream in = new DataInputStream(
				new BufferedInputStream(new GZIPInputStream(raw), 1 << 16)))
		{
			// 12: the same inputs (13 only added the attention's weights);
			// 14 added inputs at the end (the enchantments): older fights
			// read with those 0 - unknown
			if(in.readInt() != MAGIC || in.readInt() < 12
				|| in.readInt() > NeuralObservation.SIZE)
				return null;
			Fight f = new Fight();
			f.start = in.readLong();
			f.opponent = new UUID(in.readLong(), in.readLong());
			f.name = in.readUTF();
			try
			{
				for(;;)
				{
					byte type = in.readByte();
					if(type == END)
					{
						f.result = in.readByte();
						break;
					}
					int tick = in.readInt();
					if(type == EVENT)
					{
						f.events.add(new Event(tick, in.readByte(),
							in.readFloat(), in.readFloat(), in.readFloat(),
							in.readFloat()));
						continue;
					}
					float[] x = readX(in);
					byte[] y = new byte[Backprop.LABELS];
					in.readFully(y);
					if(type == OURS)
						f.ours.add(new Row(tick, x, y, in.readBoolean(),
							in.readFloat(), in.readFloat()));
					else
						f.theirs.add(new Row(tick, x, y, false, 0, 0));
				}
			}catch(EOFException e)
			{
				// cut off (the game closed): what's there counts
			}
			return f;
		}catch(IOException e)
		{
			return null;
		}
	}
	
	private static float[] readX(DataInputStream in) throws IOException
	{
		// (an older, shorter row: the rest stays 0)
		int n = in.readShort();
		float[] x = new float[Math.max(n, NeuralObservation.SIZE)];
		for(int i = 0; i < n; i++)
			x[i] = Float.float16ToFloat(in.readShort());
		return x;
	}
	
}
