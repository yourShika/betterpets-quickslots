package de.yourshika.betterpets.quickslots;

import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's view of the player's quickslots, and the only place that talks to the server.
 *
 * <p>The server is authoritative: every action here is a request, and what is shown comes from the
 * {@link QuickslotProtocol.State}/{@link QuickslotProtocol.Pets} it sends back. Until a state arrived
 * (a server without the plugin never sends one) the mod does nothing but say so when a key is pressed.</p>
 *
 * <p>The mod is also polite about it: a switch is only requested when the server would accept one - not
 * during the cooldown it announced, not while it has locked switching after too many of them. The server
 * enforces both anyway; holding back here just keeps a hammered key from turning into a stream of
 * pointless requests, and lets the display show the wait instead.</p>
 *
 * <p>All of this runs on the client thread.</p>
 */
final class QuickslotClient {

    private static final long HELLO_RETRY_MILLIS = 5000L;
    private static final long NOTICE_INTERVAL_MILLIS = 2000L;
    private static final long PETS_REQUEST_INTERVAL_MILLIS = 1000L;
    /** How long after the plugin's "menu opens now" note a container screen counts as that menu. */
    private static final long MENU_MARKER_MILLIS = 3000L;
    /** The least time between two switch requests, so a double press cannot outrun the server's answer. */
    private static final long MIN_REQUEST_GAP_MILLIS = 150L;

    private static QuickslotProtocol.State state;
    private static List<QuickslotProtocol.Pet> pets = List.of();
    private static Map<String, QuickslotProtocol.Pet> petsById = Map.of();
    private static boolean petsKnown;
    private static int petsRevision;
    private static long lastHelloMillis;
    private static long lastNoticeMillis;
    private static long lastPetsRequestMillis;
    private static long menuMarkerMillis;
    private static int menuContainerId = -1;
    // Throttling, on the client's own clock.
    private static long lastRequestMillis;
    private static long lastSwitchMillis;
    private static long lockedUntilMillis;

