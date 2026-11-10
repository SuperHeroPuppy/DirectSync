package net.supersnetwork.direct_sync.mixin.client;

import net.supersnetwork.direct_sync.DirectSyncPreflight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void directsync$beforeConnect(Screen parent, Minecraft client, ServerAddress address,
                                                  ServerData serverData, boolean quickPlay, CallbackInfo ci) {
        if (DirectSyncPreflight.intercept(parent, client, address, serverData, quickPlay)) ci.cancel();
    }
}
