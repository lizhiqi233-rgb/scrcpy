package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.util.Ln;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ControlMessageReader {

    static final int INJECT_KEYCODE_PAYLOAD_LENGTH = 13;
    static final int INJECT_TOUCH_EVENT_PAYLOAD_LENGTH = 27;
    static final int INJECT_SCROLL_EVENT_PAYLOAD_LENGTH = 20;
    static final int BACK_OR_SCREEN_ON_LENGTH = 1;
    static final int SET_SCREEN_POWER_MODE_PAYLOAD_LENGTH = 1;
    static final int SET_CLIPBOARD_FIXED_PAYLOAD_LENGTH = 1;

    private static final int MESSAGE_MAX_SIZE = 1 << 18;

    private final byte[] rawBuffer = new byte[MESSAGE_MAX_SIZE];
    private final ByteBuffer buffer = ByteBuffer.wrap(rawBuffer);

    public ControlMessageReader() {
        buffer.limit(0);
    }

    public ControlMessage parseEvent(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return null;
        }
        int savedPosition = buffer.position();

        int type = buffer.get();
        ControlMessage msg;
        switch (type) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                msg = parseInjectKeycode(buffer);
                break;
            case ControlMessage.TYPE_INJECT_TEXT:
                msg = parseInjectText(buffer);
                break;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                msg = parseInjectTouchEvent(buffer);
                break;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                msg = parseInjectScrollEvent(buffer);
                break;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                msg = parseBackOrScreenOnEvent(buffer);
                break;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                msg = parseSetClipboard(buffer);
                break;
            case ControlMessage.TYPE_SET_SCREEN_POWER_MODE:
                msg = parseSetScreenPowerMode(buffer);
                break;
            case ControlMessage.TYPE_CHANGE_STREAM_PARAMETERS:
                msg = parseChangeStreamParameters(buffer);
                break;
            case ControlMessage.TYPE_PUSH_FILE:
                msg = parsePushFile(buffer);
                break;
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL:
            case ControlMessage.TYPE_COLLAPSE_PANELS:
            case ControlMessage.TYPE_GET_CLIPBOARD:
            case ControlMessage.TYPE_ROTATE_DEVICE:
                msg = ControlMessage.createEmpty(type);
                break;
            default:
                Ln.w("Unknown event type: " + type);
                msg = null;
                break;
        }

        if (msg == null) {
            buffer.position(savedPosition);
        }
        return msg;
    }

    private ControlMessage parseChangeStreamParameters(ByteBuffer buffer) {
        int re = buffer.remaining();
        byte[] bytes = new byte[re];
        if (re > 0) {
            buffer.get(bytes, 0, re);
        }
        return ControlMessage.createChangeSteamParameters(bytes);
    }

    private ControlMessage parsePushFile(ByteBuffer buffer) {
        int re = buffer.remaining();
        byte[] bytes = new byte[re];
        if (re > 0) {
            buffer.get(bytes, 0, re);
        }
        return ControlMessage.createFilePush(bytes);
    }

    private ControlMessage parseInjectKeycode(ByteBuffer buffer) {
        if (buffer.remaining() < INJECT_KEYCODE_PAYLOAD_LENGTH) {
            return null;
        }
        int action = toUnsigned(buffer.get());
        int keycode = buffer.getInt();
        int repeat = buffer.getInt();
        int metaState = buffer.getInt();
        return ControlMessage.createInjectKeycode(action, keycode, repeat, metaState);
    }

    private String parseString(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return null;
        }
        int len = buffer.getInt();
        if (buffer.remaining() < len) {
            return null;
        }
        buffer.get(rawBuffer, 0, len);
        return new String(rawBuffer, 0, len, StandardCharsets.UTF_8);
    }

    private ControlMessage parseInjectText(ByteBuffer buffer) {
        String text = parseString(buffer);
        if (text == null) {
            return null;
        }
        return ControlMessage.createInjectText(text);
    }

    private ControlMessage parseInjectTouchEvent(ByteBuffer buffer) {
        if (buffer.remaining() < INJECT_TOUCH_EVENT_PAYLOAD_LENGTH) {
            return null;
        }
        int action = toUnsigned(buffer.get());
        long pointerId = buffer.getLong();
        Position position = readPosition(buffer);
        int pressureInt = toUnsigned(buffer.getShort());
        float pressure = pressureInt == 0xffff ? 1f : (pressureInt / 0x1p16f);
        int buttons = buffer.getInt();
        return ControlMessage.createInjectTouchEvent(action, pointerId, position, pressure, buttons);
    }

    private ControlMessage parseInjectScrollEvent(ByteBuffer buffer) {
        if (buffer.remaining() < INJECT_SCROLL_EVENT_PAYLOAD_LENGTH) {
            return null;
        }
        Position position = readPosition(buffer);
        int hScroll = buffer.getInt();
        int vScroll = buffer.getInt();
        return ControlMessage.createInjectScrollEvent(position, hScroll, vScroll);
    }

    private ControlMessage parseBackOrScreenOnEvent(ByteBuffer buffer) {
        if (buffer.remaining() < BACK_OR_SCREEN_ON_LENGTH) {
            return null;
        }
        int action = toUnsigned(buffer.get());
        return ControlMessage.createBackOrScreenOn(action);
    }

    private ControlMessage parseSetClipboard(ByteBuffer buffer) {
        if (buffer.remaining() < SET_CLIPBOARD_FIXED_PAYLOAD_LENGTH) {
            return null;
        }
        boolean paste = buffer.get() != 0;
        String text = parseString(buffer);
        if (text == null) {
            return null;
        }
        return ControlMessage.createSetClipboard(text, paste);
    }

    private ControlMessage parseSetScreenPowerMode(ByteBuffer buffer) {
        if (buffer.remaining() < SET_SCREEN_POWER_MODE_PAYLOAD_LENGTH) {
            return null;
        }
        int mode = buffer.get();
        return ControlMessage.createSetScreenPowerMode(mode);
    }

    private static Position readPosition(ByteBuffer buffer) {
        int x = buffer.getInt();
        int y = buffer.getInt();
        int screenWidth = toUnsigned(buffer.getShort());
        int screenHeight = toUnsigned(buffer.getShort());
        return new Position(x, y, screenWidth, screenHeight);
    }

    private static int toUnsigned(short value) {
        return value & 0xffff;
    }

    private static int toUnsigned(byte value) {
        return value & 0xff;
    }
}
