package de.yourshika.betterpets.quickslots;

import com.mojang.blaze3d.platform.InputConstants;
import de.kamil.betterpets.quickslots.QuickslotProtocol;
import de.yourshika.betterpets.quickslots.ui.Anim;
import de.yourshika.betterpets.quickslots.ui.Draw;
import de.yourshika.betterpets.quickslots.ui.Palette;
import de.yourshika.betterpets.quickslots.ui.Sprites;
import de.yourshika.betterpets.quickslots.ui.TextButton;
import de.yourshika.betterpets.quickslots.ui.UiSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The slot screen: the quickslot bar on top, every owned pet below it.
 *
 * <p>Pick a slot, click a pet - that is the whole interaction. The pet hops over into its slot and the
 * selection moves on to the next free one, so filling all slots is one click per pet. Right-click empties
 * a slot (or takes a pet out of its slot), and a slot's own key can be set right here by clicking the key
 * under it; everything else about the keys is in the settings, behind the gear button.</p>
 */
class QuickslotScreen extends Screen {

    private static final String KEY = BetterPetsQuickslots.MOD_ID + ".screen.";

    // --- layout (GUI-scaled pixels) ---
    private static final int MAX_PANEL_WIDTH = 640;
    private static final int MAX_PANEL_HEIGHT = 360;
    private static final int MARGIN = 11;
    private static final int SLOT_SIZE = 36;
    private static final int SLOT_GAP = 6;
    private static final int CARD_MIN_WIDTH = 134;
    private static final int CARD_HEIGHT = 38;
    private static final int CARD_GAP = 4;
    private static final int BUTTON_HEIGHT = 18;
    private static final int SEARCH_HEIGHT = 16;

    /** How long a pet takes to hop from its card into its slot, and how long the slot bounces after. */
    private static final long FLIGHT_MILLIS = 260L;
    private static final long POP_MILLIS = 340L;

    /** A pet on its way from its card to the slot it was just put into. */
    private record Flight(String texture, float fromX, float fromY, int slot, long startedAt) {
    }

    private final Anim.FrameTimer timer = new Anim.FrameTimer();
    private final long openedAt = Anim.now();
    private EditBox search;
    private String query = "";
    private int selectedSlot;
    /** The slot whose key is being rebound (waiting for a key press), or -1. */
    private int capturingSlot = -1;
    /** Where the pet list is scrolling to, and where it is right now. */
    private float scroll;
    private float scrollShown;
    private boolean draggingScrollbar;
    /** The key that opened the screen closes it again - once it has been let go of first. */
    private boolean closeKeyArmed;
    private long gridChangedAt = openedAt;
    private Flight flight;

    // How much the mouse is on each slot, each key and each pet card (0..1, eased), and when a slot last
    // had a pet land in it.
    private final float[] slotHover = new float[QuickslotProtocol.MAX_SLOTS];
    private final float[] keyHover = new float[QuickslotProtocol.MAX_SLOTS];
    private final long[] slotFilledAt = new long[QuickslotProtocol.MAX_SLOTS];
    private final Map<String, Float> cardHover = new HashMap<>();

    // Recomputed every frame from the window size and the current data.
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int searchX;
    private int searchWidth;
    private int slotBarX;
    private int slotBarY;
    private int keysY;
    private int gridX;
    private int gridY;
    private int gridWidth;
    private int gridHeight;
    private int columns;
    private int cardWidth;
    private int contentHeight;
    private int footerY;
    private List<SlotView> slots = List.of();
    private List<QuickslotProtocol.Pet> visiblePets = List.of();

    QuickslotScreen() {
        super(Component.translatable(KEY + "title"));
        selectedSlot = firstEmptySlot(0);
    }

