/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * {@code Math.acos} and {@code Math.atan2} for the simulator. The JDK's are
 * the exact (fdlibm) ones, not CPU intrinsics, and were a quarter of a
 * simulated tick. These are Abramowitz and Stegun's polynomials 4.4.46 and
 * 4.4.49: within 2e-8 radians (~1e-6 degrees) everywhere, a few
 * multiply-adds each.
 */
public final class FastTrig
{
	private FastTrig()
	{}
	
	/** acos(x) for x in [-1, 1]. */
	public static double acos(double x)
	{
		boolean negative = x < 0;
		double a = negative ? -x : x;
		double p = -0.0012624911;
		p = p * a + 0.0066700901;
		p = p * a - 0.0170881256;
		p = p * a + 0.0308918810;
		p = p * a - 0.0501743046;
		p = p * a + 0.0889789874;
		p = p * a - 0.2145988016;
		p = p * a + 1.5707963050;
		double r = Math.sqrt(1 - a) * p;
		return negative ? Math.PI - r : r;
	}
	
	/** atan2(y, x), in radians. */
	public static double atan2(double y, double x)
	{
		double ax = Math.abs(x), ay = Math.abs(y);
		if(ax == 0 && ay == 0)
			return 0;
		double r =
			ay <= ax ? atanUnit(ay / ax) : Math.PI / 2 - atanUnit(ax / ay);
		if(x < 0)
			r = Math.PI - r;
		return y < 0 ? -r : r;
	}
	
	/** atan(t) for t in [0, 1]. */
	private static double atanUnit(double t)
	{
		double t2 = t * t;
		double p = 0.0028662257;
		p = p * t2 - 0.0161657367;
		p = p * t2 + 0.0429096138;
		p = p * t2 - 0.0752896400;
		p = p * t2 + 0.1065626393;
		p = p * t2 - 0.1420889944;
		p = p * t2 + 0.1999355085;
		p = p * t2 - 0.3333314528;
		return t + t * t2 * p;
	}
}
