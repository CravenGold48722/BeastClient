/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist;

/**
 * The strategy the AI wants AimAssist to fight with.
 *
 * <p>
 * This is deliberately <i>not</i> a list of key presses. A Gemini round trip
 * takes far longer than a tick, so the model sets the plan and the tick-rate
 * reflex layer in AimAssist decides the exact moment to act on it.
 *
 * @param dodgeBias
 *            which way to strafe when a dodge is triggered
 * @param dodgeTicks
 *            how long a triggered dodge should be held, in ticks
 * @param aggression
 *            0 = play it safe and dodge at the first sign of a threat, 1 =
 *            stay on the target and dodge only when a hit is imminent
 * @param targetId
 *            entity ID the AI wants to fight, or -1 to keep the current target
 * @param disengage
 *            whether to back away instead of pressing the attack
 * @param reason
 *            the model's short explanation, surfaced for debugging
 * @param receivedAt
 *            {@link System#nanoTime()} when this strategy arrived, used to
 *            ignore advice that has gone stale
 */
public record AiStrategy(Dodge dodgeBias, int dodgeTicks, float aggression,
	int targetId, boolean disengage, String reason, long receivedAt)
{
	public enum Dodge
	{
		LEFT,
		RIGHT,
		NONE;
		
		public static Dodge parse(String raw)
		{
			if(raw == null)
				return NONE;
			
			return switch(raw.trim().toLowerCase())
			{
				case "left", "a" -> LEFT;
				case "right", "d" -> RIGHT;
				default -> NONE;
			};
		}
	}
	
	/** The neutral strategy used before the AI has said anything. */
	public static final AiStrategy DEFAULT =
		new AiStrategy(Dodge.NONE, 4, 0.5F, -1, false, "", 0L);
	
	/**
	 * Whether this strategy is recent enough to act on. Advice older than a
	 * couple of seconds describes a fight that has already moved on.
	 */
	public boolean isFresh(long maxAgeNanos)
	{
		return receivedAt != 0L
			&& System.nanoTime() - receivedAt <= maxAgeNanos;
	}
}
