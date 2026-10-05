package de.yourshika.betterpets.quickslots;

import de.kamil.betterpets.quickslots.QuickslotProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The {@code betterpets:quickslots} custom payload, in both directions. It deliberately carries nothing
 * but the raw bytes: the server is a Bukkit plugin, which only ever sees a byte array on a plugin-message
 * channel, so all framing lives in {@link QuickslotProtocol} - the one file both sides share.
 */
public record QuickslotPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<QuickslotPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(QuickslotProtocol.CHANNEL_NAMESPACE, QuickslotProtocol.CHANNEL_PATH));

    public static final StreamCodec<FriendlyByteBuf, QuickslotPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeBytes(payload.data()),
        buffer -> {
            // The payload is the rest of the packet; the game rejects packets that leave bytes unread.
            final byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return new QuickslotPayload(data);
        });

    @Override
    public Type<QuickslotPayload> type() {
        return TYPE;
    }
}
