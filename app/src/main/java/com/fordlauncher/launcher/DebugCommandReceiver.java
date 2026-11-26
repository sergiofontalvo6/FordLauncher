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
 * adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es alert test_temp
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
        String overlayQuery = intent.getStringExtra("overlay_query");
        String overlayResp = intent.getStringExtra("overlay_resp");
        String overlayResp64 = intent.getStringExtra("overlay_resp64");
        // Hook de pruebas: simula una alerta crítica real (notificación + banner
        // flotante + TTS offline) sin adaptador OBD ni conexión a internet.
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es alert test_temp
        // Hook de pruebas: comprueba la detección de red que usan voz y copiloto.
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es net_check 1
        String netCheck = intent.getStringExtra("net_check");
        if (netCheck != null) {
            android.util.Log.i("FordLauncher/VoiceCmd", "net_check isNetworkAvailable="
                    + com.fordlauncher.launcher.Net.isNetworkAvailable(context));
            return;
        }
        // Hook de pruebas: estado del motor neuronal offline.
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es sherpa_status 1
        String sherpaStatus = intent.getStringExtra("sherpa_status");
        if (sherpaStatus != null) {
            // Si el broadcast arranca un proceso nuevo, también dispara la carga.
            com.fordlauncher.launcher.ai.SherpaTtsEngine.get().precalentar(context);
            android.util.Log.i("FordLauncher/VoiceCmd", com.fordlauncher.launcher.ai.SherpaTtsEngine
                    .get().estadoDetallado(context));
            return;
        }
        // Hook de pruebas: muestra de un speaker (0-9) por Supertonic 3.
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es sherpa_test 1 --ei sid 3
        String sherpaTest = intent.getStringExtra("sherpa_test");
        if (sherpaTest != null) {
            int sid = intent.getIntExtra("sid", -1);
            if (sid < 0) sid = com.fordlauncher.launcher.ai.SherpaTtsEngine.speakerPreferido(context);
            android.util.Log.i("FordLauncher/VoiceCmd", "sherpa_test sid=" + sid);
            com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(context)
                    .previsualizarSupertonic(sid);
            return;
        }
        // Hook de pruebas: síntesis directa, sin pasar por TextToSpeechManager.
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es sherpa_direct "Hola" --ei sid 0
        String sherpaDirect = intent.getStringExtra("sherpa_direct");
        if (sherpaDirect != null) {
            int sid = intent.getIntExtra("sid", 0);
            android.util.Log.i("FordLauncher/VoiceCmd", "sherpa_direct sid=" + sid
                    + " texto=" + sherpaDirect);
            com.fordlauncher.launcher.ai.SherpaTtsEngine.get().speak(sherpaDirect, sid,
                    new com.fordlauncher.launcher.ai.SherpaTtsEngine.Callback() {
                        public void onDone() { android.util.Log.i("FordLauncher/VoiceCmd", "sherpa_direct done"); }
                        public void onError(String msg) { android.util.Log.w("FordLauncher/VoiceCmd", "sherpa_direct error=" + msg); }
                    });
            return;
        }
        // Hook de pruebas: fija la voz de nube sin pasar por la UI (no altera
        // el speaker offline ni el motor local).
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es set_tts_voice edge:es-CO-GonzaloNeural
        String setVoice = intent.getStringExtra("set_tts_voice");
        if (setVoice != null) {
            android.content.SharedPreferences p = context.getSharedPreferences("launcher", 0);
            if ("auto".equalsIgnoreCase(setVoice) || setVoice.length() == 0) {
                p.edit().remove("tts_voice").apply();
            } else {
                p.edit().putString("tts_voice", setVoice).apply();
            }
            com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(context).reiniciarVoz();
            android.util.Log.i("FordLauncher/VoiceCmd", "set_tts_voice=" + p.getString("tts_voice", "auto"));
            return;
        }
        String alert = intent.getStringExtra("alert");
        if (alert != null) {
            android.util.Log.i("FordLauncher/VoiceCmd", "debug: alerta simulada " + alert);
            try {
                com.fordlauncher.launcher.obd.SensorAnomalyTriggerManager
                        .get(context, com.fordlauncher.launcher.obd.ObdRepository.getInstance(context))
                        .simular(alert);
            } catch (Throwable t) {
                android.util.Log.e("FordLauncher/VoiceCmd", "debug: alerta falló", t);
            }
            return;
        }
        // Hook de pruebas: dispara EXACTAMENTE el mismo handler que el botón
        // físico de voz del volante (broadcast SWC/HCN -> VoiceTrigger).
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es voice swc
        String voice = intent.getStringExtra("voice");
        if (voice != null) {
            android.util.Log.i("FordLauncher/VoiceCmd",
                    "debug: simulando botón físico de voz (" + voice + ")");
            com.fordlauncher.launcher.VoiceTrigger.fire(context, "adb debug " + voice);
            return;
        }
        if (overlayResp64 != null) {
            try { overlayResp = new String(android.util.Base64.decode(overlayResp64, android.util.Base64.NO_WRAP), "UTF-8"); }
            catch (Throwable ignored) {}
        }
        if (overlayQuery != null || overlayResp != null) {
            com.fordlauncher.launcher.VoiceOverlayService svc =
                    com.fordlauncher.launcher.VoiceOverlayService.instance();
            if (svc == null) {
                android.util.Log.w("FordLauncher/VoiceCmd", "overlay: servicio flotante no activo");
                return;
            }
            if (overlayResp != null) svc.simularRespuesta(overlayResp, intent.getStringExtra("user_speech"));
            else svc.simularConsulta(overlayQuery);
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
        if (simResp != null) {
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
