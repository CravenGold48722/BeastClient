/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.function.Consumer;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

public final class FaceTargetSetting
	extends EnumSetting<FaceTargetSetting.FaceTarget>
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final WText FULL_DESCRIPTION_SUFFIX =
		buildDescriptionSuffix(true);
	private static final WText REDUCED_DESCRIPTION_SUFFIX =
		buildDescriptionSuffix(false);
	
	private FaceTargetSetting(WText description, FaceTarget[] values,
		FaceTarget selected)
	{
		super("Face target", description, values, selected);
	}
	
	public static FaceTargetSetting withPacketSpam(Hack hack,
		FaceTarget selected)
	{
		return withPacketSpam(hackDescription(hack), selected);
	}
	
	public static FaceTargetSetting withPacketSpam(WText description,
		FaceTarget selected)
	{
		return new FaceTargetSetting(
			description.append(FULL_DESCRIPTION_SUFFIX), FaceTarget.values(),
			selected);
	}
	
	public static FaceTargetSetting withoutPacketSpam(Hack hack,
		FaceTarget selected)
	{
		return withoutPacketSpam(hackDescription(hack), selected);
	}
	
	public static FaceTargetSetting withoutPacketSpam(WText description,
		FaceTarget selected)
	{
		FaceTarget[] values =
			{FaceTarget.OFF, FaceTarget.SERVER, FaceTarget.CLIENT};
		return new FaceTargetSetting(
			description.append(REDUCED_DESCRIPTION_SUFFIX), values, selected);
	}
	
	private static WText hackDescription(Hack hack)
	{
		return WText.translated("description.wurst.setting."
			+ hack.getName().toLowerCase() + ".face_target");
	}
	
	public void face(Vec3 v)
	{
		getSelected().face(v);
	}
	
	/**
	 * Faces an explicit rotation instead of a point, e.g. a bow's firing
	 * solution or a fixed pitch.
	 */
	public void face(float yaw, float pitch)
	{
		getSelected().face(yaw, pitch);
	}
	
	/**
	 * Only one rotation reaches the server per tick. With Server-side or
	 * Client-side, a hack that wants every action backed by a matching
	 * rotation has to act on one target per tick.
	 */
	public boolean canFaceMultipleTargetsPerTick()
	{
		FaceTarget selected = getSelected();
		return selected == FaceTarget.OFF || selected == FaceTarget.SPAM;
	}
	
	private static WText buildDescriptionSuffix(boolean includePacketSpam)
	{
		WText text = WText.literal("\n\n");
		FaceTarget[] values =
			includePacketSpam ? FaceTarget.values() : new FaceTarget[]{
				FaceTarget.OFF, FaceTarget.SERVER, FaceTarget.CLIENT};
		
		for(FaceTarget value : values)
			text.append("\u00a7l" + value.name + "\u00a7r - ")
				.append(value.description).append("\n\n");
		
		return text;
	}
	
	public enum FaceTarget
	{
		OFF("Off", v -> {}, r -> {}),
		
		SERVER("Server-side", v -> WURST.getRotationFaker().faceVectorPacket(v),
			r -> WURST.getRotationFaker().faceRotationPacket(r.yaw(),
				r.pitch())),
		
		CLIENT("Client-side", v -> WURST.getRotationFaker().faceVectorClient(v),
			Rotation::applyToClientPlayer),
		
		SPAM("Packet spam",
			v -> RotationUtils.getNeededRotations(v).sendPlayerLookPacket(),
			Rotation::sendPlayerLookPacket);
		
		private static final String TRANSLATION_KEY_PREFIX =
			"description.wurst.setting.generic.face_target.";
		
		private final String name;
		private final WText description;
		private final Consumer<Vec3> face;
		private final Consumer<Rotation> faceRotation;
		
		private FaceTarget(String name, Consumer<Vec3> face,
			Consumer<Rotation> faceRotation)
		{
			this.name = name;
			description =
				WText.translated(TRANSLATION_KEY_PREFIX + name().toLowerCase());
			this.face = face;
			this.faceRotation = faceRotation;
		}
		
		public void face(Vec3 v)
		{
			face.accept(v);
		}
		
		public void face(float yaw, float pitch)
		{
			faceRotation.accept(new Rotation(yaw, pitch));
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
