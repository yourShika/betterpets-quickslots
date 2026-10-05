package de.yourshika.betterpets.quickslots;

import com.mojang.blaze3d.platform.InputConstants;
import de.yourshika.betterpets.quickslots.ui.Anim;
import de.yourshika.betterpets.quickslots.ui.Draw;
import de.yourshika.betterpets.quickslots.ui.Palette;
import de.yourshika.betterpets.quickslots.ui.Sprites;
import de.yourshika.betterpets.quickslots.ui.TextButton;
import de.yourshika.betterpets.quickslots.ui.UiSounds;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The mod's settings: which keys switch pets, when and how the quickslots show on the HUD, the pet wheel,
 * and the look of it all - with a preview next to the options that acts every change out at once.
 *
 * <p>Nothing here is the game's stock options list. The panel, the tabs, the switches and the sliders are
 * drawn from the mod's own textures and move: the lit tab glides to the one chosen, switches slide, the
 * rows of a tab arrive one after another. All of that follows the "animations" setting on the last tab.</p>
 *
 * <p>The "full size" button puts the panel away and shows the preview across the whole screen, exactly as
 * large as the game will - there the display can simply be dragged to where it should sit.</p>
 */
class SettingsScreen extends Screen {

    private static final String KEY = BetterPetsQuickslots.MOD_ID + ".settings.";

    private static final int MAX_PANEL_WIDTH = 580;
    private static final int MAX_PANEL_HEIGHT = 320;
    private static final int MARGIN = 10;
    private static final int TAB_HEIGHT = 18;
    private static final int TAB_GAP = 2;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 2;
    private static final int BUTTON_HEIGHT = 18;
    private static final int SIDE_HEADER = 15;
    private static final long TOOLTIP_DELAY_MILLIS = 450L;
    private static final long RESET_ARMED_MILLIS = 3000L;
    /** The settings as they come out of the box - what a right-click on a slider goes back to. */
    private static final ModConfig.Values DEFAULTS = new ModConfig.Values();

    /** The pages of the settings. */
    enum Tab {
        KEYS(Sprites.ICON_KEYS, SettingsPreview.Mode.KEYS),
        DISPLAY(Sprites.ICON_EYE_ON, SettingsPreview.Mode.HUD),
        WHEEL(Sprites.ICON_WHEEL, SettingsPreview.Mode.WHEEL),
        LOOK(Sprites.ICON_SKIN, SettingsPreview.Mode.HUD);

        private final Identifier icon;
        private final SettingsPreview.Mode preview;

        Tab(final Identifier icon, final SettingsPreview.Mode preview) {
            this.icon = icon;
            this.preview = preview;
        }

        private Component title() {
            return Component.translatable(KEY + "tab." + name().toLowerCase(Locale.ROOT));
        }
    }

    private final Screen parent;
    private final SettingsPreview preview = new SettingsPreview();
    private final Anim.FrameTimer timer = new Anim.FrameTimer();
    private final long openedAt = Anim.now();

    private Tab tab = Tab.KEYS;
    private long tabChangedAt = openedAt;
    /** Where the lit tab is on its way between tabs, in tab widths. */
    private float tabSlide;
    private final float[] tabHover = new float[Tab.values().length];

    private List<Row> rows = List.of();
    /** Where the list is scrolling to, and where it is right now. */
    private float scroll;
    private float scrollShown;
    private Row hovered;
    private long hoveredSince;
    /** The key binding waiting for a key press, or {@code null}. */
    private KeyMapping capturing;
    private SliderRow sliding;
    private boolean draggingScrollbar;
    /** The key that opened the screen closes it again - once it has been let go of first. */
    private boolean closeKeyArmed;

    private boolean fullPreview;
    private float fullShown;
    private boolean draggingDisplay;
    private double dragStartX;
    private double dragStartY;
    private int dragStartOffsetX;
    private int dragStartOffsetY;

    private long resetArmedAt;
    private final List<TextButton> panelButtons = new ArrayList<>();
    private TextButton resetButton;
    private TextButton backButton;

    // Worked out from the window size.
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private boolean titled;
    private int tabsY;
    private int tabWidth;
    private int contentY;
    private int contentHeight;
    private int listX;
    private int listWidth;
    private int rowWidth;
    private int sideX;
    private int sideWidth;
    private int footerY;

