package com.fordlauncher.launcher.obd;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

/** Puertos físicos del ELM327, portados de Links.kt de LiteDash. */
public final class Links {
    private Links() {}
    public static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static class BluetoothLink implements ElmLink {
        private final BluetoothSocket socket;
        public BluetoothLink(BluetoothDevice device) throws IOException {
            BluetoothSocket s;
            try {
                s = device.createRfcommSocketToServiceRecord(SPP);
                s.connect();
            } catch (Exception e) {
                try { s = device.createInsecureRfcommSocketToServiceRecord(SPP); s.connect(); }
                catch (Exception e2) { throw new IOException(e2.getMessage()); }
            }
            socket = s;
        }
        public InputStream getInput() throws IOException { return socket.getInputStream(); }
        public OutputStream getOutput() throws IOException { return socket.getOutputStream(); }
        public void close() throws IOException { socket.close(); }
    }

    public static class WifiLink implements ElmLink {
        private final Socket socket;
        public WifiLink(String host, int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
        }
        public InputStream getInput() throws IOException { return socket.getInputStream(); }
        public OutputStream getOutput() throws IOException { return socket.getOutputStream(); }
        public void close() throws IOException { socket.close(); }
    }
}
