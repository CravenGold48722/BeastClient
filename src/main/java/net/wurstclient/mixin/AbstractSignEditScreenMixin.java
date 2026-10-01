/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.function.IntFunction;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoSignHack;

@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin extends Screen
{
	@Shadow
	@Final
	private String[] messages;
	
	private AbstractSignEditScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	/**
	 * VanillaSpoof: the sign's lines are turned into the strings that get
	 * sent back to the server when you close the editor. Resolve them as a
	 * vanilla client would, so a mod translation key or keybind that a server
	 * planted on the sign comes back raw instead of revealing the mod.
	 */
	@WrapOperation(
		method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;ZZLnet/minecraft/network/chat/Component;)V",
		at = @At(value = "INVOKE",
			target = "Ljava/util/stream/Stream;toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"))
	private Object[] wrapMessagesToArray(Stream<?> stream,
		IntFunction<?> generator, Operation<Object[]> original)
	{
		return WurstClient.INSTANCE.getOtfs().vanillaSpoofOtf
			.withVanillaTranslations(() -> original.call(stream, generator));
	}
	
	@Inject(method = "init()V", at = @At("HEAD"))
	private void onInit(CallbackInfo ci)
	{
		AutoSignHack autoSignHack = WurstClient.INSTANCE.getHax().autoSignHack;
		
		String[] autoSignText = autoSignHack.getSignText();
		if(autoSignText == null)
			return;
		
		for(int i = 0; i < 4; i++)
			messages[i] = autoSignText[i];
		
		onDone();
	}
	
	@Inject(method = "onDone()V", at = @At("HEAD"))
	private void onFinishEditing(CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().autoSignHack.setSignText(messages);
	}
	
	@Shadow
	private void onDone()
	{
		
	}
}
