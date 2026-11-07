package com.fordlauncher.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Clima actual (Open-Meteo, sin clave) y nombre de la ciudad. */
public class Weather {

    public static class Now {
        public double temp;
        public int code;
        public boolean day = true;
        public String city;
        public String unit = "C";
    }

    public interface Listener { void onWeather(Now n, String error); }

    static String lastError = "—";

    /** Se ejecuta en un hilo aparte; el resultado llega por el listener (en ese mismo hilo). */
    public static void fetch(final Context c, final double lat, final double lon, final Listener l) {
        new Thread(new Runnable() {
            public void run() {
                SharedPreferences p = c.getSharedPreferences("launcher", 0);
                boolean f = "F".equals(p.getString("temp_unit", "C"));
                String q = String.format(Locale.US,
                        "api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto%s",
                        lat, lon, f ? "&temperature_unit=fahrenheit" : "");
                Now n = null;
                for (String scheme : new String[]{"https://", "http://"}) {
                    try {
                        JSONObject cur = new JSONObject(new String(Net.get(scheme + q, 10000), "UTF-8")).getJSONObject("current");
                        n = new Now();
                        n.temp = cur.getDouble("temperature_2m");
                        n.code = cur.optInt("weather_code", 0);
                        n.day = cur.optInt("is_day", 1) == 1;
                        n.unit = f ? "F" : "C";
                        break;
                    } catch (Throwable e) {
                        lastError = e.toString();
                    }
                }
                if (n == null) { l.onWeather(null, lastError); return; }
                n.city = city(c, lat, lon);
                l.onWeather(n, null);
            }
        }).start();
    }

    /** Ciudad: la escrita por el usuario, la guardada para esa zona, Geocoder o BigDataCloud. */
    static String city(Context c, double lat, double lon) {
        SharedPreferences p = c.getSharedPreferences("launcher", 0);
        String manual = p.getString("city_manual", "").trim();
        if (manual.length() > 0) return manual;
        String key = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        if (key.equals(p.getString("city_key", ""))) return p.getString("city_name", null);
        String name = null;
        try {
            if (Geocoder.isPresent()) {
                List<Address> a = new Geocoder(c, new Locale("es")).getFromLocation(lat, lon, 1);
                if (a != null && !a.isEmpty()) {
                    name = a.get(0).getLocality();
                    if (name == null) name = a.get(0).getSubAdminArea();
                }
            }
        } catch (Throwable ignored) { }
        if (name == null) {
            String q = String.format(Locale.US,
                    "api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=es", lat, lon);
            for (String scheme : new String[]{"https://", "http://"}) {
                try {
                    JSONObject o = new JSONObject(new String(Net.get(scheme + q, 8000), "UTF-8"));
                    name = o.optString("city", "");
                    if (name.length() == 0) name = o.optString("locality", "");
                    if (name.length() == 0) name = null;
                    if (name != null) break;
                } catch (Throwable ignored) { }
            }
        }
        if (name != null) p.edit().putString("city_key", key).putString("city_name", name).apply();
        return name;
    }

    /** Código WMO → ícono. */
    public static String icon(int code, boolean day) {
        if (code == 0) return day ? "wb_sunny" : "nights_stay";
        if (code <= 2) return day ? "filter_drama" : "nights_stay";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "dehaze";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "umbrella";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "ac_unit";
        if (code >= 95) return "thunderstorm";
        return "cloud";
    }

    public static String describe(int code) {
        if (code == 0) return "Despejado";
        if (code <= 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Niebla";
        if (code >= 51 && code <= 57) return "Llovizna";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Lluvia";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Nieve";
        if (code >= 95) return "Tormenta";
        return "";
    }
}
