package com.genymobile.scrcpy.ws;

import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.model.CodecOption;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenEncoder implements Connection.StreamInvalidateListener, Runnable {

    private static final int REPEAT_FRAME_DELAY_US = 100_000;
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";
    private static final int NO_PTS = -1;

    private final AtomicBoolean streamIsInvalid = new AtomicBoolean();
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);
    private Thread selectorThread;

    private long ptsOrigin;
    private Device device;
    private Connection connection;
    private VideoSettings videoSettings;
    private MediaFormat format;
    private int timeout = -1;

    private IBinder display;
    private VirtualDisplay virtualDisplay;

    public ScreenEncoder(VideoSettings videoSettings) {
        this.videoSettings = videoSettings;
        updateFormat();
    }

    private void updateFormat() {
        format = createFormat(videoSettings);
        int maxFps = videoSettings.getMaxFps();
        if (maxFps > 0) {
            timeout = 1_000_000 / maxFps;
        } else {
            timeout = -1;
        }
    }

    @Override
    public void onStreamInvalidate() {
        Ln.d("invalidate stream");
        streamIsInvalid.set(true);
        updateFormat();
    }

    public boolean consumeStreamInvalidation() {
        return streamIsInvalid.getAndSet(false);
    }

    public boolean isAlive() {
        return selectorThread != null && selectorThread.isAlive();
    }

    public void streamScreen() throws IOException {
        try {
            internalStreamScreen();
        } catch (NullPointerException e) {
            Ln.d("Applying workarounds to avoid NullPointerException");
            Workarounds.apply();
            internalStreamScreen();
        }
    }

    private void internalStreamScreen() throws IOException {
        updateFormat();
        connection.setStreamInvalidateListener(this);
        boolean alive;
        try {
            do {
                MediaCodec codec = createCodec(videoSettings.getEncoderName());
                if (display != null) {
                    destroyDisplay(display);
                    display = null;
                }
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }

                ScreenInfo screenInfo = device.getScreenInfo();
                Rect contentRect = screenInfo.getContentRect();
                Rect videoRect = screenInfo.getVideoSize().toRect();
                Rect unlockedVideoRect = screenInfo.getUnlockedVideoSize().toRect();
                int videoRotation = screenInfo.getVideoRotation();
                int layerStack = device.getLayerStack();

                setSize(format, videoRect.width(), videoRect.height());
                configure(codec, format);
                Surface surface = codec.createInputSurface();
                try {
                    virtualDisplay = ServiceManager.getDisplayManager()
                            .createVirtualDisplay("scrcpy", videoRect.width(), videoRect.height(), device.getDisplayId(), surface);
                    Ln.d("Display: using DisplayManager API");
                } catch (Exception displayManagerException) {
                    try {
                        display = createDisplay();
                        setDisplaySurface(display, surface, videoRotation, contentRect, unlockedVideoRect, layerStack);
                        Ln.d("Display: using SurfaceControl API");
                    } catch (Exception surfaceControlException) {
                        Ln.e("Could not create display using DisplayManager", displayManagerException);
                        Ln.e("Could not create display using SurfaceControl", surfaceControlException);
                        throw new AssertionError("Could not create display");
                    }
                }
                codec.start();
                try {
                    alive = encode(codec);
                    codec.stop();
                } finally {
                    if (display != null) {
                        destroyDisplay(display);
                        display = null;
                    }
                    if (virtualDisplay != null) {
                        virtualDisplay.release();
                        virtualDisplay = null;
                    }
                    codec.release();
                    surface.release();
                }
            } while (alive);
        } finally {
            connection.setStreamInvalidateListener(null);
        }
    }

    private boolean encode(MediaCodec codec) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (!consumeStreamInvalidation() && !eof && connection.hasConnections()) {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, timeout);
            eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeStreamInvalidation()) {
                    break;
                }
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                    if (videoSettings.getSendFrameMeta()) {
                        writeFrameMeta(bufferInfo, codecBuffer.remaining());
                    }
                    connection.send(codecBuffer);
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        return !eof && connection.hasConnections();
    }

    private void writeFrameMeta(MediaCodec.BufferInfo bufferInfo, int packetSize) {
        headerBuffer.clear();
        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = NO_PTS;
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
        }
        headerBuffer.putLong(pts);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        connection.send(headerBuffer);
    }

    public static MediaCodecInfo[] listEncoders() {
        List<MediaCodecInfo> result = new ArrayList<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo codecInfo : list.getCodecInfos()) {
            if (codecInfo.isEncoder() && Arrays.asList(codecInfo.getSupportedTypes()).contains(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                result.add(codecInfo);
            }
        }
        return result.toArray(new MediaCodecInfo[0]);
    }

    private static MediaCodec createCodec(String encoderName) throws IOException {
        if (encoderName != null) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                return MediaCodec.createByCodecName(encoderName);
            } catch (IllegalArgumentException e) {
                throw new InvalidEncoderException(encoderName, listEncoders());
            }
        }
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        Ln.d("Using encoder: '" + codec.getName() + "'");
        return codec;
    }

    private static void setCodecOption(MediaFormat format, CodecOption codecOption) {
        String key = codecOption.getKey();
        Object value = codecOption.getValue();
        if (value instanceof Integer) {
            format.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            format.setLong(key, (Long) value);
        } else if (value instanceof Float) {
            format.setFloat(key, (Float) value);
        } else if (value instanceof String) {
            format.setString(key, (String) value);
        }
    }

    private static MediaFormat createFormat(VideoSettings videoSettings) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoSettings.getBitRate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoSettings.getIFrameInterval());
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US);
        if (videoSettings.getMaxFps() > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, videoSettings.getMaxFps());
        }
        List<CodecOption> codecOptions = videoSettings.getCodecOptions();
        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                setCodecOption(format, option);
            }
        }
        return format;
    }

    private static IBinder createDisplay() throws Exception {
        boolean secure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void configure(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, int orientation, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static void destroyDisplay(IBinder display) {
        SurfaceControl.destroyDisplay(display);
    }

    @Override
    public void run() {
        synchronized (this) {
            if (selectorThread != null && selectorThread.isAlive()) {
                throw new IllegalStateException(getClass().getName() + " can only be started once.");
            }
            selectorThread = Thread.currentThread();
        }
        try {
            streamScreen();
        } catch (IOException e) {
            Ln.e("Failed to start screen recorder", e);
        }
    }

    public void start(Device device, Connection connection) {
        this.device = device;
        this.connection = connection;
        if (selectorThread != null && selectorThread.isAlive()) {
            throw new IllegalStateException(getClass().getName() + " can only be started once.");
        }
        new Thread(this).start();
    }
}
