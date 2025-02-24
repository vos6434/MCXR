package net.sorenon.minexraft.client.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.sorenon.minexraft.client.MineXRaftClient;
import org.lwjgl.openxr.XrVector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    void mv(boolean slowDown, CallbackInfo ci) {
        XrVector2f thumbstick = MineXRaftClient.vanillaCompatActionSet.thumbstickOffHandState.currentState();
        this.movementForward = thumbstick.y();
        this.movementSideways = -thumbstick.x();

        this.pressingForward = thumbstick.y() > 0;
        this.pressingBack = thumbstick.y() < 0;
        this.pressingRight = thumbstick.x() > 0;
        this.pressingLeft = thumbstick.y() < 0;

        this.jumping = MineXRaftClient.vanillaCompatActionSet.jumpState.currentState();
    }
}
