package org.mateof24.sce.net;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.sce.SimpleCraftEditor;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries this mod's channels over the one thing 26.2 still offers: a typed payload.
 *
 * <p>Architectury 21 removed the API that let a mod send a buffer under an id of its own choosing.
 * What is left is the typed route — a {@code CustomPacketPayload} class per message, with its own
 * {@code StreamCodec}. Turning fourteen channels into fourteen payload classes would mean rewriting
 * every one of them, and the writing and reading in {@link SceNetworking} is exactly the part that has
 * been right since 1.20.1.
 *
 * <p>So there are two payloads, one per direction, and each carries a channel id and the bytes of a
 * buffer. The channels are dispatched here rather than by the network layer, and everything above this
 * class keeps writing and reading buffers as it always did. The same shape ConditionalVideos settled
 * on for the same break.
 *
 * <p>The buffer handed to a receiver is registry-aware, built with the registries of the connection the
 * bytes arrived on, because the editor sends item stacks and a stack cannot be read without them.
 */
public final class SceRawNetwork {
    /** What a channel's receiver looks like: the same shape the removed API used, so the callers stand. */
    @FunctionalInterface
    public interface Receiver {
        void receive(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext context);
    }

    private static final Map<Identifier, Receiver> TO_SERVER = new HashMap<>();
    private static final Map<Identifier, Receiver> TO_CLIENT = new HashMap<>();

    private SceRawNetwork() {
    }

    /** A buffer's worth of bytes, addressed to one of this mod's channels. */
    public record Raw(Identifier channel, byte[] data) {
        static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> codec(
                java.util.function.Function<Raw, T> wrap, java.util.function.Function<T, Raw> unwrap) {
            return StreamCodec.of(
                    (buf, payload) -> {
                        Raw raw = unwrap.apply(payload);
                        buf.writeIdentifier(raw.channel());
                        buf.writeByteArray(raw.data());
                    },
                    buf -> wrap.apply(new Raw(buf.readIdentifier(), buf.readByteArray())));
        }
    }

    /** Server to client. */
    public record ToClient(Raw raw) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(channelId("raw_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToClient> CODEC =
                Raw.codec(ToClient::new, ToClient::raw);

        @Override
        public Type<ToClient> type() {
            return TYPE;
        }
    }

    /** Client to server. */
    public record ToServer(Raw raw) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(channelId("raw_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToServer> CODEC =
                Raw.codec(ToServer::new, ToServer::raw);

        @Override
        public Type<ToServer> type() {
            return TYPE;
        }
    }

    private static Identifier channelId(String path) {
        return Identifier.fromNamespaceAndPath(SimpleCraftEditor.MOD_ID, path);
    }

    // ------------------------------------------------------------------ registration

    /**
     * Takes the messages coming from clients. Called from the common entrypoint, on both sides: a
     * listening server needs it, and a client hosting a world is that server.
     */
    public static void registerServerSide() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ToServer.TYPE, ToServer.CODEC,
                (payload, context) -> dispatch(TO_SERVER, payload.raw(), context));
    }

    /**
     * Takes the messages coming from the server, and declares the payload that carries them.
     *
     * <p>Registering a receiver declares its payload type as a side effect, which is why the two
     * directions are declared from different places — see {@link #declareToClient()}.
     */
    public static void registerClientSide() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ToClient.TYPE, ToClient.CODEC,
                (payload, context) -> dispatch(TO_CLIENT, payload.raw(), context));
    }

    /**
     * Declares the server-to-client payload where nothing else will.
     *
     * <p>A payload type has to be known to the side <em>sending</em> it as much as to the side reading
     * it. On a client the receiver above already declares it, and declaring it twice makes NeoForge
     * refuse the duplicate and the game not start; a dedicated server never runs the client entrypoint,
     * so there it would otherwise never exist and the login sync would drop the player. So: declare
     * here exactly when there is no receiver to do it.
     */
    public static void declareToClient() {
        NetworkManager.registerS2CPayloadType(ToClient.TYPE, ToClient.CODEC);
    }

    /** Registers what handles one channel's messages from clients. */
    public static void toServer(Identifier channel, Receiver receiver) {
        TO_SERVER.put(channel, receiver);
    }

    /** Registers what handles one channel's messages from the server. */
    public static void toClient(Identifier channel, Receiver receiver) {
        TO_CLIENT.put(channel, receiver);
    }

    private static void dispatch(Map<Identifier, Receiver> receivers, Raw raw,
                                 NetworkManager.PacketContext context) {
        Receiver receiver = receivers.get(raw.channel());
        if (receiver == null) {
            // A channel this build does not know: a mismatched pair of versions, not a fault to throw on.
            SimpleCraftEditor.LOGGER.warn("Nothing here reads '{}'", raw.channel());
            return;
        }
        RegistryFriendlyByteBuf buf =
                new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(raw.data()), context.registryAccess());
        receiver.receive(buf, context);
    }

    // ------------------------------------------------------------------ sending

    public static void sendToPlayer(ServerPlayer player, Identifier channel, RegistryFriendlyByteBuf buf) {
        NetworkManager.sendToPlayer(player, new ToClient(new Raw(channel, drain(buf))));
    }

    public static void sendToServer(Identifier channel, RegistryFriendlyByteBuf buf) {
        NetworkManager.sendToServer(new ToServer(new Raw(channel, drain(buf))));
    }

    /** Everything written into a buffer, as bytes, and the buffer let go of. */
    private static byte[] drain(RegistryFriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        return data;
    }
}
