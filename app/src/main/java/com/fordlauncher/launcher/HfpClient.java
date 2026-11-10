package com.fordlauncher.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Puente HFP (Hands-Free Profile) para FordLauncher en el SoC AC8227L_demo.
 *
 * <p>En estas pantallas la llamada por Bluetooth <b>no</b> se hace con broadcasts
 * genéricos tipo {@code com.autochips.bluetooth.action.DIAL} (esos no los escucha
 * nadie en esta firmware). La vía real es <b>BluetoothHeadsetClient</b>:
 * {@code com.android.bluetooth.headsetclient.HeadsetClientService} en
 * {@code dumpsys bluetooth_manager} como {@code HeadsetClientService}, que es
 * exactamente el que usa la app de fábrica {@code com.autochips.bluetooth}
 * (BTSuite) vía {@code HeadsetClientProfile.dial(device, number)}.
 *
 * <p>{@code BluetoothHeadsetClient} es <b>API oculta</b> (no está en el SDK
 * público), por eso <b>todo</b> el acceso a esa clase se hace por reflexión
 * ({@code Class.forName("android.bluetooth.BluetoothHeadsetClient")}) para
 * compilar contra {@code compileSdk 35} sin depender de símbolos hidden.
 *
 * <p>También lee las acciones de HFP por su String literal
 * ({@code android.bluetooth.headsetclient.action.CALL_CHANGED}, etc.), porque
 * las constantes tampoco son visibles en tiempo de compilación.
 */
final class HfpClient {

    private static final String TAG = "HfpClient";

    /** Profile type de HeadsetClient (HFP HF role). {@link BluetoothProfile#HEADSET_CLIENT} es 16. */
    private static final int PROFILE_HEADSET_CLIENT = 16;

    private static final String CLS = "android.bluetooth.BluetoothHeadsetClient";
    private static final String ACT_CONN = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACT_CALL = "android.bluetooth.headsetclient.profile.action.CALL_CHANGED";
    private static final String ACT_AUDIO = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED";
    private static final String ACT_RESULT = "android.bluetooth.headsetclient.profile.action.RESULT";

    public interface CallListener {
        void onDialResult(boolean success, String detail);
        void onConnectionChanged(String deviceName);
        void onCallChanged(String state, String number);
    }

    private final Context appCtx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;

    private Object headsetClient;
    private boolean proxyRequested;
    private BluetoothDevice connectedDevice;
    private String connectedName;
    private CallListener listener;
    private BroadcastReceiver receiver;

    HfpClient(Context ctx) {
        appCtx = ctx.getApplicationContext();
        BluetoothAdapter a = null;
        try { a = BluetoothAdapter.getDefaultAdapter(); } catch (Throwable ignored) {}
        adapter = a;
    }

    void setListener(CallListener l) { listener = l; }

    boolean isBluetoothOn() {
        try { return adapter != null && adapter.isEnabled(); } catch (Throwable t) { return false; }
    }

    BluetoothDevice getConnectedDevice() { return connectedDevice; }
    String getConnectedName() { return connectedName; }

