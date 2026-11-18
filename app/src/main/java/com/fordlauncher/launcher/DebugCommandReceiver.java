package com.fordlauncher.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

/**
 * Receptor de comandos para pruebas por ADB. Solo actúa en compilaciones debug
 * (FLAG_DEBUGGABLE); en release se ignora por completo.
 *
 * <p>Uso:
 * <pre>
 * adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es cmd "abre diagnóstico"
 * adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD \
 *     --es json '{"action":"OPEN_DIAGNOSTIC","query":"sensores","speech":"..."}'
 * </pre>
 */
public class DebugCommandReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.fordlauncher.launcher.DEBUG_CMD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            if ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        } catch (Throwable t) {
            return;
        }
        Main m = Main.instance();
        if (m == null) {
            android.util.Log.w("FordLauncher/VoiceCmd", "debug: launcher no está vivo");
            return;
        }
        String json = intent.getStringExtra("json");
        String cmd = intent.getStringExtra("cmd");
        String resolve = intent.getStringExtra("resolve");
        String fmt = intent.getStringExtra("fmt");
        String simResp = intent.getStringExtra("simresp");
        String userSpeech = intent.getStringExtra("user_speech");
        String overlayQuery = intent.getStringExtra("overlay_query");
        // `am broadcast` interpreta las llaves del JSON como URI; para probar desde ADB
        // se puede enviar el mismo JSON en Base64 con el extra "json64".
        String j64 = intent.getStringExtra("json64");
        if (j64 != null) {
            try {
                json = new String(android.util.Base64.decode(j64, android.util.Base64.NO_WRAP), "UTF-8");
            } catch (Throwable ignored) {}
        }
        String sr64 = intent.getStringExtra("simresp64");
        if (sr64 != null) {
            try {
                simResp = new String(android.util.Base64.decode(sr64, android.util.Base64.NO_WRAP), "UTF-8");
            } catch (Throwable ignored) {}
        }
        android.util.Log.i("FordLauncher/VoiceCmd", "debug json=" + json + " cmd=" + cmd
                + " resolve=" + resolve + " fmt=" + fmt);
        if (overlayQuery != null) {
            com.fordlauncher.launcher.VoiceOverlayService svc =
                    com.fordlauncher.launcher.VoiceOverlayService.instance();
            if (svc != null) svc.simularConsulta(overlayQuery);
            else android.util.Log.w("FordLauncher/VoiceCmd", "overlay: servicio flotante no activo");
        } else if (simResp != null) {
            m.debugSimularGemini(simResp, userSpeech);
        } else if (json != null) {
            boolean ok = m.debugEjecutarJson(json);
            android.util.Log.i("FordLauncher/VoiceCmd", "debug json ejecutado=" + ok);
        } else if (cmd != null) {
            m.debugEjecutarTexto(cmd);
        } else if (resolve != null) {
            m.debugResolverContacto(resolve);
        } else if (fmt != null) {
            m.debugFormatear(fmt);
        }
    }
}
