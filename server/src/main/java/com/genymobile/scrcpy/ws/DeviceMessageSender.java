package com.genymobile.scrcpy.ws;

import java.io.IOException;

public final class DeviceMessageSender {

    private final Connection connection;
    private String clipboardText;

    public DeviceMessageSender(Connection connection) {
        this.connection = connection;
    }

    public synchronized void pushClipboardText(String text) {
        clipboardText = text;
        notify();
    }

    public void loop() throws IOException, InterruptedException {
        while (true) {
            String text;
            synchronized (this) {
                while (clipboardText == null) {
                    wait();
                }
                text = clipboardText;
                clipboardText = null;
            }
            connection.sendDeviceMessage(DeviceMessage.createClipboard(text));
        }
    }
}
