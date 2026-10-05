package de.yourshika.betterpets.quickslots;

import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Development aid: exercises the mod without a Better Pets server, screenshots the result and quits.
 *
 * <p>Inactive unless the game is started with {@code -Dbetterpets.quickslots.preview=<pets.tsv>} (see
 * {@code run-preview.sh}). The file holds one pet per line, tab-separated:
 * {@code id, name, type, rarity, colour (hex), level, stars, disabled, texture, ability}.</p>
 *
 * <p>It has to happen inside a world - items (the pet heads) cannot even be created on the title screen -
 * so a throw-away creative test world is generated first. On its integrated server {@link PreviewServer}
 * stands in for the plugin, which makes this a small end-to-end test as well: everything shown arrived
 * over the real channel, a slot assignment and a pet switch are sent and checked, and the button on the
 * plugin's chest menu is checked both ways (there on the announced menu, absent on any other chest).
 * The screenshots land in the game directory's {@code screenshots/}, the checks in
 * {@code preview-report.txt}.</p>
 */
final class DevPreview {

    private static final String PROPERTY = "betterpets.quickslots.preview";
    private static final String WORLD_NAME = "quickslots-preview";
    /** Ticks to wait for the server's answer before the handshake counts as failed. */
    private static final int HANDSHAKE_TIMEOUT_TICKS = 200;
    /** Ticks the screen is open before its screenshot, so the pet skins have been fetched. */
    private static final int SCREEN_SETTLE_TICKS = 100;
    private static final int TOOLTIP_SETTLE_TICKS = 4;
    /** Ticks a request gets to travel to the server and back. */
    private static final int ROUND_TRIP_TICKS = 10;

    private enum Stage {
        WAIT_FOR_TITLE, WAIT_FOR_WORLD, HANDSHAKE, SCREEN_OPEN, PET_TOOLTIP, SLOT_TOOLTIP, ASSIGN_SENT, SWITCH_SENT,
        PLAIN_CHEST_OPEN, PETS_MENU_OPEN, BUTTON_USED, DONE
    }

    private static Stage stage = Stage.WAIT_FOR_TITLE;
    private static int ticks;
    private static final List<String> report = new ArrayList<>();
    private static List<QuickslotProtocol.Pet> pets = List.of();
    // Where the previewed screen believes the mouse is, so tooltips can be captured without anyone
    // touching the mouse (and the real cursor cannot wander into a shot). Starts in an empty corner.
    private static int hoverX = 2;
    private static int hoverY = 2;

    private DevPreview() {
    }

    static void registerIfRequested() {
        final String dataFile = System.getProperty(PROPERTY);
        if (dataFile == null || dataFile.isBlank()) {
            return;
        }
        pets = readPets(Path.of(dataFile));
        PreviewServer.start(pets);
        ClientTickEvents.END_CLIENT_TICK.register(DevPreview::tick);
    }

