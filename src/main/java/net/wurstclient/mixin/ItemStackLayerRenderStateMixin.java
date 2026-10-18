/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ViewmodelHack;

/**
 * Viewmodel's glint, tint and opacity for first-person items: patches the
 * layer right before it's submitted and puts it back when it's cleared.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public class ItemStackLayerRenderStateMixin
{
	@Shadow
	private ItemStackRenderState.FoilType foilType;
	
	@Shadow
	@Final
	private List<BakedQuad> quads;
	
	@Shadow
	public native IntList tintLayers();
	
	@Shadow
	@Final
	ItemStackRenderState this$0;
	
	@Unique
	private List<BakedQuad> wurst$originalQuads;
	@Unique
	private IntArrayList wurst$originalTintLayers;
	@Unique
	private boolean wurst$statePatched;
	
	@Inject(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
		at = @At("HEAD"))
	private void onSubmit(PoseStack matrices, SubmitNodeCollector collector,
		int light, int overlay, int seed, CallbackInfo ci)
	{
		wurst$statePatched = false;
		wurst$originalQuads = null;
		wurst$originalTintLayers = null;
		
		ItemDisplayContext context =
			((ItemStackRenderStateAccessor)this$0).wurst$getDisplayContext();
		if(context == null || !context.firstPerson())
			return;
		
		ViewmodelHack viewmodel = WurstClient.INSTANCE.getHax().viewmodelHack;
		HumanoidArm arm =
			context.leftHand() ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
		if(viewmodel.shouldForceGlint(arm))
			foilType = ItemStackRenderState.FoilType.STANDARD;
		
		float opacity = viewmodel.getOpacity(arm);
		int tint = viewmodel.getOverlayColor(arm);
		if(tint != 0)
			applyTint(tint, opacity < 0.999F);
	}
	
	@Inject(method = "clear()V", at = @At("RETURN"))
	private void onClear(CallbackInfo ci)
	{
		if(!wurst$statePatched)
			return;
		
		quads.clear();
		quads.addAll(wurst$originalQuads);
		
		IntList tints = tintLayers();
		tints.clear();
		tints.addAll(wurst$originalTintLayers);
		
		wurst$statePatched = false;
		wurst$originalQuads = null;
		wurst$originalTintLayers = null;
	}
	
	@Unique
	private void applyTint(int tint, boolean forceTranslucent)
	{
		wurst$originalQuads = new ArrayList<>(quads);
		wurst$originalTintLayers = new IntArrayList(tintLayers());
		wurst$statePatched = true;
		
		IntList tints = tintLayers();
		if(tints.isEmpty())
			tints.add(tint);
		else
			tints.set(0, tint);
		
		ArrayList<BakedQuad> transformed = new ArrayList<>(quads.size());
		for(BakedQuad quad : quads)
		{
			MaterialInfo info = quad.materialInfo();
			ChunkSectionLayer layer =
				forceTranslucent ? ChunkSectionLayer.TRANSLUCENT : info.layer();
			RenderType renderType = forceTranslucent
				? RenderTypes.itemTranslucent(info.sprite().atlasLocation())
				: info.itemRenderType();
			
			transformed.add(new BakedQuad(quad.position0(), quad.position1(),
				quad.position2(), quad.position3(), quad.packedUV0(),
				quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
				quad.direction(),
				new MaterialInfo(info.sprite(), layer, renderType,
					info.tintIndex(), info.shade(), info.lightEmission())));
		}
		
		quads.clear();
		quads.addAll(transformed);
	}
}
