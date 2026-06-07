package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public abstract class DeviceMessage {
    private static final int MESSAGE_MAX_SIZE = 1 << 18;
    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_PUSH_RESPONSE = 101;

    private final int type;

    private DeviceMessage(int type) {
        this.type = type;
    }

    private static final class ClipboardMessage extends DeviceMessage {
        private static final int CLIPBOARD_TEXT_MAX_LENGTH = MESSAGE_MAX_SIZE - 5;
        private final byte[] raw;
        private final int len;

        private ClipboardMessage(String text) {
            super(TYPE_CLIPBOARD);
            raw = text.getBytes(StandardCharsets.UTF_8);
            len = StringUtils.getUtf8TruncationIndex(raw, CLIPBOARD_TEXT_MAX_LENGTH);
        }

        @Override
        public void writeToByteArray(byte[] array, int offset) {
            ByteBuffer buffer = ByteBuffer.wrap(array, offset, array.length - offset);
            buffer.put((byte) getType());
            buffer.putInt(len);
            buffer.put(raw, 0, len);
        }

        @Override
        public int getLen() {
            return 5 + len;
        }
    }

    private static final class FilePushResponseMessage extends DeviceMessage {
        private final short id;
        private final int result;

        private FilePushResponseMessage(short id, int result) {
            super(TYPE_PUSH_RESPONSE);
            this.id = id;
            this.result = result;
        }

        @Override
        public void writeToByteArray(byte[] array, int offset) {
            ByteBuffer buffer = ByteBuffer.wrap(array, offset, array.length - offset);
            buffer.put((byte) getType());
            buffer.putShort(id);
            buffer.put((byte) result);
        }

        @Override
        public int getLen() {
            return 4;
        }
    }

    public static DeviceMessage createClipboard(String text) {
        return new ClipboardMessage(text);
    }

    public static DeviceMessage createPushResponse(short id, int result) {
        return new FilePushResponseMessage(id, result);
    }

    public int getType() {
        return type;
    }

    public byte[] writeToByteArray(int offset) {
        byte[] temp = new byte[offset + getLen()];
        writeToByteArray(temp, offset);
        return temp;
    }

    public abstract void writeToByteArray(byte[] array, int offset);

    public abstract int getLen();
}
