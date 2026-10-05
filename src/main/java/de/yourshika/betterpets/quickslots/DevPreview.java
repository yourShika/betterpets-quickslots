package de.yourshika.betterpets.quickslots;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import de.kamil.betterpets.quickslots.QuickslotProtocol;
import de.yourshika.betterpets.quickslots.ui.TextButton;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * over the real channel, and the screens are worked with real clicks and key presses at the places they
 * draw their controls, so a control that is drawn in one place and listens in another fails a check.
 * Keys are pressed the way the keyboard does it - through the game's key bindings - and "held" by
 * answering the mod's question whether a key is down; the mouse is moved and its wheel turned through
 * the game's own mouse handler, mixin included.
 * The screenshots land in the game directory's {@code screenshots/}, the checks in
 * {@code preview-report.txt}.</p>
 *
 * <p>The whole run is a script: a queue of steps, each waiting a number of ticks (or for a condition)
 * before it acts.</p>
 */
final class DevPreview {

    private static final String PROPERTY = "betterpets.quickslots.preview";
    private static final String WORLD_NAME = "quickslots-preview";
    /** Ticks a step may wait for its condition before the run is given up. */
    private static final int TIMEOUT_TICKS = 2400;
    /** Ticks the slot screen is open before its screenshot, so the pet skins have been fetched. */
    private static final int SCREEN_SETTLE_TICKS = 100;
    private static final int TOOLTIP_SETTLE_TICKS = 4;
    /** Ticks a request gets to travel to the server and back. */
    private static final int ROUND_TRIP_TICKS = 10;

    /** One step of the script: once {@code waitTicks} have passed and {@code ready} holds, {@code action} runs. */
    private record Step(String name, int waitTicks, Predicate<Minecraft> ready, Consumer<Minecraft> action) {
    }

