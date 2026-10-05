package de.kamil.betterpets.quickslots;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format of the {@code betterpets:quickslots} plugin-message channel, spoken between the Better Pets
 * Paper plugin and the "Better Pets Quickslots" Fabric mod.
 *
 * <p>This file is deliberately dependency-free (plain {@code java.io}) and is kept byte-identical in both
 * projects, so the two sides can never drift apart: neither Bukkit nor Minecraft types appear here. Every
 * message is one opcode byte followed by its fields, written with {@link DataOutputStream}.</p>
 *
 * <p>Compatibility rules: fields are only ever appended; a reader ignores trailing bytes it does not know
 * and falls back to a default for appended fields the peer did not send; unknown opcodes decode to
 * {@code null} (ignored). So an older mod keeps working against a newer plugin and vice versa.</p>
 */
public final class QuickslotProtocol {

    /** Plugin-message channel id (namespace:path). */
    public static final String CHANNEL = "betterpets:quickslots";
    public static final String CHANNEL_NAMESPACE = "betterpets";
    public static final String CHANNEL_PATH = "quickslots";
    /** Bumped only for changes an older peer could not read. */
    public static final int VERSION = 1;
    /** Hard upper bound of quickslots (one per hotbar-style key). */
    public static final int MAX_SLOTS = 9;

    // --- client -> server opcodes ---
    private static final int C_HELLO = 1;
    private static final int C_REQUEST_PETS = 2;
    private static final int C_ASSIGN = 3;
    private static final int C_SWITCH = 4;
    private static final int C_CYCLE = 5;
    private static final int C_DESPAWN = 6;

    // --- server -> client opcodes ---
    private static final int S_STATE = 1;
    private static final int S_PETS = 2;
    private static final int S_OPEN_SCREEN = 3;
    private static final int S_MENU_OPENED = 4;

    private static final int MAX_PETS = 512;
    private static final int MAX_STRING = 16_000;

    private QuickslotProtocol() {
    }

    // ------------------------------------------------------------------------------------------------
    // Client -> server
    // ------------------------------------------------------------------------------------------------

    /** A message sent by the mod. */
    public sealed interface ClientMessage permits Hello, RequestPets, Assign, Switch, Cycle, Despawn {
    }

    /** First message after joining: announces the mod and the protocol version it speaks. */
    public record Hello(int version) implements ClientMessage {
    }

    /** Asks for the full pet list (sent when the cached list is missing or out of date). */
    public record RequestPets() implements ClientMessage {
    }

    /** Puts a pet (by definition id) into a slot; an empty {@code petId} clears the slot. */
    public record Assign(int slot, String petId) implements ClientMessage {
    }

    /** Summons the pet in the given slot (0-based). */
    public record Switch(int slot) implements ClientMessage {
    }

    /** Steps to the next ({@code +1}) or previous ({@code -1}) filled slot. */
    public record Cycle(int direction) implements ClientMessage {
    }

    /** Puts the active pet away. */
    public record Despawn() implements ClientMessage {
    }

    public static byte[] encode(final ClientMessage message) {
        return write(out -> {
            switch (message) {
                case Hello hello -> {
                    out.writeByte(C_HELLO);
                    out.writeInt(hello.version());
                }
                case RequestPets ignored -> out.writeByte(C_REQUEST_PETS);
                case Assign assign -> {
                    out.writeByte(C_ASSIGN);
                    out.writeByte(assign.slot());
                    out.writeUTF(clip(assign.petId()));
                }
                case Switch sw -> {
                    out.writeByte(C_SWITCH);
                    out.writeByte(sw.slot());
                }
                case Cycle cycle -> {
                    out.writeByte(C_CYCLE);
                    out.writeByte(cycle.direction() < 0 ? -1 : 1);
                }
                case Despawn ignored -> out.writeByte(C_DESPAWN);
            }
        });
    }