    private QuickslotClient() {
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(QuickslotPayload.TYPE,
            (payload, context) -> receive(context.client(), payload.data()));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
            reset();
            sayHello();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());
        // The server announces the channels it listens on shortly after the join; that announcement
        // (not the join itself) is the earliest moment a hello can actually be delivered.
        ServerboundPlayChannelEvents.REGISTER.register((listener, sender, client, channels) -> {
            if (channels.contains(QuickslotPayload.TYPE.id())) {
                client.execute(QuickslotClient::sayHello);
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(Keybinds::pollEarly);
        ClientTickEvents.END_CLIENT_TICK.register(QuickslotClient::tick);
    }

    private static void tick(final Minecraft client) {
        Keybinds.poll(client);
        // No answer yet although the server takes our channel: the hello may have been sent too early.
        if (client.player != null && state == null && canSend()
            && Util.getMillis() - lastHelloMillis > HELLO_RETRY_MILLIS) {
            sayHello();
        }
    }

    private static void reset() {
        state = null;
        pets = List.of();
        petsById = Map.of();
        petsKnown = false;
        lastHelloMillis = 0L;
        menuMarkerMillis = 0L;
        menuContainerId = -1;
        lastRequestMillis = 0L;
        lastSwitchMillis = 0L;
        lockedUntilMillis = 0L;
        QuickslotHud.reset();
    }

    // ------------------------------------------------------------------------------------------------
    // Receiving
    // ------------------------------------------------------------------------------------------------

    private static void receive(final Minecraft client, final byte[] data) {
        final QuickslotProtocol.ServerMessage message;
        try {
            message = QuickslotProtocol.decodeServer(data);
        } catch (final IOException | RuntimeException malformed) {
            BetterPetsQuickslots.LOGGER.warn("Ignored a malformed quickslot message: {}", malformed.toString());
            return;
        }
        if (message == null) {
            return;
        }
        switch (message) {
            case QuickslotProtocol.State next -> accept(next);
            case QuickslotProtocol.Pets list -> accept(list);
            case QuickslotProtocol.OpenScreen ignored -> openScreen(client);
            case QuickslotProtocol.MenuOpened ignored -> menuMarkerMillis = Util.getMillis();
        }
    }

    private static void accept(final QuickslotProtocol.State next) {
        final QuickslotProtocol.State previous = state;
        final long now = Util.getMillis();
        state = next;
        lockedUntilMillis = next.lockoutMillis() > 0 ? now + next.lockoutMillis() : 0L;
        // Not on the first state after joining: only an actual change of pet is worth showing.
        if (previous != null && !previous.activeId().equals(next.activeId())) {
            lastSwitchMillis = now;
            QuickslotHud.onPetChanged();
        }
        // The server pushes the list whenever it changes; asking is only the safety net.
        if ((!petsKnown || petsRevision != next.petsRevision())
            && now - lastPetsRequestMillis > PETS_REQUEST_INTERVAL_MILLIS) {
            lastPetsRequestMillis = now;
            send(new QuickslotProtocol.RequestPets());
        }
    }

    private static void accept(final QuickslotProtocol.Pets list) {
        final Map<String, QuickslotProtocol.Pet> byId = new HashMap<>();
        for (final QuickslotProtocol.Pet pet : list.pets()) {
            byId.putIfAbsent(pet.id(), pet);
        }
        pets = list.pets();
        petsById = byId;
        petsRevision = list.revision();
        petsKnown = true;
    }

    // ------------------------------------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------------------------------------

    private static boolean canSend() {
        try {
            return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(QuickslotPayload.TYPE);
        } catch (final IllegalStateException notInGame) {
            return false;
        }
    }

    private static boolean send(final QuickslotProtocol.ClientMessage message) {
        if (!canSend()) {
            return false;
        }
        ClientPlayNetworking.send(new QuickslotPayload(QuickslotProtocol.encode(message)));
        return true;
    }

    private static void sayHello() {
        if (send(new QuickslotProtocol.Hello(QuickslotProtocol.VERSION))) {
            lastHelloMillis = Util.getMillis();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Actions (keys, the wheel and the screens)
    // ------------------------------------------------------------------------------------------------

    /** Summons the pet in a slot (0-based); pressing the active pet's slot may put it away, per server. */
    static void switchTo(final int slot) {
        if (!checkUsable()) {
            return;
        }
        if (slot >= state.slots().size()) {
            notice(Component.translatable("betterpets-quickslots.notice.no_such_slot", slot + 1, state.slots().size()));
            return;
        }
        if (mayRequestSwitch()) {
            send(new QuickslotProtocol.Switch(slot));
        }
    }

    static void cycle(final int direction) {
        if (checkUsable() && mayRequestSwitch()) {
            send(new QuickslotProtocol.Cycle(direction));
        }
    }

    static void putAway() {
        if (checkUsable() && mayRequestSwitch()) {
            send(new QuickslotProtocol.Despawn());
        }
    }

    /**
     * Whether a switch would be accepted right now. If not, nothing is sent and the display shows the
     * wait instead.
     */
    private static boolean mayRequestSwitch() {
        final long now = Util.getMillis();
        if (now < lockedUntilMillis
            || now - lastSwitchMillis < state.cooldownMillis()
            || now - lastRequestMillis < MIN_REQUEST_GAP_MILLIS) {
            QuickslotHud.onRefused();
            return false;
        }
        lastRequestMillis = now;
        return true;
    }

    /** Parks a pet in a slot ({@code ""} empties it) and shows the result at once. */
    static void assign(final int slot, final String petId) {
        if (!usable() || slot < 0 || slot >= state.slots().size()) {
            return;
        }
        if (!send(new QuickslotProtocol.Assign(slot, petId))) {
            return;
        }
        // Reflect the change immediately; the server's answer then replaces this with the real state.
        final List<String> slots = new ArrayList<>(state.slots());
        if (!petId.isEmpty()) {
            slots.replaceAll(existing -> existing.equals(petId) ? "" : existing);
        }
        slots.set(slot, petId);
        state = new QuickslotProtocol.State(state.version(), state.enabled(), slots, state.activeId(),
            state.cooldownMillis(), state.sameSlotDespawns(), state.petsRevision(), state.lockoutMillis());
    }

    /** Opens the slot screen (closing the plugin's chest menu properly if that is what is open). */
    static void openScreen(final Minecraft client) {
        if (client.player == null || !checkUsable()) {
            return;
        }
        if (client.gui.screen() instanceof AbstractContainerScreen<?>) {
            // Tells the server the container is closed; merely replacing the screen would not.
            client.player.closeContainer();
        }
        client.gui.setScreen(new QuickslotScreen());
    }

    /** Opens the pet wheel, if there is anything to choose from. */
    static void openWheel(final Minecraft client) {
        if (client.player != null && client.gui.screen() == null && checkUsable()) {
            client.gui.setScreen(new WheelScreen());
        }
    }

    /** Runs {@code /pets} - the plugin's own menu. */
    static void openPetsMenu(final Minecraft client) {
        if (client.player != null && client.getConnection() != null) {
            client.getConnection().sendCommand("pets");
        }
    }

    private static boolean checkUsable() {
        if (usable()) {
            return true;
        }
        notice(Component.translatable(state == null
            ? "betterpets-quickslots.notice.unsupported"
            : "betterpets-quickslots.notice.disabled"));
        return false;
    }

    /** A short action-bar note, throttled so a held key cannot spam it. */
    private static void notice(final Component text) {
        final Minecraft client = Minecraft.getInstance();
        final long now = Util.getMillis();
        if (client.player != null && now - lastNoticeMillis > NOTICE_INTERVAL_MILLIS) {
            lastNoticeMillis = now;
            client.player.sendOverlayMessage(text);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // State for the screens, the display and the menu button
    // ------------------------------------------------------------------------------------------------

    /** The server runs the plugin and allows this player to use quickslots through the mod. */
    static boolean usable() {
        return state != null && state.enabled();
    }

    /** The slots as pet ids ("" = empty); empty list while unsupported. */
    static List<String> slots() {
        return state == null ? List.of() : state.slots();
    }

    static String activeId() {
        return state == null ? "" : state.activeId();
    }

    static List<QuickslotProtocol.Pet> pets() {
        return pets;
    }

    /** The owned pet with that id, or {@code null} (e.g. a slot whose pet was traded away). */
    static QuickslotProtocol.Pet pet(final String id) {
        return petsById.get(id);
    }

    /** The slot (0-based) holding the pet, or -1. */
    static int slotOf(final String petId) {
        return slots().indexOf(petId);
    }

    /**
     * Whether summoning the pet that is already out puts it away again - the server's choice. Assumed
     * while there is no server to ask, because that is how the plugin comes configured.
     */
    static boolean sameSlotPutsAway() {
        return state == null || state.sameSlotDespawns();
    }

    /** The pause the server asks for between two switches, in milliseconds; 0 if none or not connected. */
    static int cooldownMillis() {
        return state == null ? 0 : Math.max(0, state.cooldownMillis());
    }

    /** Milliseconds the server keeps quick switching locked for (too many switches), 0 if it does not. */
    static long lockRemainingMillis() {
        return Math.max(0L, lockedUntilMillis - Util.getMillis());
    }

    /** How much of the cooldown after the last switch is still to go, 1 (just switched) down to 0 (ready). */
    static float cooldownRemaining() {
        if (state == null || state.cooldownMillis() <= 0) {
            return 0.0F;
        }
        final float elapsed = (Util.getMillis() - lastSwitchMillis) / (float) state.cooldownMillis();
        return elapsed >= 1.0F ? 0.0F : 1.0F - Math.max(0.0F, elapsed);
    }

    /**
     * Whether a container screen that is opening is the plugin's main menu. The plugin announces that
     * menu right before opening it; the first container to appear after the note is the one, and it stays
     * recognised (the screen is rebuilt on every window resize) by its container id.
     */
    static boolean isPetsMenu(final int containerId) {
        if (!usable()) {
            return false;
        }
        if (containerId == menuContainerId) {
            return true;
        }
        if (menuMarkerMillis != 0L && Util.getMillis() - menuMarkerMillis <= MENU_MARKER_MILLIS) {
            menuMarkerMillis = 0L;
            menuContainerId = containerId;
            return true;
        }
        return false;
    }
}
