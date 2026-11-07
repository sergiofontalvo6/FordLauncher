package com.fordlauncher.launcher.obd;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/** Canal con el adaptador: Bluetooth, Wi-Fi o USB. El protocolo es el mismo. */
public interface ElmLink extends Closeable {
    InputStream getInput() throws java.io.IOException;
    OutputStream getOutput() throws java.io.IOException;
}