    /**
     * Decodes a message from the mod.
     *
     * @return the message, or {@code null} for an opcode this version does not know
     * @throws IOException if the payload is truncated or malformed
     */
    public static ClientMessage decodeClient(final byte[] data) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        final int opcode = in.readUnsignedByte();
        return switch (opcode) {
            case C_HELLO -> new Hello(in.readInt());
            case C_REQUEST_PETS -> new RequestPets();
            case C_ASSIGN -> new Assign(in.readByte(), in.readUTF());
            case C_SWITCH -> new Switch(in.readByte());
            case C_CYCLE -> new Cycle(in.readByte() < 0 ? -1 : 1);
            case C_DESPAWN -> new Despawn();
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------
    // Server -> client
    // ------------------------------------------------------------------------------------------------

    /** A message sent by the plugin. */
    public sealed interface ServerMessage permits State, Pets, OpenScreen, MenuOpened {
    }

    /**
     * The player's quickslot state. Small and sent often.
     *
     * @param version          protocol version of the plugin
     * @param enabled          whether the player may use quickslots through the mod at all
     * @param slots            one pet definition id per slot ("" = empty); the size is the slot count
     * @param activeId         definition id of the summoned pet ("" = none)
     * @param cooldownMillis   minimum time between two switches
     * @param sameSlotDespawns whether pressing the active pet's slot again puts the pet away
     * @param petsRevision     changes whenever the pet list ({@link Pets}) would look different
     * @param lockoutMillis    how long quick switching stays locked from now on because of too many
     *                         switches (0 = not locked). Appended in plugin 1.33; older plugins do not
     *                         send it and it then reads as 0.
     */
    public record State(int version, boolean enabled, List<String> slots, String activeId, int cooldownMillis,
                        boolean sameSlotDespawns, int petsRevision, int lockoutMillis) implements ServerMessage {
        public State {
            slots = List.copyOf(slots);
        }
    }

    /** Every pet the player owns, with what the mod needs to draw it. */
    public record Pets(int revision, List<Pet> pets) implements ServerMessage {
        public Pets {
            pets = List.copyOf(pets);
        }
    }

    /** Tells the mod to open its quickslot screen (e.g. after {@code /pets quick}). */
    public record OpenScreen() implements ServerMessage {
    }

    /** Sent right before the plugin opens its main chest menu, so the mod can add its button to it. */
    public record MenuOpened() implements ServerMessage {
    }

    /**
     * One owned pet.
     *
     * @param id       definition id (what slots refer to), e.g. {@code penguin}
     * @param name     display name (the nickname if the player set one)
     * @param typeName the pet type's name, e.g. {@code Penguin}
     * @param rarity   rarity label, e.g. {@code Legendary}
     * @param color    rarity colour as 0xRRGGBB
     * @param disabled true if the server turned this pet type off (owned, but cannot be summoned)
     * @param texture  base64 "textures" profile property of the pet's head
     * @param ability  one-line ability summary ("" if none)
     */
    public record Pet(String id, String name, String typeName, String rarity, int color, int level, int stars,
                      boolean disabled, String texture, String ability) {
    }

    public static byte[] encode(final ServerMessage message) {
        return write(out -> {
            switch (message) {
                case State state -> {
                    out.writeByte(S_STATE);
                    out.writeInt(state.version());
                    out.writeBoolean(state.enabled());
                    final int count = Math.min(MAX_SLOTS, state.slots().size());
                    out.writeByte(count);
                    for (int i = 0; i < count; i++) {
                        out.writeUTF(clip(state.slots().get(i)));
                    }
                    out.writeUTF(clip(state.activeId()));
                    out.writeInt(state.cooldownMillis());
                    out.writeBoolean(state.sameSlotDespawns());
                    out.writeInt(state.petsRevision());
                    out.writeInt(state.lockoutMillis());
                }
                case Pets pets -> {
                    out.writeByte(S_PETS);
                    out.writeInt(pets.revision());
                    final int count = Math.min(MAX_PETS, pets.pets().size());
                    out.writeShort(count);
                    for (int i = 0; i < count; i++) {
                        final Pet pet = pets.pets().get(i);
                        out.writeUTF(clip(pet.id()));
                        out.writeUTF(clip(pet.name()));
                        out.writeUTF(clip(pet.typeName()));
                        out.writeUTF(clip(pet.rarity()));
                        out.writeInt(pet.color());
                        out.writeShort(pet.level());
                        out.writeByte(pet.stars());
                        out.writeBoolean(pet.disabled());
                        out.writeUTF(clip(pet.texture()));
                        out.writeUTF(clip(pet.ability()));
                    }
                }
                case OpenScreen ignored -> out.writeByte(S_OPEN_SCREEN);
                case MenuOpened ignored -> out.writeByte(S_MENU_OPENED);
            }
        });
    }

    /**
     * Decodes a message from the plugin.
     *
     * @return the message, or {@code null} for an opcode this version does not know
     * @throws IOException if the payload is truncated or malformed
     */
    public static ServerMessage decodeServer(final byte[] data) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        final int opcode = in.readUnsignedByte();
        switch (opcode) {
            case S_STATE -> {
                final int version = in.readInt();
                final boolean enabled = in.readBoolean();
                final int count = in.readUnsignedByte();
                if (count > MAX_SLOTS) {
                    throw new IOException("slot count " + count + " exceeds " + MAX_SLOTS);
                }
                final List<String> slots = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    slots.add(in.readUTF());
                }
                final String activeId = in.readUTF();
                final int cooldownMillis = in.readInt();
                final boolean sameSlotDespawns = in.readBoolean();
                final int petsRevision = in.readInt();
                // Fields appended later are optional: a peer from before they existed simply ends here.
                final int lockoutMillis = in.available() >= Integer.BYTES ? in.readInt() : 0;
                return new State(version, enabled, slots, activeId, cooldownMillis, sameSlotDespawns, petsRevision, lockoutMillis);
            }
            case S_PETS -> {
                final int revision = in.readInt();
                final int count = in.readUnsignedShort();
                if (count > MAX_PETS) {
                    throw new IOException("pet count " + count + " exceeds " + MAX_PETS);
                }
                final List<Pet> pets = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    pets.add(new Pet(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readInt(),
                        in.readUnsignedShort(), in.readUnsignedByte(), in.readBoolean(), in.readUTF(), in.readUTF()));
                }
                return new Pets(revision, pets);
            }
            case S_OPEN_SCREEN -> {
                return new OpenScreen();
            }
            case S_MENU_OPENED -> {
                return new MenuOpened();
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(final Writer writer) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (final IOException impossible) {
            // A ByteArrayOutputStream never fails, and clip() keeps every string inside writeUTF's limit.
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    /** Null-safe, and short enough that {@link DataOutputStream#writeUTF} can never overflow (65535 bytes). */
    private static String clip(final String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_STRING ? value : value.substring(0, MAX_STRING);
    }
}
