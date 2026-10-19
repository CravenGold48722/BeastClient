/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.events;

import java.util.ArrayList;

import net.wurstclient.event.Event;
import net.wurstclient.event.Listener;
import net.wurstclient.util.HackPerformanceTracker;

public interface UpdateListener extends Listener
{
	public void onUpdate();
	
	public static class UpdateEvent extends Event<UpdateListener>
	{
		public static final UpdateEvent INSTANCE = new UpdateEvent();
		
		@Override
		public void fire(ArrayList<UpdateListener> listeners)
		{
			// PerformanceOverlay (ported from the CevAPI fork): time each hack
			// only while the overlay is on
			if(!HackPerformanceTracker.shouldProfile())
			{
				for(UpdateListener listener : listeners)
					listener.onUpdate();
				return;
			}
			
			for(UpdateListener listener : listeners)
			{
				long start = System.nanoTime();
				try
				{
					listener.onUpdate();
					
				}finally
				{
					HackPerformanceTracker.record(listener,
						HackPerformanceTracker.Phase.UPDATE,
						System.nanoTime() - start);
				}
			}
		}
		
		@Override
		public Class<UpdateListener> getListenerType()
		{
			return UpdateListener.class;
		}
	}
}
