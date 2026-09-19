/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.EspStyleSetting.EspStyle;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.FilterInvisibleSetting;
import net.wurstclient.settings.filters.FilterSleepingSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;
import net.wurstclient.util.RenderUtils.ColoredText;

@SearchTags({"player esp", "PlayerTracers", "player tracers"})
public final class PlayerEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style =
		new EspStyleSetting(EspStyle.LINES_AND_BOXES);
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each player.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.");
	
	private final SliderSetting range = new SliderSetting("Range",
		"How far away a player can be before this hack stops showing them.\n\n"
			+ "Only the horizontal distance counts, so players are shown no"
			+ " matter which Y level they are on.\n\n"
			+ "Your client can only see players that the server sends it,"
			+ " which is usually a much smaller radius than this. Use"
			+ " \u00a7lMemory\u00a7r to keep showing them anyway.",
		32, 1, 32, 1, ValueDisplay.INTEGER.withSuffix(" chunks"));
	
	private final SliderSetting memory = new SliderSetting("Memory",
		"Keeps showing a player at the last place you saw them, for this long"
			+ " after the server stops sending their position.\n\n"
			+ "This is what turns \u00a7lRange\u00a7r into an actual radius:"
			+ " a player who walks out of the server's tracking range stays on"
			+ " your screen until they leave the server, leave the range, or"
			+ " this timer runs out.\n\n"
			+ "Remembered players are drawn faded, with a cross through their"
			+ " box, and their distance shows how long ago they were seen.",
		300, 0, 600, 5,
		ValueDisplay.INTEGER.withSuffix("s").withLabel(0, "off"));
	
	private final CheckboxSetting showDistance =
		new CheckboxSetting("Show distance",
			"Shows how many blocks away each player is, as a number on their"
				+ " tracer.",
			true);
	
	private final SliderSetting distanceScale = new SliderSetting(
		"Distance size", "How large the distance numbers should be.", 1, 0.25,
		4, 0.05, ValueDisplay.PERCENTAGE);
	
	private final EntityFilterList entityFilters = new EntityFilterList(
		new FilterSleepingSetting("Won't show sleeping players.", false),
		new FilterInvisibleSetting("Won't show invisible players.", false));
	
	private final ArrayList<Player> players = new ArrayList<>();
	private final HashMap<UUID, LastSeen> lastSeenPlayers = new HashMap<>();
	private final ArrayList<LastSeen> rememberedPlayers = new ArrayList<>();
	private ClientLevel lastLevel;
	
	public PlayerEspHack()
	{
		super("PlayerESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(range);
		addSetting(memory);
		addSetting(showDistance);
		addSetting(distanceScale);
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		
		warnIfRangeIsOutOfReach();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		players.clear();
		lastSeenPlayers.clear();
		rememberedPlayers.clear();
		lastLevel = null;
	}
	
	@Override
	public void onUpdate()
	{
		players.clear();
		
		// No Y limit on purpose: players are shown at any Y level, as long as
		// they are within the horizontal range.
		Stream<AbstractClientPlayer> stream = MC.level.players()
			.parallelStream().filter(e -> !e.isRemoved() && e.getHealth() > 0)
			.filter(e -> e != MC.player)
			.filter(e -> !(e instanceof FakePlayerEntity))
			.filter(e -> isInRange(e.getX(), e.getZ()));
		
		stream = entityFilters.applyTo(stream);
		
		players.addAll(stream.collect(Collectors.toList()));
		
		updateMemory();
	}
	
	private boolean isInRange(double x, double z)
	{
		double rangeSq = Math.pow(range.getValue() * 16, 2);
		return Mth.lengthSquared(x - MC.player.getX(),
			z - MC.player.getZ()) <= rangeSq;
	}
	
	/**
	 * Remembers where each player was last seen, so that they stay on screen
	 * when the server stops sending them. A remembered player is only dropped
	 * once they leave the server, leave the range, or the memory runs out.
	 */
	private void updateMemory()
	{
		rememberedPlayers.clear();
		
		if(memory.getValue() <= 0)
		{
			lastSeenPlayers.clear();
			return;
		}
		
		// Last known positions are worthless after changing worlds.
		if(lastLevel != MC.level)
		{
			lastSeenPlayers.clear();
			lastLevel = MC.level;
		}
		
		long now = System.currentTimeMillis();
		HashSet<UUID> visible = new HashSet<>();
		for(Player e : players)
		{
			lastSeenPlayers.put(e.getUUID(),
				new LastSeen(e.getBoundingBox(), now));
			visible.add(e.getUUID());
		}
		
		long maxAge = (long)(memory.getValue() * 1000);
		ClientPacketListener netHandler = MC.getConnection();
		
		Iterator<Map.Entry<UUID, LastSeen>> itr =
			lastSeenPlayers.entrySet().iterator();
		while(itr.hasNext())
		{
			Map.Entry<UUID, LastSeen> entry = itr.next();
			LastSeen lastSeen = entry.getValue();
			Vec3 pos = lastSeen.box().getCenter();
			
			// The tab list is the only way to tell "out of range" apart from
			// "logged out", so a player who leaves the server is forgotten
			// immediately.
			if(now - lastSeen.time() > maxAge || !isInRange(pos.x, pos.z)
				|| netHandler == null
				|| netHandler.getPlayerInfo(entry.getKey()) == null)
			{
				itr.remove();
				continue;
			}
			
			if(!visible.contains(entry.getKey()))
				rememberedPlayers.add(lastSeen);
		}
	}
	
	/**
	 * The game never loads chunks, and with them players, beyond the render
	 * distance. A larger range can only work through the memory.
	 */
	private void warnIfRangeIsOutOfReach()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		int renderDistance = MC.options.getEffectiveRenderDistance();
		int rangeChunks = range.getValueI();
		if(renderDistance >= rangeChunks)
			return;
		
		ChatUtils.warning("PlayerESP's range is " + rangeChunks
			+ " chunks, but the game only loads " + renderDistance
			+ " chunks around you.");
		ChatUtils.message("Raise your render distance to see players further"
			+ " away. Servers usually send players from an even smaller"
			+ " radius, which is what the Memory setting works around.");
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(style.hasBoxes())
		{
			double extraSize = boxSize.getExtraSize() / 2;
			
			ArrayList<ColoredBox> boxes = new ArrayList<>(players.size());
			for(Player e : players)
			{
				AABB box = EntityUtils.getLerpedBox(e, partialTicks)
					.move(0, extraSize, 0).inflate(extraSize);
				boxes.add(new ColoredBox(box, getColor(e)));
			}
			
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, false);
			
			ArrayList<ColoredBox> oldBoxes =
				new ArrayList<>(rememberedPlayers.size());
			for(LastSeen lastSeen : rememberedPlayers)
			{
				AABB box =
					lastSeen.box().move(0, extraSize, 0).inflate(extraSize);
				oldBoxes.add(new ColoredBox(box, getColor(lastSeen)));
			}
			
			RenderUtils.drawOutlinedBoxes(matrixStack, oldBoxes, false);
			RenderUtils.drawCrossBoxes(matrixStack, oldBoxes, false);
		}
		
		if(style.hasLines())
		{
			ArrayList<ColoredPoint> ends =
				new ArrayList<>(players.size() + rememberedPlayers.size());
			for(Player e : players)
			{
				Vec3 point =
					EntityUtils.getLerpedBox(e, partialTicks).getCenter();
				ends.add(new ColoredPoint(point, getColor(e)));
			}
			
			for(LastSeen lastSeen : rememberedPlayers)
				ends.add(new ColoredPoint(lastSeen.box().getCenter(),
					getColor(lastSeen)));
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
		}
		
		if(showDistance.isChecked())
			drawDistances(matrixStack, partialTicks);
	}
	
	/**
	 * Draws the distance to each player, in blocks, onto their tracer. The
	 * number sits near the player end of the line, where it's least likely to
	 * overlap with the other numbers.
	 */
	private void drawDistances(PoseStack matrixStack, float partialTicks)
	{
		Vec3 start = RenderUtils.getTracerStart();
		ArrayList<ColoredText> texts =
			new ArrayList<>(players.size() + rememberedPlayers.size());
		
		for(Player e : players)
		{
			Vec3 end = EntityUtils.getLerpedBox(e, partialTicks).getCenter();
			texts.add(new ColoredText("" + Math.round(MC.player.distanceTo(e)),
				start.lerp(end, 0.9), getColor(e) | 0xFF000000));
		}
		
		long now = System.currentTimeMillis();
		for(LastSeen lastSeen : rememberedPlayers)
		{
			Vec3 end = lastSeen.box().getCenter();
			String text = Math.round(MC.player.position().distanceTo(end))
				+ " (" + (now - lastSeen.time()) / 1000 + "s)";
			texts.add(new ColoredText(text, start.lerp(end, 0.9),
				getColor(lastSeen) | 0xFF000000));
		}
		
		RenderUtils.drawTextsInWorld(matrixStack, texts,
			distanceScale.getValueF(), true);
	}
	
	private int getColor(Player e)
	{
		if(WURST.getFriends().contains(e.getName().getString()))
			return 0x800000FF;
		
		return getColor(MC.player.distanceTo(e), 0.5F);
	}
	
	private int getColor(LastSeen lastSeen)
	{
		double distance =
			MC.player.position().distanceTo(lastSeen.box().getCenter());
		return getColor((float)distance, 0.25F);
	}
	
	private int getColor(float distance, float opacity)
	{
		float f = distance / 20F;
		float r = Mth.clamp(2 - f, 0, 1);
		float g = Mth.clamp(f, 0, 1);
		float[] rgb = {r, g, 0};
		return RenderUtils.toIntColor(rgb, opacity);
	}
	
	/**
	 * The hitbox a player was last seen in, and when that was.
	 */
	private record LastSeen(AABB box, long time)
	{}
}
