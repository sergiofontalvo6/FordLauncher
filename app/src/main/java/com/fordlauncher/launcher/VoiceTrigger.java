package com.fordlauncher.launcher;

import android.content.Context;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;

/**
 * Punto único de disparo del asistente de voz desde el volante (SWC/HCN).
 *
 * <p>Se usa desde tres sitios para que la función «Hablar» del volante (prensa
 * corta o larga en {@code com.hcn.swc}) active siempre el modo conversación en
 * vivo, equivalente a tocar el FAB de IA:
 *
 * <ul>
 *   <li>{@code Main.dispatchKeyEvent}: teclas {@code KEYCODE_VOICE_ASSIST},
 *       {@code KEYCODE_ASSIST} y {@code KEYCODE_SEARCH}.</li>
 *   <li>Receptor dinámico de {@code Main}: broadcasts de voz de HCN mientras el
 *       launcher está en primer plano.</li>
 *   <li>Receptor dinámico de {@code ObdForegroundService}: los mismos
 *       broadcasts aunque la actividad {@code Main} no exista (proceso vivo por
 *       el servicio en primer plano).</li>
 * </ul>
 *
 * <p>El anti-rebote es estático y compartido por todo el proceso, de modo que
 * una prensa larga que emite varios eventos solo abre una conversación y nunca
 * la cierra por duplicado.
 */
public final class VoiceTrigger {

    /** Extra para pedir a {@link Main} que abra el asistente al reanudarse. */
    public static final String EXTRA_VOICE = "com.fordlauncher.launcher.EXTRA_VOICE";

    private static final String TAG = "FordLauncher/VoiceCmd";
    private static final long DEBOUNCE_MS = 900;
    private static volatile long lastMs;

    /**
     * Acciones de voz conocidas/posibles de HCN en radios 8227L, del módulo SWC
     * y del propio Android. Se registran todas: una acción desconocida no
     * molesta y una conocida evita depender del firmware concreto.
     */
    public static final String[] ACTIONS = {
            // HCN (radio 8227L)
            "com.hcn.action.VOICE", "com.hcn.action.VOICE_ASSIST", "com.hcn.action.VOICE_KEY",
            "com.hcn.action.SPEECH", "com.hcn.action.ASSIST", "com.hcn.action.SW_VOICE",
            "com.hcn.action.OPEN_VOICE", "com.hcn.action.SWITCH_VOICE",
            "com.hcn.voice.ACTION_VOICE", "com.hcn.voice.VOICE_ASSIST",
            "com.hcn.voice.SPEECH", "com.hcn.voice.ACTION_SPEECH",
            "com.hcn.intent.action.VOICE", "com.hcn.intent.action.VOICE_ASSIST",
            "com.hcn.intent.action.SPEECH", "com.hcn.intent.action.SW_VOICE",
            "com.hcn.autocan.action.VOICE", "com.hcn.autocan.action.VOICE_ASSIST",
            // Sistema de voz HCN (la acción que usa el SWC en esta radio)
            "com.hcn.voicesystem.action.StartVoice",
            "com.hcn.voicesystem.action.VOICE", "com.hcn.voicesystem.action.VOICE_ASSIST",
            "com.hcn.voicesystem.VOICE", "com.hcn.voicesystem.START_VOICE",
            // Módulo de botones del volante
            "com.hcn.swc.VOICE", "com.hcn.swc.ACTION_VOICE", "com.hcn.swc.VOICE_ASSIST",
            "com.hcn.swc.SPEECH", "com.hcn.swc.action.VOICE", "com.hcn.swc.action.VOICE_ASSIST",
            "com.hcn.swc.action.SPEECH",
            // Android estándar
            "android.intent.action.VOICE_COMMAND", "android.intent.action.ASSIST",
            "android.intent.action.SEARCH_LONG_PRESS"
    };

    private VoiceTrigger() {}

    /** Filtro con todas las acciones de voz para registrar un receptor dinámico. */
    public static IntentFilter filter() {
        IntentFilter f = new IntentFilter();
        for (String a : ACTIONS) f.addAction(a);
        return f;
    }

    /**
     * Dispara la conversación en vivo desde el volante.
     *
     * <p><b>Regla de oro:</b> el botón físico nunca debe abrir {@link Main} ni
     * emitir {@code CATEGORY_HOME}. La orden va siempre al
     * {@link VoiceOverlayService}, que escucha, consulta a la IA y ejecuta las
     * acciones en segundo plano sobre la app que el conductor esté viendo. Solo
     * cuando una acción concreta lo pida (HOME, pestañas del launcher) el motor
     * traerá {@code Main} al frente.
     *
     * <p>Respaldo: si el permiso de superposición no está concedido y {@code Main}
     * ya está visible con foco, se usa su botón local (tampoco se lanza Main).
     */
    public static void fire(Context ctx, String source) {
        long now = SystemClock.elapsedRealtime();
        synchronized (VoiceTrigger.class) {
            if (now - lastMs < DEBOUNCE_MS) return;
            lastMs = now;
        }
        Log.i(TAG, "Disparo de voz SWC (" + source + ")");

        Context app = ctx != null ? ctx.getApplicationContext() : null;
        if (app == null) {
            Main m = Main.instance();
            if (m != null) app = m.getApplicationContext();
        }

        // 1) Ruta normal: servicio flotante global (no abre ninguna actividad).
        if (app != null) {
            try {
                VoiceOverlayService.toggleListen(app);
                Log.i(TAG, "Voz enviada a VoiceOverlayService (Main no se abre)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "No se pudo usar el servicio flotante: " + t);
            }
        }

        // 2) Sin servicio: usar el FAB local SOLO si el launcher ya está al frente.
        Main m = Main.instance();
        if (m != null && m.activarVozSwcSiVisible()) return;

        Log.w(TAG, "Voz SWC sin destino: activa «Mostrar sobre otras apps» para el copiloto");
    }
}
