package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.display.DisplayMonitor;
import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ClipboardManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.content.IOnPrimaryClipChangedListener;
import android.graphics.Rect;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Device {

    public static final int POWER_MODE_OFF = com.genymobile.scrcpy.device.Device.POWER_MODE_OFF;
    public static final int POWER_MODE_NORMAL = com.genymobile.scrcpy.device.Device.POWER_MODE_NORMAL;

    public static final int LOCK_VIDEO_ORIENTATION_UNLOCKED = -1;
    public static final int LOCK_VIDEO_ORIENTATION_INITIAL = -2;

    public interface RotationListener {
        void onRotationChanged(int rotation);
    }

    public interface ClipboardListener {
        void onClipboardTextChanged(String text);
    }

    private ScreenInfo screenInfo;
    private RotationListener rotationListener;
    private ClipboardListener clipboardListener;
    private final AtomicBoolean isSettingClipboard = new AtomicBoolean();
    private final int displayId;
    private final int layerStack;
    private final boolean supportsInputEvents;

    private final DisplayMonitor displayMonitor = new DisplayMonitor();
    private IOnPrimaryClipChangedListener clipChangedListener;

    public Device(com.genymobile.scrcpy.Options options, VideoSettings videoSettings) {
        displayId = videoSettings.getDisplayId();
        DisplayInfo displayInfo = getDisplayInfo(displayId);
        if (displayInfo == null) {
            throw new InvalidDisplayIdException(displayId, getDisplayIds());
        }

        int displayInfoFlags = displayInfo.getFlags();
        screenInfo = ScreenInfo.computeScreenInfo(displayInfo, videoSettings);
        layerStack = displayInfo.getLayerStack();

        displayMonitor.start(displayId, props -> {
            synchronized (Device.this) {
                applyNewVideoSetting(videoSettings);
                if (rotationListener != null && props != null) {
                    rotationListener.onRotationChanged(props.getRotation());
                }
            }
        });

        if (options.getControl()) {
            ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
            clipChangedListener = new IOnPrimaryClipChangedListener.Stub() {
                @Override
                public void dispatchPrimaryClipChanged() {
                    if (isSettingClipboard.get()) {
                        return;
                    }
                    synchronized (Device.this) {
                        if (clipboardListener != null) {
                            String text = com.genymobile.scrcpy.device.Device.getClipboardText();
                            if (text != null) {
                                clipboardListener.onClipboardTextChanged(text);
                            }
                        }
                    }
                }
            };
            if (clipboardManager != null) {
                clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
            } else {
                Ln.w("No clipboard manager, copy-paste between device and computer will not work");
            }
        }

        if ((displayInfoFlags & DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) == 0) {
            Ln.w("Display doesn't have FLAG_SUPPORTS_PROTECTED_BUFFERS flag, mirroring can be restricted");
        }

        supportsInputEvents = com.genymobile.scrcpy.device.Device.supportsInputEvents(displayId);
        if (!supportsInputEvents) {
            Ln.w("Input events are not supported for secondary displays before Android 10");
        }
    }

    public int getDisplayId() {
        return displayId;
    }

    public synchronized ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public int getLayerStack() {
        return layerStack;
    }

    public void applyNewVideoSetting(VideoSettings videoSettings) {
        setScreenInfo(ScreenInfo.computeScreenInfo(getDisplayInfo(displayId), videoSettings));
    }

    public synchronized void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public Point getPhysicalPoint(Position position) {
        ScreenInfo screenInfo = getScreenInfo();
        Size unlockedVideoSize = screenInfo.getUnlockedVideoSize();
        int reverseVideoRotation = screenInfo.getReverseVideoRotation();
        Position devicePosition = position.rotate(reverseVideoRotation);
        Size clientVideoSize = devicePosition.getScreenSize();
        if (!unlockedVideoSize.equals(clientVideoSize)) {
            return null;
        }
        Rect contentRect = screenInfo.getContentRect();
        Point point = devicePosition.getPoint();
        int convertedX = contentRect.left + point.getX() * contentRect.width() / unlockedVideoSize.getWidth();
        int convertedY = contentRect.top + point.getY() * contentRect.height() / unlockedVideoSize.getHeight();
        return new Point(convertedX, convertedY);
    }

    public static String getDeviceName() {
        return com.genymobile.scrcpy.device.Device.getDeviceName();
    }

    public boolean supportsInputEvents() {
        return supportsInputEvents;
    }

    public boolean injectEvent(InputEvent event) {
        return com.genymobile.scrcpy.device.Device.injectEvent(event, displayId, com.genymobile.scrcpy.device.Device.INJECT_MODE_ASYNC);
    }

    public boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState) {
        return com.genymobile.scrcpy.device.Device.injectKeyEvent(action, keyCode, repeat, metaState, displayId,
                com.genymobile.scrcpy.device.Device.INJECT_MODE_ASYNC);
    }

    public boolean pressReleaseKeycode(int keyCode) {
        return com.genymobile.scrcpy.device.Device.pressReleaseKeycode(keyCode, displayId, com.genymobile.scrcpy.device.Device.INJECT_MODE_ASYNC);
    }

    public static boolean isScreenOn(int displayId) {
        return com.genymobile.scrcpy.device.Device.isScreenOn(displayId);
    }

    public synchronized void setRotationListener(RotationListener rotationListener) {
        this.rotationListener = rotationListener;
    }

    public synchronized void setClipboardListener(ClipboardListener clipboardListener) {
        this.clipboardListener = clipboardListener;
    }

    public boolean setClipboardText(String text) {
        return com.genymobile.scrcpy.device.Device.setClipboardText(text);
    }

    public void release() {
        displayMonitor.stopAndRelease();
        if (clipChangedListener != null) {
            ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
            if (clipboardManager != null) {
                clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
            }
            clipChangedListener = null;
        }
        rotationListener = null;
        clipboardListener = null;
    }

    public static boolean setScreenPowerMode(int displayId, int mode) {
        return com.genymobile.scrcpy.device.Device.setDisplayPower(displayId, mode == POWER_MODE_NORMAL);
    }

    public static void rotateDevice(int displayId) {
        com.genymobile.scrcpy.device.Device.rotateDevice(displayId);
    }

    public static int[] getDisplayIds() {
        return ServiceManager.getDisplayManager().getDisplayIds();
    }

    public static DisplayInfo getDisplayInfo(int displayId) {
        com.genymobile.scrcpy.display.DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (info == null) {
            return null;
        }
        return DisplayInfo.from(info);
    }
}
