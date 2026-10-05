package de.yourshika.betterpets.quickslots;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import de.yourshika.betterpets.quickslots.ui.Draw;
import de.yourshika.betterpets.quickslots.ui.Sprites;
import de.yourshika.betterpets.quickslots.ui.TextButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * Puts a "Quickslots" button onto the plugin's main chest menu ({@code /pets}).
 *
 * <p>The chest menu is an ordinary container to the client, indistinguishable from any other chest, and
 * the plugin has no free slot left in it for one more item button. So the plugin sends a short note right
 * before opening that menu (see {@link QuickslotClient#isPetsMenu}) and the button is added here as a
 * regular client-side widget next to the container.</p>
 */
final class MenuButton {

    private static final int PANEL_WIDTH = 176;
    private static final int WIDTH = 84;
    private static final int HEIGHT = 20;

    private MenuButton() {
    }

    static void register() {
        ScreenEvents.AFTER_INIT.register(MenuButton::afterInit);
    }

    private static void afterInit(final Minecraft client, final Screen screen, final int scaledWidth, final int scaledHeight) {
        if (!(screen instanceof ContainerScreen container) || !QuickslotClient.isPetsMenu(container.getMenu().containerId)) {
            return;
        }
        // The same arithmetic the chest screen uses to centre its panel.
        final int panelHeight = 114 + container.getMenu().getRowCount() * 18;
        final int left = (scaledWidth - PANEL_WIDTH) / 2;
        final int top = (scaledHeight - panelHeight) / 2;

        final int x;
        final int y;
        if (top >= HEIGHT + 4) {
            // Normal case: just above the panel, flush with its right edge.
            x = left + PANEL_WIDTH - WIDTH;
            y = top - HEIGHT - 2;
        } else if (left >= WIDTH + 6) {
            // Very large GUI scale: no room above, so beside the panel.
            x = left - WIDTH - 4;
            y = top + 4;
        } else {
            x = left + PANEL_WIDTH - WIDTH - 6;
            y = Math.max(0, top - 2);
        }

        final TextButton button = new TextButton(x, y, WIDTH, HEIGHT, Component.translatable(BetterPetsQuickslots.MOD_ID + ".menu_button"),
            Sprites.ICON_FOX, () -> QuickslotClient.openScreen(client));
        button.setTooltip(Draw.tooltip(Component.translatable(BetterPetsQuickslots.MOD_ID + ".menu_button.tooltip", Keybinds.label(Keybinds.openScreen))));
        Screens.getWidgets(screen).add(button);
    }
}
