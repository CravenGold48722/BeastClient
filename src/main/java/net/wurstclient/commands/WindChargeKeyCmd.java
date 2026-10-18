/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;

/**
 * For keybinds: ".windchargekey use" throws a wind charge the way
 * WindChargeKey's key does (only while that hack is on).
 */
public final class WindChargeKeyCmd extends Command
{
	public WindChargeKeyCmd()
	{
		super("windchargekey",
			"Throws a wind charge with WindChargeKey's settings, while"
				+ " WindChargeKey is enabled.",
			".windchargekey use");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length != 1 || !args[0].equalsIgnoreCase("use"))
			throw new CmdSyntaxError();
		
		WURST.getHax().windChargeKeyHack.useFromKeybind();
	}
}