    @Override
    protected void init() {
        layout();
        // The game's text field without its own box: the frame around it is drawn from the mod's textures.
        search = new EditBox(font, searchX + 17, panelY + 11, searchWidth - 22, 9, Component.translatable(KEY + "search"));
        search.setBordered(false);
        search.setTextColor(Palette.TEXT);
        search.setHint(Draw.clip(font, Component.translatable(KEY + "search").withColor(Palette.TEXT_FAINT & 0xFFFFFF), searchWidth - 24));
        search.setMaxLength(32);
        search.setValue(query);
        search.setResponder(value -> {
            if (!value.equals(query)) {
                query = value;
                scroll = 0.0F;
                scrollShown = 0.0F;
                gridChangedAt = Anim.now();
            }
        });
        addRenderableWidget(search);

        final int buttonWidth = Math.min(112, (panelWidth - 2 * MARGIN - 2 * 4) / 3);
        int x = (width - (3 * buttonWidth + 2 * 4)) / 2;
        addRenderableWidget(new TextButton(x, footerY, buttonWidth, BUTTON_HEIGHT, Component.translatable(KEY + "pets_menu"), Sprites.ICON_MENU, () -> {
            onClose();
            QuickslotClient.openPetsMenu(minecraft);
        })).setTooltip(Draw.tooltip(Component.translatable(KEY + "pets_menu.tooltip")));
        x += buttonWidth + 4;
        addRenderableWidget(new TextButton(x, footerY, buttonWidth, BUTTON_HEIGHT, Component.translatable(KEY + "settings"), Sprites.ICON_GEAR,
            () -> minecraft.gui.setScreen(new SettingsScreen(this)))).setTooltip(Draw.tooltip(Component.translatable(KEY + "settings.tooltip")));
        x += buttonWidth + 4;
        addRenderableWidget(new TextButton(x, footerY, buttonWidth, BUTTON_HEIGHT, Component.translatable("gui.done"), Sprites.ICON_CHECK, this::onClose));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------------------

    private void layout() {
        panelWidth = Math.min(width - 8, MAX_PANEL_WIDTH);
        panelHeight = Math.min(height - 8, MAX_PANEL_HEIGHT);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        searchWidth = panelWidth >= 460 ? 118 : 94;
        searchX = panelX + panelWidth - MARGIN - searchWidth;

        slots = SlotView.live();
        final int barWidth = slots.size() * SLOT_SIZE + Math.max(0, slots.size() - 1) * SLOT_GAP;
        slotBarX = (width - barWidth) / 2;
        slotBarY = panelY + 29;
        keysY = slotBarY + SLOT_SIZE + 3;
        footerY = panelY + panelHeight - MARGIN - BUTTON_HEIGHT;

        gridX = panelX + MARGIN;
        gridY = keysY + Draw.KEY_CAP_HEIGHT + 7;
        gridWidth = panelWidth - 2 * MARGIN - Draw.SCROLLBAR_WIDTH - 4;
        gridHeight = Math.max(CARD_HEIGHT, footerY - 6 - gridY);
        columns = Math.max(1, (gridWidth + CARD_GAP) / (CARD_MIN_WIDTH + CARD_GAP));
        // The cards share the width out between them, so the grid ends flush with the panel.
        cardWidth = (gridWidth - (columns - 1) * CARD_GAP) / columns;

        visiblePets = filteredPets();
        final int rows = (visiblePets.size() + columns - 1) / columns;
        contentHeight = rows * CARD_HEIGHT + Math.max(0, rows - 1) * CARD_GAP;
        scroll = Math.max(0.0F, Math.min(scroll, maxScroll()));
        if (selectedSlot >= slots.size()) {
            selectedSlot = Math.max(0, slots.size() - 1);
        }
    }

    private float maxScroll() {
        return Math.max(0, contentHeight - gridHeight);
    }

    private List<QuickslotProtocol.Pet> filteredPets() {
        final String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return QuickslotClient.pets();
        }
        final List<QuickslotProtocol.Pet> out = new ArrayList<>();
        for (final QuickslotProtocol.Pet pet : QuickslotClient.pets()) {
            if (pet.name().toLowerCase(Locale.ROOT).contains(needle)
                || pet.typeName().toLowerCase(Locale.ROOT).contains(needle)
                || pet.rarity().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(pet);
            }
        }
        return out;
    }

    private int slotX(final int slot) {
        return slotBarX + slot * (SLOT_SIZE + SLOT_GAP);
    }