    private static void tick(final Minecraft client) {
        switch (stage) {
            case WAIT_FOR_TITLE -> {
                // The title screen already exists while resources are still loading behind an overlay.
                if (client.gui.overlay() == null && client.gui.screen() instanceof TitleScreen) {
                    stage = Stage.WAIT_FOR_WORLD;
                    createWorld(client);
                }
            }
            case WAIT_FOR_WORLD -> {
                if (client.player != null && client.level != null && client.gui.overlay() == null && client.gui.screen() == null) {
                    stage = Stage.HANDSHAKE;
                    ticks = 0;
                }
            }
            case HANDSHAKE -> {
                // Nothing is fed in by hand here: the mod has to notice the channel, say hello and
                // receive the state and the pet list on its own, exactly as on a real server.
                if (QuickslotClient.usable() && QuickslotClient.pets().size() == pets.size()) {
                    check("handshake: state and " + pets.size() + " pets arrived over the channel", true);
                    client.gui.setScreen(new QuickslotScreen() {
                        @Override
                        public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
                            super.extractRenderState(graphics, hoverX, hoverY, partialTick);
                        }
                    });
                    stage = Stage.SCREEN_OPEN;
                    ticks = 0;
                } else if (++ticks > HANDSHAKE_TIMEOUT_TICKS) {
                    check("handshake: state and pets arrived over the channel", false);
                    finish(client);
                }
            }
            case SCREEN_OPEN -> {
                if (++ticks == SCREEN_SETTLE_TICKS) {
                    screenshot(client, "quickslots-screen.png", () -> {
                        // Onto the first pet card of the grid (a little left of centre, so the point
                        // lies on a card whether the grid has an odd or an even number of columns).
                        hover(client.gui.screen().width / 2 - 30, 109);
                        stage = Stage.PET_TOOLTIP;
                    });
                }
            }
            case PET_TOOLTIP -> {
                if (++ticks == TOOLTIP_SETTLE_TICKS) {
                    screenshot(client, "quickslots-tooltip-pet.png", () -> {
                        // Onto the second of the five slots.
                        hover(client.gui.screen().width / 2 - 42, 54);
                        stage = Stage.SLOT_TOOLTIP;
                    });
                }
            }
            case SLOT_TOOLTIP -> {
                if (++ticks == TOOLTIP_SETTLE_TICKS) {
                    screenshot(client, "quickslots-tooltip-slot.png", () -> {
                        client.gui.setScreen(null);
                        // Park the fourth pet in the empty fourth slot...
                        QuickslotClient.assign(3, pets.get(3).id());
                        stage = Stage.ASSIGN_SENT;
                        ticks = 0;
                    });
                }
            }
            case ASSIGN_SENT -> {
                if (++ticks == ROUND_TRIP_TICKS) {
                    check("assign: the server parked " + pets.get(3).id() + " in slot 4", PreviewServer.slot(3).equals(pets.get(3).id()));
                    check("assign: the client shows it there", QuickslotClient.slotOf(pets.get(3).id()) == 3);
                    // ...then summon the pet of slot 1 (the one of slot 2 is out).
                    QuickslotClient.switchTo(0);
                    stage = Stage.SWITCH_SENT;
                    ticks = 0;
                }
            }
            case SWITCH_SENT -> {
                if (++ticks == ROUND_TRIP_TICKS) {
                    // Only the server's answer can change the active pet - and that answer is also what
                    // makes the slot bar appear, so the last screenshot shows the bar it triggered.
                    check("switch: the server's answer made " + pets.get(0).id() + " the active pet",
                        QuickslotClient.activeId().equals(pets.get(0).id()));
                    screenshot(client, "quickslots-hud.png", () -> {
                        // An ordinary chest first: it must NOT get the button.
                        openChest(client, false);
                        stage = Stage.PLAIN_CHEST_OPEN;
                        ticks = 0;
                    });
                }
            }
            case PLAIN_CHEST_OPEN -> {
                if (++ticks == ROUND_TRIP_TICKS) {
                    check("menu button: an ordinary chest opened", client.gui.screen() instanceof ContainerScreen);
                    check("menu button: an ordinary chest does not get it", !hasMenuButton(client));
                    client.player.closeContainer();
                    // Now the chest the "plugin" announces as its /pets menu.
                    openChest(client, true);
                    stage = Stage.PETS_MENU_OPEN;
                    ticks = 0;
                }
            }
            case PETS_MENU_OPEN -> {
                if (++ticks == ROUND_TRIP_TICKS) {
                    check("menu button: the announced pet menu gets it", hasMenuButton(client));
                    screenshot(client, "quickslots-menu-button.png", () -> {
                        // What a click on the button does.
                        QuickslotClient.openScreen(client);
                        stage = Stage.BUTTON_USED;
                        ticks = 0;
                    });
                }
            }
            case BUTTON_USED -> {
                if (++ticks == ROUND_TRIP_TICKS) {
                    check("menu button: it opens the slot screen", client.gui.screen() instanceof QuickslotScreen);
                    check("menu button: the server was told the chest is closed", !PreviewServer.hasContainerOpen(serverPlayer(client)));
                    finish(client);
                }
            }
            case DONE -> {
            }
        }
    }

    private static void createWorld(final Minecraft client) {
        final LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.CREATIVE,
            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        client.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, WorldOptions.testWorldWithRandomSeed(),
            WorldPresets::createTestWorldDimensions, client.gui.screen());
    }

    private static ServerPlayer serverPlayer(final Minecraft client) {
        return client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
    }

    private static void openChest(final Minecraft client, final boolean announce) {
        final ServerPlayer player = serverPlayer(client);
        client.getSingleplayerServer().execute(() -> PreviewServer.openChest(player, announce));
    }

    /** Whether the open screen carries the mod's Quickslots button. */
    private static boolean hasMenuButton(final Minecraft client) {
        if (client.gui.screen() == null) {
            return false;
        }
        final String label = Component.translatable(BetterPetsQuickslots.MOD_ID + ".menu_button").getString();
        for (final AbstractWidget widget : Screens.getWidgets(client.gui.screen())) {
            if (widget instanceof Button && widget.getMessage().getString().equals(label)) {
                return true;
            }
        }
        return false;
    }

    private static void hover(final int x, final int y) {
        hoverX = x;
        hoverY = y;
        ticks = 0;
    }

    private static void screenshot(final Minecraft client, final String fileName, final Runnable afterwards) {
        Screenshot.grab(client.gameDirectory, fileName, client.gameRenderer.mainRenderTarget(), 1,
            message -> client.execute(afterwards));
    }

    private static void check(final String what, final boolean passed) {
        report.add((passed ? "PASS  " : "FAIL  ") + what);
    }

    private static void finish(final Minecraft client) {
        stage = Stage.DONE;
        try {
            Files.write(client.gameDirectory.toPath().resolve("preview-report.txt"), report, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            BetterPetsQuickslots.LOGGER.error("Could not write the preview report", exception);
        }
        client.stop();
    }

    private static List<QuickslotProtocol.Pet> readPets(final Path dataFile) {
        final List<QuickslotProtocol.Pet> result = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
                final String[] cells = line.split("\t", -1);
                if (cells.length >= 10) {
                    result.add(new QuickslotProtocol.Pet(cells[0], cells[1], cells[2], cells[3], Integer.parseInt(cells[4], 16),
                        Integer.parseInt(cells[5]), Integer.parseInt(cells[6]), Boolean.parseBoolean(cells[7]), cells[8],
                        cells[9].replace("\\n", "\n")));
                }
            }
        } catch (final IOException | RuntimeException exception) {
            BetterPetsQuickslots.LOGGER.error("Could not read the preview data {}", dataFile, exception);
        }
        return result;
    }
}
