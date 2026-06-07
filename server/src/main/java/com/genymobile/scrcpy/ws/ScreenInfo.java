package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;

import android.graphics.Rect;

import java.nio.ByteBuffer;

public final class ScreenInfo {
    private final Rect contentRect;
    private final Size unlockedVideoSize;
    private final int deviceRotation;
    private final int lockedVideoOrientation;

    public ScreenInfo(Rect contentRect, Size unlockedVideoSize, int deviceRotation, int lockedVideoOrientation) {
        this.contentRect = contentRect;
        this.unlockedVideoSize = unlockedVideoSize;
        this.deviceRotation = deviceRotation;
        this.lockedVideoOrientation = lockedVideoOrientation;
    }

    public Rect getContentRect() {
        return contentRect;
    }

    public Size getUnlockedVideoSize() {
        return unlockedVideoSize;
    }

    public Size getVideoSize() {
        if (getVideoRotation() % 2 == 0) {
            return unlockedVideoSize;
        }
        return unlockedVideoSize.rotate();
    }

    public int getDeviceRotation() {
        return deviceRotation;
    }

    public ScreenInfo withDeviceRotation(int newDeviceRotation) {
        if (newDeviceRotation == deviceRotation) {
            return this;
        }
        boolean orientationChanged = (deviceRotation + newDeviceRotation) % 2 != 0;
        Rect newContentRect;
        Size newUnlockedVideoSize;
        if (orientationChanged) {
            newContentRect = flipRect(contentRect);
            newUnlockedVideoSize = unlockedVideoSize.rotate();
        } else {
            newContentRect = contentRect;
            newUnlockedVideoSize = unlockedVideoSize;
        }
        return new ScreenInfo(newContentRect, newUnlockedVideoSize, newDeviceRotation, lockedVideoOrientation);
    }

    public static ScreenInfo computeScreenInfo(DisplayInfo displayInfo, VideoSettings videoSettings) {
        int lockedVideoOrientation = videoSettings.getLockedVideoOrientation();
        Rect crop = videoSettings.getCrop();
        int rotation = displayInfo.getRotation();

        if (lockedVideoOrientation == Device.LOCK_VIDEO_ORIENTATION_INITIAL) {
            lockedVideoOrientation = rotation;
        }

        Size deviceSize = displayInfo.getSize();
        Rect contentRect = new Rect(0, 0, deviceSize.getWidth(), deviceSize.getHeight());
        if (crop != null) {
            if (rotation % 2 != 0) {
                crop = flipRect(crop);
            }
            if (!contentRect.intersect(crop)) {
                Ln.w("Crop rectangle does not intersect device screen");
                contentRect = new Rect();
            }
        }

        Size bounds = videoSettings.getBounds();
        Size videoSize = computeVideoSize(contentRect.width(), contentRect.height(), bounds);
        return new ScreenInfo(contentRect, videoSize, rotation, lockedVideoOrientation);
    }

    private static Size computeVideoSize(int w, int h, Size bounds) {
        if (bounds == null) {
            w &= ~15;
            h &= ~15;
            return new Size(w, h);
        }
        int boundsWidth = bounds.getWidth();
        int boundsHeight = bounds.getHeight();
        int scaledHeight;
        int scaledWidth;
        if (boundsWidth > w) {
            scaledHeight = h;
        } else {
            scaledHeight = boundsWidth * h / w;
        }
        if (boundsHeight > scaledHeight) {
            boundsHeight = scaledHeight;
        }
        if (boundsHeight == h) {
            scaledWidth = w;
        } else {
            scaledWidth = boundsHeight * w / h;
        }
        if (boundsWidth > scaledWidth) {
            boundsWidth = scaledWidth;
        }
        boundsWidth &= ~15;
        boundsHeight &= ~15;
        return new Size(boundsWidth, boundsHeight);
    }

    private static Rect flipRect(Rect crop) {
        return new Rect(crop.top, crop.left, crop.bottom, crop.right);
    }

    public int getVideoRotation() {
        if (lockedVideoOrientation == -1) {
            return 0;
        }
        return (deviceRotation + 4 - lockedVideoOrientation) % 4;
    }

    public int getReverseVideoRotation() {
        if (lockedVideoOrientation == -1) {
            return 0;
        }
        return (lockedVideoOrientation + 4 - deviceRotation) % 4;
    }

    public byte[] toByteArray() {
        ByteBuffer temp = ByteBuffer.allocate(6 * 4 + 1);
        temp.putInt(contentRect.left);
        temp.putInt(contentRect.top);
        temp.putInt(contentRect.right);
        temp.putInt(contentRect.bottom);
        temp.putInt(unlockedVideoSize.getWidth());
        temp.putInt(unlockedVideoSize.getHeight());
        temp.put((byte) getVideoRotation());
        return temp.array();
    }
}
