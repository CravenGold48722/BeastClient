/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import javax.annotation.Nullable;
import net.minecraft.resources.Identifier;
import net.wurstclient.Category;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;

public final class MobViewHack extends Hack implements UpdateListener
{
	private final EnumSetting<Mob> mob =
		new EnumSetting<>("Mob", Mob.values(), Mob.SPIDER);
	@Nullable
	private Identifier appliedPostEffect;
	
	public MobViewHack()
	{
		super("MobView");
		setCategory(Category.FUN);
		addSetting(mob);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		updatePostEffect();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		removePostEffect();
	}
	
	@Override
	public void onUpdate()
	{
		updatePostEffect();
	}
	
	private void updatePostEffect()
	{
		if(MC.player == null)
			return;
		appliedPostEffect = mob.getSelected().postEffect;
		if(appliedPostEffect != null)
			MC.gameRenderer.setPostEffect(appliedPostEffect);
		else
			MC.gameRenderer.clearPostEffect();
	}
	
	private void removePostEffect()
	{
		if(appliedPostEffect != null)
			MC.gameRenderer.clearPostEffect();
		appliedPostEffect = null;
	}
	
	private enum Mob
	{
		SPIDER("spider"),
		CREEPER("creeper"),
		ENDERMAN("invert"),
		CHICKEN(null);
		
		@Nullable
		private final Identifier postEffect;
		
		Mob(@Nullable String postEffect)
		{
			this.postEffect = postEffect == null ? null
				: Identifier.withDefaultNamespace(postEffect);
		}
	}
}