    private int cardX(final int index) {
        return gridX + (index % columns) * (cardWidth + CARD_GAP);
    }

    private int cardY(final int index) {
        return gridY + (index / columns) * (CARD_HEIGHT + CARD_GAP) - Math.round(scrollShown);
    }

    private int scrollbarX() {
        return gridX + gridWidth + 4;
    }

    /** The slot under the mouse, or -1. */
    private int slotAt(final double mouseX, final double mouseY) {
        for (int slot = 0; slot < slots.size(); slot++) {
            if (Draw.inside(mouseX, mouseY, slotX(slot), slotBarY, SLOT_SIZE, SLOT_SIZE)) {
                return slot;
            }
        }
        return -1;
    }

    /** The slot whose key is under the mouse, or -1. */
    private int keyAt(final double mouseX, final double mouseY) {
        for (int slot = 0; slot < slots.size(); slot++) {
            if (Draw.inside(mouseX, mouseY, slotX(slot) - SLOT_GAP / 2, keysY - 1, SLOT_SIZE + SLOT_GAP, Draw.KEY_CAP_HEIGHT + 2)) {
                return slot;
            }
        }
        return -1;
    }

    /** The index into {@link #visiblePets} of the card under the mouse, or -1. */
    private int cardAt(final double mouseX, final double mouseY) {
        if (mouseY < gridY || mouseY >= gridY + gridHeight) {
            return -1;
        }
        for (int index = 0; index < visiblePets.size(); index++) {
            if (Draw.inside(mouseX, mouseY, cardX(index), cardY(index), cardWidth, CARD_HEIGHT)) {
                return index;
            }
        }
        return -1;
    }

