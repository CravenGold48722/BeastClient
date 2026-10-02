/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FaceTargetSetting;
import net.wurstclient.settings.FaceTargetSetting.FaceTarget;
import net.wurstclient.settings.PauseAttackOnContainersSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.CameraAim;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"kill aura", "ForceField", "force field", "CrystalAura",
	"crystal aura", "AutoCrystal", "auto crystal"})
public final class KillauraHack extends Hack
	implements UpdateListener, HandleInputListener, RenderListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Determines how far Killaura will reach to attack entities.\n"
			+ "Anything that is further away than the specified value will not be attacked.",
		5, 1, 10, 0.05, ValueDisplay.DECIMAL);
	
	private final AttackSpeedSliderSetting speed =
		new AttackSpeedSliderSetting();
	
	private final SliderSetting speedRandMS =
		new SliderSetting("Speed randomization",
			"Helps you bypass anti-cheat plugins by varying the delay between"
				+ " attacks.\n\n" + "\u00b1100ms is recommended for Vulcan.\n\n"
				+ "0 (off) is fine for NoCheat+, AAC, Grim, Verus, Spartan, and"
				+ " vanilla servers.",
			100, 0, 1000, 50, ValueDisplay.INTEGER.withPrefix("\u00b1")
				.withSuffix("ms").withLabel(0, "off"));
	
	private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
		"Determines which entity will be attacked first.\n"
			+ "\u00a7lDistance\u00a7r - Attacks the closest entity.\n"
			+ "\u00a7lAngle\u00a7r - Attacks the entity that requires the least head movement.\n"
			+ "\u00a7lHealth\u00a7r - Attacks the weakest entity.",
		Priority.values(), Priority.ANGLE);
	
	private final SliderSetting fov =
		new SliderSetting("FOV", 360, 30, 360, 10, ValueDisplay.DEGREES);
	
	private final FaceTargetSetting faceTarget =
		FaceTargetSetting.withoutPacketSpam(
			WText.literal("How Killaura faces the entity it attacks.\n\n"
				+ "§lClient-side§r turns your camera smoothly, like a hand"
				+ " on a mouse (the same aim AimAssist uses), and only attacks"
				+ " once your crosshair is on the target."),
			FaceTarget.CLIENT);
	
	private final SliderSetting aimSpeed = new SliderSetting("Aim speed",
		"How fast Killaura turns your camera toward the target, with"
			+ " §lFace target§r on Client-side. On top of this it keeps up"
			+ " with a moving target.\n\n" + "AimAssist turns at 720.",
		720, 30, 3600, 10, ValueDisplay.DEGREES.withSuffix("/s"));
	
	/** The smooth, human-like aim, shared with AimAssist. */
	private final CameraAim cameraAim = new CameraAim();
	
	/** What the camera is turning toward (Client-side). */
	private Entity aimTarget;
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericCombatDescription(this), SwingHand.CLIENT);
	
	private final CheckboxSetting damageIndicator = new CheckboxSetting(
		"Damage indicator",
		"Renders a colored box within the target, inversely proportional to its remaining health.",
		true);
	
	private final PauseAttackOnContainersSetting pauseOnContainers =
		new PauseAttackOnContainersSetting(true);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"Ensures that you don't reach through blocks when attacking.\n\n"
				+ "Slower but can help with anti-cheat plugins.",
			false);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericCombat();
	
	private Entity target;
	private Entity renderTarget;
	
	public KillauraHack()
	{
		super("Killaura");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(speed);
		addSetting(speedRandMS);
		addSetting(priority);
		addSetting(fov);
		addSetting(faceTarget);
		addSetting(aimSpeed);
		addSetting(swingHand);
		addSetting(damageIndicator);
		addSetting(pauseOnContainers);
		addSetting(checkLOS);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		speed.resetTimer(speedRandMS.getValue());
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		target = null;
		renderTarget = null;
		aimTarget = null;
		cameraAim.reset();
	}
	
	@Override
	public void onUpdate()
	{
		speed.updateTimer();
		
		// Client-side aiming turns the camera smoothly toward the target
		// every frame, so the target is picked every tick, not just when the
		// next attack is due.
		boolean smoothAim = faceTarget.getSelected() == FaceTarget.CLIENT;
		if(!smoothAim)
			aimTarget = null;
		
		if(!smoothAim && !speed.isTimeToAttack())
			return;
		
		if(pauseOnContainers.shouldPause())
		{
			aimTarget = null;
			return;
		}
		
		Stream<Entity> stream = EntityUtils.getAttackableEntities();
		double rangeSq = range.getValueSq();
		stream =
			stream.filter(e -> EntityUtils.distanceToHitboxSq(e) <= rangeSq);
		
		if(fov.getValue() < 360.0)
			stream = stream.filter(e -> RotationUtils.getAngleToLookVec(
				e.getBoundingBox().getCenter()) <= fov.getValue() / 2.0);
		
		stream = entityFilters.applyTo(stream);
		
		Entity found =
			stream.min(priority.getSelected().comparator).orElse(null);
		
		// Keep turning toward the same target while it's still valid, rather
		// than flicking between targets every tick.
		if(smoothAim && aimTarget != null && isStillValid(aimTarget, found))
			found = aimTarget;
		
		target = null;
		renderTarget = found;
		if(found == null)
		{
			aimTarget = null;
			return;
		}
		
		WURST.getHax().autoSwordHack.setSlot(found);
		
		Vec3 hitVec = found.getBoundingBox().getCenter();
		if(checkLOS.isChecked() && !BlockUtils.hasLineOfSight(hitVec))
		{
			aimTarget = null;
			return;
		}
		
		if(smoothAim)
		{
			aimTarget = found;
			
			// Attack only once the crosshair is actually on it - the camera
			// is still turning otherwise.
			if(speed.isTimeToAttack() && CameraAim.isLookingAt(
				MC.player.getYRot(), MC.player.getXRot(),
				found.getBoundingBox().inflate(found.getPickRadius()),
				range.getValue() + 1))
				target = found;
			return;
		}
		
		faceTarget.face(hitVec);
		target = found;
	}
	
	/**
	 * Whether the entity being aimed at is still a fine target: still among
	 * the ones the filters let through this tick.
	 */
	private boolean isStillValid(Entity current, Entity best)
	{
		if(current == best)
			return true;
		
		if(!current.isAlive() || current.level() != MC.level)
			return false;
		
		if(EntityUtils.distanceToHitboxSq(current) > range.getValueSq())
			return false;
		
		return entityFilters.applyTo(Stream.of(current)).findAny().isPresent();
	}
	
	@Override
	public void onHandleInput()
	{
		if(target == null)
			return;
		
		MC.gameMode.attack(MC.player, target);
		swingHand.swing(InteractionHand.MAIN_HAND);
		
		target = null;
		speed.resetTimer(speedRandMS.getValue());
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		// Client-side: the camera turns every frame, like a mouse, with the
		// same
		// smooth, human-like aim as AimAssist.
		if(aimTarget != null && faceTarget.getSelected() == FaceTarget.CLIENT)
			cameraAim.aimAtEntity(aimTarget,
				aimTarget.getBoundingBox().getCenter(), partialTicks, true,
				aimSpeed.getValue());
		else
			cameraAim.reset();
		
		if(renderTarget == null || !damageIndicator.isChecked())
			return;
		
		float p = 1;
		if(renderTarget instanceof LivingEntity le && le.getMaxHealth() > 1e-5)
			p = 1 - le.getHealth() / le.getMaxHealth();
		float red = p * 2F;
		float green = 2 - red;
		float[] rgb = {red, green, 0};
		int quadColor = RenderUtils.toIntColor(rgb, 0.25F);
		int lineColor = RenderUtils.toIntColor(rgb, 0.5F);
		
		AABB box = EntityUtils.getLerpedBox(renderTarget, partialTicks);
		if(p < 1)
			box = box.deflate((1 - p) * 0.5 * box.getXsize(),
				(1 - p) * 0.5 * box.getYsize(), (1 - p) * 0.5 * box.getZsize());
		
		RenderUtils.drawSolidBox(matrixStack, box, quadColor, false);
		RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, false);
	}
	
	private enum Priority
	{
		DISTANCE("Distance", EntityUtils::distanceToHitboxSq),
		
		ANGLE("Angle",
			e -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter())),
		
		HEALTH("Health", e -> e instanceof LivingEntity
			? ((LivingEntity)e).getHealth() : Integer.MAX_VALUE);
		
		private final String name;
		private final Comparator<Entity> comparator;
		
		private Priority(String name, ToDoubleFunction<Entity> keyExtractor)
		{
			this.name = name;
			comparator = Comparator.comparingDouble(keyExtractor);
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
