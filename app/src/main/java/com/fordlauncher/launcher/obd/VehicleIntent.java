package com.fordlauncher.launcher.obd;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Decide si una consulta por voz necesita telemetría del vehículo. */
public final class VehicleIntent {

    // Palabras completas (con plural) para no confundir "auto" con "automático" o "autopista".
    private static final Pattern VEHICULO = Pattern.compile(
            "(?:^|[^a-z0-9])(" +
            "auto|autos|carro|carros|fiesta|motor|motores|temperatura|temperaturas|" +
            "revoluciones|rpm|falla|fallas|dtc|aceite|aceites|sensor|sensores|" +
            "refrigerante|refrigerantes|bateria|baterias|voltaje|velocidad|consumo|" +
            "vehiculo|vehiculos|recalent\\w*|codigo de falla|codigos de falla" +
            ")(?=[^a-z0-9]|$)");

    private VehicleIntent() {}

    public static boolean requiereVehiculo(String texto) {
        if (texto == null) return false;
        return VEHICULO.matcher(normalizar(texto)).find();
    }

    /** Minúsculas y sin tildes, para que "batería" y "BATERIA" coincidan. */
    static String normalizar(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }
}
