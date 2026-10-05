package de.yourshika.betterpets.quickslots.mixin;

import de.yourshika.betterpets.quickslots.ScrollHook;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the quickslots take a turn of the mouse wheel before the hotbar does.
 *
 * <p>The one thing in this mod that needs a mixin: the game offers no event for the wheel outside of
 * screens, and by the time a tick comes round the hotbar selection has already moved - undoing it then
 * would show as a flicker. Everything about when the wheel is taken lives in {@link ScrollHook}.</p>
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void betterpets$quickslotScroll(final long window, final double horizontal, final double vertical, final CallbackInfo callback) {
        if (ScrollHook.onScroll(vertical)) {
            callback.cancel();
        }
    }
}
