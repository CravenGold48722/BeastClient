/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.SearchHack;
import net.wurstclient.util.BlockUtils;

public final class SearchCmd extends Command
{
	public SearchCmd()
	{
		super("search",
			"Sets the block Search looks for and turns it on, or turns it"
				+ " on/off from chat.",
			".search <block>", ".search [on|off]");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		SearchHack search = WURST.getHax().searchHack;
		
		if(args.length == 0)
		{
			search.setEnabled(!search.isEnabled());
			return;
		}
		
		String action = args[0].toLowerCase();
		switch(action)
		{
			case "on":
			search.setEnabled(true);
			return;
			
			case "off":
			search.setEnabled(false);
			return;
			
			default:
			if(args.length != 1)
				throw new CmdSyntaxError();
			
			Block block = BlockUtils.getBlockFromNameOrID(args[0]);
			if(block == null || block == Blocks.AIR)
				throw new CmdError("Unknown block: " + args[0]);
			
			search.setBlock(block);
			search.setEnabled(true);
			return;
		}
	}
}
