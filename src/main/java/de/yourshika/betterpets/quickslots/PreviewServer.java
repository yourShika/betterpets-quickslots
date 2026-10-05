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
 * to the bare minimum (no cooldowns, no ownership checks). Only ever registered in preview mode.</p>
 */
final class PreviewServer {

    private static List<QuickslotProtocol.Pet> pets = List.of();
    private static final List<String> slots = new ArrayList<>();
    private static String active = "";

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
                if (target.slot() >= 0 && target.slot() < slots.size() && !slots.get(target.slot()).isEmpty()) {
                    final String petId = slots.get(target.slot());
                    active = petId.equals(active) ? "" : petId;
                }
            }
            case QuickslotProtocol.Cycle step -> {
                final int current = slots.indexOf(active);
                for (int i = 1; i <= slots.size(); i++) {
                    final String candidate = slots.get(Math.floorMod(current + step.direction() * i, slots.size()));
                    if (!candidate.isEmpty()) {
                        active = candidate;
                        break;
                    }
                }
            }
            case QuickslotProtocol.Despawn ignored -> active = "";
        }
        if (sendPets) {
            send(player, new QuickslotProtocol.Pets(pets.hashCode(), pets));
        }
        send(player, new QuickslotProtocol.State(QuickslotProtocol.VERSION, true, slots, active, 500, true, pets.hashCode()));
    }

    private static void send(final ServerPlayer player, final QuickslotProtocol.ServerMessage message) {
        ServerPlayNetworking.send(player, new QuickslotPayload(QuickslotProtocol.encode(message)));
    }
}