    SettingsScreen(final Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------------------------------------
    // Setting up
    // ------------------------------------------------------------------------------------------------

    private void layout() {
        panelWidth = Math.min(width - 8, MAX_PANEL_WIDTH);
        panelHeight = Math.min(height - 8, MAX_PANEL_HEIGHT);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        // Only a tall panel can spare a line for its title.
        titled = panelHeight >= 270;
        tabsY = panelY + (titled ? 24 : 9);
        final int inner = panelWidth - 2 * MARGIN;
        tabWidth = (inner - (Tab.values().length - 1) * TAB_GAP) / Tab.values().length;
        contentY = tabsY + TAB_HEIGHT + 5;
        footerY = panelY + panelHeight - MARGIN - BUTTON_HEIGHT;
        contentHeight = Math.max(24, footerY - 5 - contentY);
        // The preview gets what the list can spare, up to the size its scene is drawn at.
        sideWidth = Math.max(120, Math.min(262, inner - 216 - 8));
        listWidth = inner - sideWidth - 8;
        listX = panelX + MARGIN;
        rowWidth = listWidth - Draw.SCROLLBAR_WIDTH - 4;
        sideX = listX + listWidth + 8;
    }

    @Override
    protected void init() {
        layout();
        rows = buildRows();
        panelButtons.clear();

        resetButton = panelButton(new TextButton(panelX + MARGIN, footerY, 96, BUTTON_HEIGHT, resetLabel(), null, this::reset));
        final TextButton done = panelButton(new TextButton(panelX + panelWidth - MARGIN - 74, footerY, 74, BUTTON_HEIGHT,
            Component.translatable("gui.done"), Sprites.ICON_CHECK, this::onClose));
        if (!(parent instanceof QuickslotScreen)) {
            // "Assign slots" only makes sense on a server that has the plugin.
            panelButton(new TextButton(done.getX() - 4 - 104, footerY, 104, BUTTON_HEIGHT,
                Component.translatable(KEY + "slots"), Sprites.ICON_FOX, () -> QuickslotClient.openScreen(minecraft))).active = QuickslotClient.usable();
        }
        final Component full = Component.translatable(KEY + "preview.full");
        final int fullWidth = Math.min(font.width(full) + 14, sideWidth - 4);
        panelButton(new TextButton(sideX + sideWidth - fullWidth, contentY, fullWidth, 13, full, null, () -> setFullPreview(true)));

        final Component back = Component.translatable(KEY + "preview.back");
        backButton = addRenderableWidget(new TextButton(6, 6, font.width(back) + 30, BUTTON_HEIGHT, back, Sprites.ICON_BACK, () -> setFullPreview(false)));
        backButton.visible = fullPreview;
        placeBackButton();
    }

    /** The way back sits in the top left corner - or the right one, if that is where the display is. */
    private void placeBackButton() {
        final boolean taken = tab.preview != SettingsPreview.Mode.WHEEL && config().hudAnchor == ModConfig.HudAnchor.TOP_LEFT;
        backButton.setX(taken ? width - 6 - backButton.getWidth() : 6);
    }

    private TextButton panelButton(final TextButton button) {
        panelButtons.add(button);
        return addRenderableWidget(button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        // Whichever way the screen goes away, what was set in it is on disk afterwards.
        ModConfig.changed();
    }

    /** Switches to another page. */
    void showTab(final Tab next) {
        if (next == tab) {
            return;
        }
        tab = next;
        tabChangedAt = Anim.now();
        scroll = 0.0F;
        scrollShown = 0.0F;
        capturing = null;
        hovered = null;
        rows = buildRows();
    }

    /** Puts the panel away and shows the preview across the whole screen, or brings the panel back. */
    void setFullPreview(final boolean full) {
        fullPreview = full;
        capturing = null;
        sliding = null;
        draggingDisplay = false;
        // Nothing of the panel may stay focused while it is out of sight.
        clearFocus();
        if (full) {
            preview.restart();
            placeBackButton();
        }
    }

    private Component resetLabel() {
        return Component.translatable(KEY + (resetArmedAt != 0L ? "reset.confirm" : "reset"));
    }

    /** Asks once more before it throws the settings away: the second click within a few seconds does it. */
    private void reset() {
        if (resetArmedAt == 0L) {
            resetArmedAt = Anim.now();
        } else {
            resetArmedAt = 0L;
            ModConfig.reset();
            rows = buildRows();
        }
        resetButton.setMessage(resetLabel());
    }

    // ------------------------------------------------------------------------------------------------
    // The options
    // ------------------------------------------------------------------------------------------------

    private static ModConfig.Values config() {
        return ModConfig.get();
    }

    private List<Row> buildRows() {
        final List<Row> list = new ArrayList<>();
        switch (tab) {
            case KEYS -> buildKeys(list);
            case DISPLAY -> buildDisplay(list);
            case WHEEL -> buildWheel(list);
            case LOOK -> buildLook(list);
        }
        return list;
    }

    private void buildKeys(final List<Row> list) {
        list.add(new HeaderRow("keys.presets"));
        list.add(new ActionRow(
            new Action("keys.preset.modifier", () -> preset(Keybinds.Preset.MODIFIER)),
            new Action("keys.preset.numpad", () -> preset(Keybinds.Preset.NUMPAD)),
            new Action("keys.preset.wheel", () -> preset(Keybinds.Preset.WHEEL_ONLY))));

        list.add(new HeaderRow("keys.wheel"));
        list.add(new KeyRow("keys.wheel.key", Keybinds.wheel));

        list.add(new HeaderRow("keys.modifier"));
        list.add(new KeyRow("keys.modifier.key", Keybinds.modifier));
        final BooleanSupplier hasModifier = () -> !Keybinds.modifier.isUnbound();
        list.add(new ToggleRow("keys.modifier.numbers", () -> config().modifierNumbers, value -> config().modifierNumbers = value).when(hasModifier));
        list.add(new ToggleRow("keys.modifier.scroll", () -> config().modifierScroll, value -> config().modifierScroll = value).when(hasModifier));

        list.add(new HeaderRow("keys.direct"));
        for (int slot = 0; slot < Keybinds.SLOTS.length; slot += 3) {
            list.add(new SlotKeysRow(slot));
        }
        list.add(new KeyRow("keys.next", Keybinds.next));
        list.add(new KeyRow("keys.previous", Keybinds.previous));
        list.add(new KeyRow("keys.put_away", Keybinds.putAway));

        list.add(new HeaderRow("keys.menus"));
        list.add(new KeyRow("keys.open_screen", Keybinds.openScreen));
        list.add(new KeyRow("keys.open_settings", Keybinds.openSettings));
        list.add(new KeyRow("keys.open_menu", Keybinds.openMenu));
    }

    private void buildDisplay(final List<Row> list) {
        final BooleanSupplier shown = () -> config().hudStyle != ModConfig.HudStyle.OFF;
        final BooleanSupplier timed = () -> shown.getAsBoolean() && !config().hudAlways;

        list.add(new HeaderRow("display.style.header"));
        list.add(new ChoiceRow<>("display.style", ModConfig.HudStyle.values(), () -> config().hudStyle, value -> config().hudStyle = value,
            value -> Component.translatable(KEY + "display.style." + value.name().toLowerCase(Locale.ROOT))));

        list.add(new HeaderRow("display.when"));
        list.add(new ToggleRow("display.on_change", () -> config().hudOnChange, value -> config().hudOnChange = value).when(timed));
        list.add(new SliderRow("display.seconds", 0.5F, 6.0F, 0.5F, DEFAULTS.hudSeconds, () -> config().hudSeconds, value -> config().hudSeconds = value,
            value -> Component.translatable(KEY + "unit.seconds", String.format(Locale.ROOT, "%.1f", value)))
            .when(() -> timed.getAsBoolean() && config().hudOnChange));
        list.add(new ToggleRow("display.while_modifier", () -> config().hudWhileModifier, value -> config().hudWhileModifier = value)
            .when(() -> timed.getAsBoolean() && !Keybinds.modifier.isUnbound()));
        list.add(new ToggleRow("display.always", () -> config().hudAlways, value -> config().hudAlways = value).when(shown));

        list.add(new HeaderRow("display.position"));
        list.add(new ChoiceRow<>("display.anchor", ModConfig.HudAnchor.values(), () -> config().hudAnchor, value -> config().hudAnchor = value,
            value -> Component.translatable(KEY + "display.anchor." + value.name().toLowerCase(Locale.ROOT))).when(shown));
        list.add(new SliderRow("display.offset_x", -200.0F, 200.0F, 1.0F, 0.0F, () -> config().hudOffsetX, value -> config().hudOffsetX = Math.round(value),
            SettingsScreen::pixels).when(shown));
        list.add(new SliderRow("display.offset_y", -200.0F, 200.0F, 1.0F, 0.0F, () -> config().hudOffsetY, value -> config().hudOffsetY = Math.round(value),
            SettingsScreen::pixels).when(shown));
        list.add(new SliderRow("display.scale", 0.5F, 2.0F, 0.05F, DEFAULTS.hudScale, () -> config().hudScale, value -> config().hudScale = value,
            SettingsScreen::percent).when(shown));
        list.add(new SliderRow("display.opacity", 0.3F, 1.0F, 0.05F, DEFAULTS.hudOpacity, () -> config().hudOpacity, value -> config().hudOpacity = value,
            SettingsScreen::percent).when(shown));

        list.add(new HeaderRow("display.content"));
        list.add(new ToggleRow("display.names", () -> config().hudNames, value -> config().hudNames = value)
            .when(() -> config().hudStyle == ModConfig.HudStyle.BAR || config().hudStyle == ModConfig.HudStyle.WHEEL));
        list.add(new ToggleRow("display.keys", () -> config().hudKeys, value -> config().hudKeys = value)
            .when(() -> config().hudStyle == ModConfig.HudStyle.BAR));
        list.add(new ToggleRow("display.empty_slots", () -> config().hudEmptySlots, value -> config().hudEmptySlots = value)
            .when(() -> config().hudStyle == ModConfig.HudStyle.BAR || config().hudStyle == ModConfig.HudStyle.WHEEL));
    }

    private void buildWheel(final List<Row> list) {
        list.add(new HeaderRow("wheel.header"));
        list.add(new KeyRow("keys.wheel.key", Keybinds.wheel));
        list.add(new ToggleRow("wheel.hold", () -> config().wheelHold, value -> config().wheelHold = value));
        list.add(new SliderRow("wheel.scale", 0.75F, 1.5F, 0.05F, DEFAULTS.wheelScale, () -> config().wheelScale, value -> config().wheelScale = value,
            SettingsScreen::percent));
        list.add(new HeaderRow("wheel.how"));
        list.add(new NoteRow(Component.translatable(KEY + "wheel.note")));
    }

    private void buildLook(final List<Row> list) {
        list.add(new HeaderRow("look.header"));
        list.add(new ToggleRow("look.animations", () -> config().animations, value -> config().animations = value));
        list.add(new SliderRow("look.speed", 0.5F, 2.0F, 0.25F, DEFAULTS.animationSpeed, () -> config().animationSpeed, value -> config().animationSpeed = value,
            SettingsScreen::percent).when(() -> config().animations));
        list.add(new ToggleRow("look.decorations", () -> config().decorations, value -> config().decorations = value));
        list.add(new ToggleRow("look.sounds", () -> config().sounds, value -> config().sounds = value));

        list.add(new HeaderRow("look.spam"));
        list.add(new NoteRow(Component.translatable(KEY + "look.spam.note")));
        if (QuickslotClient.usable() && QuickslotClient.cooldownMillis() > 0) {
            list.add(new NoteRow(Component.translatable(KEY + "look.spam.server",
                String.format(Locale.ROOT, "%.1f", QuickslotClient.cooldownMillis() / 1000.0F)).withColor(Palette.TEAL & 0xFFFFFF)));
        }
    }

    private static Component percent(final float value) {
        return Component.literal(Math.round(value * 100.0F) + "%");
    }

    private static Component pixels(final float value) {
        final int rounded = Math.round(value);
        return Component.literal((rounded > 0 ? "+" : "") + rounded);
    }

    private void preset(final Keybinds.Preset preset) {
        Keybinds.applyPreset(preset);
        // A preset is a whole way of switching: turn its parts on with it.
        if (preset != Keybinds.Preset.WHEEL_ONLY) {
            config().modifierNumbers = true;
            config().modifierScroll = true;
        }
        ModConfig.changed();
    }

    /** The name of another key binding on the same key, or {@code null} if the key is this one's alone. */
    private Component conflict(final KeyMapping mapping) {
        if (mapping.isUnbound()) {
            return null;
        }
        for (final KeyMapping other : minecraft.options.keyMappings) {
            if (other != mapping && !other.isUnbound() && other.same(mapping)) {
                return Component.translatable(other.getName());
            }
        }
        return null;
    }

    private void bind(final KeyMapping mapping, final InputConstants.Key key) {
        Keybinds.bind(mapping, key);
        capturing = null;
        UiSounds.click();
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        if (minecraft.level == null) {
            extractPanorama(graphics, partialTick);
        }
        // A veil rather than the usual blur: the full-size preview wants the world sharp behind it, and
        // a veil can fade as the panel leaves where a blur could only be there or not.
        Draw.veil(graphics, width, height, 0.62F * Anim.outCubic(Anim.progress(openedAt, 220L)) * (1.0F - 0.8F * fullShown));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        final float delta = timer.tick();
        layout();
        if (!Keybinds.isHeld(Keybinds.openSettings)) {
            closeKeyArmed = true;
        }
        if (resetArmedAt != 0L && Anim.now() - resetArmedAt > RESET_ARMED_MILLIS) {
            resetArmedAt = 0L;
            resetButton.setMessage(resetLabel());
        }
        fullShown = Anim.approach(fullShown, fullPreview ? 1.0F : 0.0F, 11.0F, delta);
        // Out of sight means out of reach: hidden widgets take neither clicks nor the keyboard focus.
        backButton.visible = fullShown > 0.01F;
        for (final TextButton button : panelButtons) {
            button.visible = fullShown < 0.99F;
        }

        if (fullShown > 0.01F) {
            preview.drawFull(graphics, tab.preview, width, height, mouseX, mouseY, drawFullHint(graphics) + 30, fullShown, delta);
            backButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        if (fullShown < 0.99F) {
            // The panel arrives from just below its place, and leaves downwards when the preview takes over.
            final int offset = Math.round((1.0F - Anim.outCubic(Anim.progress(openedAt, 260L))) * 14.0F
                + Anim.inOutSine(fullShown) * (height - panelY + 12));
            graphics.pose().pushMatrix();
            graphics.pose().translate(0.0F, offset);
            final int panelMouseY = fullPreview ? -1000 : mouseY - offset;
            drawPanel(graphics, mouseX, panelMouseY, delta);
            for (final TextButton button : panelButtons) {
                button.extractRenderState(graphics, mouseX, panelMouseY, partialTick);
            }
            graphics.pose().popMatrix();
            drawTooltip(graphics, offset);
        }
    }

    private void drawPanel(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        drawDecorations(graphics, true);
        Sprites.draw(graphics, Sprites.PANEL, panelX, panelY, panelWidth, panelHeight);
        if (titled) {
            graphics.centeredText(font, title, width / 2, panelY + 10, Palette.GOLD);
        }
        drawTabs(graphics, mouseX, mouseY, delta);
        drawRows(graphics, mouseX, mouseY, delta);

        Draw.text(graphics, font, Component.translatable(KEY + "preview.title"), sideX + 1, contentY + 3, Palette.COPPER);
        preview.draw(graphics, tab.preview, sideX, contentY + SIDE_HEADER, sideWidth, contentHeight - SIDE_HEADER, width, height, mouseX, mouseY, delta);
        drawDecorations(graphics, false);
    }

    private int tabX(final int index) {
        return panelX + MARGIN + index * (tabWidth + TAB_GAP);
    }

    private void drawTabs(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        final Tab[] tabs = Tab.values();
        tabSlide = Anim.approach(tabSlide, tab.ordinal(), 16.0F, delta);
        for (int i = 0; i < tabs.length; i++) {
            final boolean over = tabs[i] != tab && Draw.inside(mouseX, mouseY, tabX(i), tabsY, tabWidth, TAB_HEIGHT);
            tabHover[i] = Anim.approach(tabHover[i], over ? 1.0F : 0.0F, 16.0F, delta);
            Sprites.draw(graphics, Sprites.TAB, tabX(i), tabsY, tabWidth, TAB_HEIGHT);
            Sprites.draw(graphics, Sprites.TAB_HOVER, tabX(i), tabsY, tabWidth, TAB_HEIGHT, Palette.fade(Palette.WHITE, tabHover[i]));
        }
        // The lit tab glides to the one that was chosen.
        graphics.pose().pushMatrix();
        graphics.pose().translate(panelX + MARGIN + tabSlide * (tabWidth + TAB_GAP), tabsY);
        Sprites.draw(graphics, Sprites.TAB_ACTIVE, 0, 0, tabWidth, TAB_HEIGHT);
        graphics.pose().popMatrix();

        for (int i = 0; i < tabs.length; i++) {
            final float lit = 1.0F - Math.min(1.0F, Math.abs(tabSlide - i));
            final Component label = tabs[i].title();
            final boolean labelled = font.width(label) + 16 <= tabWidth - 8;
            final int contentWidth = 12 + (labelled ? 4 + font.width(label) : 0);
            final int x = tabX(i) + (tabWidth - contentWidth) / 2;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, tabsY + 3 - lit);
            graphics.pose().scale(0.75F, 0.75F);
            Sprites.draw(graphics, tabs[i].icon, 0, 0, 16, 16, Palette.fade(Palette.WHITE, 0.6F + 0.4F * Math.max(lit, tabHover[i])));
            graphics.pose().popMatrix();
            if (labelled) {
                graphics.text(font, label, x + 16, tabsY + 5, Palette.mix(Palette.mix(Palette.TEXT_DIM, Palette.TEXT, tabHover[i]), Palette.GOLD, lit));
            }
        }
    }

    private int listHeight() {
        int total = 0;
        for (final Row row : rows) {
            total += row.height() + ROW_GAP;
        }
        return Math.max(0, total - ROW_GAP);
    }

    private float maxScroll() {
        return Math.max(0, listHeight() - contentHeight);
    }

    private void drawRows(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float delta) {
        scroll = Math.max(0.0F, Math.min(scroll, maxScroll()));
        scrollShown = Anim.approach(scrollShown, scroll, 20.0F, delta);
        final boolean mouseInList = sliding == null && !draggingScrollbar && Draw.inside(mouseX, mouseY, listX, contentY, rowWidth, contentHeight);
        Row nowHovered = null;

        graphics.enableScissor(listX - 12, contentY, listX + listWidth, contentY + contentHeight);
        int y = contentY - Math.round(scrollShown);
        int visibleIndex = 0;
        for (final Row row : rows) {
            final int rowHeight = row.height();
            if (y + rowHeight > contentY && y < contentY + contentHeight) {
                final boolean over = mouseInList && row.interactive() && mouseY >= y && mouseY < y + rowHeight;
                if (over) {
                    nowHovered = row;
                }
                row.hover = Anim.approach(row.hover, (over || row == sliding) && row.enabled() ? 1.0F : 0.0F, 16.0F, delta);
                // The rows of a page arrive one after another, sliding in from the left.
                final float arrive = Anim.outCubic(Anim.staggered(tabChangedAt, 220L, Math.min(visibleIndex, 12), 22L));
                row.y = y;
                row.draw(graphics, listX - Math.round((1.0F - arrive) * 10.0F), y, rowWidth, mouseX, mouseY, arrive, delta);
                visibleIndex++;
            } else {
                row.hover = 0.0F;
                row.y = Integer.MIN_VALUE;
            }
            y += rowHeight + ROW_GAP;
        }
        graphics.disableScissor();
        Draw.scrollbar(graphics, scrollbarX(), contentY, contentHeight, scrollShown, maxScroll(),
            draggingScrollbar || Draw.inside(mouseX, mouseY, scrollbarX() - 3, contentY, Draw.SCROLLBAR_WIDTH + 6, contentHeight));

        if (nowHovered != hovered) {
            hovered = nowHovered;
            hoveredSince = Anim.now();
        }
    }

    private int scrollbarX() {
        return listX + listWidth - Draw.SCROLLBAR_WIDTH;
    }

    private void scrollbarTo(final double mouseY) {
        scroll = Draw.scrollbarTarget(contentY, contentHeight, maxScroll(), mouseY);
        scrollShown = scroll;
    }

    /** Explains the option under the mouse - below its row rather than at the cursor, clear of the preview. */
    private void drawTooltip(final GuiGraphicsExtractor graphics, final int offset) {
        if (hovered == null || fullPreview || capturing != null || hovered.y == Integer.MIN_VALUE
            || Anim.now() - hoveredSince < TOOLTIP_DELAY_MILLIS) {
            return;
        }
        final List<Component> text = hovered.tooltip();
        if (text.isEmpty()) {
            return;
        }
        final List<FormattedCharSequence> lines = new ArrayList<>();
        for (final Component paragraph : text) {
            lines.addAll(font.split(paragraph, Math.min(210, width - 40)));
        }
        final int tooltipHeight = lines.size() * 10 + 8;
        final int below = hovered.y + hovered.height() + offset + 4;
        // The game places a tooltip 12 right of and 12 above the point it is given.
        final int top = below + tooltipHeight <= height - 4 ? below : hovered.y + offset - tooltipHeight - 2;
        graphics.setTooltipForNextFrame(font, lines, listX - 6, top + 12, Sprites.TOOLTIP_STYLE);
    }

    /**
     * What can be done in the full-size preview, written where it is not in the way of what is on show.
     *
     * @return the height the hint is written at
     */
    private int drawFullHint(final GuiGraphicsExtractor graphics) {
        if (tab.preview == SettingsPreview.Mode.WHEEL) {
            // Beside the wheel, in the bottom left corner the ring curves away from.
            final float extent = WheelRenderer.EXTENT * Math.min(config().wheelScale, (Math.min(width, height) - 8) / WheelRenderer.EXTENT);
            final int cardWidth = Math.min(150, Math.round((width - extent) / 2.0F) + 14);
            if (cardWidth < 76) {
                return height;
            }
            final List<FormattedCharSequence> lines = font.split(Component.translatable(KEY + "preview.hint.wheel"), cardWidth - 12);
            final int cardHeight = lines.size() * 10 + 7;
            final int y = height - 6 - cardHeight;
            Sprites.draw(graphics, Sprites.CARD, 6, y, cardWidth, cardHeight, Palette.fade(Palette.WHITE, fullShown * 0.92F));
            if (fullShown > 0.1F) {
                for (int line = 0; line < lines.size(); line++) {
                    graphics.text(font, lines.get(line), 12, y + 4 + line * 10, Palette.fade(Palette.TEXT_DIM, fullShown));
                }
            }
            return y;
        }
        final Component hint = Component.translatable(KEY + "preview.hint.display");
        final int plateWidth = Math.min(width - 12, font.width(hint) + 16);
        final int plateX = (width - plateWidth) / 2;
        // Along the top, next to the way back if it fits there and below it if not - unless the display
        // itself sits at the top, then lower down.
        final boolean beside = plateX > backButton.getRight() + 6 && plateX + plateWidth < width - backButton.getWidth() - 12;
        final ModConfig.HudAnchor anchor = config().hudAnchor;
        final int y = anchor == ModConfig.HudAnchor.TOP || !beside && (anchor == ModConfig.HudAnchor.TOP_LEFT || anchor == ModConfig.HudAnchor.TOP_RIGHT)
            ? Math.round(height * 0.56F)
            : beside ? 7 : backButton.getBottom() + 4;
        Sprites.draw(graphics, Sprites.CARD, plateX, y, plateWidth, 16, Palette.fade(Palette.WHITE, fullShown * 0.92F));
        Draw.centred(graphics, font, Draw.clip(font, hint, plateWidth - 10), width / 2, y + 4, Palette.fade(Palette.TEXT_DIM, fullShown));
        return y;
    }

    /** The pets leaning on the panel (where there is room beside it) and a twinkle on its corners. */
    private void drawDecorations(final GuiGraphicsExtractor graphics, final boolean behindPanel) {
        if (!config().decorations) {
            return;
        }
        if (behindPanel) {
            if (panelX >= 64) {
                final float sway = (Anim.wave(3200L) - 0.5F) * 3.0F;
                Sprites.draw(graphics, Sprites.CHARACTER_FOX, panelX - 58, Math.round(panelY + panelHeight - 70 + sway), 70, 63);
                Sprites.draw(graphics, Sprites.CHARACTER_OTTER, panelX + panelWidth - 12, Math.round(panelY + panelHeight - 66 - sway), 70, 59);
            }
            return;
        }
        Draw.sparkles(graphics, panelX, panelY, panelWidth, panelHeight);
    }

    // ------------------------------------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------------------------------------

    /** One line of the options list. */
    private abstract class Row {
        /** The language key the row was made from, or {@code null} for headings and notes. */
        final String id;
        final Component label;
        final Component description;
        private BooleanSupplier condition = () -> true;
        /** 0..1, eased: how much the mouse is on this row. */
        float hover;
        /** Where it was last drawn, or {@link Integer#MIN_VALUE} while scrolled out of view. */
        int y = Integer.MIN_VALUE;

        Row(final String key) {
            this.id = key;
            this.label = Component.translatable(KEY + key);
            this.description = Component.translatable(KEY + key + ".info");
        }

        Row(final Component label) {
            this.id = null;
            this.label = label;
            this.description = null;
        }

        /** Greys the row out and makes it ignore clicks while the condition does not hold. */
        Row when(final BooleanSupplier holds) {
            condition = holds;
            return this;
        }

        boolean enabled() {
            return condition.getAsBoolean();
        }

        int height() {
            return ROW_HEIGHT;
        }

        /** Whether the mouse can do anything with this row at all. */
        boolean interactive() {
            return true;
        }

        abstract void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int mouseX, int mouseY, float alpha, float delta);

        void click(final int x, final int width, final double mouseX, final int button) {
        }

        List<Component> tooltip() {
            return description == null ? List.of() : List.of(description);
        }

        void drawCard(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final float alpha) {
            Sprites.draw(graphics, Sprites.CARD, x, y, width, height(), Palette.fade(Palette.WHITE, alpha * (enabled() ? 0.95F : 0.55F)));
            Sprites.draw(graphics, Sprites.CARD_HOVER, x, y, width, height(), Palette.fade(Palette.WHITE, alpha * hover));
        }

        void drawLabel(final GuiGraphicsExtractor graphics, final int x, final int y, final int maxWidth, final float alpha) {
            Draw.text(graphics, font, Draw.clip(font, label, maxWidth), x + 7, y + (height() - 8) / 2,
                Palette.fade(enabled() ? Palette.mix(Palette.TEXT_DIM, Palette.TEXT, 0.55F + 0.45F * hover) : Palette.TEXT_FAINT, alpha));
        }

        float strength(final float alpha) {
            return alpha * (enabled() ? 1.0F : 0.4F);
        }
    }

    /** The title of a group of options: copper letters and an engraved line. */
    private final class HeaderRow extends Row {
        HeaderRow(final String key) {
            super(Component.translatable(KEY + key));
        }

        @Override
        int height() {
            return 13;
        }

        @Override
        boolean interactive() {
            return false;
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            Draw.text(graphics, font, label, x + 2, y + 4, Palette.fade(Palette.COPPER, alpha));
            final int lineX = x + 2 + font.width(label) + 6;
            final int lineEnd = x + width - 10;
            if (lineEnd > lineX) {
                graphics.fill(lineX, y + 7, lineEnd, y + 8, Palette.rgb(0x211615, alpha));
                graphics.fill(lineX, y + 8, lineEnd, y + 9, Palette.rgb(0x71533F, alpha * 0.8F));
            }
            Sprites.draw(graphics, Sprites.GEM_GREEN, x + width - 8, y + 5, 6, 6, Palette.fade(Palette.WHITE, alpha));
        }
    }

    /** A paragraph of explanation between the options. */
    private final class NoteRow extends Row {
        private final List<FormattedCharSequence> lines;

        NoteRow(final Component text) {
            super(text);
            lines = font.split(text, Math.max(40, rowWidth - 12));
        }

        @Override
        int height() {
            return lines.size() * 10 + 4;
        }

        @Override
        boolean interactive() {
            return false;
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            if (alpha > 0.1F) {
                for (int line = 0; line < lines.size(); line++) {
                    graphics.text(font, lines.get(line), x + 6, y + 2 + line * 10, Palette.fade(Palette.TEXT_DIM, alpha));
                }
            }
        }
    }

    /** On or off: a switch whose knob slides across. */
    private final class ToggleRow extends Row {
        private final BooleanSupplier getter;
        private final Consumer<Boolean> setter;
        private float knob = -1.0F;

        ToggleRow(final String key, final BooleanSupplier getter, final Consumer<Boolean> setter) {
            super(key);
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            final float target = getter.getAsBoolean() ? 1.0F : 0.0F;
            knob = knob < 0.0F ? target : Anim.approach(knob, target, 18.0F, delta);
            drawCard(graphics, x, y, width, alpha);
            drawLabel(graphics, x, y, width - 7 - 30 - 12, alpha);
            final int switchX = x + width - 6 - 30;
            final int switchY = y + (height() - 14) / 2;
            final float strength = strength(alpha);
            Sprites.draw(graphics, Sprites.TOGGLE_OFF, switchX, switchY, 30, 14, Palette.fade(Palette.WHITE, strength));
            Sprites.draw(graphics, Sprites.TOGGLE_ON, switchX, switchY, 30, 14, Palette.fade(Palette.WHITE, strength * knob));
            graphics.pose().pushMatrix();
            graphics.pose().translate(switchX + 1 + knob * 16.0F, switchY + 1);
            Sprites.draw(graphics, Sprites.TOGGLE_KNOB, 0, 0, 12, 12, Palette.fade(Palette.WHITE, strength));
            graphics.pose().popMatrix();
        }

        @Override
        void click(final int x, final int width, final double mouseX, final int button) {
            setter.accept(!getter.getAsBoolean());
            ModConfig.changed();
            UiSounds.tick();
        }
    }

    /** One of several: click to go on to the next, right-click (or the left arrow) to go back. */
    private final class ChoiceRow<E extends Enum<E>> extends Row {
        private final E[] options;
        private final Supplier<E> getter;
        private final Consumer<E> setter;
        private final Function<E, Component> name;
        private long changedAt;
        private int direction = 1;

        ChoiceRow(final String key, final E[] options, final Supplier<E> getter, final Consumer<E> setter, final Function<E, Component> name) {
            super(key);
            this.options = options;
            this.getter = getter;
            this.setter = setter;
            this.name = name;
        }

        private int boxWidth(final int width) {
            return Math.min(112, width * 11 / 20);
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            drawCard(graphics, x, y, width, alpha);
            final int boxWidth = boxWidth(width);
            final int boxX = x + width - 5 - boxWidth;
            drawLabel(graphics, x, y, boxX - x - 12, alpha);
            final float strength = strength(alpha);
            Sprites.draw(graphics, Sprites.BUTTON, boxX, y + 2, boxWidth, height() - 4, Palette.fade(Palette.WHITE, strength));
            Sprites.draw(graphics, Sprites.BUTTON_HOVER, boxX, y + 2, boxWidth, height() - 4, Palette.fade(Palette.WHITE, strength * hover));
            final float centreY = y + height() / 2.0F;
            final boolean onBox = hover > 0.5F && mouseX >= boxX;
            final boolean onBack = onBox && mouseX < boxX + boxWidth / 3.0F;
            Sprites.drawCentred(graphics, Sprites.WHEEL_POINTER, boxX + 8, centreY, 15, 12, 0.5F, (float) (-Math.PI / 2.0),
                Palette.fade(Palette.WHITE, strength * (onBack ? 1.0F : 0.55F)));
            Sprites.drawCentred(graphics, Sprites.WHEEL_POINTER, boxX + boxWidth - 8, centreY, 15, 12, 0.5F, (float) (Math.PI / 2.0),
                Palette.fade(Palette.WHITE, strength * (onBox && !onBack ? 1.0F : 0.55F)));
            // The new choice slides in from the side it was stepped from.
            final float settle = Anim.outCubic(Anim.progress(changedAt, 180L));
            Draw.centred(graphics, font, Draw.clip(font, name.apply(getter.get()), boxWidth - 26),
                boxX + boxWidth / 2 + Math.round((1.0F - settle) * 9.0F * direction), y + (height() - 8) / 2,
                Palette.fade(enabled() ? Palette.TEXT : Palette.TEXT_FAINT, alpha * settle));
        }

        @Override
        void click(final int x, final int width, final double mouseX, final int button) {
            final int boxX = x + width - 5 - boxWidth(width);
            direction = button == 1 || mouseX >= boxX && mouseX < boxX + boxWidth(width) / 3.0 ? -1 : 1;
            setter.accept(options[Math.floorMod(getter.get().ordinal() + direction, options.length)]);
            changedAt = Anim.now();
            ModConfig.changed();
            UiSounds.tick();
        }
    }

    /** A number between two ends: drag the knob, or right-click to go back to the default. */
    private final class SliderRow extends Row {
        private final float min;
        private final float max;
        private final float step;
        private final float fallback;
        private final Supplier<Float> getter;
        private final Consumer<Float> setter;
        private final Function<Float, Component> format;
        private float shown = Float.NaN;

        SliderRow(final String key, final float min, final float max, final float step, final float fallback,
                  final Supplier<? extends Number> getter, final Consumer<Float> setter, final Function<Float, Component> format) {
            super(key);
            this.min = min;
            this.max = max;
            this.step = step;
            this.fallback = fallback;
            this.getter = () -> getter.get().floatValue();
            this.setter = setter;
            this.format = format;
        }

        private int trackWidth(final int width) {
            return Math.min(96, width * 2 / 5);
        }

        private int trackX(final int x, final int width) {
            return x + width - 9 - trackWidth(width);
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            final float value = getter.get();
            final float target = Anim.clamp01((value - min) / (max - min));
            shown = Float.isNaN(shown) || sliding == this ? target : Anim.approach(shown, target, 20.0F, delta);
            drawCard(graphics, x, y, width, alpha);
            final int trackWidth = trackWidth(width);
            final int trackX = trackX(x, width);
            final Component number = format.apply(value);
            final float strength = strength(alpha);
            Draw.rightAligned(graphics, font, number, trackX - 7, y + (height() - 8) / 2, Palette.fade(enabled() ? Palette.TEAL : Palette.TEXT_FAINT, alpha));
            drawLabel(graphics, x, y, trackX - 7 - font.width(number) - 6 - (x + 7), alpha);

            final int trackY = y + (height() - 6) / 2;
            Sprites.draw(graphics, Sprites.SLIDER_TRACK, trackX, trackY, trackWidth, 6, Palette.fade(Palette.WHITE, strength));
            final int fill = Math.round(shown * (trackWidth - 8)) + 4;
            if (fill >= 5) {
                Sprites.draw(graphics, Sprites.SLIDER_FILL, trackX, trackY, fill, 6, Palette.fade(Palette.WHITE, strength));
            }
            Sprites.drawCentred(graphics, Sprites.SLIDER_KNOB, trackX + 4 + shown * (trackWidth - 8), y + height() / 2.0F, 8, 14,
                1.0F + 0.15F * hover, 0.0F, Palette.fade(Palette.WHITE, strength));
        }

        @Override
        void click(final int x, final int width, final double mouseX, final int button) {
            if (button == 1) {
                setter.accept(fallback);
                ModConfig.changed();
                UiSounds.tick();
            } else if (mouseX >= trackX(x, width) - 6) {
                sliding = this;
                drag(x, width, mouseX);
            }
        }

        void drag(final int x, final int width, final double mouseX) {
            final float share = Anim.clamp01((float) ((mouseX - trackX(x, width) - 4) / (trackWidth(width) - 8)));
            final float value = min + Math.round(share * (max - min) / step) * step;
            setter.accept(Math.max(min, Math.min(max, value)));
            // Applied at once so the preview follows the knob; written to disk when it is let go of.
            ModConfig.adjusting();
        }
    }

    /** A key binding: click it and press the new key. */
    private final class KeyRow extends Row {
        private final KeyMapping mapping;

        KeyRow(final String key, final KeyMapping mapping) {
            super(key);
            this.mapping = mapping;
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            drawCard(graphics, x, y, width, alpha);
            final int capWidth = drawKey(graphics, mapping, x + width - 6, y + (height() - Draw.KEY_CAP_HEIGHT) / 2, 38, width / 2, alpha, hover);
            drawLabel(graphics, x, y, width - 7 - capWidth - 12, alpha);
        }

        @Override
        void click(final int x, final int width, final double mouseX, final int button) {
            clickKey(mapping, button);
        }

        @Override
        List<Component> tooltip() {
            return keyTooltip(description, mapping);
        }
    }

    /** Three of the keys that summon one slot directly, side by side: nine rows of them would be a long list. */
    private final class SlotKeysRow extends Row {
        private final int firstSlot;
        private int hoveredCell = -1;

        SlotKeysRow(final int firstSlot) {
            super(Component.translatable(KEY + "keys.slot", firstSlot + 1));
            this.firstSlot = firstSlot;
        }

        private int cells() {
            return Math.min(3, Keybinds.SLOTS.length - firstSlot);
        }

        private int cellAt(final int x, final int width, final double mouseX) {
            final int cell = (int) ((mouseX - x) * 3 / Math.max(1, width));
            return cell >= 0 && cell < cells() ? cell : -1;
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            hoveredCell = hover > 0.05F ? cellAt(x, width, mouseX) : -1;
            final int cellWidth = (width - 4) / 3;
            for (int cell = 0; cell < cells(); cell++) {
                final int cellX = x + cell * (cellWidth + 2);
                final float cellHover = cell == hoveredCell ? hover : 0.0F;
                Sprites.draw(graphics, Sprites.CARD, cellX, y, cellWidth, height(), Palette.fade(Palette.WHITE, alpha * 0.95F));
                Sprites.draw(graphics, Sprites.CARD_HOVER, cellX, y, cellWidth, height(), Palette.fade(Palette.WHITE, alpha * cellHover));
                Draw.text(graphics, font, Component.literal(Integer.toString(firstSlot + cell + 1)), cellX + 6, y + (height() - 8) / 2,
                    Palette.fade(Palette.GOLD, alpha));
                drawKey(graphics, Keybinds.SLOTS[firstSlot + cell], cellX + cellWidth - 4, y + (height() - Draw.KEY_CAP_HEIGHT) / 2, 30, cellWidth - 20,
                    alpha, cellHover);
            }
        }

        @Override
        void click(final int x, final int width, final double mouseX, final int button) {
            final int cell = cellAt(x, width, mouseX);
            if (cell >= 0) {
                clickKey(Keybinds.SLOTS[firstSlot + cell], button);
            }
        }

        @Override
        List<Component> tooltip() {
            if (hoveredCell < 0) {
                return List.of();
            }
            return keyTooltip(Component.translatable(KEY + "keys.slot.info", firstSlot + hoveredCell + 1), Keybinds.SLOTS[firstSlot + hoveredCell]);
        }
    }

    /**
     * Draws the key a binding is on as a key cap ending at {@code right}: pulsing while it waits for the
     * new key, red while another binding sits on the same key.
     *
     * @return the width of the cap
     */
    private int drawKey(final GuiGraphicsExtractor graphics, final KeyMapping mapping, final int right, final int y, final int minWidth, final int maxWidth,
                        final float alpha, final float highlight) {
        final boolean waiting = capturing == mapping;
        final boolean clash = !waiting && conflict(mapping) != null;
        Component text = waiting ? Component.literal("> ? <") : Keybinds.label(mapping);
        final int capWidth = Math.max(minWidth, Math.min(maxWidth, font.width(text) + 12));
        text = Draw.clip(font, text, capWidth - 6);
        final int capX = right - capWidth;
        final int colour = waiting ? Palette.GOLD : clash ? Palette.RED : mapping.isUnbound() ? Palette.TEXT_FAINT : Palette.TEXT;
        Draw.keyCap(graphics, font, text, capX, y, capWidth, colour, alpha * (mapping.isUnbound() && !waiting ? 0.7F : 1.0F));
        if (waiting) {
            Sprites.draw(graphics, Sprites.FRAME_GOLD, capX - 2, y - 2, capWidth + 4, Draw.KEY_CAP_HEIGHT + 4,
                Palette.fade(Palette.WHITE, alpha * (0.55F + 0.45F * Anim.wave(700L))));
        } else if (highlight > 0.02F) {
            Sprites.draw(graphics, Sprites.FRAME_GOLD, capX - 2, y - 2, capWidth + 4, Draw.KEY_CAP_HEIGHT + 4,
                Palette.fade(Palette.WHITE, alpha * highlight * 0.45F));
        }
        return capWidth;
    }

    private void clickKey(final KeyMapping mapping, final int button) {
        if (button == 1) {
            bind(mapping, InputConstants.UNKNOWN);
        } else {
            capturing = mapping;
            UiSounds.click();
        }
    }

    private List<Component> keyTooltip(final Component description, final KeyMapping mapping) {
        final List<Component> lines = new ArrayList<>();
        lines.add(description);
        final Component clash = conflict(mapping);
        if (clash != null) {
            lines.add(Component.translatable(KEY + "key.conflict", clash).withColor(Palette.RED & 0xFFFFFF));
        }
        lines.add(Component.translatable(KEY + "key.hint").withColor(Palette.TEXT_FAINT & 0xFFFFFF));
        return lines;
    }

    /** One button of an {@link ActionRow}. */
    private record Action(String key, Runnable run) {
    }

    /** A row of buttons - the ready-made key layouts. */
    private final class ActionRow extends Row {
        private final Action[] actions;
        private final float[] glow;
        private int hoveredAction = -1;

        ActionRow(final Action... actions) {
            super(Component.empty());
            this.actions = actions;
            this.glow = new float[actions.length];
        }

        private int actionAt(final int x, final int width, final double mouseX) {
            final int index = (int) ((mouseX - x) * actions.length / Math.max(1, width));
            return index >= 0 && index < actions.length ? index : -1;
        }

        @Override
        void draw(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int mouseX, final int mouseY, final float alpha, final float delta) {
            hoveredAction = hover > 0.05F ? actionAt(x, width, mouseX) : -1;
            final int buttonWidth = (width - (actions.length - 1) * 2) / actions.length;
            for (int i = 0; i < actions.length; i++) {
                glow[i] = Anim.approach(glow[i], i == hoveredAction ? 1.0F : 0.0F, 16.0F, delta);
                final int buttonX = x + i * (buttonWidth + 2);
                Sprites.draw(graphics, Sprites.BUTTON, buttonX, y, buttonWidth, height(), Palette.fade(Palette.WHITE, alpha));
                Sprites.draw(graphics, Sprites.BUTTON_HOVER, buttonX, y, buttonWidth, height(), Palette.fade(Palette.WHITE, alpha * glow[i]));
                Draw.fitted(graphics, font, Component.translatable(KEY + actions[i].key()), buttonX + buttonWidth / 2.0F, y + (height() - 8) / 2.0F,
                    buttonWidth - 6, Palette.fade(Palette.mix(Palette.TEXT_DIM, Palette.TEXT, glow[i]), alpha));
            }
        }

        @Override
        void click(final int x, final int width, final double mouseX, final int button) {
            final int index = actionAt(x, width, mouseX);
            if (index >= 0 && button == 0) {
                actions[index].run().run();
                UiSounds.click();
            }
        }

        @Override
        List<Component> tooltip() {
            return hoveredAction < 0 ? List.of() : List.of(Component.translatable(KEY + actions[hoveredAction].key() + ".info"));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------------

    /** The row at a point of the list, or {@code null}. */
    private Row rowAt(final double mouseX, final double mouseY) {
        if (!Draw.inside(mouseX, mouseY, listX, contentY, rowWidth, contentHeight)) {
            return null;
        }
        for (final Row row : rows) {
            if (row.y != Integer.MIN_VALUE && row.interactive() && mouseY >= row.y && mouseY < row.y + row.height()) {
                return row;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (capturing != null) {
            // While a binding waits for its key, a mouse button is a perfectly good answer.
            bind(capturing, InputConstants.Type.MOUSE.getOrCreate(event.button()));
            return true;
        }
        if (fullPreview) {
            // The panel's buttons are still on their way out, but no longer where they are drawn.
            if (backButton.mouseClicked(event, doubleClick)) {
                return true;
            }
            if (tab.preview == SettingsPreview.Mode.WHEEL) {
                preview.clickFullWheel(width, height, event.x(), event.y());
                UiSounds.tick();
            } else if (event.button() == 0) {
                draggingDisplay = true;
                dragStartX = event.x();
                dragStartY = event.y();
                dragStartOffsetX = config().hudOffsetX;
                dragStartOffsetY = config().hudOffsetY;
            }
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        final Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (Draw.inside(event.x(), event.y(), tabX(i), tabsY, tabWidth, TAB_HEIGHT)) {
                if (tabs[i] != tab) {
                    showTab(tabs[i]);
                    UiSounds.click();
                }
                return true;
            }
        }
        if (maxScroll() > 0.0F && Draw.inside(event.x(), event.y(), scrollbarX() - 3, contentY, Draw.SCROLLBAR_WIDTH + 6, contentHeight)) {
            draggingScrollbar = true;
            scrollbarTo(event.y());
            return true;
        }
        final Row row = event.button() <= 1 ? rowAt(event.x(), event.y()) : null;
        if (row != null) {
            if (row.enabled()) {
                row.click(listX, rowWidth, event.x(), event.button());
            }
            // Once an option has been used its explanation gets out of the way.
            hoveredSince = Long.MAX_VALUE / 2;
            return true;
        }
        // Extra mouse buttons can be bound too; the tester shows what they do.
        return event.button() >= 2 && tab == Tab.KEYS && tryKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double deltaX, final double deltaY) {
        if (sliding != null) {
            sliding.drag(listX, rowWidth, event.x());
            return true;
        }
        if (draggingScrollbar) {
            scrollbarTo(event.y());
            return true;
        }
        if (draggingDisplay) {
            config().hudOffsetX = dragStartOffsetX + (int) Math.round(event.x() - dragStartX);
            config().hudOffsetY = dragStartOffsetY + (int) Math.round(event.y() - dragStartY);
            ModConfig.adjusting();
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (sliding != null) {
            sliding = null;
            ModConfig.changed();
            UiSounds.tick();
        }
        if (draggingDisplay) {
            draggingDisplay = false;
            ModConfig.changed();
        }
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (fullPreview) {
            // In the full-size preview the mouse wheel sizes what is on show.
            final float change = (float) Math.signum(scrollY) * 0.05F;
            if (tab.preview == SettingsPreview.Mode.WHEEL) {
                config().wheelScale += change;
            } else {
                config().hudScale += change;
            }
            ModConfig.changed();
            return true;
        }
        if (tab == Tab.KEYS && config().modifierScroll && !Keybinds.modifier.isUnbound() && Keybinds.isHeld(Keybinds.modifier) && scrollY != 0.0) {
            preview.testCycle(scrollY > 0.0 ? -1 : 1,
                Component.empty().append(Keybinds.shortLabel(Keybinds.modifier)).append("+").append(Component.translatable(KEY + "preview.mouse_wheel")));
            return true;
        }
        if (Draw.inside(mouseX, mouseY, listX, contentY, listWidth, contentHeight)) {
            scroll = Math.max(0.0F, Math.min(maxScroll(), scroll - (float) scrollY * (ROW_HEIGHT + ROW_GAP) * 1.5F));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (capturing != null) {
            // Escape clears the binding, exactly as in the game's own key binds screen.
            bind(capturing, event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            return true;
        }
        if (fullPreview) {
            if (event.isEscape()) {
                setFullPreview(false);
                return true;
            }
            return super.keyPressed(event);
        }
        if (closeKeyArmed && Keybinds.openSettings.matches(event)) {
            onClose();
            return true;
        }
        if (tab == Tab.KEYS && !event.isEscape() && tryKey(InputConstants.getKey(event))) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return capturing == null && !fullPreview;
    }

    /**
     * The key tester: if the key does something in the mod, the preview shows what - nothing is sent to
     * the server.
     *
     * @return true if the key belongs to the mod
     */
    private boolean tryKey(final InputConstants.Key key) {
        if (config().modifierNumbers && !Keybinds.modifier.isUnbound() && Keybinds.isHeld(Keybinds.modifier)) {
            final KeyMapping[] hotbar = minecraft.options.keyHotbarSlots;
            for (int slot = 0; slot < hotbar.length && slot < Keybinds.SLOTS.length; slot++) {
                if (hotbar[slot].matches(key)) {
                    preview.testSlot(slot, Keybinds.combo(Keybinds.modifier, hotbar[slot]));
                    return true;
                }
            }
        }
        for (int slot = 0; slot < Keybinds.SLOTS.length; slot++) {
            if (Keybinds.SLOTS[slot].matches(key)) {
                preview.testSlot(slot, Keybinds.label(Keybinds.SLOTS[slot]));
                return true;
            }
        }
        if (Keybinds.next.matches(key)) {
            preview.testCycle(1, Keybinds.label(Keybinds.next));
        } else if (Keybinds.previous.matches(key)) {
            preview.testCycle(-1, Keybinds.label(Keybinds.previous));
        } else if (Keybinds.putAway.matches(key)) {
            preview.testPutAway(Keybinds.label(Keybinds.putAway));
        } else if (Keybinds.wheel.matches(key)) {
            preview.testWheel(Keybinds.label(Keybinds.wheel));
        } else if (Keybinds.openScreen.matches(key)) {
            preview.testOther(Keybinds.label(Keybinds.openScreen), Component.translatable(KEY + "keys.open_screen"));
        } else if (Keybinds.openMenu.matches(key)) {
            preview.testOther(Keybinds.label(Keybinds.openMenu), Component.translatable(KEY + "keys.open_menu"));
        } else {
            // Holding the modifier is shown by the preview on its own; the key is still "ours".
            return Keybinds.modifier.matches(key);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // For the development preview
    // ------------------------------------------------------------------------------------------------

    SettingsPreview preview() {
        return preview;
    }

    Tab tab() {
        return tab;
    }

    boolean showsFullPreview() {
        return fullPreview;
    }

    /** A point on a tab. */
    int[] pointOnTab(final Tab which) {
        layout();
        return new int[] {tabX(which.ordinal()) + tabWidth / 2, tabsY + TAB_HEIGHT / 2};
    }

    /** A point on one of the panel's buttons, by its label's language key (such as "reset"). */
    int[] pointOnButton(final String key) {
        final String label = Component.translatable(KEY + key).getString();
        final String confirm = Component.translatable(KEY + key + ".confirm").getString();
        for (final TextButton button : panelButtons) {
            final String shown = button.getMessage().getString();
            if (shown.equals(label) || shown.equals(confirm)) {
                return new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
            }
        }
        return null;
    }

    /**
     * A point in the n-th row of the page, {@code share} of the way across it (0 = left edge, 1 = right) -
     * for rows that are several things side by side. Scrolls the row into view like {@link #pointOnRow}.
     */
    int[] pointInRow(final int index, final float share) {
        if (index < 0 || index >= rows.size()) {
            return null;
        }
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset += rows.get(i).height() + ROW_GAP;
        }
        final Row row = rows.get(index);
        if (offset < scrollShown) {
            scroll = offset;
        } else if (offset + row.height() > scrollShown + contentHeight) {
            scroll = offset + row.height() - contentHeight;
        }
        scroll = Math.max(0.0F, Math.min(scroll, maxScroll()));
        scrollShown = scroll;
        int y = contentY - Math.round(scrollShown);
        for (final Row placed : rows) {
            placed.y = y + placed.height() > contentY && y < contentY + contentHeight ? y : Integer.MIN_VALUE;
            y += placed.height() + ROW_GAP;
        }
        return new int[] {listX + Math.round(share * rowWidth), row.y + row.height() / 2};
    }

    /** The place in the list of the option made from a language key, or -1. */
    int rowIndex(final String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(rows.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    /** Scrolls the list of the current page as far down as it goes, without the glide. */
    void scrollToEnd() {
        scroll = maxScroll();
        scrollShown = scroll;
    }

    /**
     * A point on the control of the option made from a language key (such as "display.always"), scrolling
     * the list first if that option is out of view - or {@code null} if this page has no such option.
     */
    int[] pointOnRow(final String id) {
        int offset = 0;
        for (final Row row : rows) {
            if (id.equals(row.id)) {
                if (offset < scrollShown) {
                    scroll = offset;
                } else if (offset + row.height() > scrollShown + contentHeight) {
                    scroll = offset + row.height() - contentHeight;
                }
                scroll = Math.max(0.0F, Math.min(scroll, maxScroll()));
                scrollShown = scroll;
                // Every row has to know its new place at once: a click may follow before the next frame.
                int y = contentY - Math.round(scrollShown);
                for (final Row placed : rows) {
                    placed.y = y + placed.height() > contentY && y < contentY + contentHeight ? y : Integer.MIN_VALUE;
                    y += placed.height() + ROW_GAP;
                }
                return new int[] {listX + rowWidth - 20, row.y + row.height() / 2};
            }
            offset += row.height() + ROW_GAP;
        }
        return null;
    }
}
