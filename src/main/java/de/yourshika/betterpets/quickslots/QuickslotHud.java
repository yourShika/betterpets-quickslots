package de.yourshika.betterpets.quickslots;

import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;

import java.util.List;

/**
 * A slot bar that appears above the hotbar for a moment whenever the summoned pet changes, so a key press
 * shows what it did - which pet is out now and where it sits among the slots - and then gets out of the
 * way again. Can be switched off in the slot screen.
 */
final class QuickslotHud {

    private static final long VISIBLE_MILLIS = 1800L;
    private static final long FADE_MILLIS = 350L;
    private static final int TILE = 20;
    private static final int GAP = 2;

    private static long shownAt;

    private QuickslotHud() {
    }

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
            Identifier.fromNamespaceAndPath(BetterPetsQuickslots.MOD_ID, "slot_bar"), QuickslotHud::extract);
    }

    /** Shows the bar now (called when the server reports a different active pet). */
    static void flash() {
        shownAt = Util.getMillis();
    }

    static void hide() {
        shownAt = 0L;
    }

    private static void extract(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
        if (shownAt == 0L || !ModConfig.hudEnabled() || !QuickslotClient.usable()) {
            return;
        }
        final long age = Util.getMillis() - shownAt;
        if (age > VISIBLE_MILLIS) {
            shownAt = 0L;
            return;
        }
        final Minecraft client = Minecraft.getInstance();
        // F1 hides the whole HUD; this bar belongs to it.
        if (client.gui.hud.isHidden()) {
            return;
        }
        final List<String> slots = QuickslotClient.slots();
        if (slots.isEmpty()) {
            return;
        }
        // Fades out over the last moments instead of popping away.
        final float alpha = age > VISIBLE_MILLIS - FADE_MILLIS ? (VISIBLE_MILLIS - age) / (float) FADE_MILLIS : 1.0F;

        final String activeId = QuickslotClient.activeId();
        final int width = slots.size() * TILE + (slots.size() - 1) * GAP;
        final int left = (graphics.guiWidth() - width) / 2;
        // Clear of the hotbar, the health/food rows and the item-name line above them.
        final int top = graphics.guiHeight() - 22 - 46 - TILE;

        graphics.fill(left - 3, top - 3, left + width + 3, top + TILE + 3, ARGB.color(alpha * 0.55F, 0x000000));
        for (int slot = 0; slot < slots.size(); slot++) {
            final int x = left + slot * (TILE + GAP);
            final String petId = slots.get(slot);
            final QuickslotProtocol.Pet pet = petId.isEmpty() ? null : QuickslotClient.pet(petId);
            final boolean active = !petId.isEmpty() && petId.equals(activeId);
            graphics.fill(x, top, x + TILE, top + TILE, ARGB.color(alpha * (active ? 0.75F : 0.45F), active ? 0x3A3420 : 0x101014));
            graphics.outline(x, top, TILE, TILE, ARGB.color(alpha, active ? 0xFFC83C : 0x4A4A56));
            // Items cannot be drawn translucent, so they simply drop out once the bar is mostly faded.
            if (pet != null && alpha > 0.5F) {
                graphics.fakeItem(PetIcons.head(pet.texture()), x + 2, top + 2);
            }
        }

        final QuickslotProtocol.Pet activePet = activeId.isEmpty() ? null : QuickslotClient.pet(activeId);
        final Component label = activePet == null
            ? Component.translatable(BetterPetsQuickslots.MOD_ID + ".hud.put_away")
            : Component.literal(activePet.name());
        final int color = ARGB.color(alpha, activePet == null ? 0xA8A8B4 : activePet.color());
        // Text with a (near) zero alpha would be drawn fully opaque by the font renderer; skip it then.
        if (alpha > 0.05F) {
            graphics.centeredText(client.font, label, graphics.guiWidth() / 2, top - 13, color);
        }
    }
}
