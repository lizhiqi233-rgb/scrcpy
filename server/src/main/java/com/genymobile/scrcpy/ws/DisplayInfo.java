package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.model.Size;

import java.nio.ByteBuffer;

public final class DisplayInfo {
    private final int displayId;
    private final Size size;
    private final int rotation;
    private final int layerStack;
    private final int flags;

    public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 0x00000001;

    public DisplayInfo(int displayId, Size size, int rotation, int layerStack, int flags) {
        this.displayId = displayId;
        this.size = size;
        this.rotation = rotation;
        this.layerStack = layerStack;
        this.flags = flags;
    }

    public static DisplayInfo from(com.genymobile.scrcpy.display.DisplayInfo info) {
        return new DisplayInfo(
                info.getDisplayId(),
                info.getSize(),
                info.getRotation(),
                info.getLayerStack(),
                info.getFlags());
    }

    public int getDisplayId() {
        return displayId;
    }

    public Size getSize() {
        return size;
    }

    public int getRotation() {
        return rotation;
    }

    public int getLayerStack() {
        return layerStack;
    }

    public int getFlags() {
        return flags;
    }

    public byte[] toByteArray() {
        ByteBuffer temp = ByteBuffer.allocate(24);
        temp.putInt(displayId);
        temp.putInt(size.getWidth());
        temp.putInt(size.getHeight());
        temp.putInt(rotation);
        temp.putInt(layerStack);
        temp.putInt(flags);
        temp.rewind();
        return temp.array();
    }
}