    synchronized void requestProxy() {
        if (adapter == null) {
            notifyDial(false, "Bluetooth no disponible en esta pantalla");
            return;
        }
        if (Build.VERSION.SDK_INT < 25) {
            notifyDial(false, "HFP requiere Android 7.0+");
            return;
        }
        if (proxyRequested) return;
        proxyRequested = true;
        try {
            if (Class.forName(CLS) == null) {
                proxyRequested = false;
                notifyDial(false, "HFP no soportado en este firmware");
                return;
            }
        } catch (Throwable t) {
            proxyRequested = false;
            notifyDial(false, "HFP no soportado en este firmware");
            return;
        }
        try {
            BluetoothProfile.ServiceListener sl = new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == PROFILE_HEADSET_CLIENT) {
                        headsetClient = proxy;
                        Log.i(TAG, "HFP proxy conectado: " + proxy.getClass().getName());
                        refreshConnectedDevice();
                        registerReceiver();
                        if (listener != null && connectedName != null) {
                            final String n = connectedName;
                            ui.post(new Runnable() { public void run() { listener.onConnectionChanged(n); } });
                        } else if (listener != null) {
                            ui.post(new Runnable() { public void run() { listener.onConnectionChanged(null); } });
                        }
                    }
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == PROFILE_HEADSET_CLIENT) {
                        headsetClient = null;
                        connectedDevice = null;
                        connectedName = null;
                        Log.i(TAG, "HFP proxy desconectado");
                    }
                }
            };
            boolean ok = adapter.getProfileProxy(appCtx, sl, PROFILE_HEADSET_CLIENT);
            Log.i(TAG, "getProfileProxy HEADSET_CLIENT -> " + ok);
            if (!ok) {
                proxyRequested = false;
                notifyDial(false, "No se pudo obtener el perfil HFP");
            }
        } catch (Throwable t) {
            Log.w(TAG, "getProfileProxy falló", t);
            notifyDial(false, "Error al obtener HFP: " + t.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void refreshConnectedDevice() {
        if (headsetClient == null) return;
        try {
            Object client = headsetClient;
            List devices = (List) client.getClass().getMethod("getConnectedDevices").invoke(client);
            if (devices != null && !devices.isEmpty()) {
                BluetoothDevice d = (BluetoothDevice) devices.get(0);
                connectedDevice = d;
                String name = null;
                try { name = d.getName(); } catch (Throwable ignored) {}
                if (name == null) {
                    try { name = d.getAlias(); } catch (Throwable ignored2) {}
                }
                if (name == null) name = "Celular Bluetooth";
                connectedName = name;
                Log.i(TAG, "HFP conectado: " + name + " [" + d.getAddress() + "]");
            } else {
                connectedDevice = null;
                connectedName = null;
                Log.i(TAG, "HFP sin dispositivos conectados (getConnectedDevices vacío)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshConnectedDevice falló", t);
        }
    }

    void dial(final String number) {
        if (number == null || number.trim().length() == 0) {
            notifyDial(false, "Número vacío");
            return;
        }
        if (Build.VERSION.SDK_INT < 25) {
            notifyDial(false, "HFP no disponible (Android < 7.0)");
            return;
        }
        if (headsetClient == null) {
            proxyRequested = false;
            requestProxy();
            notifyDial(false, "Conectando perfil HFP… vuelve a tocar Llamar");
            return;
        }
        refreshConnectedDevice();
        if (connectedDevice == null) {
            notifyDial(false, "Celular no conectado por Bluetooth HFP. Conéctalo desde la app Bluetooth de fábrica y vuelve a intentar.");
            return;
        }
        final BluetoothDevice dev = connectedDevice;
        final String num = number.trim();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object client = headsetClient;
                    Object call = client.getClass()
                            .getMethod("dial", BluetoothDevice.class, String.class)
                            .invoke(client, dev, num);
                    boolean ok = call != null;
                    Log.i(TAG, "dial(" + num + ") -> " + (ok ? "OK" : "NULL"));
                    final boolean success = ok;
                    final String detail = ok ? "Llamando a " + num : "HFP no aceptó el número. Revisa que el celular esté conectado (pantalla Bluetooth).";
                    ui.post(new Runnable() { @Override public void run() {
                        if (listener != null) listener.onDialResult(success, detail);
                    }});
                } catch (Throwable t) {
                    Log.w(TAG, "dial falló", t);
                    final String detail = "Error HFP: " + t.getMessage();
                    ui.post(new Runnable() { @Override public void run() {
                        if (listener != null) listener.onDialResult(false, detail);
                    }});
                }
            }
        }, "hfp-dial").start();
    }

    void rejectCall() { headsetCmd("rejectCall"); }
    void acceptCall() { headsetCmd("acceptCall"); }
    void acceptCall(int flag) { headsetCmd("acceptCall", flag); }
    /** Igual que BtUtils.doRejectCall de BTSuite: rechaza entrantes y termina marcando/sonando/activa. */
    void hangUp() {
        if (headsetClient == null || connectedDevice == null) return;
        final BluetoothDevice dev = connectedDevice;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object client = headsetClient;
                    List calls = (List) client.getClass().getMethod("getCurrentCalls", BluetoothDevice.class).invoke(client, dev);
                    Object toTerminate = null;
                    if (calls != null) {
                        for (Object c : calls) {
                            int st = (Integer) c.getClass().getMethod("getState").invoke(c);
                            if (st == 4 || st == 5) { toTerminate = null; break; }
                            if (st == 0 || st == 1 || st == 2 || st == 3) toTerminate = c;
                        }
                    }
                    if (toTerminate != null) {
                        client.getClass().getMethod("terminateCall", BluetoothDevice.class, toTerminate.getClass())
                                .invoke(client, dev, toTerminate);
                        Log.i(TAG, "terminateCall OK");
                    } else {
                        client.getClass().getMethod("rejectCall", BluetoothDevice.class).invoke(client, dev);
                        Log.i(TAG, "rejectCall OK");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "hangUp falló", t);
                }
            }
        }, "hfp-hangup").start();
    }

    private void headsetCmd(final String method) { headsetCmd(method, 0); }
    private void headsetCmd(final String method, final int flag) {
        if (headsetClient == null || connectedDevice == null) return;
        final BluetoothDevice dev = connectedDevice;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object client = headsetClient;
                    if ("acceptCall".equals(method)) {
                        try {
                            client.getClass().getMethod("acceptCall", BluetoothDevice.class, int.class).invoke(client, dev, flag);
                        } catch (NoSuchMethodException e) {
                            client.getClass().getMethod("acceptCall", BluetoothDevice.class).invoke(client, dev);
                        }
                    } else if ("rejectCall".equals(method)) {
                        client.getClass().getMethod("rejectCall", BluetoothDevice.class).invoke(client, dev);
                    }
                    Log.i(TAG, method + " OK");
                } catch (Throwable t) {
                    Log.w(TAG, method + " falló", t);
                }
            }
        }, "hfp-cmd").start();
    }

    void sendDtmf(char c) {
        if (headsetClient == null || connectedDevice == null) return;
        final BluetoothDevice dev = connectedDevice;
        final byte code = (byte) c;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    headsetClient.getClass()
                            .getMethod("sendDTMF", BluetoothDevice.class, byte.class)
                            .invoke(headsetClient, dev, code);
                    Log.i(TAG, "sendDTMF " + (char) code + " OK");
                } catch (Throwable t) {
                    Log.w(TAG, "sendDTMF falló", t);
                }
            }
        }, "hfp-dtmf").start();
    }

    /** 0 desconectado (teléfono), 1 conectando, 2 conectado (radio/coche). */
    int getAudioState() {
        if (headsetClient == null || connectedDevice == null) return -1;
        try {
            Object s = headsetClient.getClass()
                    .getMethod("getAudioState", BluetoothDevice.class)
                    .invoke(headsetClient, connectedDevice);
            if (s instanceof Integer) return (Integer) s;
        } catch (Throwable t) {
            Log.w(TAG, "getAudioState falló", t);
        }
        return -1;
    }

    /** Hay alguna llamada en curso según HFP (útil cuando el otro cuelga y no llega el estado). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean hasActiveCall() {
        if (headsetClient == null || connectedDevice == null) return false;
        try {
            List calls = (List) headsetClient.getClass()
                    .getMethod("getCurrentCalls", BluetoothDevice.class)
                    .invoke(headsetClient, connectedDevice);
            if (calls == null || calls.isEmpty()) return false;
            for (Object c : calls) {
                try {
                    int st = (Integer) c.getClass().getMethod("getState").invoke(c);
                    if (st != 7) return true; // 7 = terminada
                } catch (Throwable ignored) { return true; }
            }
            return false;
        } catch (Throwable t) {
            return true; // ante duda, no cerrar
        }
    }

    /** Alterna el audio de la llamada entre el radio/coche y el teléfono (SCO HFP). */
    void toggleAudioRoute() {
        if (headsetClient == null || connectedDevice == null) return;
        final BluetoothDevice dev = connectedDevice;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object client = headsetClient;
                    int st = -1;
                    try {
                        Object s = client.getClass().getMethod("getAudioState", BluetoothDevice.class).invoke(client, dev);
                        if (s instanceof Integer) st = (Integer) s;
                    } catch (Throwable ignored) {}
                    if (st == 2) {
                        client.getClass().getMethod("disconnectAudio", BluetoothDevice.class).invoke(client, dev);
                        Log.i(TAG, "disconnectAudio (al teléfono) OK");
                    } else if (st == 0 || st == -1) {
                        client.getClass().getMethod("connectAudio", BluetoothDevice.class).invoke(client, dev);
                        Log.i(TAG, "connectAudio (al radio) OK");
                    } else {
                        Log.i(TAG, "audio en transición: " + st);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "toggleAudioRoute falló", t);
                }
            }
        }, "hfp-audio").start();
    }

    private void registerReceiver() {
        if (receiver != null) return;
        if (Build.VERSION.SDK_INT < 25) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    String action = i.getAction();
                    if (action == null) return;
                    if (ACT_CONN.equals(action)) {
                        refreshConnectedDevice();
                        if (listener != null) {
                            final String n = connectedName;
                            ui.post(new Runnable() { @Override public void run() { listener.onConnectionChanged(n); } });
                        }
                    } else if (ACT_CALL.equals(action)) {
                        String state = "";
                        String num = "";
                        // El CALL llega como Parcelable BluetoothHeadsetClientCall (hidden); se lee por reflexión.
                        try {
                            Object call = i.getParcelableExtra("android.bluetooth.headsetclient.extra.CALL");
                            if (call != null) {
                                try {
                                    Object st = call.getClass().getMethod("getState").invoke(call);
                                    state = String.valueOf(st);
                                } catch (Throwable ignored) {}
                                try {
                                    Object nb = call.getClass().getMethod("getNumber").invoke(call);
                                    num = nb != null ? String.valueOf(nb) : "";
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                        if (state.length() == 0) {
                            try { state = i.getStringExtra("android.bluetooth.headsetclient.extra.CALL_STATE"); } catch (Throwable ignored) {}
                            if (state == null) state = "";
                        }
                        // Si no hay llamada o terminó, notificar fin para cerrar la tarjeta.
                        if (state.length() == 0) {
                            boolean active = false;
                            try {
                                List calls = (List) headsetClient.getClass().getMethod("getCurrentCalls", BluetoothDevice.class).invoke(headsetClient, connectedDevice);
                                active = calls != null && !calls.isEmpty();
                            } catch (Throwable ignored) {}
                            if (!active) state = "7";
                        }
                        if (num.length() == 0) {
                            try { num = i.getStringExtra("android.bluetooth.headsetclient.extra.CALL_NUMBER"); } catch (Throwable ignored) {}
                        }
                        if (listener != null) {
                            final String s2 = state, n2 = num;
                            ui.post(new Runnable() { @Override public void run() { listener.onCallChanged(s2, n2); } });
                        }
                        refreshConnectedDevice();
                    }
                } catch (Throwable ignored) {}
            }
        };
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACT_CONN);
            f.addAction(ACT_CALL);
            f.addAction(ACT_RESULT);
            // AUDIO_STATE_CHANGED solo existe desde API 28; lo intentamos sin romper en 27
            try { f.addAction(ACT_AUDIO); } catch (Throwable ignored) {}
            appCtx.registerReceiver(receiver, f);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver falló", t);
        }
    }

    void close() {
        try { if (receiver != null) appCtx.unregisterReceiver(receiver); } catch (Throwable ignored) {}
        receiver = null;
        try {
            if (adapter != null && headsetClient instanceof BluetoothProfile) {
                adapter.closeProfileProxy(PROFILE_HEADSET_CLIENT, (BluetoothProfile) headsetClient);
            }
        } catch (Throwable ignored) {}
        headsetClient = null;
        proxyRequested = false;
    }

    private void notifyDial(final boolean ok, final String detail) {
        Log.i(TAG, "dial result ok=" + ok + " detail=" + detail);
        if (listener != null) {
            ui.post(new Runnable() { @Override public void run() { listener.onDialResult(ok, detail); } });
        }
    }

    static List<String> bondedNames(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null && a.getBondedDevices() != null) {
                for (BluetoothDevice d : a.getBondedDevices()) {
                    String n = null;
                    try { n = d.getName(); } catch (Throwable ignored) {}
                    if (n == null) n = d.getAlias();
                    if (n == null) n = d.getAddress();
                    out.add(n);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
