package de.yourshika.betterpets.quickslots;

import com.mojang.blaze3d.platform.InputConstants;
import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The slot screen: the quickslot bar on top, every owned pet below it.
 *
 * <p>Pick a slot, click a pet - that is the whole interaction. The selection then moves on to the next
 * free slot, so filling all slots is one click per pet. Right-click empties a slot (or takes a pet out of
 * its slot), and the key under each slot can be rebound right here by clicking it.</p>
 */
class QuickslotScreen extends Screen {

    private static final String KEY = BetterPetsQuickslots.MOD_ID + ".screen.";

    // --- layout (GUI-scaled pixels) ---
    private static final int SLOT_SIZE = 36;
    private static final int SLOT_GAP = 6;
    private static final int KEY_LABEL_HEIGHT = 11;
    private static final int CARD_WIDTH = 134;
    private static final int CARD_HEIGHT = 38;
    private static final int CARD_GAP = 4;
    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 30;
    private static final int SCROLLBAR_WIDTH = 4;

    // --- colours (ARGB) ---
    private static final int PANEL = 0xC0101014;
    private static final int PANEL_HOVER = 0xD0202028;
    private static final int BORDER = 0xFF3A3A44;
    private static final int BORDER_HOVER = 0xFFB8B8C8;
    private static final int GOLD = 0xFFFFC83C;
    private static final int GREEN = 0xFF5BE36A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFA8A8B4;
    private static final int TEXT_FAINT = 0xFF70707C;
    private static final int RED = 0xFFFF6B6B;

    private EditBox search;
    private String query = "";
    private int selectedSlot;
    /** The slot whose key is being rebound (waiting for a key press), or -1. */
    private int capturingSlot = -1;
    private int scroll;

    // Recomputed every frame from the window size and the current data.
    private int slotBarX;
    private int slotBarY;
    private int gridX;
    private int gridY;
    private int gridHeight;
    private int columns;
    private int contentHeight;
    private List<QuickslotProtocol.Pet> visiblePets = List.of();

    QuickslotScreen() {
        super(Component.translatable(KEY + "title"));
        selectedSlot = firstEmptySlot(0);
    }

