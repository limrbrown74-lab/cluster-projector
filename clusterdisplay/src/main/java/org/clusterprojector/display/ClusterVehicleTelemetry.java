package org.clusterprojector.display;

import android.util.Log;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * FX11 车辆信号引擎，移植自 carlife_mod(Redash) 的 VehicleTelemetry
 * （DiPlay Fx11Signals + Fx11DashboardSignals 绑定表，HIDL VHAL V2_0 轮询）。
 *
 * <p>两档频率：快档 250ms（车速/转速/档位/转向灯），慢档 1.5s（其余）。
 * 每个信号都可选；缺失属性保持 "unknown" 值，绝不影响其他信号。
 */
public final class ClusterVehicleTelemetry {

    private static final String TAG = "ClusterTelemetry";

    // ---- 属性 id（十进制），与 DiPlay 绑定完全一致 ----
    private static final int SPEED_KPH_RAW = 291504647;      // m/s * 3.6 -> km/h
    private static final int SPEED_INDICATED = 557874773;    // km/h 直接
    private static final int RPM_RAW = 557850019;            // raw / 2 -> rpm
    private static final int GEAR = 557874334;               // 0=P 1=R 2=N 3=D
    private static final int RANGE_KM = 561024488;
    private static final int FUEL_PERCENT = 561024416;
    private static final int ODOMETER_M = 561024140;         // 米 / 1000
    private static final int ODOMETER_KM = 291504644;
    private static final int AMBIENT_C = 559970552;
    private static final int COOLANT_C = 291504897;
    private static final int LEFT_TURN = 557873489;
    private static final int RIGHT_TURN = 557873490;
    private static final int LOW_BEAM = 557849510;
    private static final int HIGH_BEAM = 557849181;
    private static final int POSITION_LIGHT = 557849179;
    private static final int REAR_FOG = 557855125;
    private static final int PARKING_BRAKE = 557850632;
    private static final int DRIVER_BELT = 557855239;        // 0=系好 1=未系
    private static final int CRUISE_STATE = 557849289;
    private static final int ACC_STATE = 557871508;
    private static final int ACC_SET_SPEED = 291508243;

    private static final long FAST_MS = 250L;
    private static final long SLOW_MS = 1500L;

    private static volatile double speed = -1;          // km/h
    private static volatile double rpm = -1;            // rpm
    private static volatile String gear;                // P/R/N/D 或 null
    private static volatile double range = -1;          // km
    private static volatile double fuel = -1;           // %
    private static volatile double odometer = -1;       // km
    private static volatile double ambient = -1;        // °C
    private static volatile double coolant = -1;        // °C
    private static volatile boolean leftTurn;
    private static volatile boolean rightTurn;
    private static volatile boolean lowBeam;
    private static volatile boolean highBeam;
    private static volatile boolean positionLight;
    private static volatile boolean rearFog;
    private static volatile boolean parkingBrake;
    private static volatile boolean beltUnfastened;
    private static volatile boolean cruiseOn;
    private static volatile double accSetSpeed = -1;

    private static volatile boolean running;
    private static volatile boolean online;

    private static Object vehicle;
    private static Class<?> vehicleClass;
    private static Class<?> propValueClass;
    private static Class<?> callbackClass;

    private ClusterVehicleTelemetry() {}

    public static void start() {
        if (running) {
            return;
        }
        running = true;
        Thread thread = new Thread(ClusterVehicleTelemetry::loop, "cluster-telemetry");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        running = false;
        online = false;
    }

    public static boolean online() {
        return online;
    }

    // ------------------------------------------------------------- accessors

    public static int speed() {
        return speed >= 0 ? (int) Math.round(speed) : -1;
    }

    public static double speedValue() {
        return speed;
    }

    public static int rpm() {
        return rpm >= 0 ? (int) Math.round(rpm) : -1;
    }

    public static double rpmValue() {
        return rpm;
    }

    public static int gear() {
        String g = gear;
        if (g == null) {
            return -1;
        }
        switch (g) {
            case "P": return 0;
            case "R": return 1;
            case "N": return 2;
            default: return 3;
        }
    }

    public static String gearText() {
        return gear;
    }

    public static int range() {
        return range >= 0 ? (int) Math.round(range) : -1;
    }

    public static double fuelPercent() {
        return fuel;
    }

    public static double odometerKm() {
        return odometer;
    }

    public static int ambient() {
        return ambient > -100 ? (int) Math.round(ambient) : -100;
    }

    public static double coolantC() {
        return coolant;
    }

    public static boolean leftTurn() { return leftTurn; }
    public static boolean rightTurn() { return rightTurn; }
    public static boolean lowBeam() { return lowBeam; }
    public static boolean highBeam() { return highBeam; }
    public static boolean positionLight() { return positionLight; }
    public static boolean rearFog() { return rearFog; }
    public static boolean parkingBrake() { return parkingBrake; }
    public static boolean beltUnfastened() { return beltUnfastened; }
    public static boolean cruiseOn() { return cruiseOn; }
    public static double accSetSpeed() { return accSetSpeed; }

    // ---------------------------------------------------------------- engine

