package de.yourshika.betterpets.quickslots;

import de.kamil.betterpets.quickslots.QuickslotProtocol;
import de.yourshika.betterpets.quickslots.ui.Sprites;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What one quickslot looks like right now - everything a display needs to draw it, whether that is the
 * HUD, the pet wheel or a preview.
 *
 * @param index   the slot, 0-based
 * @param petId   the pet parked here, "" for an empty slot
 * @param name    the pet's name ("" if empty)
 * @param colour  its rarity colour, 0xRRGGBB
 * @param texture the head texture the server sent, or {@code null} to draw {@code icon} instead
 * @param icon    a sprite standing in for the head (sample pets in previews)
 * @param active  this pet is the one that is out
 * @param missing parked here, but not owned at the moment
 */
record SlotView(int index, String petId, String name, int colour, int level, String texture, Identifier icon,
                boolean active, boolean missing, boolean disabled) {

    boolean empty() {
        return petId.isEmpty();
    }

    /** Holds a pet that can be summoned. */
    boolean usable() {
        return !empty() && !missing && !disabled;
    }

    /** The same slot with a different answer to "is this pet out?" - the previews act out switches. */
    SlotView withActive(final boolean nowActive) {
        return nowActive == active ? this : new SlotView(index, petId, name, colour, level, texture, icon, nowActive, missing, disabled);
    }

    /** The player's real quickslots. */
    static List<SlotView> live() {
        final List<String> slots = QuickslotClient.slots();
        final String activeId = QuickslotClient.activeId();
        final List<SlotView> views = new ArrayList<>(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            final String petId = slots.get(slot);
            final QuickslotProtocol.Pet pet = petId.isEmpty() ? null : QuickslotClient.pet(petId);
            if (pet == null) {
                views.add(new SlotView(slot, petId, petId, 0x94806C, 0, null, null, false, !petId.isEmpty(), false));
            } else {
                views.add(new SlotView(slot, petId, pet.name(), pet.color(), pet.level(), pet.texture(), null,
                    petId.equals(activeId), false, pet.disabled()));
            }
        }
        return views;
    }

    /**
     * Stand-in slots for a preview when there is nothing real to show - on a server without the plugin,
     * before any slot is filled, or in the settings opened from the title screen. {@code activeSlot}
     * picks which of them is "out".
     */
    static List<SlotView> sample(final int activeSlot) {
        final List<SlotView> views = new ArrayList<>(5);
        views.add(sampleSlot(0, "Fox", 0xFFAA00, 64, Sprites.ICON_FOX, activeSlot));
        views.add(sampleSlot(1, "Cat", 0x55FF55, 12, Sprites.ICON_CAT, activeSlot));
        views.add(sampleSlot(2, "Luna", 0xFF55FF, 38, Sprites.ICON_SKIN, activeSlot));
        views.add(new SlotView(3, "", "", 0, 0, null, null, false, false, false));
        views.add(sampleSlot(4, "Goldie", 0x5555FF, 7, Sprites.ICON_COIN, activeSlot));
        return views;
    }

    private static SlotView sampleSlot(final int slot, final String name, final int colour, final int level, final Identifier icon, final int activeSlot) {
        return new SlotView(slot, "sample_" + slot, name, colour, level, null, icon, slot == activeSlot, false, false);
    }

    /** The real slots if at least one of them holds a pet, the samples otherwise. */
    static List<SlotView> forPreview(final int sampleActiveSlot) {
        if (QuickslotClient.usable()) {
            final List<SlotView> real = live();
            for (final SlotView view : real) {
                if (!view.empty()) {
                    return real;
                }
            }
        }
        return sample(sampleActiveSlot);
    }

    /**
     * Draws the pet's picture, 16 pixels wide times {@code scale}, with its top left at {@code x, y}.
     * A pet from the server is drawn as its head; that needs the game's item data, which only exists in
     * a world - anywhere else (and for the sample pets) the icon sprite is used.
     */
    void drawIcon(final GuiGraphicsExtractor graphics, final float x, final float y, final float scale) {
        if (empty()) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        if (texture != null && Minecraft.getInstance().level != null) {
            graphics.fakeItem(PetIcons.head(texture), 0, 0);
        } else {
            Sprites.draw(graphics, icon != null ? icon : missing ? Sprites.ICON_LOCKED : Sprites.ICON_EMPTY, 0, 0, 16, 16);
        }
        graphics.pose().popMatrix();
    }
}