    @Override
    protected void init() {
        final int searchWidth = width >= 460 ? 110 : 90;
        search = new EditBox(font, width - 12 - searchWidth, 7, searchWidth, 16, Component.translatable(KEY + "search"));
        search.setHint(Component.translatable(KEY + "search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(32);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            scroll = 0;
        });
        addRenderableWidget(search);

        final int buttonY = height - FOOTER_HEIGHT + 5;
        final int buttonWidth = Math.min(98, (width - 24 - 3 * 4) / 4);
        int x = (width - (4 * buttonWidth + 3 * 4)) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "pets_menu"), button -> {
            onClose();
            QuickslotClient.openPetsMenu(minecraft);
        }).bounds(x, buttonY, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable(KEY + "pets_menu.tooltip"))).build());
        x += buttonWidth + 4;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "keys"),
                button -> minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)))
            .bounds(x, buttonY, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable(KEY + "keys.tooltip"))).build());
        x += buttonWidth + 4;
        addRenderableWidget(Button.builder(hudLabel(), button -> {
            ModConfig.setHudEnabled(!ModConfig.hudEnabled());
            button.setMessage(hudLabel());
        }).bounds(x, buttonY, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable(KEY + "hud.tooltip"))).build());
        x += buttonWidth + 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
            .bounds(x, buttonY, buttonWidth, 20).build());
    }

    private static Component hudLabel() {
        return Component.translatable(KEY + (ModConfig.hudEnabled() ? "hud.on" : "hud.off"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------------------

    private void layout() {
        final int slotCount = QuickslotClient.slots().size();
        final int barWidth = slotCount * SLOT_SIZE + Math.max(0, slotCount - 1) * SLOT_GAP;
        slotBarX = (width - barWidth) / 2;
        slotBarY = HEADER_HEIGHT + 6;

        gridY = slotBarY + SLOT_SIZE + KEY_LABEL_HEIGHT + 14;
        gridHeight = Math.max(CARD_HEIGHT, height - FOOTER_HEIGHT - 4 - gridY);
        columns = Math.max(1, (width - 24 - SCROLLBAR_WIDTH + CARD_GAP) / (CARD_WIDTH + CARD_GAP));
        gridX = (width - (columns * CARD_WIDTH + (columns - 1) * CARD_GAP)) / 2;

        visiblePets = filteredPets();
        final int rows = (visiblePets.size() + columns - 1) / columns;
        contentHeight = rows * CARD_HEIGHT + Math.max(0, rows - 1) * CARD_GAP;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - gridHeight)));
        if (selectedSlot >= slotCount) {
            selectedSlot = Math.max(0, slotCount - 1);
        }
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
        return gridX + (index % columns) * (CARD_WIDTH + CARD_GAP);
    }

    private int cardY(final int index) {
        return gridY + (index / columns) * (CARD_HEIGHT + CARD_GAP) - scroll;
    }

    /** The slot under the mouse, or -1. */
    private int slotAt(final double mouseX, final double mouseY) {
        for (int slot = 0; slot < QuickslotClient.slots().size(); slot++) {
            if (inside(mouseX, mouseY, slotX(slot), slotBarY, SLOT_SIZE, SLOT_SIZE)) {
                return slot;
            }
        }
        return -1;
    }

    /** The slot whose key label is under the mouse, or -1. */
    private int keyLabelAt(final double mouseX, final double mouseY) {
        for (int slot = 0; slot < QuickslotClient.slots().size(); slot++) {
            if (inside(mouseX, mouseY, slotX(slot) - SLOT_GAP / 2, slotBarY + SLOT_SIZE + 1, SLOT_SIZE + SLOT_GAP, KEY_LABEL_HEIGHT + 1)) {
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
            if (inside(mouseX, mouseY, cardX(index), cardY(index), CARD_WIDTH, CARD_HEIGHT)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean inside(final double mouseX, final double mouseY, final int x, final int y, final int w, final int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private int firstEmptySlot(final int from) {
        final List<String> slots = QuickslotClient.slots();
        for (int i = 0; i < slots.size(); i++) {
            final int slot = (from + i) % slots.size();
            if (slots.get(slot).isEmpty()) {
                return slot;
            }
        }
        return slots.isEmpty() ? 0 : Math.min(from, slots.size() - 1);
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        layout();

        graphics.centeredText(font, title, width / 2, 8, GOLD);
        // Centred, but never wider than the room the search box in the corner leaves on either side.
        final int hintWidth = 2 * (search.getX() - 8 - width / 2);
        graphics.centeredText(font, clip(Component.translatable(KEY + (capturingSlot >= 0 ? "hint.capture" : "hint")), hintWidth),
            width / 2, 20, capturingSlot >= 0 ? GOLD : TEXT_DIM);
        graphics.fill(12, HEADER_HEIGHT, width - 12, HEADER_HEIGHT + 1, 0x40FFFFFF);
        graphics.fill(12, height - FOOTER_HEIGHT, width - 12, height - FOOTER_HEIGHT + 1, 0x40FFFFFF);

        if (!QuickslotClient.usable()) {
            graphics.centeredText(font, Component.translatable(KEY + "unavailable"), width / 2, height / 2 - 4, RED);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }

        final int hoveredSlot = slotAt(mouseX, mouseY);
        final int hoveredKey = keyLabelAt(mouseX, mouseY);
        for (int slot = 0; slot < QuickslotClient.slots().size(); slot++) {
            drawSlot(graphics, slot, slot == hoveredSlot, slot == hoveredKey);
        }

        final int hoveredCard = cardAt(mouseX, mouseY);
        if (visiblePets.isEmpty()) {
            graphics.centeredText(font, Component.translatable(KEY + (QuickslotClient.pets().isEmpty() ? "no_pets" : "no_match")),
                width / 2, gridY + gridHeight / 2 - 4, TEXT_DIM);
        } else {
            graphics.enableScissor(0, gridY, width, gridY + gridHeight);
            for (int index = 0; index < visiblePets.size(); index++) {
                final int y = cardY(index);
                if (y + CARD_HEIGHT > gridY && y < gridY + gridHeight) {
                    drawCard(graphics, visiblePets.get(index), cardX(index), y, index == hoveredCard);
                }
            }
            graphics.disableScissor();
            drawScrollbar(graphics);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (capturingSlot < 0) {
            if (hoveredSlot >= 0) {
                graphics.setComponentTooltipForNextFrame(font, slotTooltip(hoveredSlot), mouseX, mouseY);
            } else if (hoveredKey >= 0) {
                graphics.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable(KEY + "key.tooltip", hoveredKey + 1),
                    Component.translatable(KEY + "key.tooltip.rebind").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            } else if (hoveredCard >= 0) {
                graphics.setComponentTooltipForNextFrame(font, petTooltip(visiblePets.get(hoveredCard)), mouseX, mouseY);
            }
        }
    }

    private void drawSlot(final GuiGraphicsExtractor graphics, final int slot, final boolean hovered, final boolean keyHovered) {
        final int x = slotX(slot);
        final int y = slotBarY;
        final String petId = QuickslotClient.slots().get(slot);
        final QuickslotProtocol.Pet pet = petId.isEmpty() ? null : QuickslotClient.pet(petId);
        final boolean selected = slot == selectedSlot;
        final boolean active = !petId.isEmpty() && petId.equals(QuickslotClient.activeId());

        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, hovered ? PANEL_HOVER : PANEL);
        if (selected) {
            // A double frame so the chosen slot reads at a glance.
            graphics.outline(x - 2, y - 2, SLOT_SIZE + 4, SLOT_SIZE + 4, GOLD);
            graphics.outline(x - 1, y - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, GOLD);
        } else {
            graphics.outline(x, y, SLOT_SIZE, SLOT_SIZE, hovered ? BORDER_HOVER : pet == null ? BORDER : rarityBorder(pet, false));
        }

        if (pet != null) {
            drawIcon(graphics, pet, x + 2, y + 2, 2.0F);
            if (pet.disabled()) {
                graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0xA0101014);
            }
        } else if (!petId.isEmpty()) {
            // Parked, but not owned right now (converted to an item or traded away).
            graphics.centeredText(font, "?", x + SLOT_SIZE / 2, y + SLOT_SIZE / 2 - 4, TEXT_FAINT);
        }
        graphics.text(font, Integer.toString(slot + 1), x + 3, y + 3, selected ? GOLD : TEXT_DIM);
        if (active) {
            graphics.fill(x + SLOT_SIZE - 7, y + 3, x + SLOT_SIZE - 3, y + 7, GREEN);
        }

        final Component keyText = capturingSlot == slot
            ? Component.literal("> ? <").withStyle(ChatFormatting.YELLOW)
            : keyLabel(Keybinds.SLOTS[slot]);
        final int keyColor = capturingSlot == slot ? GOLD : keyHovered ? TEXT : Keybinds.SLOTS[slot].isUnbound() ? TEXT_FAINT : TEXT_DIM;
        graphics.centeredText(font, clip(keyText, SLOT_SIZE + SLOT_GAP - 2), x + SLOT_SIZE / 2, y + SLOT_SIZE + 3, keyColor);
    }

    private void drawCard(final GuiGraphicsExtractor graphics, final QuickslotProtocol.Pet pet, final int x, final int y, final boolean hovered) {
        final int slot = QuickslotClient.slotOf(pet.id());
        final boolean active = pet.id().equals(QuickslotClient.activeId());

        graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, hovered && !pet.disabled() ? PANEL_HOVER : PANEL);
        graphics.outline(x, y, CARD_WIDTH, CARD_HEIGHT, rarityBorder(pet, hovered || slot >= 0));
        // The rarity colour as a solid strip down the left edge.
        graphics.fill(x + 1, y + 1, x + 3, y + CARD_HEIGHT - 1, ARGB.opaque(pet.color()));

        drawIcon(graphics, pet, x + 5, y + 3, 2.0F);

        final int textX = x + 41;
        final int textWidth = CARD_WIDTH - 41 - (slot >= 0 ? 18 : 4);
        graphics.text(font, clip(Component.literal(pet.name()), textWidth), textX, y + 4, pet.disabled() ? TEXT_FAINT : ARGB.opaque(pet.color()));
        final MutableComponent level = Component.translatable(KEY + "level", pet.level());
        if (pet.stars() > 0) {
            level.append(Component.literal("  " + "★".repeat(pet.stars())).withStyle(ChatFormatting.GOLD));
        }
        graphics.text(font, level, textX, y + 15, pet.disabled() ? TEXT_FAINT : TEXT);
        final Component status = pet.disabled()
            ? Component.translatable(KEY + "pet.disabled").withStyle(ChatFormatting.RED)
            : active ? Component.translatable(KEY + "pet.active").withStyle(ChatFormatting.GREEN)
            : Component.literal(pet.rarity());
        graphics.text(font, clip(status, CARD_WIDTH - 45), textX, y + 26, TEXT_FAINT);

        if (slot >= 0) {
            // Badge with the slot this pet is parked in.
            final int badgeX = x + CARD_WIDTH - 15;
            graphics.fill(badgeX, y + 3, badgeX + 12, y + 14, GOLD);
            graphics.centeredText(font, Component.literal(Integer.toString(slot + 1)).withoutShadow(), badgeX + 6, y + 5, 0xFF201800);
        }
        if (pet.disabled()) {
            graphics.fill(x + 1, y + 1, x + 39, y + CARD_HEIGHT - 1, 0x90101014);
        }
    }

    /** Draws the pet's head, scaled up from the 16px item size. */
    private void drawIcon(final GuiGraphicsExtractor graphics, final QuickslotProtocol.Pet pet, final int x, final int y, final float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.fakeItem(PetIcons.head(pet.texture()), 0, 0);
        graphics.pose().popMatrix();
    }

    private void drawScrollbar(final GuiGraphicsExtractor graphics) {
        if (contentHeight <= gridHeight) {
            return;
        }
        final int trackX = gridX + columns * CARD_WIDTH + (columns - 1) * CARD_GAP + 4;
        final int thumbHeight = Math.max(12, gridHeight * gridHeight / contentHeight);
        final int thumbY = gridY + (gridHeight - thumbHeight) * scroll / (contentHeight - gridHeight);
        graphics.fill(trackX, gridY, trackX + SCROLLBAR_WIDTH, gridY + gridHeight, 0x60000000);
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFF8C8C9C);
    }

    private static int rarityBorder(final QuickslotProtocol.Pet pet, final boolean bright) {
        return bright ? ARGB.opaque(pet.color()) : ARGB.color(0x80, pet.color());
    }

    /** Shortens a line to a pixel width with an ellipsis. */
    private Component clip(final Component text, final int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        final String plain = text.getString();
        final String cut = font.plainSubstrByWidth(plain, Math.max(0, maxWidth - font.width("…")));
        return Component.literal(cut + "…").withStyle(text.getStyle());
    }

    private static Component keyLabel(final KeyMapping mapping) {
        if (mapping.isUnbound()) {
            return Component.literal("—");
        }
        // The game names keypad keys by their character alone ("1"), which reads exactly like the hotbar
        // key next to a slot number - so those get a "Num" in front here.
        final InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
        if (key.getType() == InputConstants.Type.KEYSYM) {
            final String keypad = switch (key.getValue()) {
                case GLFW.GLFW_KEY_KP_0, GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_KP_2, GLFW.GLFW_KEY_KP_3, GLFW.GLFW_KEY_KP_4,
                     GLFW.GLFW_KEY_KP_5, GLFW.GLFW_KEY_KP_6, GLFW.GLFW_KEY_KP_7, GLFW.GLFW_KEY_KP_8, GLFW.GLFW_KEY_KP_9 ->
                    Integer.toString(key.getValue() - GLFW.GLFW_KEY_KP_0);
                case GLFW.GLFW_KEY_KP_ADD -> "+";
                case GLFW.GLFW_KEY_KP_SUBTRACT -> "-";
                case GLFW.GLFW_KEY_KP_MULTIPLY -> "*";
                case GLFW.GLFW_KEY_KP_DIVIDE -> "/";
                case GLFW.GLFW_KEY_KP_DECIMAL -> ".";
                default -> null;
            };
            if (keypad != null) {
                return Component.literal("Num " + keypad);
            }
        }
        return mapping.getTranslatedKeyMessage();
    }

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

    private List<Component> petTooltip(final QuickslotProtocol.Pet pet) {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(pet.name()).withColor(pet.color()));
        final MutableComponent type = Component.literal(pet.rarity() + " " + pet.typeName()).withStyle(ChatFormatting.GRAY);
        lines.add(type);
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
        if (!QuickslotClient.usable()) {
            return false;
        }
        final boolean rightClick = event.button() == 1;
        final int keyLabel = keyLabelAt(event.x(), event.y());
        if (keyLabel >= 0) {
            if (rightClick) {
                bind(keyLabel, InputConstants.UNKNOWN);
            } else {
                capturingSlot = keyLabel;
            }
            click();
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
            click();
            return true;
        }
        final int card = cardAt(event.x(), event.y());
        if (card >= 0) {
            final QuickslotProtocol.Pet pet = visiblePets.get(card);
            if (rightClick) {
                final int parked = QuickslotClient.slotOf(pet.id());
                if (parked >= 0) {
                    QuickslotClient.assign(parked, "");
                    click();
                }
            } else if (!pet.disabled()) {
                QuickslotClient.assign(selectedSlot, pet.id());
                selectedSlot = firstEmptySlot(selectedSlot + 1);
                click();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (mouseY >= gridY && mouseY < gridY + gridHeight && contentHeight > gridHeight) {
            scroll = (int) Math.max(0, Math.min(contentHeight - gridHeight, scroll - scrollY * (CARD_HEIGHT + CARD_GAP) / 2.0));
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
        if (!search.isFocused() && Keybinds.openScreen.matches(event)) {
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
        Keybinds.SLOTS[slot].setKey(key);
        KeyMapping.resetMapping();
        minecraft.options.save();
        capturingSlot = -1;
        click();
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
