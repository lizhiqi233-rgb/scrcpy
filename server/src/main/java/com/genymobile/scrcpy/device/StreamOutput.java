package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.model.Codec;

import android.media.MediaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Output sink for encoded media packets (socket or WebSocket).
 */
public interface StreamOutput {

    Codec getCodec();

    void writeAudioHeader() throws IOException;

    void writePacket(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) throws IOException;

    void writeDisableStream(boolean error) throws IOException;
}