    private static void loop() {
        try {
            init();
            online = true;
            ClusterLog.i(TAG, "VHAL telemetry online");
        } catch (Throwable t) {
            Log.w(TAG, "VHAL unavailable: " + t);
            ClusterLog.w(TAG, "VHAL telemetry unavailable: " + t);
            running = false;
            return;
        }
        long lastSlow = 0L;
        while (running) {
            try {
                // fast tier
                Double indicated = read(SPEED_INDICATED);
                if (indicated != null) {
                    speed = clamp(indicated, 0, 350);
                } else {
                    Double raw = read(SPEED_KPH_RAW);
                    speed = raw != null ? clamp(Math.abs(raw) * 3.6d, 0, 350) : -1;
                }
                Double r = read(RPM_RAW);
                rpm = r != null ? clamp(r / 2.0d, 0, 12000) : -1;
                Double g = read(GEAR);
                gear = gearText(g);
                Double lt = read(LEFT_TURN);
                leftTurn = lt != null && lt == 1.0d;
                Double rt = read(RIGHT_TURN);
                rightTurn = rt != null && rt == 1.0d;

                long now = System.currentTimeMillis();
                if (now - lastSlow >= SLOW_MS) {
                    lastSlow = now;
                    Double v;
                    v = read(RANGE_KM);        range = v != null ? clamp(v, 0, 3000) : -1;
                    v = read(FUEL_PERCENT);    fuel = v != null ? clamp(v, 0, 100) : -1;
                    v = read(ODOMETER_M);      odometer = v != null ? clamp(v / 1000.0d, 0, 3000000) : -1;
                    if (odometer < 0) {
                        v = read(ODOMETER_KM); odometer = v != null ? clamp(v, 0, 3000000) : -1;
                    }
                    v = read(AMBIENT_C);       ambient = v != null ? clamp(v, -60, 85) : -999;
                    v = read(COOLANT_C);       coolant = v != null ? clamp(v, -40, 215) : -999;
                    v = read(LOW_BEAM);        lowBeam = v != null && v == 1.0d;
                    v = read(HIGH_BEAM);       highBeam = v != null && v == 1.0d;
                    v = read(POSITION_LIGHT);  positionLight = v != null && v == 1.0d;
                    v = read(REAR_FOG);        rearFog = v != null && v == 1.0d;
                    v = read(PARKING_BRAKE);   parkingBrake = v != null && v == 1.0d;
                    v = read(DRIVER_BELT);     beltUnfastened = v != null && v == 1.0d;
                    v = read(CRUISE_STATE);    cruiseOn = v != null && v != 0.0d;
                    v = read(ACC_SET_SPEED);   accSetSpeed = v != null ? clamp(v, 0, 350) : -1;
                }
            } catch (Throwable t) {
                Log.w(TAG, "telemetry read failed: " + t);
            }
            try {
                Thread.sleep(FAST_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String gearText(Double value) {
        if (value == null) {
            return null;
        }
        switch ((int) value.doubleValue()) {
            case 0: return "P";
            case 1: return "R";
            case 2: return "N";
            case 3: return "D";
            default: return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void init() throws Exception {
        vehicleClass = Class.forName("android.hardware.automotive.vehicle.V2_0.IVehicle");
        propValueClass = Class.forName("android.hardware.automotive.vehicle.V2_0.VehiclePropValue");
        callbackClass = Class.forName(
                "android.hardware.automotive.vehicle.V2_0.IVehicle$getCallback");
        vehicle = vehicleClass.getMethod("getService", String.class, boolean.class)
                .invoke(null, "default", false);
        if (vehicle == null) {
            throw new IllegalStateException("VHAL service is null");
        }
    }

    /** 读取属性的第一个 int32/float 值；不可用时返回 null。 */
    private static Double read(int prop) {
        try {
            Object request = propValueClass.getConstructor().newInstance();
            propValueClass.getField("prop").setInt(request, prop);
            propValueClass.getField("areaId").setInt(request, 0);

            final Object[] result = new Object[1];
            Object callback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader() != null ? callbackClass.getClassLoader()
                            : ClusterVehicleTelemetry.class.getClassLoader(),
                    new Class<?>[]{callbackClass}, (proxy, method, args) -> {
                        if ("onValues".equals(method.getName())
                                && args != null && args.length >= 2) {
                            try {
                                Object value = args[1];
                                if (value != null) {
                                    Object raw = propValueClass.getField("value").get(value);
                                    List<?> ints = (List<?>) raw.getClass()
                                            .getField("int32Values").get(raw);
                                    if (ints != null && !ints.isEmpty()
                                            && ints.get(0) instanceof Number) {
                                        result[0] = ((Number) ints.get(0)).doubleValue();
                                        return null;
                                    }
                                    List<?> floats = (List<?>) raw.getClass()
                                            .getField("floatValues").get(raw);
                                    if (floats != null && !floats.isEmpty()
                                            && floats.get(0) instanceof Number) {
                                        result[0] = ((Number) floats.get(0)).doubleValue();
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        return null;
                    });
            vehicleClass.getMethod("get", propValueClass, callbackClass)
                    .invoke(vehicle, request, callback);
            return result[0] instanceof Double ? (Double) result[0] : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
