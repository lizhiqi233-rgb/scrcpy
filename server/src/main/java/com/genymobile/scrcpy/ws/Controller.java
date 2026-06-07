package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.control.KeyComposition;
import com.genymobile.scrcpy.control.Pointer;
import com.genymobile.scrcpy.control.PointersState;
import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.util.Ln;

import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Controller {

    private static final int DEFAULT_DEVICE_ID = 0;
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private final Device device;
    private final Connection connection;
    private final DeviceMessageSender sender;
    private final KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private long lastTouchDown;
    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];
    private boolean keepPowerModeOff;

    public Controller(Device device, Connection connection) {
        this.device = device;
        this.connection = connection;
        initPointers();
        sender = new DeviceMessageSender(connection);
    }

    private void initPointers() {
        for (int i = 0; i < PointersState.MAX_POINTERS; ++i) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.orientation = 0;
            coords.size = 0;
            pointerProperties[i] = props;
            pointerCoords[i] = coords;
        }
    }

    public DeviceMessageSender getSender() {
        return sender;
    }

    public void handleEvent(ControlMessage msg) {
        switch (msg.getType()) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                if (device.supportsInputEvents()) {
                    injectKeycode(msg.getAction(), msg.getKeycode(), msg.getRepeat(), msg.getMetaState());
                }
                break;
            case ControlMessage.TYPE_INJECT_TEXT:
                if (device.supportsInputEvents()) {
                    injectText(msg.getText());
                }
                break;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                if (device.supportsInputEvents()) {
                    injectTouch(msg.getAction(), msg.getPointerId(), msg.getPosition(), msg.getPressure(), msg.getButtons());
                }
                break;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                if (device.supportsInputEvents()) {
                    injectScroll(msg.getPosition(), msg.getHScroll(), msg.getVScroll());
                }
                break;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                if (device.supportsInputEvents()) {
                    pressBackOrTurnScreenOn(msg.getAction());
                }
                break;
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
                com.genymobile.scrcpy.device.Device.expandNotificationPanel();
                break;
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL:
                com.genymobile.scrcpy.device.Device.expandSettingsPanel();
                break;
            case ControlMessage.TYPE_COLLAPSE_PANELS:
                com.genymobile.scrcpy.device.Device.collapsePanels();
                break;
            case ControlMessage.TYPE_GET_CLIPBOARD:
                String clipboardText = com.genymobile.scrcpy.device.Device.getClipboardText();
                if (clipboardText != null) {
                    try {
                        connection.sendDeviceMessage(DeviceMessage.createClipboard(clipboardText));
                    } catch (IOException e) {
                        Ln.w("Could not send clipboard to client");
                    }
                }
                break;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                setClipboard(msg.getText(), msg.getPaste());
                break;
            case ControlMessage.TYPE_SET_SCREEN_POWER_MODE:
                if (device.supportsInputEvents()) {
                    int mode = msg.getAction();
                    boolean setPowerModeOk = Device.setScreenPowerMode(device.getDisplayId(), mode);
                    if (setPowerModeOk) {
                        keepPowerModeOff = mode == Device.POWER_MODE_OFF;
                    }
                }
                break;
            case ControlMessage.TYPE_ROTATE_DEVICE:
                Device.rotateDevice(device.getDisplayId());
                break;
            default:
                break;
        }
    }

    private boolean injectKeycode(int action, int keycode, int repeat, int metaState) {
        if (keepPowerModeOff && action == KeyEvent.ACTION_UP && (keycode == KeyEvent.KEYCODE_POWER || keycode == KeyEvent.KEYCODE_WAKEUP)) {
            schedulePowerModeOff();
        }
        return device.injectKeyEvent(action, keycode, repeat, metaState);
    }

    private boolean injectChar(char c) {
        String decomposed = KeyComposition.decompose(c);
        char[] chars = decomposed != null ? decomposed.toCharArray() : new char[]{c};
        KeyEvent[] events = charMap.getEvents(chars);
        if (events == null) {
            return false;
        }
        for (KeyEvent event : events) {
            if (!device.injectEvent(event)) {
                return false;
            }
        }
        return true;
    }

    private int injectText(String text) {
        int successCount = 0;
        for (char c : text.toCharArray()) {
            if (!injectChar(c)) {
                Ln.w("Could not inject char u+" + String.format("%04x", (int) c));
                continue;
            }
            successCount++;
        }
        return successCount;
    }

    private boolean injectTouch(int action, long pointerId, Position position, float pressure, int buttons) {
        long now = SystemClock.uptimeMillis();
        Point point = device.getPhysicalPoint(position);
        if (point == null) {
            Ln.w("Ignore touch event, it was generated for a different device size");
            return false;
        }

        int pointerIndex = pointersState.getPointerIndex(pointerId);
        if (pointerIndex == -1) {
            Ln.w("Too many pointers for touch event");
            return false;
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(pressure);
        pointer.setUp(action == MotionEvent.ACTION_UP);

        int pointerCount = pointersState.update(pointerProperties, pointerCoords);
        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now;
            }
        } else {
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else if (action == MotionEvent.ACTION_DOWN) {
                action = MotionEvent.ACTION_POINTER_DOWN | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        }

        boolean nonPrimaryButtonPressed = (buttons & ~MotionEvent.BUTTON_PRIMARY) != 0;
        int source = nonPrimaryButtonPressed ? InputDevice.SOURCE_MOUSE : InputDevice.SOURCE_TOUCHSCREEN;
        if (source != InputDevice.SOURCE_MOUSE) {
            buttons = 0;
        }

        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, pointerCount, pointerProperties, pointerCoords, 0, buttons, 1f, 1f,
                DEFAULT_DEVICE_ID, 0, source, 0);
        return device.injectEvent(event);
    }

    private boolean injectScroll(Position position, int hScroll, int vScroll) {
        long now = SystemClock.uptimeMillis();
        Point point = device.getPhysicalPoint(position);
        if (point == null) {
            return false;
        }

        MotionEvent.PointerProperties props = pointerProperties[0];
        props.id = 0;
        MotionEvent.PointerCoords coords = pointerCoords[0];
        coords.x = point.getX();
        coords.y = point.getY();
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);

        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_SCROLL, 1, pointerProperties, pointerCoords, 0, 0, 1f, 1f,
                DEFAULT_DEVICE_ID, 0, InputDevice.SOURCE_MOUSE, 0);
        return device.injectEvent(event);
    }

    private void schedulePowerModeOff() {
        int displayId = device.getDisplayId();
        EXECUTOR.schedule(() -> Device.setScreenPowerMode(displayId, Device.POWER_MODE_OFF), 200, TimeUnit.MILLISECONDS);
    }

    private boolean pressBackOrTurnScreenOn(int action) {
        if (Device.isScreenOn(device.getDisplayId())) {
            return device.injectKeyEvent(action, KeyEvent.KEYCODE_BACK, 0, 0);
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (keepPowerModeOff) {
            schedulePowerModeOff();
        }
        return device.pressReleaseKeycode(KeyEvent.KEYCODE_POWER);
    }

    private boolean setClipboard(String text, boolean paste) {
        boolean ok = device.setClipboardText(text);
        if (ok) {
            Ln.i("Device clipboard set");
        }
        if (paste && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && device.supportsInputEvents()) {
            device.pressReleaseKeycode(KeyEvent.KEYCODE_PASTE);
        }
        return ok;
    }

    public void turnScreenOn() {
        device.pressReleaseKeycode(KeyEvent.KEYCODE_POWER);
    }
}