    private static final Deque<Step> script = new ArrayDeque<>();
    private static final List<String> report = new ArrayList<>();
    private static List<QuickslotProtocol.Pet> pets = List.of();
    private static int ticks;
    /** A screenshot is being written; the script waits for it. */
    private static boolean busy;
    private static boolean finished;
    private static int requestsBefore;
    /** The keys the preview is "holding down". */
    private static final Set<InputConstants.Key> heldKeys = new HashSet<>();
    /** How many frames of each film have been queued. */
    private static final Map<String, Integer> filmFrames = new HashMap<>();
    // Where the previewed screen believes the mouse is, so tooltips and hover effects can be captured
    // without anyone touching the mouse (and the real cursor cannot wander into a shot).
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
        Keybinds.physicallyDown = heldKeys::contains;
        writeScript();
        ClientTickEvents.END_CLIENT_TICK.register(DevPreview::tick);
    }

    private static void tick(final Minecraft client) {
        if (finished || busy) {
            return;
        }
        final Step step = script.peek();
        if (step == null) {
            finish(client);
            return;
        }
        ticks++;
        if (ticks >= step.waitTicks() && (step.ready() == null || step.ready().test(client))) {
            script.poll();
            ticks = 0;
            try {
                step.action().accept(client);
            } catch (final RuntimeException exception) {
                BetterPetsQuickslots.LOGGER.error("Preview step '{}' failed", step.name(), exception);
                check(step.name() + " (threw " + exception + ")", false);
                finish(client);
            }
        } else if (ticks > step.waitTicks() + TIMEOUT_TICKS) {
            check("timed out waiting for: " + step.name(), false);
            finish(client);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The script
    // ------------------------------------------------------------------------------------------------

    private static void then(final String name, final int waitTicks, final Consumer<Minecraft> action) {
        script.add(new Step(name, waitTicks, null, action));
    }

    private static void when(final String name, final Predicate<Minecraft> ready, final Consumer<Minecraft> action) {
        script.add(new Step(name, 0, ready, action));
    }

    private static void shot(final int waitTicks, final String fileName) {
        script.add(new Step("screenshot " + fileName, waitTicks, null, client -> {
            busy = true;
            Screenshot.grab(client.gameDirectory, fileName, client.gameRenderer.mainRenderTarget(), 1, message -> client.execute(() -> busy = false));
        }));
    }

    private static void writeScript() {
        // The title screen already exists while resources are still loading behind an overlay.
        when("the title screen", client -> client.gui.overlay() == null && client.gui.screen() instanceof TitleScreen, client -> {
            ModConfig.reset();
            modMenu();
            // The settings can be opened before any world is: no server, no pets, no item data.
            client.gui.setScreen(new SettingsScreen(client.gui.screen()));
        });
        shot(16, "settings-no-world-keys.png");
        then("display page, no world", 0, client -> {
            config(client).showTab(SettingsScreen.Tab.DISPLAY);
            config(client).preview().restart();
        });
        shot(28, "settings-no-world-display.png");
        then("back to the title screen", 0, client -> client.gui.screen().onClose());
        when("the title screen again", client -> client.gui.screen() instanceof TitleScreen, DevPreview::createWorld);
        when("the test world", client -> client.player != null && client.level != null && client.gui.overlay() == null && client.gui.screen() == null,
            client -> { });
        // Nothing is fed in by hand here: the mod has to notice the channel, say hello and receive the
        // state and the pet list on its own, exactly as on a real server.
        when("the handshake", client -> QuickslotClient.usable() && QuickslotClient.pets().size() == pets.size(), client -> {
            check("handshake: state and " + pets.size() + " pets arrived over the channel", true);
            client.gui.setScreen(new QuickslotScreen() {
                @Override
                public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
                    super.extractRenderState(graphics, hoverX, hoverY, partialTick);
                }
            });
        });
        slotScreen();
        display();
        spamGuard();
        wheel();
        keyboard();
        settings();
        films();
        menuButton();
    }

    private static void slotScreen() {
        shot(SCREEN_SETTLE_TICKS, "quickslots-screen.png");
        then("hover a pet", 0, client -> hover(slots(client).pointOnCard(0)));
        shot(TOOLTIP_SETTLE_TICKS, "quickslots-tooltip-pet.png");
        then("hover a slot", 0, client -> hover(slots(client).pointOnSlot(1)));
        shot(TOOLTIP_SETTLE_TICKS, "quickslots-tooltip-slot.png");
        // Park the fourth pet in the empty fourth slot - with real clicks: on the slot, then on the card.
        then("assign by clicking", 0, client -> {
            hoverAway();
            click(client.gui.screen(), slots(client).pointOnSlot(3), 0);
            click(client.gui.screen(), slots(client).pointOnCard(3), 0);
        });
        shot(3, "quickslots-assign.png");
        then("assign arrived", ROUND_TRIP_TICKS, client -> {
            check("assign: a click on slot 4 and on a pet parked " + pets.get(3).id() + " there on the server", PreviewServer.slot(3).equals(pets.get(3).id()));
            check("assign: the client shows it there", QuickslotClient.slotOf(pets.get(3).id()) == 3);
            client.gui.setScreen(null);
        });
    }

    private static void display() {
        // Summon the pet of slot 1 (the one of slot 2 is out). Only the server's answer can change the
        // active pet - and that answer is also what makes the display appear.
        then("switch", 2, client -> QuickslotClient.switchTo(0));
        then("switch arrived", ROUND_TRIP_TICKS, client ->
            check("switch: the server's answer made " + pets.get(0).id() + " the active pet", QuickslotClient.activeId().equals(pets.get(0).id())));
        shot(0, "quickslots-hud.png");
        then("ring style", 0, client -> {
            ModConfig.get().hudAlways = true;
            ModConfig.get().hudStyle = ModConfig.HudStyle.WHEEL;
            ModConfig.adjusting();
        });
        shot(10, "quickslots-hud-ring.png");
        then("compact style", 0, client -> {
            ModConfig.get().hudStyle = ModConfig.HudStyle.COMPACT;
            ModConfig.get().hudAnchor = ModConfig.HudAnchor.TOP_LEFT;
            ModConfig.adjusting();
        });
        shot(10, "quickslots-hud-compact.png");
        then("bar with keys", 0, client -> {
            ModConfig.get().hudStyle = ModConfig.HudStyle.BAR;
            ModConfig.get().hudAnchor = ModConfig.HudAnchor.ABOVE_HOTBAR;
            ModConfig.get().hudKeys = true;
            ModConfig.adjusting();
        });
        shot(10, "quickslots-hud-keys.png");
        then("defaults again", 0, client -> ModConfig.reset());
    }

    private static void spamGuard() {
        // The stand-in server enforces nothing, so whatever arrives there is what the mod let through.
        then("a double press", 24, client -> {
            requestsBefore = PreviewServer.switchRequests();
            QuickslotClient.switchTo(1);
            QuickslotClient.switchTo(2);
        });
        then("a press during the cooldown", 4, client -> QuickslotClient.switchTo(2));
        then("spam guard counted", ROUND_TRIP_TICKS, client -> {
            check("spam guard: a double press and a press during the cooldown send one request, not three",
                PreviewServer.switchRequests() - requestsBefore == 1);
            server(client, () -> PreviewServer.lock(serverPlayer(client), 4000));
        });
        then("a press while locked", 6, client -> {
            check("lockout: the client knows switching is locked", QuickslotClient.lockRemainingMillis() > 0L);
            requestsBefore = PreviewServer.switchRequests();
            QuickslotClient.switchTo(0);
        });
        shot(6, "quickslots-hud-locked.png");
        then("lockout counted", 4, client -> {
            check("lockout: nothing is sent while locked", PreviewServer.switchRequests() == requestsBefore);
            server(client, () -> PreviewServer.lock(serverPlayer(client), 0));
        });
    }

    private static void wheel() {
        then("open the wheel", 8, client -> {
            // In click mode, or the wheel would choose and close the moment it sees its key is not held.
            ModConfig.get().wheelHold = false;
            client.gui.setScreen(new WheelScreen() {
                @Override
                public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
                    super.extractRenderState(graphics, hoverX, hoverY, partialTick);
                }
            });
            // Towards the third of the five slots: they start at the top and are 72 degrees apart.
            pointFromCentre(client, -90.0 + 2 * 72.0, 60.0);
        });
        shot(14, "quickslots-wheel.png");
        then("click on the wheel", 0, client -> click(client.gui.screen(), new int[] {hoverX, hoverY}, 0));
        then("wheel choice arrived", ROUND_TRIP_TICKS, client -> {
            check("wheel: a click chose the pet pointed at (" + pets.get(2).id() + ")", QuickslotClient.activeId().equals(pets.get(2).id()));
            check("wheel: it closed after the choice", client.gui.screen() == null);
            ModConfig.reset();
            hoverAway();
        });
    }

    /** The ways of switching that hang on a key being held: the modifier key and the wheel's hold-and-release. */
    private static void keyboard() {
        then("hold the modifier", 14, client -> {
            client.player.getInventory().setSelectedSlot(6);
            requestsBefore = PreviewServer.switchRequests();
            press(GLFW.GLFW_KEY_LEFT_ALT);
        });
        then("press 2 with it", 2, client -> press(GLFW.GLFW_KEY_2));
        then("modifier + number arrived", ROUND_TRIP_TICKS, client -> {
            check("modifier key: Alt + 2 summons the pet of slot 2",
                PreviewServer.switchRequests() == requestsBefore + 1 && QuickslotClient.activeId().equals(pets.get(1).id()));
            check("modifier key: the hotbar stays where it was", client.player.getInventory().getSelectedSlot() == 6);
        });
        // The 2 is still down: what the keyboard's auto-repeat sends now must not switch again.
        then("auto-repeat of the held 2", 14, client -> {
            requestsBefore = PreviewServer.switchRequests();
            KeyMapping.click(key(GLFW.GLFW_KEY_2));
        });
        then("auto-repeat ignored", ROUND_TRIP_TICKS, client -> {
            check("modifier key: a number key that stays held switches only once", PreviewServer.switchRequests() == requestsBefore);
            release(GLFW.GLFW_KEY_2);
        });
        then("turn the mouse wheel with it", 4, client -> {
            requestsBefore = PreviewServer.switchRequests();
            scroll(client, -1.0);
        });
        then("modifier + wheel arrived", ROUND_TRIP_TICKS, client -> {
            check("modifier key: Alt + a notch of the mouse wheel steps to the next pet",
                PreviewServer.switchRequests() == requestsBefore + 1 && QuickslotClient.activeId().equals(pets.get(2).id()));
            check("modifier key: the hotbar stays where it was then, too", client.player.getInventory().getSelectedSlot() == 6);
            release(GLFW.GLFW_KEY_LEFT_ALT);
        });
        then("the same without the modifier", 14, client -> {
            requestsBefore = PreviewServer.switchRequests();
            scroll(client, -1.0);
            press(GLFW.GLFW_KEY_4);
        });
        then("left to the game", 4, client -> {
            release(GLFW.GLFW_KEY_4);
            check("without the modifier the mouse wheel and the number keys are the game's: no request, hotbar on 4",
                PreviewServer.switchRequests() == requestsBefore && client.player.getInventory().getSelectedSlot() == 3);
        });

        then("hold the wheel's key", 6, client -> {
            requestsBefore = PreviewServer.switchRequests();
            press(GLFW.GLFW_KEY_R);
        });
        then("point at the first pet", 8, client -> {
            check("pet wheel: holding its key opens it", client.gui.screen() instanceof WheelScreen);
            // Straight up from the middle: slot 1.
            moveMouse(client, client.gui.screen().width / 2.0, client.gui.screen().height / 2.0 - 70.0);
        });
        shot(8, "quickslots-wheel-held.png");
        then("let go of the wheel's key", 0, client -> release(GLFW.GLFW_KEY_R));
        then("the wheel chose", ROUND_TRIP_TICKS, client -> {
            check("pet wheel: letting go over a pet summons it",
                PreviewServer.switchRequests() == requestsBefore + 1 && QuickslotClient.activeId().equals(pets.get(0).id()));
            check("pet wheel: and closes the wheel", client.gui.screen() == null);
        });
    }

    private static void settings() {
        then("open the settings", 4, client -> client.gui.setScreen(new SettingsScreen(null) {
            @Override
            public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
                super.extractRenderState(graphics, hoverX, hoverY, partialTick);
            }
        }));
        shot(14, "settings-keys.png");
        // The key tester: the wheel's key (R out of the box) makes the preview open a wheel.
        then("press the wheel key", 0, client -> client.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_R, 0, 0)));
        shot(10, "settings-keys-test.png");
        then("to the end of the keys", 30, client -> config(client).scrollToEnd());
        shot(6, "settings-keys-end.png");

        then("display page", 0, client -> {
            config(client).showTab(SettingsScreen.Tab.DISPLAY);
            config(client).preview().restart();
        });
        shot(28, "settings-display.png");
        then("hover an option", 0, client -> hover(config(client).pointOnRow("display.on_change")));
        shot(14, "settings-tooltip.png");
        then("use a switch and a choice", 0, client -> {
            hoverAway();
            final SettingsScreen screen = config(client);
            click(screen, screen.pointOnRow("display.always"), 0);
            check("settings: a click on the 'always' switch turns it on", ModConfig.get().hudAlways);
            click(screen, screen.pointOnRow("display.always"), 0);
            check("settings: a second click turns it off again", !ModConfig.get().hudAlways);
            click(screen, screen.pointOnRow("display.style"), 0);
            check("settings: a click on the style steps it from bar to ring", ModConfig.get().hudStyle == ModConfig.HudStyle.WHEEL);
            screen.preview().restart();
        });
        shot(28, "settings-display-ring.png");
        then("drag a slider", 0, client -> {
            final SettingsScreen screen = config(client);
            click(screen, screen.pointOnRow("display.style"), 1);
            check("settings: a right-click steps the style back", ModConfig.get().hudStyle == ModConfig.HudStyle.BAR);
            // From the control of the size slider to the far right: as large as it goes.
            final int[] knob = screen.pointOnRow("display.scale");
            if (knob == null) {
                check("settings: the size slider is in view", false);
                return;
            }
            screen.mouseClicked(mouse(knob[0], knob[1], 0), false);
            screen.mouseDragged(mouse(knob[0] + 200, knob[1], 0), 200.0, 0.0);
            screen.mouseReleased(mouse(knob[0] + 200, knob[1], 0));
            check("settings: dragging the size slider to the right end gives 200%", Math.abs(ModConfig.get().hudScale - 2.0F) < 0.001F);
            click(screen, knob, 1);
            check("settings: a right-click on it goes back to 100%", Math.abs(ModConfig.get().hudScale - 1.0F) < 0.001F);
        });

        then("to the end of the display options", 0, client -> {
            config(client).scrollToEnd();
            config(client).preview().restart();
        });
        shot(28, "settings-display-end.png");

        then("wheel page", 0, client -> {
            config(client).showTab(SettingsScreen.Tab.WHEEL);
            config(client).preview().restart();
        });
        shot(44, "settings-wheel.png");
        then("look page", 0, client -> config(client).showTab(SettingsScreen.Tab.LOOK));
        shot(14, "settings-look.png");

        then("full-size display", 0, client -> {
            config(client).showTab(SettingsScreen.Tab.DISPLAY);
            config(client).setFullPreview(true);
        });
        shot(30, "settings-full-display.png");
        then("drag the display", 0, client -> {
            final SettingsScreen screen = config(client);
            screen.mouseClicked(mouse(100, 100, 0), false);
            screen.mouseDragged(mouse(130, 88, 0), 30.0, -12.0);
            screen.mouseReleased(mouse(130, 88, 0));
            check("settings: dragging the display in the full-size preview moves it by as much",
                ModConfig.get().hudOffsetX == 30 && ModConfig.get().hudOffsetY == -12);
            ModConfig.get().hudOffsetX = 0;
            ModConfig.get().hudOffsetY = 0;
            ModConfig.changed();
            screen.setFullPreview(false);
            screen.showTab(SettingsScreen.Tab.WHEEL);
            screen.setFullPreview(true);
            pointFromCentre(client, -90.0, 70.0);
        });
        shot(16, "settings-full-wheel.png");
        then("close the settings", 0, client -> {
            hoverAway();
            client.gui.setScreen(null);
            ModConfig.reset();
        });
    }

    /**
     * Two short films, as runs of screenshots in {@code screenshots/film/}: the wheel being used, and the
     * settings being worked. No checks here - this is for looking at how things move.
     */
    private static void films() {
        then("film: hold the wheel's key", 14, client -> {
            client.gameDirectory.toPath().resolve("screenshots/film").toFile().mkdirs();
            press(GLFW.GLFW_KEY_R);
        });
        frames("wheel", 6, 2);
        // Out from the middle and round from the first pet to the third.
        for (int frame = 0; frame < 16; frame++) {
            final double angle = -90.0 + 144.0 * Math.min(1.0, frame / 11.0);
            final double reach = 18.0 + 52.0 * Math.min(1.0, frame / 4.0);
            then("film: sweep", 2, client -> moveMouse(client,
                client.gui.screen().width / 2.0 + Math.cos(Math.toRadians(angle)) * reach,
                client.gui.screen().height / 2.0 + Math.sin(Math.toRadians(angle)) * reach));
            frames("wheel", 1, 0);
        }
        then("film: let go", 2, client -> release(GLFW.GLFW_KEY_R));
        // The wheel is gone, the display comes up with the new pet.
        frames("wheel", 14, 2);

        then("film: open the settings", 30, client -> client.gui.setScreen(new SettingsScreen(null) {
            @Override
            public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
                super.extractRenderState(graphics, hoverX, hoverY, partialTick);
            }
        }));
        frames("settings", 8, 2);
        then("film: display page", 0, client -> {
            config(client).showTab(SettingsScreen.Tab.DISPLAY);
            config(client).preview().restart();
        });
        frames("settings", 16, 2);
        for (int step = 0; step < 2; step++) {
            then("film: next style", 0, client -> {
                hover(config(client).pointOnRow("display.style"));
                click(config(client), config(client).pointOnRow("display.style"), 0);
                config(client).preview().restart();
            });
            frames("settings", 14, 2);
        }
        then("film: back to the bar", 0, client -> {
            click(config(client), config(client).pointOnRow("display.style"), 1);
            click(config(client), config(client).pointOnRow("display.style"), 1);
            hover(config(client).pointOnRow("display.always"));
            click(config(client), config(client).pointOnRow("display.always"), 0);
        });
        frames("settings", 10, 2);
        then("film: take hold of the size slider", 0, client -> {
            click(config(client), config(client).pointOnRow("display.always"), 0);
            final int[] knob = config(client).pointOnRow("display.scale");
            hover(knob);
            config(client).mouseClicked(mouse(knob[0] - 44, knob[1], 0), false);
        });
        // Up to about twice the size and back again.
        for (int frame = 0; frame < 16; frame++) {
            final int shift = Math.round((frame < 8 ? frame : 15 - frame) * 5.5F) - 44;
            then("film: drag", 2, client -> {
                final int[] knob = config(client).pointOnRow("display.scale");
                config(client).mouseDragged(mouse(knob[0] + shift, knob[1], 0), 0.0, 0.0);
            });
            frames("settings", 1, 0);
        }
        then("film: let go of the slider", 0, client -> {
            final int[] knob = config(client).pointOnRow("display.scale");
            config(client).mouseReleased(mouse(knob[0] - 44, knob[1], 0));
            click(config(client), knob, 1);
            hoverAway();
        });
        frames("settings", 6, 2);
        then("film: close the settings", 0, client -> {
            client.gui.setScreen(null);
            ModConfig.reset();
        });
    }

    /** Queues screenshots a few ticks apart: the next frames of a film. */
    private static void frames(final String film, final int count, final int ticksApart) {
        for (int i = 0; i < count; i++) {
            final int number = filmFrames.merge(film, 1, Integer::sum) - 1;
            shot(ticksApart, String.format(Locale.ROOT, "film/%s-%03d.png", film, number));
        }
    }

    private static void menuButton() {
        // An ordinary chest first: it must NOT get the button.
        then("an ordinary chest", 2, client -> openChest(client, false));
        then("the ordinary chest is open", ROUND_TRIP_TICKS, client -> {
            check("menu button: an ordinary chest opened", client.gui.screen() instanceof ContainerScreen);
            check("menu button: an ordinary chest does not get it", !hasMenuButton(client));
            client.player.closeContainer();
            // Now the chest the "plugin" announces as its /pets menu.
            openChest(client, true);
        });
        then("the pet menu is open", ROUND_TRIP_TICKS, client -> check("menu button: the announced pet menu gets it", hasMenuButton(client)));
        shot(0, "quickslots-menu-button.png");
        // What a click on the button does.
        then("use the button", 0, QuickslotClient::openScreen);
        then("the button was used", ROUND_TRIP_TICKS, client -> {
            check("menu button: it opens the slot screen", client.gui.screen() instanceof QuickslotScreen);
            check("menu button: the server was told the chest is closed", !PreviewServer.hasContainerOpen(serverPlayer(client)));
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    /**
     * With Mod Menu in the test instance (see EXTRA_MODS in run-preview.sh): asks the mod for its config
     * screen exactly as Mod Menu does. By reflection, so that this class loads without Mod Menu too.
     */
    private static void modMenu() {
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) {
            return;
        }
        boolean opens = false;
        try {
            for (final EntrypointContainer<Object> entry : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
                if (entry.getProvider().getMetadata().getId().equals(BetterPetsQuickslots.MOD_ID)) {
                    final Object factory = entry.getEntrypoint().getClass().getMethod("getModConfigScreenFactory").invoke(entry.getEntrypoint());
                    final Method create = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory").getMethod("create", Screen.class);
                    opens = create.invoke(factory, (Object) null) instanceof SettingsScreen;
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            BetterPetsQuickslots.LOGGER.error("Mod Menu check failed", exception);
        }
        check("mod menu: its configure button gets the settings screen", opens);
    }

    private static QuickslotScreen slots(final Minecraft client) {
        return (QuickslotScreen) client.gui.screen();
    }

    private static SettingsScreen config(final Minecraft client) {
        return (SettingsScreen) client.gui.screen();
    }

    private static MouseButtonEvent mouse(final int x, final int y, final int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    private static void click(final Screen screen, final int[] point, final int button) {
        if (point == null) {
            check("a control that should be in view is not", false);
            return;
        }
        screen.mouseClicked(mouse(point[0], point[1], button), false);
        screen.mouseReleased(mouse(point[0], point[1], button));
    }

    private static InputConstants.Key key(final int glfwKey) {
        return InputConstants.Type.KEYSYM.getOrCreate(glfwKey);
    }

    /** Presses a key and keeps it down: the bindings on it get their click, and the key reads as held. */
    private static void press(final int glfwKey) {
        heldKeys.add(key(glfwKey));
        KeyMapping.set(key(glfwKey), true);
        KeyMapping.click(key(glfwKey));
    }

    private static void release(final int glfwKey) {
        heldKeys.remove(key(glfwKey));
        KeyMapping.set(key(glfwKey), false);
    }

    /** Turns the mouse wheel by notches (negative = towards the player), through the game's own handler. */
    private static void scroll(final Minecraft client, final double notches) {
        mouse(client, "onScroll", 0.0, notches);
    }

    /** Moves the mouse to a point given in the GUI's coordinates. */
    private static void moveMouse(final Minecraft client, final double guiX, final double guiY) {
        final Window window = client.getWindow();
        mouse(client, "onMove", guiX * window.getScreenWidth() / window.getGuiScaledWidth(), guiY * window.getScreenHeight() / window.getGuiScaledHeight());
    }

    /** Calls one of the mouse handler's callbacks, which the game keeps private. */
    private static void mouse(final Minecraft client, final String callback, final double first, final double second) {
        try {
            final Method method = MouseHandler.class.getDeclaredMethod(callback, long.class, double.class, double.class);
            method.setAccessible(true);
            method.invoke(client.mouseHandler, client.getWindow().handle(), first, second);
        } catch (final ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not reach MouseHandler." + callback, exception);
        }
    }

    private static void hover(final int[] point) {
        if (point == null) {
            check("a control that should be in view is not", false);
            return;
        }
        hoverX = point[0];
        hoverY = point[1];
    }

    private static void hoverAway() {
        hoverX = 2;
        hoverY = 2;
    }

    /** Puts the pretend mouse at an angle (degrees, 0 = right, 90 = down) and distance from the middle of the screen. */
    private static void pointFromCentre(final Minecraft client, final double degrees, final double distance) {
        final Screen screen = client.gui.screen();
        hoverX = (int) Math.round(screen.width / 2.0 + Math.cos(Math.toRadians(degrees)) * distance);
        hoverY = (int) Math.round(screen.height / 2.0 + Math.sin(Math.toRadians(degrees)) * distance);
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

    private static void server(final Minecraft client, final Runnable task) {
        client.getSingleplayerServer().execute(task);
    }

    private static void openChest(final Minecraft client, final boolean announce) {
        final ServerPlayer player = serverPlayer(client);
        server(client, () -> PreviewServer.openChest(player, announce));
    }

    /** Whether the open screen carries the mod's Quickslots button. */
    private static boolean hasMenuButton(final Minecraft client) {
        if (client.gui.screen() == null) {
            return false;
        }
        final String label = Component.translatable(BetterPetsQuickslots.MOD_ID + ".menu_button").getString();
        for (final AbstractWidget widget : Screens.getWidgets(client.gui.screen())) {
            if (widget instanceof TextButton && widget.getMessage().getString().equals(label)) {
                return true;
            }
        }
        return false;
    }

    private static void check(final String what, final boolean passed) {
        report.add((passed ? "PASS  " : "FAIL  ") + what);
    }

    private static void finish(final Minecraft client) {
        finished = true;
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
