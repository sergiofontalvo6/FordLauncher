package com.fordlauncher.launcher.obd;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descripción de códigos, portada de Codes.kt (LiteDash). */
public final class Codes {
    private Codes() {}
    private static final Pattern MISFIRE = Pattern.compile("P030([1-8])");
    private static final Map<String,String[]> COMMON = new HashMap<String,String[]>();
    static {
        put("P0011","Tiempo del árbol de levas adelantado (banco 1)","Camshaft timing over-advanced (bank 1)");
        put("P0016","Cigüeñal y árbol de levas desfasados","Crankshaft/camshaft correlation");
        put("P0030","Calentador del sensor de oxígeno (banco 1, sensor 1)","O2 sensor heater circuit (bank 1, sensor 1)");
        put("P0036","Calentador del sensor de oxígeno (banco 1, sensor 2)","O2 sensor heater circuit (bank 1, sensor 2)");
        put("P0101","Sensor de flujo de aire (MAF) fuera de rango","Mass air flow sensor range/performance");
        put("P0102","Sensor de flujo de aire (MAF): señal baja","Mass air flow sensor low input");
        put("P0113","Sensor de temperatura del aire de admisión: señal alta","Intake air temperature sensor high input");
        put("P0117","Sensor de temperatura del motor: señal baja","Coolant temperature sensor low input");
        put("P0118","Sensor de temperatura del motor: señal alta","Coolant temperature sensor high input");
        put("P0121","Sensor de posición del acelerador fuera de rango","Throttle position sensor range/performance");
        put("P0128","El motor tarda en calentar (termostato)","Coolant thermostat below regulating temperature");
        put("P0131","Sensor de oxígeno: voltaje bajo (banco 1, sensor 1)","O2 sensor low voltage (bank 1, sensor 1)");
        put("P0133","Sensor de oxígeno lento (banco 1, sensor 1)","O2 sensor slow response (bank 1, sensor 1)");
        put("P0135","Calentador del sensor de oxígeno (banco 1, sensor 1)","O2 sensor heater circuit (bank 1, sensor 1)");
        put("P0141","Calentador del sensor de oxígeno (banco 1, sensor 2)","O2 sensor heater circuit (bank 1, sensor 2)");
        put("P0171","Mezcla pobre (banco 1): entra aire o falta gasolina","System too lean (bank 1)");
        put("P0172","Mezcla rica (banco 1): sobra gasolina","System too rich (bank 1)");
        put("P0174","Mezcla pobre (banco 2)","System too lean (bank 2)");
        put("P0175","Mezcla rica (banco 2)","System too rich (bank 2)");
        put("P0300","Falla de encendido en varios cilindros","Random/multiple cylinder misfire");
        put("P0325","Sensor de detonación (banco 1)","Knock sensor circuit (bank 1)");
        put("P0335","Sensor de posición del cigüeñal","Crankshaft position sensor circuit");
        put("P0340","Sensor de posición del árbol de levas","Camshaft position sensor circuit");
        put("P0401","Flujo de recirculación de gases (EGR) insuficiente","EGR flow insufficient");
        put("P0420","Catalizador con baja eficiencia (banco 1)","Catalyst efficiency below threshold (bank 1)");
        put("P0430","Catalizador con baja eficiencia (banco 2)","Catalyst efficiency below threshold (bank 2)");
        put("P0440","Sistema de vapores de gasolina (EVAP)","Evaporative emission system");
        put("P0442","Fuga chica en vapores de gasolina (EVAP): revisa el tapón","EVAP small leak detected");
        put("P0446","Válvula de ventilación de vapores (EVAP)","EVAP vent control circuit");
        put("P0455","Fuga grande en vapores de gasolina (EVAP): tapón flojo o abierto","EVAP large leak detected");
        put("P0456","Fuga muy chica en vapores de gasolina (EVAP)","EVAP very small leak detected");
        put("P0500","Sensor de velocidad del auto","Vehicle speed sensor");
        put("P0505","Control de ralentí","Idle air control system");
        put("P0506","Ralentí más bajo de lo normal","Idle speed lower than expected");
        put("P0507","Ralentí más alto de lo normal","Idle speed higher than expected");
        put("P0562","Voltaje bajo del sistema (batería o alternador)","System voltage low");
        put("P0563","Voltaje alto del sistema","System voltage high");
        put("P0605","Falla interna de la computadora del motor","Internal control module error");
        put("P0700","La transmisión reporta una falla (revisa sus códigos)","Transmission control system malfunction");
        put("P0715","Sensor de velocidad de entrada de la transmisión","Input/turbine speed sensor");
        put("P0740","Embrague del convertidor de torque","Torque converter clutch circuit");
        put("U0100","Se perdió la comunicación con la computadora del motor","Lost communication with ECM/PCM");
    }
    private static void put(String k, String es, String en){ COMMON.put(k, new String[]{es,en}); }
    public static String describe(String code){ return describe(code, true); }
    public static String describe(String code, boolean spanish) {
        String[] c = COMMON.get(code);
        if (c != null) return spanish ? c[0] : c[1];
        Matcher m = MISFIRE.matcher(code);
        if (m.matches()) { String n=m.group(1); return spanish? "Falla de encendido en el cilindro "+n : "Cylinder "+n+" misfire"; }
        return system(code, spanish);
    }
    private static String system(String code, boolean spanish) {
        boolean maker = code.length()>1 && code.charAt(1)!='0';
        String text;
        char first = code.length()>0? code.charAt(0) : '?';
        if (first=='B') text = spanish? "Carrocería" : "Body";
        else if (first=='C') text = spanish? "Chasis (frenos, suspensión)" : "Chassis (brakes, suspension)";
        else if (first=='U') text = spanish? "Comunicación entre computadoras" : "Network communication";
        else {
            char third = code.length()>2? code.charAt(2) : '?';
            if (third=='1'||third=='2') text=spanish?"Mezcla de aire y combustible":"Fuel and air metering";
            else if (third=='3') text=spanish?"Encendido o falla de cilindros":"Ignition or misfire";
            else if (third=='4') text=spanish?"Control de emisiones":"Emission controls";
            else if (third=='5') text=spanish?"Velocidad y ralentí":"Speed and idle control";
            else if (third=='6') text=spanish?"Computadora del motor":"Engine computer";
            else if (third=='7'||third=='8'||third=='9') text=spanish?"Transmisión":"Transmission";
            else text=spanish?"Motor":"Engine";
        }
        return maker? text+" · "+(spanish?"código de la marca":"manufacturer code") : text;
    }
}