    private int firstEmptySlot(final int from) {
        final List<String> ids = QuickslotClient.slots();
        for (int i = 0; i < ids.size(); i++) {
            final int slot = (from + i) % ids.size();
            if (ids.get(slot).isEmpty()) {
                return slot;
            }
        }
        return ids.isEmpty() ? 0 : Math.min(from, ids.size() - 1);
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        Draw.veil(graphics, width, height, 0.62F * Anim.outCubic(Anim.progress(openedAt, 220L)));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int realMouseY, final float partialTick) {
        final float delta = timer.tick();
        layout();
        if (!Keybinds.isHeld(Keybinds.openScreen)) {
            closeKeyArmed = true;
        }
        scrollShown = Anim.approach(scrollShown, scroll, 20.0F, delta);

        // The panel arrives from just below its place; everything on it comes along.
        final int offset = Math.round((1.0F - Anim.outCubic(Anim.progress(openedAt, 260L))) * 14.0F);
        final int mouseY = realMouseY - offset;
        graphics.pose().pushMatrix();
        graphics.pose().translate(0.0F, offset);

        final boolean decorated = ModConfig.get().decorations;
        if (decorated && panelX >= 64) {
            final float sway = (Anim.wave(3200L) - 0.5F) * 3.0F;
            Sprites.draw(graphics, Sprites.CHARACTER_CAT, panelX - 58, Math.round(panelY + panelHeight - 66 + sway), 70, 60);
            Sprites.draw(graphics, Sprites.CHARACTER_OWL, panelX + panelWidth - 14, Math.round(panelY + panelHeight - 78 - sway), 70, 73);
        }
        Sprites.draw(graphics, Sprites.PANEL, panelX, panelY, panelWidth, panelHeight);

        final int titleX = panelX + 14;
        graphics.text(font, title, titleX, panelY + 11, Palette.GOLD);
        // The hint takes the room between the title and the search box, if there is any.
        final int hintLeft = titleX + font.width(title) + 10;
        final int hintWidth = searchX - 8 - hintLeft;
        if (hintWidth > 60) {
            final boolean capturing = capturingSlot >= 0;
            graphics.centeredText(font, Draw.clip(font, Component.translatable(KEY + (capturing ? "hint.capture" : "hint")), hintWidth),
                hintLeft + hintWidth / 2, panelY + 11, capturing ? Palette.GOLD : Palette.TEXT_DIM);
        }
        Sprites.draw(graphics, Sprites.INSET, searchX, panelY + 7, searchWidth, SEARCH_HEIGHT);
        graphics.pose().pushMatrix();
        graphics.pose().translate(searchX + 3, panelY + 9);
        graphics.pose().scale(0.75F, 0.75F);
        Sprites.draw(graphics, Sprites.ICON_SEARCH, 0, 0, 16, 16, Palette.fade(Palette.WHITE, search.isFocused() ? 1.0F : 0.6F));
        graphics.pose().popMatrix();

        int hoveredSlot = -1;
        int hoveredKey = -1;
        int hoveredCard = -1;
        if (!QuickslotClient.usable()) {
            Draw.wrappedCentred(graphics, font, Component.translatable(KEY + "unavailable"), width / 2, panelY + panelHeight / 2 - 10,
                panelWidth - 60, Palette.RED);
        } else {
            hoveredSlot = capturingSlot >= 0 ? -1 : slotAt(mouseX, mouseY);
            hoveredKey = capturingSlot >= 0 ? -1 : keyAt(mouseX, mouseY);
            for (int slot = 0; slot < slots.size(); slot++) {
                drawSlot(graphics, slots.get(slot), slot == hoveredSlot, delta);
                drawKey(graphics, slot, slot == hoveredKey, delta);
            }
            hoveredCard = capturingSlot >= 0 || draggingScrollbar ? -1 : cardAt(mouseX, mouseY);
            if (visiblePets.isEmpty()) {
                Draw.wrappedCentred(graphics, font, Component.translatable(KEY + (QuickslotClient.pets().isEmpty() ? "no_pets" : "no_match")),
                    width / 2, gridY + gridHeight / 2 - 5, gridWidth - 20, Palette.TEXT_DIM);
            } else {
                // A little room above the grid, so a card that lifts under the mouse is not cut off.
                graphics.enableScissor(gridX - 2, gridY - 1, gridX + gridWidth + 2, gridY + gridHeight);
                int shown = 0;
                for (int index = 0; index < visiblePets.size(); index++) {
                    final int y = cardY(index);
                    if (y + CARD_HEIGHT > gridY && y < gridY + gridHeight) {
                        drawCard(graphics, visiblePets.get(index), cardX(index), y, index == hoveredCard, shown++, delta);
                    }
                }
                graphics.disableScissor();
                Draw.scrollbar(graphics, scrollbarX(), gridY, gridHeight, scrollShown, maxScroll(),
                    draggingScrollbar || Draw.inside(mouseX, mouseY, scrollbarX() - 3, gridY, Draw.SCROLLBAR_WIDTH + 6, gridHeight));
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawFlight(graphics);
        if (decorated) {
            Draw.sparkles(graphics, panelX, panelY, panelWidth, panelHeight);
        }
        graphics.pose().popMatrix();

        if (hoveredSlot >= 0) {
            graphics.setComponentTooltipForNextFrame(font, slotTooltip(hoveredSlot), mouseX, realMouseY, Sprites.TOOLTIP_STYLE);
        } else if (hoveredKey >= 0) {
            graphics.setComponentTooltipForNextFrame(font, keyTooltip(hoveredKey), mouseX, realMouseY, Sprites.TOOLTIP_STYLE);
        } else if (hoveredCard >= 0) {
            graphics.setComponentTooltipForNextFrame(font, petTooltip(visiblePets.get(hoveredCard)), mouseX, realMouseY, Sprites.TOOLTIP_STYLE);
        }
    }

    private void drawSlot(final GuiGraphicsExtractor graphics, final SlotView view, final boolean hovered, final float delta) {
        final int slot = view.index();
        slotHover[slot] = Anim.approach(slotHover[slot], hovered ? 1.0F : 0.0F, 16.0F, delta);
        final boolean selected = slot == selectedSlot;
        // The slots pop up one after another when the screen opens, and bounce when a pet lands in them.
        final float arrive = Anim.outBack(Anim.staggered(openedAt, 260L, slot, 30L));
        final float bounce = 1.0F - Anim.outCubic(Anim.progress(slotFilledAt[slot], POP_MILLIS));
        final float scale = arrive * (1.0F + 0.05F * slotHover[slot] + 0.16F * bounce);
        final float centreX = slotX(slot) + SLOT_SIZE / 2.0F;
        final float centreY = slotBarY + SLOT_SIZE / 2.0F;

        if (view.active()) {
            Sprites.drawCentred(graphics, Sprites.GLOW, centreX, centreY, 64, 64, scale * 1.05F, 0.0F,
                Palette.rgb(view.colour(), 0.5F + 0.25F * Anim.wave(1800L)));
        } else if (!view.empty() && slotHover[slot] > 0.02F) {
            Sprites.drawCentred(graphics, Sprites.GLOW, centreX, centreY, 64, 64, scale, 0.0F, Palette.rgb(view.colour(), 0.45F * slotHover[slot]));
        }

        graphics.pose().pushMatrix();
        graphics.pose().translate(centreX, centreY);
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-SLOT_SIZE / 2.0F, -SLOT_SIZE / 2.0F);
        Sprites.draw(graphics, Sprites.SLOT, 0, 0, SLOT_SIZE, SLOT_SIZE);
        // While its pet is still hopping over, the slot stays empty.
        final boolean landing = flight != null && flight.slot() == slot;
        if (!view.empty() && !landing) {
            view.drawIcon(graphics, 2.0F, 2.0F, 2.0F);
            if (!view.usable()) {
                graphics.fill(2, 2, SLOT_SIZE - 2, SLOT_SIZE - 2, Palette.rgb(0x16110F, 0.6F));
            }
        }
        graphics.text(font, Integer.toString(slot + 1), 4, 4, selected ? Palette.GOLD : Palette.TEXT_DIM);
        if (view.active()) {
            Sprites.draw(graphics, Sprites.GEM_GREEN, SLOT_SIZE - 10, 4, 6, 6);
        }
        if (selected) {
            Sprites.draw(graphics, Sprites.FRAME_GOLD, -2, -2, SLOT_SIZE + 4, SLOT_SIZE + 4, Palette.fade(Palette.WHITE, 0.7F + 0.3F * Anim.wave(1400L)));
        } else if (slotHover[slot] > 0.02F) {
            Sprites.draw(graphics, Sprites.FRAME_GOLD, -2, -2, SLOT_SIZE + 4, SLOT_SIZE + 4, Palette.fade(Palette.WHITE, 0.4F * slotHover[slot]));
        }
        graphics.pose().popMatrix();
    }

    /** The key under a slot: its own if it has one, otherwise - dimmer - how the modifier key reaches it. */
    private void drawKey(final GuiGraphicsExtractor graphics, final int slot, final boolean hovered, final float delta) {
        keyHover[slot] = Anim.approach(keyHover[slot], hovered ? 1.0F : 0.0F, 16.0F, delta);
        final KeyMapping mapping = Keybinds.SLOTS[slot];
        final boolean waiting = capturingSlot == slot;
        final boolean own = !mapping.isUnbound();
        Component text = waiting ? Component.literal("> ? <") : own ? Keybinds.label(mapping) : Keybinds.slotHint(slot);
        final int capWidth = Math.max(24, Math.min(SLOT_SIZE + SLOT_GAP - 2, font.width(text) + 8));
        text = Draw.clip(font, text, capWidth - 4);
        final int capX = slotX(slot) + (SLOT_SIZE - capWidth) / 2;
        final float arrive = Anim.outCubic(Anim.staggered(openedAt, 260L, slot, 30L));
        // Reached through the modifier key: a little dimmer than a key of its own. Not reachable at all: faint.
        final boolean reachable = own || waiting || !text.getString().equals("—");
        Sprites.draw(graphics, Sprites.KEY_CAP, capX, keysY, capWidth, Draw.KEY_CAP_HEIGHT, Palette.fade(Palette.WHITE, arrive * (reachable ? 1.0F : 0.6F)));
        Draw.centred(graphics, font, text, capX + capWidth / 2, keysY + 2,
            Palette.fade(waiting ? Palette.GOLD : own ? Palette.TEXT : reachable ? Palette.TEXT_DIM : Palette.TEXT_FAINT, arrive));
        final float frame = waiting ? 0.55F + 0.45F * Anim.wave(700L) : 0.45F * keyHover[slot];
        if (frame > 0.02F) {
            Sprites.draw(graphics, Sprites.FRAME_GOLD, capX - 2, keysY - 2, capWidth + 4, Draw.KEY_CAP_HEIGHT + 4, Palette.fade(Palette.WHITE, frame));
        }
    }

    private void drawCard(final GuiGraphicsExtractor graphics, final QuickslotProtocol.Pet pet, final int x, final int restY, final boolean hovered,
                          final int order, final float delta) {
        final float hover = Anim.approach(cardHover.getOrDefault(pet.id(), 0.0F), hovered && !pet.disabled() ? 1.0F : 0.0F, 16.0F, delta);
        cardHover.put(pet.id(), hover);
        final int slot = QuickslotClient.slotOf(pet.id());
        final boolean active = pet.id().equals(QuickslotClient.activeId());
        // The cards fade in one after another (again after every search), and lift a little under the mouse.
        final float arrive = Anim.outCubic(Anim.staggered(gridChangedAt, 200L, Math.min(order, 14), 16L));
        final int y = restY + Math.round((1.0F - arrive) * 6.0F - hover);
        final float strength = arrive * (pet.disabled() ? 0.6F : 1.0F);

        Sprites.draw(graphics, Sprites.CARD, x, y, cardWidth, CARD_HEIGHT, Palette.fade(Palette.WHITE, strength));
        Sprites.draw(graphics, Sprites.CARD_HOVER, x, y, cardWidth, CARD_HEIGHT, Palette.fade(Palette.WHITE, arrive * hover));
        // The rarity colour as a strip down the left edge, the pet itself in a slot of its own.
        graphics.fill(x + 3, y + 4, x + 5, y + CARD_HEIGHT - 4, Palette.rgb(pet.color(), strength));
        Sprites.draw(graphics, Sprites.SLOT, x + 7, y + 2, 34, 34, Palette.fade(Palette.WHITE, strength));
        if (arrive > 0.5F) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + 8, y + 3);
            graphics.pose().scale(2.0F, 2.0F);
            graphics.fakeItem(PetIcons.head(pet.texture()), 0, 0);
            graphics.pose().popMatrix();
            if (pet.disabled()) {
                graphics.fill(x + 9, y + 4, x + 39, y + 34, Palette.rgb(0x16110F, 0.6F));
            }
        }
        if (active) {
            Sprites.draw(graphics, Sprites.GEM_GREEN, x + 33, y + 4, 6, 6, Palette.fade(Palette.WHITE, arrive));
        }

