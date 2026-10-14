/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.LeftClickListener;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.util.HitCheck;
import net.wurstclient.util.KeyPresser;

/**
 * The jump modes do what a player does for a critical hit: hit while falling
 * from a real jump. A hit you click on the ground is held back, the jump key
 * is pressed, and the hit goes out on the way down - no faked movement, so a
 * movement simulation (Grim, Intave) has nothing to flag. (Mini Jump used to
 * push you up 0.1 blocks, which no player can do; it's the quick version of
 * the real jump now.)
 */
@SearchTags({"Crits"})
public final class CriticalsHack extends Hack
	implements PlayerAttacksEntityListener, LeftClickListener, UpdateListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"§lPacket§r mode sends packets to server without actually"
			+ " moving you at all. Anti-cheats catch it.\n\n"
			+ "§lMini Jump§r mode jumps when you hit and lands the"
			+ " hit as soon as the server has seen you falling - the quickest real"
			+ " critical hit.\n\n"
			+ "§lFull Jump§r mode jumps when you hit and lands the"
			+ " hit on the way down, once your attack is fully recharged.\n\n"
			+ "Both jump modes are real jumps with the jump key - nothing is"
			+ " faked. Hits from other hacks (Killaura...) just make you jump,"
			+ " so their next hit is a critical one.",
		Mode.values(), Mode.FULL_JUMP);
	
	/** The hit waiting for the fall, or null. */
	private Entity heldTarget;
	private int heldTicks;
	private boolean leftGround;
	private int fallTicks;
	/** Our own attack call, which mustn't start another jump. */
	private boolean attacking;
	
	public CriticalsHack()
	{
		super("Criticals");
		setCategory(Category.COMBAT);
		addSetting(mode);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + mode.getSelected() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		heldTarget = null;
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		EVENTS.add(LeftClickListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		EVENTS.remove(LeftClickListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		heldTarget = null;
	}
	
	@Override
	public void onLeftClick(LeftClickEvent event)
	{
		if(mode.getSelected() == Mode.PACKET)
			return;
		
		// already jumping for a critical hit: that hit is on its way
		if(heldTarget != null)
		{
			event.cancel();
			return;
		}
		
		if(!(MC.hitResult instanceof EntityHitResult hit)
			|| !(hit.getEntity() instanceof LivingEntity target)
			|| !canJumpForCrit())
			return;
		
		// hold the hit back and jump; onUpdate() lands it on the way down
		event.cancel();
		heldTarget = target;
		heldTicks = 0;
		fallTicks = 0;
		leftGround = false;
		KeyPresser.press(MC.options.keyJump);
	}
	
	@Override
	public void onUpdate()
	{
		if(heldTarget == null)
			return;
		
		heldTicks++;
		if(!MC.player.onGround())
			leftGround = true;
		
		if(heldTarget.isRemoved() || !heldTarget.isAlive()
			|| heldTarget.level() != MC.level || heldTicks > 30
			|| MC.player.isInWater() || MC.player.isInLava())
		{
			heldTarget = null;
			return;
		}
		
		// the jump never happened, or it's over: the hit a player would have
		// made anyway (a normal one)
		boolean noJump = !leftGround && heldTicks > 4;
		boolean landed = leftGround && MC.player.onGround();
		if(noJump || landed)
		{
			attackHeld();
			heldTarget = null;
			return;
		}
		
		// vanilla's critical hit: falling, not on the ground (and not
		// sprinting, climbing, swimming, riding or blind - checked before
		// the jump)
		boolean falling = !MC.player.onGround() && MC.player.fallDistance > 0
			&& MC.player.getDeltaMovement().y < 0;
		fallTicks = falling ? fallTicks + 1 : 0;
		
		// The hit goes out before this tick's movement packet, so the server
		// judges it by last tick's: on the first falling tick it still has
		// you at the top of the jump - no critical hit (Paper/Intave). Both
		// hit as soon as the server has seen the fall; Full Jump also waits
		// (still falling) for the attack to be fully recharged.
		int wait = 2;
		if(fallTicks < wait)
			return;
		
		float needed = mode.getSelected() == Mode.FULL_JUMP ? 0.99F : 0.9F;
		if(MC.player.getAttackStrengthScale(0.5F) < needed)
			return;
		
		// only when the crosshair really is on it, like a click
		if(!HitCheck.isVerifiable(heldTarget))
			return;
		
		attackHeld();
		heldTarget = null;
	}
	
	private void attackHeld()
	{
		if(!HitCheck.isVerifiable(heldTarget))
			return;
		
		attacking = true;
		try
		{
			MC.gameMode.attack(MC.player, heldTarget);
			MC.player.swing(InteractionHand.MAIN_HAND);
			
		}finally
		{
			attacking = false;
		}
	}
	
	/** Everything vanilla's critical hit needs, apart from the fall. */
	private boolean canJumpForCrit()
	{
		return MC.player.onGround() && !MC.player.isInWater()
			&& !MC.player.isInLava() && !MC.player.onClimbable()
			&& !MC.player.isPassenger() && !MC.player.isSprinting()
			&& !MC.player.hasEffect(MobEffects.BLINDNESS);
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		if(attacking || !(target instanceof LivingEntity))
			return;
		
		if(WURST.getHax().maceDmgHack.isEnabled()
			&& MC.player.getMainHandItem().is(Items.MACE))
			return;
		
		if(!MC.player.onGround())
			return;
		
		if(MC.player.isInWater() || MC.player.isInLava())
			return;
		
		switch(mode.getSelected())
		{
			case PACKET:
			doPacketJump();
			break;
			
			// a hit that didn't come from a click here (Killaura...): jump, so
			// the next one is a critical hit
			case MINI_JUMP:
			case FULL_JUMP:
			if(canJumpForCrit())
				KeyPresser.press(MC.options.keyJump);
			break;
		}
	}
	
	private void doPacketJump()
	{
		sendFakeY(0.0625, true);
		sendFakeY(0, false);
		sendFakeY(1.1e-5, false);
		sendFakeY(0, false);
	}
	
	private void sendFakeY(double offset, boolean onGround)
	{
		MC.player.connection
			.send(new Pos(MC.player.getX(), MC.player.getY() + offset,
				MC.player.getZ(), onGround, MC.player.horizontalCollision));
	}
	
	private enum Mode
	{
		PACKET("Packet"),
		MINI_JUMP("Mini Jump"),
		FULL_JUMP("Full Jump");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
