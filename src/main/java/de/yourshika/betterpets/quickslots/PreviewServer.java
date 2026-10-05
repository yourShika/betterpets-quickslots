package de.yourshika.betterpets.quickslots;

import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Development aid for {@link DevPreview}: plays the Better Pets plugin's part on the integrated server of
 * the preview world, so the mod's whole network path - channel announcement, hello, pet list, state,
 * assigning and switching - runs for real instead of being fed canned data.
 *
 * <p>It speaks the same {@link QuickslotProtocol} bytes the plugin does, with the plugin's rules reduced
 * to the bare minimum: it announces a cooldown and can announce a lockout, but enforces neither and checks
 * no ownership - which is exactly what lets the preview see whether the mod holds back by itself. Only
 * ever registered in preview mode.</p>
 */
final class PreviewServer {

    /** The cooldown the "server" announces, in milliseconds. */
    static final int COOLDOWN_MILLIS = 500;

    private static List<QuickslotProtocol.Pet> pets = List.of();
    private static final List<String> slots = new ArrayList<>();
    private static String active = "";
    private static int switchRequests;
    private static int lockoutMillis;

    private PreviewServer() {
    }

    /** Five slots: three filled (the second pet is out), one left empty, one whose pet is not owned. */
    static synchronized void start(final List<QuickslotProtocol.Pet> previewPets) {
        pets = List.copyOf(previewPets);
        slots.clear();
        slots.addAll(List.of("", "", "", "", "missing_pet"));
        for (int slot = 0; slot < 3 && slot < pets.size(); slot++) {
            slots.set(slot, pets.get(slot).id());
        }
        active = pets.size() > 1 ? pets.get(1).id() : "";
        ServerPlayNetworking.registerGlobalReceiver(QuickslotPayload.TYPE,
            (payload, context) -> receive(context.player(), payload.data()));
    }

    /** What the "server" currently holds in a slot - for the preview to check a request really arrived. */
    static synchronized String slot(final int index) {
        return slots.get(index);
    }

    /** How many requests to switch, cycle or put away have arrived so far. */
    static synchronized int switchRequests() {
        return switchRequests;
    }

    /** The pet that is out, as the "server" has it ("" for none). */
    static synchronized String active() {
        return active;
    }

    /**
     * Sends the player a pet list and a state no sane server would: names of absurd length, textures that
     * are not textures, numbers at the edge of their range, slots naming pets nobody has, the same pet
     * twice, a pet without an id. The mod has to live with whatever arrives. Call on the server thread.
     */
    static synchronized void sendNonsense(final ServerPlayer player) {
        final String endless = "W".repeat(300);
        final List<QuickslotProtocol.Pet> odd = List.of(
            new QuickslotProtocol.Pet("weird", endless, "", "", 0xFFFFFFFF, 65535, 255, false, "!!! not base64 !!!", (endless + "\n").repeat(12)),
            new QuickslotProtocol.Pet("bare", "", "", "", 0, 0, 0, false, "", ""),
            new QuickslotProtocol.Pet("weird", "Twin", "Twin", "Twin", -1, 1, 1, true, "e30=", "\n\n\n"),
            new QuickslotProtocol.Pet("", "No id", "x", "y", 0x123456, 5, 9, false, "AAAA", "ability"));
        send(player, new QuickslotProtocol.Pets(1, odd));
        send(player, new QuickslotProtocol.State(QuickslotProtocol.VERSION, true,
            List.of("weird", "bare", "gone", "weird", "", "bare", "weird", "weird", "weird"), "weird", -5, false, 1, -1));
    }

    /** Puts things back the way a sane server has them. Call on the server thread. */
    static synchronized void sendSane(final ServerPlayer player) {
        send(player, new QuickslotProtocol.Pets(pets.hashCode(), pets));
        sendState(player);
    }

    /**
     * Tells the player that quick switching is locked for a while (0 lifts the lock), the way the plugin
     * does after too many switches. Call on the server thread.
     */
    static synchronized void lock(final ServerPlayer player, final int millis) {
        lockoutMillis = millis;
        sendState(player);
    }

    /**
     * Opens a six-row chest for the player, like the plugin's {@code /pets} menu. With {@code announce}
     * the plugin's "my menu opens now" note goes out first; without it this is just some chest.
     * Call on the server thread.
     */
    static void openChest(final ServerPlayer player, final boolean announce) {
        if (announce) {
            send(player, new QuickslotProtocol.MenuOpened());
        }
        player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> ChestMenu.sixRows(id, inventory),
            Component.literal(announce ? "Better Pets" : "Some chest")));
    }

    /** Whether the server still considers a container open for the player. */
    static boolean hasContainerOpen(final ServerPlayer player) {
        return player.containerMenu != player.inventoryMenu;
    }

    private static synchronized void receive(final ServerPlayer player, final byte[] data) {
        final QuickslotProtocol.ClientMessage message;
        try {
            message = QuickslotProtocol.decodeClient(data);
        } catch (final IOException malformed) {
            BetterPetsQuickslots.LOGGER.error("Preview server got a malformed message", malformed);
            return;
        }
        if (message == null) {
            return;
        }
        boolean sendPets = false;
        switch (message) {
            case QuickslotProtocol.Hello ignored -> sendPets = true;
            case QuickslotProtocol.RequestPets ignored -> sendPets = true;
            case QuickslotProtocol.Assign assign -> {
                if (assign.slot() >= 0 && assign.slot() < slots.size()) {
                    if (!assign.petId().isEmpty()) {
                        slots.replaceAll(existing -> existing.equals(assign.petId()) ? "" : existing);
                    }
                    slots.set(assign.slot(), assign.petId());
                }
            }
            case QuickslotProtocol.Switch target -> {
                switchRequests++;
                if (target.slot() >= 0 && target.slot() < slots.size() && !slots.get(target.slot()).isEmpty()) {
                    final String petId = slots.get(target.slot());
                    active = petId.equals(active) ? "" : petId;
                }
            }
            case QuickslotProtocol.Cycle step -> {
                switchRequests++;
                final int current = slots.indexOf(active);
                for (int i = 1; i <= slots.size(); i++) {
                    final String candidate = slots.get(Math.floorMod(current + step.direction() * i, slots.size()));
                    if (!candidate.isEmpty()) {
                        active = candidate;
                        break;
                    }
                }
            }
            case QuickslotProtocol.Despawn ignored -> {
                switchRequests++;
                active = "";
            }
        }
        if (sendPets) {
            send(player, new QuickslotProtocol.Pets(pets.hashCode(), pets));
        }
        sendState(player);
    }

    private static void sendState(final ServerPlayer player) {
        send(player, new QuickslotProtocol.State(QuickslotProtocol.VERSION, true, slots, active, COOLDOWN_MILLIS, true, pets.hashCode(), lockoutMillis));
    }

    private static void send(final ServerPlayer player, final QuickslotProtocol.ServerMessage message) {
        ServerPlayNetworking.send(player, new QuickslotPayload(QuickslotProtocol.encode(message)));
    }
}
