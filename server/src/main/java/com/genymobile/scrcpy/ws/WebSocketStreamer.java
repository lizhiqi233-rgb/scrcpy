package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.device.StreamOutput;
import com.genymobile.scrcpy.model.Codec;

import android.media.MediaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Sends encoded audio over the ws-scrcpy WebSocket protocol.
 *
 * <p>Packet types (14-byte magic prefix, same length as {@code scrcpy_initial}):
 * <ul>
 *   <li>{@code scrcpy_audiobg} + codec id (4 bytes) or disable code (4 bytes)</li>
 *   <li>{@code scrcpy_audiodt} + 12-byte frame header + payload</li>
 * </ul>
 */
public final class WebSocketStreamer implements StreamOutput {

    public static final byte[] MAGIC_BYTES_AUDIO_BEGIN = "scrcpy_audiobg".getBytes(StandardCharsets.UTF_8);
    public static final byte[] MAGIC_BYTES_AUDIO_DATA = "scrcpy_audiodt".getBytes(StandardCharsets.UTF_8);

    private static final long PACKET_FLAG_CONFIG = 1L << 62;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 61;

    private final Connection connection;
    private final AudioCodec codec;
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    public WebSocketStreamer(Connection connection, AudioCodec codec) {
        this.connection = connection;
        this.codec = codec;
    }

    @Override
    public Codec getCodec() {
        return codec;
    }

    @Override
    public void writeAudioHeader() throws IOException {
        if (!connection.hasConnections()) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC_BYTES_AUDIO_BEGIN.length + 4);
        buffer.put(MAGIC_BYTES_AUDIO_BEGIN);
        buffer.putInt(codec.getId());
        buffer.flip();
        connection.send(buffer);
    }

    @Override
    public void writeDisableStream(boolean error) throws IOException {
        if (!connection.hasConnections()) {
            return;
        }
        byte[] code = new byte[4];
        if (error) {
            code[3] = 1;
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC_BYTES_AUDIO_BEGIN.length + 4);
        buffer.put(MAGIC_BYTES_AUDIO_BEGIN);
        buffer.put(code);
        buffer.flip();
        connection.send(buffer);
    }

    @Override
    public void writePacket(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        if (!connection.hasConnections()) {
            return;
        }

        long pts = bufferInfo.presentationTimeUs;
        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

        if (config) {
            if (codec == AudioCodec.OPUS) {
                fixOpusConfigPacket(buffer);
            } else if (codec == AudioCodec.FLAC) {
                fixFlacConfigPacket(buffer);
            }
        }

        int packetSize = buffer.remaining();
        headerBuffer.clear();

        long ptsAndFlags;
        if (config) {
            ptsAndFlags = PACKET_FLAG_CONFIG;
        } else {
            ptsAndFlags = pts;
            if (keyFrame) {
                ptsAndFlags |= PACKET_FLAG_KEY_FRAME;
            }
        }

        headerBuffer.putLong(ptsAndFlags);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();

        ByteBuffer packet = ByteBuffer.allocate(MAGIC_BYTES_AUDIO_DATA.length + 12 + packetSize);
        packet.put(MAGIC_BYTES_AUDIO_DATA);
        packet.put(headerBuffer);
        int position = buffer.position();
        packet.put(buffer);
        buffer.position(position);
        packet.flip();
        connection.send(packet);
    }

    private static void fixOpusConfigPacket(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < 16) {
            throw new IOException("Not enough data in OPUS config packet");
        }

        final byte[] opusHeaderId = {'A', 'O', 'P', 'U', 'S', 'H', 'D', 'R'};
        byte[] idBuffer = new byte[8];
        buffer.get(idBuffer);
        if (!Arrays.equals(idBuffer, opusHeaderId)) {
            throw new IOException("OPUS header not found");
        }

        long sizeLong = buffer.getLong();
        if (sizeLong < 0 || sizeLong >= 0x7FFFFFFF) {
            throw new IOException("Invalid block size in OPUS header: " + sizeLong);
        }

        int size = (int) sizeLong;
        if (buffer.remaining() < size) {
            throw new IOException("Not enough data in OPUS header (invalid size: " + size + ")");
        }

        buffer.limit(buffer.position() + size);
    }

    private static void fixFlacConfigPacket(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < 8) {
            throw new IOException("Not enough data in FLAC config packet");
        }

        final byte[] flacHeaderId = {'f', 'L', 'a', 'C'};
        byte[] idBuffer = new byte[4];
        buffer.get(idBuffer);
        if (!Arrays.equals(idBuffer, flacHeaderId)) {
            throw new IOException("FLAC header not found");
        }

        buffer.order(ByteOrder.BIG_ENDIAN);

        int size = buffer.getInt();
        if (buffer.remaining() < size) {
            throw new IOException("Not enough data in FLAC header (invalid size: " + size + ")");
        }

        buffer.limit(buffer.position() + size);
    }
}
