/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathPos;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.commands.PathCmd;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.util.KeyPresser;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.PauseAttackOnContainersSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.CameraAim;
import net.wurstclient.util.EntityUtils;

@SearchTags({"fight bot"})
@DontSaveState
public final class FightBotHack extends Hack
	implements UpdateListener, RenderListener
{
	/** Same top speed as AimAssist. */
	private static final double AIM_SPEED = 720;
	
	/** The smooth, human-like aim, shared with AimAssist. */
	private final CameraAim cameraAim = new CameraAim();
	
	/** What the camera turns toward this tick (close range only). */
	private Entity frameAimTarget;
	
	/** The previous target, kept unless something is clearly closer. */
	private Entity lastEntity;
	
	private final SliderSetting range = new SliderSetting("Range",
		"Attack range (like Killaura)", 4.25, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final AttackSpeedSliderSetting speed =
		new AttackSpeedSliderSetting();
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericCombatDescription(this), SwingHand.CLIENT);
	
	private final SliderSetting distance = new SliderSetting("Distance",
		"How closely to follow the target.\n"
			+ "This should be set to a lower value than Range.",
		3, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting useAi =
		new CheckboxSetting("Use AI (experimental)", false);
	
	private final PauseAttackOnContainersSetting pauseOnContainers =
		new PauseAttackOnContainersSetting(true);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericCombat();
	
	private EntityPathFinder pathFinder;
	private PathProcessor processor;
	private int ticksProcessing;
	
	public FightBotHack()
	{
		super("FightBot");
		
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(speed);
		addSetting(swingHand);
		addSetting(distance);
		addSetting(useAi);
		addSetting(pauseOnContainers);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		WURST.getHax().tunnellerHack.setEnabled(false);
		
		pathFinder = new EntityPathFinder(MC.player);
		
		speed.resetTimer();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		// remove listener
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		pathFinder = null;
		processor = null;
		ticksProcessing = 0;
		PathProcessor.releaseControls();
	}
	
	@Override
	public void onUpdate()
	{
		speed.updateTimer();
		
		// set again below while fighting at close range
		frameAimTarget = null;
		
		if(pauseOnContainers.shouldPause())
			return;
		
		// set entity
		Stream<Entity> stream = EntityUtils.getAttackableEntities();
		stream = entityFilters.applyTo(stream);
		
		Entity entity = stream
			.min(Comparator.comparingDouble(EntityUtils::distanceToHitboxSq))
			.orElse(null);
		entity = stickToTarget(lastEntity, entity);
		lastEntity = entity;
		if(entity == null)
			return;
		
		WURST.getHax().autoSwordHack.setSlot(entity);
		
		if(useAi.isChecked())
		{
			// reset pathfinder
			if((processor == null || processor.isDone() || ticksProcessing >= 10
				|| !pathFinder.isPathStillValid(processor.getIndex()))
				&& (pathFinder.isDone() || pathFinder.isFailed()))
			{
				pathFinder = new EntityPathFinder(entity);
				processor = null;
				ticksProcessing = 0;
			}
			
			// find path
			if(!pathFinder.isDone() && !pathFinder.isFailed())
			{
				PathProcessor.lockControls();
				WURST.getRotationFaker()
					.faceVectorClient(entity.getBoundingBox().getCenter());
				pathFinder.think();
				pathFinder.formatPath();
				processor = pathFinder.getProcessor();
			}
			
			// process path
			if(!processor.isDone())
			{
				processor.process();
				ticksProcessing++;
			}
		}else
		{
			// jump if necessary
			if(MC.player.horizontalCollision && MC.player.onGround())
				KeyPresser.press(MC.options.keyJump);
			
			// swim up if necessary
			if(MC.player.isInWater() && MC.player.getY() < entity.getY())
				KeyPresser.press(MC.options.keyJump);
			
			// control height if flying
			if(!MC.player.onGround()
				&& (MC.player.getAbilities().flying
					|| WURST.getHax().flightHack.isEnabled())
				&& MC.player.distanceToSqr(entity.getX(), MC.player.getY(),
					entity.getZ()) <= MC.player.distanceToSqr(MC.player.getX(),
						entity.getY(), MC.player.getZ()))
			{
				if(MC.player.getY() > entity.getY() + 1D)
					IKeyMapping.get(MC.options.keyShift)
						.setDownIgnoringToggle(true);
				else if(MC.player.getY() < entity.getY() - 1D)
					MC.options.keyJump.setDown(true);
			}else
			{
				IKeyMapping.get(MC.options.keyShift)
					.setDownIgnoringToggle(false);
				MC.options.keyJump.setDown(false);
			}
			
			// follow entity
			MC.options.keyUp
				.setDown(MC.player.distanceTo(entity) > distance.getValueF());
			frameAimTarget = entity;
		}
		
		// check cooldown
		if(!speed.isTimeToAttack())
			return;
		
		// check range
		if(EntityUtils.distanceToHitboxSq(entity) > range.getValueSq())
			return;
		
		// while turning smoothly, only swing once the crosshair is on it
		if(entity == frameAimTarget
			&& !CameraAim.isLookingAt(MC.player.getYRot(), MC.player.getXRot(),
				entity.getBoundingBox().inflate(entity.getPickRadius()),
				range.getValue() + 1))
			return;
		
		// attack entity
		MC.gameMode.attack(MC.player, entity);
		swingHand.swing(InteractionHand.MAIN_HAND);
		speed.resetTimer();
	}
	
	/**
	 * Keeps the previous target unless the closest one is clearly closer (by
	 * about 2 blocks). Re-picking the closest every tick flipped between two
	 * targets at a similar distance, and every flip restarted the smooth
	 * aim's turn, reaction delay and all.
	 */
	private Entity stickToTarget(Entity previous, Entity closest)
	{
		if(previous == null || closest == null || previous == closest)
			return closest;
		
		if(!EntityUtils.IS_ATTACKABLE.test(previous)
			|| previous.level() != MC.level
			|| entityFilters.applyTo(Stream.of(previous)).findAny().isEmpty())
			return closest;
		
		double keep = Math.sqrt(EntityUtils.distanceToHitboxSq(previous));
		double best = Math.sqrt(EntityUtils.distanceToHitboxSq(closest));
		return keep <= best + 2 ? previous : closest;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		// While fighting at close range the camera turns every frame, like a
		// mouse, with the same smooth, human-like aim as AimAssist.
		if(frameAimTarget != null)
			cameraAim.aimAtEntity(frameAimTarget,
				frameAimTarget.getBoundingBox().getCenter(), partialTicks, true,
				AIM_SPEED);
		else
			cameraAim.reset();
		
		PathCmd pathCmd = WURST.getCmds().pathCmd;
		pathFinder.renderPath(matrixStack, pathCmd.isDebugMode(),
			pathCmd.isDepthTest());
	}
	
	private class EntityPathFinder extends PathFinder
	{
		private final Entity entity;
		
		public EntityPathFinder(Entity entity)
		{
			super(BlockPos.containing(entity.position()));
			this.entity = entity;
			setThinkTime(1);
		}
		
		@Override
		protected boolean checkDone()
		{
			return done = entity.distanceToSqr(Vec3.atCenterOf(current)) <= Math
				.pow(distance.getValue(), 2);
		}
		
		@Override
		public ArrayList<PathPos> formatPath()
		{
			if(!done)
				failed = true;
			
			return super.formatPath();
		}
	}
}
