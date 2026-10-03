/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.EntitySpeedTracker;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.BallisticSolver;
import net.wurstclient.util.BallisticSolver.Projectile;
import net.wurstclient.util.BallisticSolver.Solution;
import net.wurstclient.util.BallisticSolver.TargetPath;
import net.wurstclient.util.CameraAim;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.TargetPredictor;

@SearchTags({"bow aimbot", "crossbow aimbot", "trident aimbot"})
public final class BowAimbotHack extends Hack
	implements UpdateListener, RenderListener, GUIRenderListener
{
	private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
		"Determines which entity will be attacked first.\n"
			+ "§lDistance§r - Attacks the closest entity.\n"
			+ "§lAngle§r - Attacks the entity that requires the least head movement.\n"
			+ "§lAngle+Dist§r - A hybrid of Angle and Distance. This is usually the best at figuring out what you want to aim at.\n"
			+ "§lHealth§r - Attacks the weakest entity.",
		Priority.values(), Priority.ANGLE_DIST);
	
	private final SliderSetting predictMovement = new SliderSetting(
		"Predict movement",
		"How much of the predicted movement to lead the shot by.\n\n"
			+ "BowAimbot learns how the target moves - including strafing back"
			+ " and forth, on a steady or an irregular rhythm - and aims where"
			+ " it's most likely to be when the projectile arrives, counting"
			+ " the projectile's real flight (drag, gravity, your own movement)"
			+ " and your ping. 100% leads by exactly that; less leads by less,"
			+ " more by more.",
		1, 0, 2, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting aimSpeed = new SliderSetting("Aim speed",
		"How fast BowAimbot turns toward the firing solution. On top of this"
			+ " it keeps up as the solution moves with the target.\n\n"
			+ "AimAssist turns at 720.",
		720, 30, 3600, 10, ValueDisplay.DEGREES.withSuffix("/s"));
	
	private final CheckboxSetting humanizeAim = new CheckboxSetting(
		"Humanize aim",
		"Turns like a hand on a mouse - the same aim AimAssist uses: a short"
			+ " reaction delay, speeding up and slowing down, a slightly curved"
			+ " path.\n\n"
			+ "Off: turns at a constant speed in a straight line.",
		true);
	
	private final CheckboxSetting silentAim = new CheckboxSetting("Silent aim",
		"Aims the bow on the server only, inside the outgoing movement packet"
			+ " (the way LiquidBounce works), so your arrows fly at the target"
			+ " without turning your camera.\n\n"
			+ "Off by default: BowAimbot then actually turns your view toward"
			+ " the target, so the server sees the same aim you do.",
		false);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericCombat();
	
	private final ColorSetting color = new ColorSetting("ESP color",
		"Color of the box that BowAimbot draws around the target.", Color.RED);
	
	/** What the projectile in hand does, or null if it can't be aimed. */
	private record Shot(Projectile projectile, double spawnDrop, float charge)
	{}
	
	private enum Status
	{
		CHARGING,
		LOCKED,
		OUT_OF_RANGE,
		BLOCKED
	}
	
	private final CameraAim cameraAim = new CameraAim();
	/** Learns how the target moves - see {@link TargetPredictor}. */
	private final TargetPredictor predictor = new TargetPredictor();
	/** The target's server position on the last three ticks, newest first. */
	private final double[] serverY = new double[3];
	
	private Entity target;
	private float velocity;
	private Status status = Status.CHARGING;
	
	/** The firing solution, and how fast it moves (degrees per second). */
	private Solution solution;
	private double[] solutionTrack = {0, 0};
	private boolean aimInFrames;
	private boolean lastSilent;
	
	public BowAimbotHack()
	{
		super("BowAimbot");
		
		setCategory(Category.COMBAT);
		addSetting(priority);
		addSetting(predictMovement);
		addSetting(aimSpeed);
		addSetting(humanizeAim);
		addSetting(silentAim);
		
		entityFilters.forEach(this::addSetting);
		
		addSetting(color);
	}
	
	@Override
	protected void onEnable()
	{
		// disable conflicting hacks
		WURST.getHax().excavatorHack.setEnabled(false);
		WURST.getHax().templateToolHack.setEnabled(false);
		
		clearTarget();
		
		// register event listeners
		EVENTS.add(GUIRenderListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		clearTarget();
	}
	
	private void clearTarget()
	{
		target = null;
		solution = null;
		aimInFrames = false;
		predictor.reset();
		cameraAim.reset();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		Shot shot = getShot(player, player.getInventory().getSelectedItem());
		if(shot == null)
		{
			clearTarget();
			return;
		}
		velocity = shot.charge();
		
		// keep the current target while it's still valid
		Entity previous = target;
		if(filterEntities(Stream.of(target)) == null)
			target = filterEntities(StreamSupport
				.stream(MC.level.entitiesForRendering().spliterator(), true));
		
		if(target == null)
		{
			clearTarget();
			return;
		}
		
		Vec3 serverPos = EntitySpeedTracker.getLatestServerPos(target);
		if(target != previous)
		{
			predictor.reset();
			solution = null;
			Arrays.fill(serverY, serverPos.y);
		}
		
		// learn how it moves, from where the server has it
		System.arraycopy(serverY, 0, serverY, 1, serverY.length - 1);
		serverY[0] = serverPos.y;
		double latency = getLatencyTicks();
		predictor.record(serverPos, player.position());
		predictor.prepare(
			solution != null ? solution.ticks()
				: serverPos.distanceTo(player.position())
					/ shot.projectile().speed(),
			latency, target.getBbWidth() / 2);
		
		// Solve the shot: spawn point, what your own movement adds to the
		// projectile, and where the target is headed.
		Vec3 start = RotationUtils.getEyesPos().add(0, -shot.spawnDrop(), 0);
		Vec3 inherited = getInheritedVelocity(player);
		Solution next = BallisticSolver.solve(start, inherited,
			shot.projectile(), predictPath(target, serverPos, latency));
		
		// how fast the solution itself is moving, so the aim keeps up
		if(solution != null)
			solutionTrack = new double[]{
				Mth.wrapDegrees(next.yaw() - solution.yaw()) / 0.05,
				(next.pitch() - solution.pitch()) / 0.05};
		else
			solutionTrack = new double[]{0, 0};
		solution = next;
		
		if(!next.reachable())
			status = Status.OUT_OF_RANGE;
		else if(isBlocked(start, inherited, shot.projectile(), next))
			status = Status.BLOCKED;
		else
			status = velocity < 1 ? Status.CHARGING : Status.LOCKED;
		
		// switching between silent and camera aim starts the aim over
		boolean silent = silentAim.isChecked();
		if(silent != lastSilent)
		{
			cameraAim.reset();
			lastSilent = silent;
		}
		
		Rotation wanted = new Rotation(next.yaw(), next.pitch());
		if(silent)
		{
			// Silent aim only matters once per tick, in the movement packet.
			aimInFrames = false;
			Rotation r = cameraAim.turnSilently(target, wanted, solutionTrack,
				humanizeAim.isChecked(), aimSpeed.getValue());
			WURST.getRotationFaker().faceRotationPacket(r.yaw(), r.pitch());
			
		}else
			// The camera turns every frame, in onRender, like a mouse.
			aimInFrames = true;
	}
	
	/**
	 * What the held item shoots and how, or null if it isn't something
	 * BowAimbot can aim right now.
	 */
	private Shot getShot(LocalPlayer player, ItemStack stack)
	{
		Item item = stack.getItem();
		
		// bow: power from how long it's been drawn (BowItem.releaseUsing)
		if(item instanceof BowItem)
		{
			if(!MC.options.keyUse.isDown() && !player.isUsingItem())
				return null;
			
			float power = BowItem.getPowerForTime(player.getTicksUsingItem());
			return new Shot(
				new Projectile(Math.max(power, 0.1) * 3, 0.05, 0.99), 0.1,
				power);
		}
		
		// loaded crossbow: arrows at 3.15, fireworks at 1.6 in a straight line
		if(item instanceof CrossbowItem)
		{
			if(!CrossbowItem.isCharged(stack))
				return null;
			
			ChargedProjectiles loaded =
				stack.get(DataComponents.CHARGED_PROJECTILES);
			if(loaded != null && loaded.contains(Items.FIREWORK_ROCKET))
				return new Shot(new Projectile(1.6, 0, 1), 0.15, 1);
			
			return new Shot(new Projectile(3.15, 0.05, 0.99), 0.1, 1);
		}
		
		// trident: thrown at 2.5 once held back for 10 ticks
		if(item instanceof TridentItem)
		{
			if(!player.isUsingItem())
				return null;
			
			float charge = Math.min(player.getTicksUsingItem() / 10F, 1);
			return new Shot(new Projectile(2.5, 0.05, 0.99), 0.1, charge);
		}
		
		return null;
	}
	
	/**
	 * What your own movement adds to the projectile: vanilla adds the
	 * shooter's movement, minus the vertical part while on the ground
	 * (Projectile.shootFromRotation).
	 */
	private static Vec3 getInheritedVelocity(LocalPlayer player)
	{
		Vec3 move = player.position().subtract(player.xo, player.yo, player.zo);
		return new Vec3(move.x, player.onGround() ? 0 : move.y, move.z);
	}
	
	/**
	 * Where the target's hitbox center will be when a projectile flying
	 * {@code t} ticks reaches it.
	 *
	 * <p>
	 * Sideways and toward/away: {@link TargetPredictor} - it learns the
	 * target's movement (strafing rhythm and all) and picks the spot it's
	 * most likely to be in. Up and down: on the ground it stays at its height;
	 * flying (elytra, creative) carries on in a straight line; falling follows
	 * vanilla gravity and drag (LivingEntity.travel) until the ground below.
	 * Everything starts from where the server has the target, not the
	 * client's smoothed position, and looks ahead by the ping as well.
	 */
	private TargetPath predictPath(Entity e, Vec3 serverPos, double latency)
	{
		double predict = predictMovement.getValue();
		double halfHeight = e.getBbHeight() / 2;
		// the server sends most positions every second tick
		double vy = (serverY[0] - serverY[2]) / 2 * predict;
		
		boolean flying = e instanceof LivingEntity le && le.isFallFlying()
			|| e instanceof Player p && p.getAbilities().flying
			|| e.isNoGravity();
		boolean falling = !flying && !e.onGround() && !e.isInWater();
		double groundY = falling ? findGroundBelow(e, serverPos) : 0;
		
		return t -> {
			double[] xz = predictor.predict(t);
			double x = serverPos.x + (xz[0] - serverPos.x) * predict;
			double z = serverPos.z + (xz[1] - serverPos.z) * predict;
			double ahead = TargetPredictor.lookAhead(t, latency);
			
			double y = serverPos.y;
			if(flying)
				y += vy * ahead;
			else if(falling)
				y = fall(serverPos.y, vy, groundY, ahead);
			
			return new Vec3(x, y + halfHeight, z);
		};
	}
	
	/** Vanilla falling (LivingEntity.travel), stopping on the ground. */
	private static double fall(double y, double vy, double groundY,
		double ticks)
	{
		int whole = (int)ticks;
		for(int i = 0; i < whole && y > groundY; i++)
		{
			y += vy;
			vy = (vy - 0.08) * 0.98;
		}
		if(y > groundY)
			y += vy * (ticks - whole);
		return Math.max(y, groundY);
	}
	
	/** Height of the ground under the entity, or far below if there's none. */
	private static double findGroundBelow(Entity e, Vec3 from)
	{
		Vec3 to = from.add(0, -64, 0);
		BlockHitResult hit = MC.level.clip(new ClipContext(from, to,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
		return hit.getType() == HitResult.Type.MISS ? to.y
			: hit.getLocation().y;
	}
	
	/**
	 * Your round trip to the server, in ticks: what you see of the target is
	 * half of it old, and your shot reaches the server half of it later.
	 */
	private static double getLatencyTicks()
	{
		PlayerInfo info = MC.getConnection() == null ? null
			: MC.getConnection().getPlayerInfo(MC.player.getUUID());
		if(info == null)
			return 0;
		
		return Math.clamp(info.getLatency() / 50.0, 0, 20);
	}
	
	/** Whether a block is in the way of the solved shot. */
	private boolean isBlocked(Vec3 start, Vec3 inherited, Projectile p,
		Solution s)
	{
		Vec3 velocity = BallisticSolver
			.launchVelocity(s.yaw(), s.pitch(), p.speed()).add(inherited);
		Vec3 pos = start;
		
		for(int tick = 0; tick < Math.ceil(s.ticks()); tick++)
		{
			double part = Math.min(1, s.ticks() - tick);
			Vec3 next = pos.add(velocity.scale(part));
			BlockHitResult hit = MC.level.clip(new ClipContext(pos, next,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
			if(hit.getType() != HitResult.Type.MISS)
				return true;
			
			pos = next;
			velocity = velocity.scale(p.drag()).add(0, -p.gravity(), 0);
		}
		
		return false;
	}
	
	private Entity filterEntities(Stream<Entity> s)
	{
		Stream<Entity> stream = s.filter(EntityUtils.IS_ATTACKABLE);
		stream = entityFilters.applyTo(stream);
		
		return stream.min(priority.getSelected().comparator).orElse(null);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(target == null)
			return;
			
		// Camera aim: turned every frame, like a mouse, with the same smooth,
		// human-like aim as AimAssist, keeping up as the solution moves.
		if(aimInFrames && solution != null)
			cameraAim.turnCamera(target,
				new Rotation(solution.yaw(), solution.pitch()), solutionTrack,
				humanizeAim.isChecked(), aimSpeed.getValue());
		
		AABB box = EntityUtils.getLerpedBox(target, partialTicks)
			.move(0, 0.05, 0).inflate(0.05);
		
		int quadColor = color.getColorI(0.5F * velocity);
		RenderUtils.drawSolidBox(matrixStack, box, quadColor, false);
		
		int lineColor = color.getColorI(0.25F * velocity);
		RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, false);
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(target == null)
			return;
		
		String message = switch(status)
		{
			case OUT_OF_RANGE -> "Out of range";
			case BLOCKED -> "Shot blocked";
			case CHARGING -> "Charging: " + (int)(velocity * 100) + "%";
			case LOCKED -> "Target Locked";
		};
		
		Font tr = MC.font;
		int msgWidth = tr.width(message);
		
		int msgX1 = context.guiWidth() / 2 - msgWidth / 2;
		int msgX2 = msgX1 + msgWidth + 3;
		int msgY1 = context.guiHeight() / 2 + 1;
		int msgY2 = msgY1 + 10;
		
		// background
		context.fill(msgX1, msgY1, msgX2, msgY2, 0x80000000);
		
		// text
		context.text(tr, message, msgX1 + 2, msgY1 + 1, 0xFFFFFFFF, false);
	}
	
	private enum Priority
	{
		DISTANCE("Distance", EntityUtils::distanceToHitboxSq),
		
		ANGLE("Angle",
			e -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter())),
		
		ANGLE_DIST("Angle+Dist",
			e -> Math
				.pow(RotationUtils
					.getAngleToLookVec(e.getBoundingBox().getCenter()), 2)
				+ EntityUtils.distanceToHitboxSq(e)),
		
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