        final int textX = x + 46;
        final int textWidth = cardWidth - 46 - (slot >= 0 ? 21 : 6);
        Draw.text(graphics, font, Draw.clip(font, Component.literal(pet.name()), textWidth), textX, y + 5,
            pet.disabled() ? Palette.fade(Palette.TEXT_FAINT, arrive) : Palette.rgb(pet.color(), arrive));
        final MutableComponent level = Component.translatable(KEY + "level", pet.level());
        if (pet.stars() > 0) {
            level.append(Component.literal("  " + "★".repeat(pet.stars())).withStyle(ChatFormatting.GOLD));
        }
        Draw.text(graphics, font, level, textX, y + 15, Palette.fade(pet.disabled() ? Palette.TEXT_FAINT : Palette.TEXT, arrive));
        final Component status = pet.disabled()
            ? Component.translatable(KEY + "pet.disabled").withStyle(ChatFormatting.RED)
            : active ? Component.translatable(KEY + "pet.active").withStyle(ChatFormatting.GREEN)
            : Component.literal(pet.rarity());
        Draw.text(graphics, font, Draw.clip(font, status, cardWidth - 52), textX, y + 25, Palette.fade(Palette.TEXT_FAINT, arrive));

        if (slot >= 0) {
            // A gold badge with the slot this pet is parked in.
            final int badgeX = x + cardWidth - 19;
            Sprites.draw(graphics, Sprites.FRAME_GOLD, badgeX, y + 4, 14, 14, Palette.fade(Palette.WHITE, arrive));
            Draw.centred(graphics, font, Component.literal(Integer.toString(slot + 1)), badgeX + 7, y + 7, Palette.fade(Palette.GOLD, arrive));
        }
    }

    /** The pet that was just assigned, hopping from its card over to its slot. */
    private void drawFlight(final GuiGraphicsExtractor graphics) {
        if (flight == null) {
            return;
        }
        final float progress = Anim.progress(flight.startedAt(), FLIGHT_MILLIS);
        if (progress >= 1.0F || flight.slot() >= slots.size()) {
            if (flight.slot() < slotFilledAt.length) {
                slotFilledAt[flight.slot()] = Anim.now();
            }
            flight = null;
            return;
        }
        final float eased = Anim.outCubic(progress);
        final float x = Anim.lerp(flight.fromX(), slotX(flight.slot()) + 2, eased);
        final float y = Anim.lerp(flight.fromY(), slotBarY + 2, eased) - (float) Math.sin(progress * Math.PI) * 16.0F;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(2.0F, 2.0F);
        graphics.fakeItem(PetIcons.head(flight.texture()), 0, 0);
        graphics.pose().popMatrix();
    }

    // ------------------------------------------------------------------------------------------------
    // Tooltips
    // ------------------------------------------------------------------------------------------------

    private List<Component> slotTooltip(final int slot) {
        final List<Component> lines = new ArrayList<>();
        final String petId = QuickslotClient.slots().get(slot);
        final QuickslotProtocol.Pet pet = petId.isEmpty() ? null : QuickslotClient.pet(petId);
        lines.add(Component.translatable(KEY + "slot", slot + 1).withStyle(ChatFormatting.GOLD));
        if (petId.isEmpty()) {
            lines.add(Component.translatable(KEY + "slot.empty").withStyle(ChatFormatting.GRAY));
        } else if (pet == null) {
            lines.add(Component.translatable(KEY + "slot.missing", petId).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal(pet.name()).withColor(pet.color())
                .append(Component.translatable(KEY + "level.suffix", pet.level()).withStyle(ChatFormatting.GRAY)));
        }
        lines.add(Component.empty());
        lines.add(Component.translatable(KEY + "slot.tooltip.select").withStyle(ChatFormatting.DARK_GRAY));
        if (!petId.isEmpty()) {
            lines.add(Component.translatable(KEY + "slot.tooltip.summon").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable(KEY + "slot.tooltip.clear").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private List<Component> keyTooltip(final int slot) {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(KEY + "key.tooltip", slot + 1));
        if (Keybinds.SLOTS[slot].isUnbound()) {
            lines.add(Component.translatable(KEY + "key.tooltip.none", Keybinds.slotHint(slot)).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable(KEY + "key.tooltip.rebind").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private List<Component> petTooltip(final QuickslotProtocol.Pet pet) {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(pet.name()).withColor(pet.color()));
        lines.add(Component.literal(pet.rarity() + " " + pet.typeName()).withStyle(ChatFormatting.GRAY));
        final MutableComponent level = Component.translatable(KEY + "level", pet.level()).withStyle(ChatFormatting.WHITE);
        if (pet.stars() > 0) {
            level.append(Component.literal("  " + "★".repeat(pet.stars())).withStyle(ChatFormatting.GOLD));
        }
        lines.add(level);
        for (final String line : pet.ability().split("\n")) {
            if (!line.isBlank()) {
                lines.add(Component.literal(line).withStyle(ChatFormatting.AQUA));
            }
        }
        lines.add(Component.empty());
        final int slot = QuickslotClient.slotOf(pet.id());
        if (pet.disabled()) {
            lines.add(Component.translatable(KEY + "pet.tooltip.disabled").withStyle(ChatFormatting.RED));
        } else {
            lines.add(Component.translatable(KEY + "pet.tooltip.assign", selectedSlot + 1).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (slot >= 0) {
            lines.add(Component.translatable(KEY + "pet.tooltip.remove", slot + 1).withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    // ------------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (capturingSlot >= 0) {
            // While rebinding, a mouse button is a perfectly good choice of key.
            bind(capturingSlot, InputConstants.Type.MOUSE.getOrCreate(event.button()));
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        // The whole framed box belongs to the search field, not just the line of text inside it.
        if (Draw.inside(event.x(), event.y(), searchX, panelY + 7, searchWidth, SEARCH_HEIGHT)) {
            setFocused(search);
            return true;
        }
        if (search.isFocused()) {
            setFocused(null);
        }
        if (!QuickslotClient.usable()) {
            return false;
        }
        final boolean rightClick = event.button() == 1;
        final int key = keyAt(event.x(), event.y());
        if (key >= 0) {
            if (rightClick) {
                bind(key, InputConstants.UNKNOWN);
            } else {
                capturingSlot = key;
                UiSounds.click();
            }
            return true;
        }
        final int slot = slotAt(event.x(), event.y());
        if (slot >= 0) {
            if (rightClick) {
                QuickslotClient.assign(slot, "");
            } else if (doubleClick && !QuickslotClient.slots().get(slot).isEmpty()) {
                QuickslotClient.switchTo(slot);
            }
            selectedSlot = slot;
            UiSounds.tick();
            return true;
        }
        if (maxScroll() > 0.0F && Draw.inside(event.x(), event.y(), scrollbarX() - 3, gridY, Draw.SCROLLBAR_WIDTH + 6, gridHeight)) {
            draggingScrollbar = true;
            scrollbarTo(event.y());
            return true;
        }
        final int card = cardAt(event.x(), event.y());
        if (card >= 0) {
            final QuickslotProtocol.Pet pet = visiblePets.get(card);
            if (rightClick) {
                final int parked = QuickslotClient.slotOf(pet.id());
                if (parked >= 0) {
                    QuickslotClient.assign(parked, "");
                    UiSounds.tick();
                }
            } else if (!pet.disabled() && selectedSlot < slots.size()) {
                flight = new Flight(pet.texture(), cardX(card) + 8, cardY(card) + 3, selectedSlot, Anim.now());
                QuickslotClient.assign(selectedSlot, pet.id());
                selectedSlot = firstEmptySlot(selectedSlot + 1);
                UiSounds.click();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double deltaX, final double deltaY) {
        if (draggingScrollbar) {
            scrollbarTo(event.y());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    private void scrollbarTo(final double mouseY) {
        scroll = Draw.scrollbarTarget(gridY, gridHeight, maxScroll(), mouseY);
        scrollShown = scroll;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (mouseY >= gridY && mouseY < gridY + gridHeight && maxScroll() > 0.0F) {
            scroll = Math.max(0.0F, Math.min(maxScroll(), scroll - (float) scrollY * (CARD_HEIGHT + CARD_GAP)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (capturingSlot >= 0) {
            // Escape clears the binding, exactly as in the game's own key binds screen.
            bind(capturingSlot, event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        // The key that opened the screen closes it again - unless it is being typed into the search box.
        if (closeKeyArmed && !search.isFocused() && Keybinds.openScreen.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return capturingSlot < 0;
    }

    private void bind(final int slot, final InputConstants.Key key) {
        Keybinds.bind(Keybinds.SLOTS[slot], key);
        capturingSlot = -1;
        UiSounds.click();
    }

    // ------------------------------------------------------------------------------------------------
    // For the development preview
    // ------------------------------------------------------------------------------------------------

    /** A point in the middle of the n-th pet card shown. */
    int[] pointOnCard(final int index) {
        layout();
        return new int[] {cardX(index) + cardWidth / 2, cardY(index) + CARD_HEIGHT / 2};
    }

    /** A point in the middle of a slot. */
    int[] pointOnSlot(final int slot) {
        layout();
        return new int[] {slotX(slot) + SLOT_SIZE / 2, slotBarY + SLOT_SIZE / 2};
    }
}
