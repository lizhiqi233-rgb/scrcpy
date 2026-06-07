package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodecInfo;

import org.java_websocket.WebSocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class WebSocketConnection extends Connection {
    private static final byte[] MAGIC_BYTES_INITIAL = "scrcpy_initial".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MAGIC_BYTES_MESSAGE = "scrcpy_message".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DEVICE_NAME_BYTES = Device.getDeviceName().getBytes(StandardCharsets.UTF_8);
    private final WSServer wsServer;
    private final HashSet<WebSocket> sockets = new HashSet<>();
    private ScreenEncoder screenEncoder;

    public WebSocketConnection(Options options, VideoSettings videoSettings, WSServer wsServer) {
        super(options, videoSettings);
        this.wsServer = wsServer;
    }

    public void join(WebSocket webSocket, VideoSettings videoSettings) {
        sockets.add(webSocket);
        boolean changed = setVideoSettings(videoSettings);
        wsServer.sendInitialInfoToAll();
        if (!Device.isScreenOn(videoSettings.getDisplayId())) {
            controller.turnScreenOn();
        }
        if (screenEncoder == null || !screenEncoder.isAlive()) {
            Ln.d("First connection. Start new encoder.");
            device.setRotationListener(this);
            screenEncoder = new ScreenEncoder(videoSettings);
            screenEncoder.start(device, this);
        } else if (!changed && streamInvalidateListener != null) {
            streamInvalidateListener.onStreamInvalidate();
        }
    }

    public void leave(WebSocket webSocket) {
        sockets.remove(webSocket);
        if (sockets.isEmpty()) {
            Ln.d("Last client has left");
            release();
        }
        wsServer.sendInitialInfoToAll();
    }

    public static ByteBuffer deviceMessageToByteBuffer(DeviceMessage msg) {
        ByteBuffer buffer = ByteBuffer.wrap(msg.writeToByteArray(MAGIC_BYTES_MESSAGE.length));
        buffer.put(MAGIC_BYTES_MESSAGE);
        buffer.rewind();
        return buffer;
    }

    @Override
    void send(ByteBuffer data) {
        if (sockets.isEmpty()) {
            return;
        }
        synchronized (sockets) {
            for (WebSocket webSocket : sockets) {
                WSServer.SocketInfo info = webSocket.getAttachment();
                if (!webSocket.isOpen() || info == null) {
                    continue;
                }
                webSocket.send(data);
            }
        }
    }

    public static void sendInitialInfo(ByteBuffer initialInfo, WebSocket webSocket, int clientId) {
        initialInfo.position(initialInfo.capacity() - 4);
        initialInfo.putInt(clientId);
        initialInfo.rewind();
        webSocket.send(initialInfo);
    }

    @Override
    void sendDeviceMessage(DeviceMessage msg) throws IOException {
        send(deviceMessageToByteBuffer(msg));
    }

    @Override
    public boolean hasConnections() {
        return !sockets.isEmpty();
    }

    @Override
    public void close() {
        // WebSocket server lifecycle is managed by WSServer
    }

    public VideoSettings getVideoSettings() {
        return videoSettings;
    }

    public Controller getController() {
        return controller;
    }

    public Device getDevice() {
        return device;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    public static ByteBuffer getInitialInfo() {
        int baseLength = MAGIC_BYTES_INITIAL.length + DEVICE_NAME_FIELD_LENGTH + 4 + 4;
        int additionalLength = 0;
        int[] displayIds = Device.getDisplayIds();
        HashMap<Integer, DisplayInfo> displayInfoHashMap = new HashMap<>();
        HashMap<Integer, Integer> connectionsCount = new HashMap<>();
        HashMap<Integer, byte[]> displayInfoMap = new HashMap<>();
        HashMap<Integer, byte[]> videoSettingsBytesMap = new HashMap<>();
        HashMap<Integer, byte[]> screenInfoBytesMap = new HashMap<>();

        for (int displayId : displayIds) {
            DisplayInfo displayInfo = Device.getDisplayInfo(displayId);
            if (displayInfo == null) {
                continue;
            }
            displayInfoHashMap.put(displayId, displayInfo);
            byte[] displayInfoBytes = displayInfo.toByteArray();
            additionalLength += displayInfoBytes.length;
            displayInfoMap.put(displayId, displayInfoBytes);
            WebSocketConnection connection = WSServer.getConnectionForDisplay(displayId);
            additionalLength += 12;
            if (connection != null) {
                connectionsCount.put(displayId, connection.sockets.size());
                byte[] screenInfoBytes = connection.getDevice().getScreenInfo().toByteArray();
                additionalLength += screenInfoBytes.length;
                screenInfoBytesMap.put(displayId, screenInfoBytes);
                byte[] videoSettingsBytes = connection.getVideoSettings().toByteArray();
                additionalLength += videoSettingsBytes.length;
                videoSettingsBytesMap.put(displayId, videoSettingsBytes);
            }
        }

        MediaCodecInfo[] encoders = ScreenEncoder.listEncoders();
        List<byte[]> encodersNames = new ArrayList<>();
        if (encoders != null && encoders.length > 0) {
            additionalLength += 4;
            for (MediaCodecInfo encoder : encoders) {
                byte[] nameBytes = encoder.getName().getBytes(StandardCharsets.UTF_8);
                additionalLength += 4 + nameBytes.length;
                encodersNames.add(nameBytes);
            }
        }

        byte[] fullBytes = new byte[baseLength + additionalLength];
        ByteBuffer initialInfo = ByteBuffer.wrap(fullBytes);
        initialInfo.put(MAGIC_BYTES_INITIAL);
        initialInfo.put(DEVICE_NAME_BYTES, 0, Math.min(DEVICE_NAME_FIELD_LENGTH - 1, DEVICE_NAME_BYTES.length));
        initialInfo.position(MAGIC_BYTES_INITIAL.length + DEVICE_NAME_FIELD_LENGTH);
        initialInfo.putInt(displayIds.length);
        for (DisplayInfo displayInfo : displayInfoHashMap.values()) {
            int displayId = displayInfo.getDisplayId();
            initialInfo.put(displayInfoMap.get(displayId));
            int count = connectionsCount.containsKey(displayId) ? connectionsCount.get(displayId) : 0;
            initialInfo.putInt(count);
            if (screenInfoBytesMap.containsKey(displayId)) {
                byte[] screenInfo = screenInfoBytesMap.get(displayId);
                initialInfo.putInt(screenInfo.length);
                initialInfo.put(screenInfo);
            } else {
                initialInfo.putInt(0);
            }
            if (videoSettingsBytesMap.containsKey(displayId)) {
                byte[] settingsBytes = videoSettingsBytesMap.get(displayId);
                initialInfo.putInt(settingsBytes.length);
                initialInfo.put(settingsBytes);
            } else {
                initialInfo.putInt(0);
            }
        }
        initialInfo.putInt(encodersNames.size());
        for (byte[] encoderNameBytes : encodersNames) {
            initialInfo.putInt(encoderNameBytes.length);
            initialInfo.put(encoderNameBytes);
        }

        return initialInfo;
    }

    @Override
    public void onRotationChanged(int rotation) {
        super.onRotationChanged(rotation);
        wsServer.sendInitialInfoToAll();
    }

    private void release() {
        WSServer.releaseConnectionForDisplay(videoSettings.getDisplayId());
    }
}
