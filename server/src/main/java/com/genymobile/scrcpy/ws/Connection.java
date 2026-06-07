package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.CleanUp;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.util.Ln;

import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class Connection implements Device.RotationListener, Device.ClipboardListener {
    public interface StreamInvalidateListener {
        void onStreamInvalidate();
    }

    protected static final int DEVICE_NAME_FIELD_LENGTH = 64;
    protected StreamInvalidateListener streamInvalidateListener;
    protected Device device;
    protected final VideoSettings videoSettings;
    protected final Options options;
    protected Controller controller;
    protected ScreenEncoder screenEncoder;
    protected CleanUp cleanUp;

    abstract void send(ByteBuffer data);

    abstract void sendDeviceMessage(DeviceMessage msg) throws IOException;

    abstract void close() throws Exception;

    abstract boolean hasConnections();

    public Connection(Options options, VideoSettings videoSettings) {
        Ln.i("Device: [" + Build.MANUFACTURER + "] " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        this.videoSettings = videoSettings;
        this.options = options;
        device = new Device(options, videoSettings);
        device.setRotationListener(this);
        controller = new Controller(device, this);
        startDeviceMessageSender(controller.getSender());
        device.setClipboardListener(this);

        if (options.getCleanup()) {
            cleanUp = CleanUp.start(options);
        }
    }

    public boolean setVideoSettings(VideoSettings newSettings) {
        if (!videoSettings.equals(newSettings)) {
            videoSettings.merge(newSettings);
            device.applyNewVideoSetting(videoSettings);
            if (streamInvalidateListener != null) {
                streamInvalidateListener.onStreamInvalidate();
            }
            return true;
        }
        return false;
    }

    public void setStreamInvalidateListener(StreamInvalidateListener listener) {
        streamInvalidateListener = listener;
    }

    @Override
    public void onRotationChanged(int rotation) {
        if (streamInvalidateListener != null) {
            streamInvalidateListener.onStreamInvalidate();
        }
    }

    @Override
    public void onClipboardTextChanged(String text) {
        controller.getSender().pushClipboardText(text);
    }

    private static void startDeviceMessageSender(final DeviceMessageSender sender) {
        new Thread(() -> {
            try {
                sender.loop();
            } catch (IOException | InterruptedException e) {
                Ln.d("Device message sender stopped");
            }
        }).start();
    }
}
