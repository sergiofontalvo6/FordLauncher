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
        // Prueba ADB: mide Gemini puro (requiere clave API guardada en Ajustes IA).
        String geminiTime = intent.getStringExtra("gemini_time");
        if (geminiTime != null) {
            final long t = android.os.SystemClock.elapsedRealtime();
            com.fordlauncher.launcher.ai.LatencyTrace.nuevoTurno("ADB-GEMINI", geminiTime);
            com.fordlauncher.launcher.obd.GeminiDiagnosticClient.analyze(context,
                    "{\"obd_conectado\":false}",
                    "El conductor pregunta por voz: \"" + geminiTime + "\"",
                    new com.fordlauncher.launcher.obd.GeminiDiagnosticClient.Callback() {
                        public void onDone(String text, String error) {
                            android.util.Log.i("FordLauncher/VoiceCmd", "gemini_time ms="
                                    + (android.os.SystemClock.elapsedRealtime() - t)
                                    + " error=" + error + " text=" + text);
                        }
                    });
            return;
        }
        // Prueba ADB: mide la voz configurada (nube con Wi-Fi, local sin red).
        String ttsTime = intent.getStringExtra("tts_time");
        if (ttsTime != null) {
            com.fordlauncher.launcher.ai.LatencyTrace.nuevoTurno("ADB-TTS", ttsTime);
            com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(context)
                    .hablarTextoLargo(ttsTime);
            return;
        }
        String netCheck = intent.getStringExtra("net_check");
        if (netCheck != null) {
            android.util.Log.i("FordLauncher/VoiceCmd", "net_check isNetworkAvailable="
                    + com.fordlauncher.launcher.Net.isNetworkAvailable(context));
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
        // Prueba ADB: inyecta el estado turn-by-turn del card sin depender de Waze.
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es navtest 1
        //   adb shell am broadcast -a com.fordlauncher.launcher.DEBUG_CMD --es navtest clear
        String navTest = intent.getStringExtra("navtest");
        if (navTest != null) {
            if ("clear".equals(navTest)) {
                com.fordlauncher.launcher.nav.NavState.clear("com.waze");
                android.util.Log.i("FordLauncher/NavNotif", "navtest: estado limpiado");
            } else {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        64, 64, android.graphics.Bitmap.Config.RGB_565);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor(0xFFE53935);
                cv.drawCircle(32, 32, 28, p);
                com.fordlauncher.launcher.nav.NavState.publish(
                        new com.fordlauncher.launcher.nav.NavState.Data("com.waze", "250 m",
                                "en dirección a Carretera 50 Bogotá-Llanogrande vía Aeropuerto El Dorado",
                                "12 min · 5,4 km · Llegada: 11:57 a. m.", bmp));
                android.util.Log.i("FordLauncher/NavNotif", "navtest: ruta simulada publicada");
            }
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
