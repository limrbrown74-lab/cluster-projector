package org.clusterprojector.display;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FX11 仪表屏显示源切换器。
 *
 * <p>移植自 carlife_mod(Redash) 的 ProjectionSwitch —— DiPlay 博越L 链路的 1:1 移植
 * （Fx11ThreeScreenController + Fx11EcarxThreeScreenBridge / Fx11OemOverlayBridge）。
 *
 * <p>核心作用：把仪表物理屏（DIM）从 QNX 原生渲染切换到 Android 显示：
 *
 * <p>A) 三屏整屏（ECARX XUI AdaptAPI）：
 * <pre>
 *   Car.create(ctx) -&gt; [IConnectable.registerConnectWatcher -&gt; await onConnected]
 *   -&gt; ICar.getICarFunction()
 *   -&gt; 验证: isFunctionSupported(0x20320500, GLOBAL) == "active"
 *          getSupportedFunctionZones 含 GLOBAL
 *          getSupportedFunctionValue 含 {0,1}
 *   -&gt; 记下旧值 -&gt; setFunctionValue(0x20320500, GLOBAL, 1)
 *   -&gt; 回读 8 x 250ms 确认 -&gt; 失败写回旧值
 * </pre>
 *
 * <p>B) 原厂叠加（VHAL HIDL V2_0）：
 * <pre>
 *   IVehicle.getService("default")
 *   -&gt; getAllPropConfigs 须含 0x21708B0F(读) 与 0x21408636(写) 且访问位正确
 *   -&gt; set 0x21408636 = 3 (overlay)，回读 0x21708B0F，8 x 250ms
 *   -&gt; 失败写回 0
 * </pre>
 *
 * <p>所有入口都可能阻塞数秒（binder 连接 + 回读），调用方必须在子线程执行。
 */
public final class ClusterProjectionSwitch {

    private static final String TAG = "ClusterSwitch";

    static final int ECARX_FUNCTION = 0x20320500;      // 540148992
    static final int ECARX_ZONE_GLOBAL = Integer.MIN_VALUE;
    static final int ECARX_CONNECT_TIMEOUT_MS = 8000;

    static final int VHAL_READ_PROP = 0x21708B0F;      // 561023759
    static final int VHAL_WRITE_PROP = 0x21408636;     // 557876790
    static final int VHAL_MODE_STOCK = 0;
    static final int VHAL_MODE_OVERLAY = 3;

    static final int READBACK_ATTEMPTS = 8;
    static final long READBACK_DELAY_MS = 250;

    private static int activePath = 0;                 // 0=none 1=ECARX 2=VHAL
    private static int restoreValue = -1;

    private static Object ecarxCar;
    private static Object ecarxFunction;
    private static Object ecarxWatcher;
    private static Object vhal;

    private ClusterProjectionSwitch() {}

    public static synchronized boolean isActive() {
        return activePath != 0;
    }

    public static synchronized String activeName() {
        return activePath == 1 ? "ECARX 三屏" : activePath == 2 ? "VHAL 叠加" : "未切换";
    }

    // ------------------------------------------------------------- lifecycle

    public static synchronized boolean enable(Context context,
            boolean allowEcarx, boolean allowVhal) {
        if (activePath != 0) {
            return true;
        }
        if (allowEcarx && enableEcarx(context)) {
            activePath = 1;
            ClusterLog.i(TAG, "cluster switch ON via ECARX three-screen");
            return true;
        }
        if (allowVhal && enableVhal()) {
            activePath = 2;
            ClusterLog.i(TAG, "cluster switch ON via VHAL OEM overlay");
            return true;
        }
        ClusterLog.w(TAG, "no usable cluster switch (ecarx=" + allowEcarx
                + " vhal=" + allowVhal + ")");
        return false;
    }

    public static synchronized void disable() {
        try {
            if (activePath == 1) {
                ecarxWrite(0);
            } else if (activePath == 2) {
                writeVhal(VHAL_MODE_STOCK);
            }
        } catch (Throwable t) {
            ClusterLog.w(TAG, "disable failed: " + t);
        } finally {
            closeEcarx();
            activePath = 0;
            restoreValue = -1;
            ClusterLog.i(TAG, "cluster switch OFF");
        }
    }

    /** 崩溃恢复：尽力把仪表切回原厂渲染。 */
    public static synchronized void forceStock(Context context) {
        try {
            if (connectEcarx(context)) {
                Object supported = ecarxCall("isFunctionSupported",
                        ECARX_FUNCTION, ECARX_ZONE_GLOBAL);
                if (supported != null && "active".equalsIgnoreCase(String.valueOf(supported))) {
                    ecarxWrite(0);
                    ClusterLog.i(TAG, "forceStock: ECARX -> 0");
                }
            }
        } catch (Throwable t) {
            ClusterLog.i(TAG, "forceStock ECARX unavailable: " + t);
        } finally {
            closeEcarx();
        }
        try {
            if (vhal() != null && writeVhal(VHAL_MODE_STOCK)) {
                ClusterLog.i(TAG, "forceStock: VHAL -> 0");
            }
        } catch (Throwable t) {
            ClusterLog.i(TAG, "forceStock VHAL unavailable: " + t);
        }
    }

    // ----------------------------------------------------------------- ECARX

    /** Car.create + (可选) IConnectable watcher + getICarFunction。 */
    private static boolean connectEcarx(Context context) {
        try {
            if (ecarxFunction != null) {
                return true;
            }
            Class<?> carClass = Class.forName("com.ecarx.xui.adaptapi.car.Car");
            Class<?> carType = Class.forName("com.ecarx.xui.adaptapi.car.ICar");
            Object car = carClass.getMethod("create", Context.class)
                    .invoke(null, context.getApplicationContext());
            if (car == null) {
                ClusterLog.w(TAG, "ECARX Car.create returned null");
                return false;
            }
            ecarxCar = car;

            Class<?> connectType = null;
            try {
                connectType = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable");
            } catch (Throwable ignored) {
            }

            if (connectType != null && connectType.isInstance(car)) {
                final Object carInstance = car;
                final Class<?> carIf = carType;
                final CountDownLatch ready = new CountDownLatch(1);
                final AtomicReference<Object> fnRef = new AtomicReference<>();
                final AtomicReference<Throwable> failure = new AtomicReference<>();
                Class<?> watcherType = Class.forName(
                        "com.ecarx.xui.adaptapi.binder.IConnectable$IConnectWatcher");
                InvocationHandler handler = (proxy, method, args) -> {
                    String name = method.getName();
                    try {
                        if ("onConnected".equals(name)) {
                            try {
                                fnRef.set(carIf.getMethod("getICarFunction").invoke(carInstance));
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                            ready.countDown();
                        } else if ("onDisConnected".equals(name)) {
                            ecarxFunction = null;
                            ClusterLog.w(TAG, "ECARX disconnected");
                        } else if ("toString".equals(name)) {
                            return "Cluster ECARX watcher";
                        } else if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        } else if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                    } catch (Throwable ignored) {
                    }
                    return null;
                };
                ClassLoader loader = watcherType.getClassLoader() != null
                        ? watcherType.getClassLoader()
                        : ClusterProjectionSwitch.class.getClassLoader();
                ecarxWatcher = Proxy.newProxyInstance(loader,
                        new Class<?>[]{watcherType}, handler);
                connectType.getMethod("registerConnectWatcher", watcherType)
                        .invoke(car, ecarxWatcher);
                ClusterLog.i(TAG, "ECARX watcher registered, waiting onConnected");

                Object direct = tryGetFunction(carIf, car);
                if (direct != null) {
                    ecarxFunction = direct;
                    ClusterLog.i(TAG, "ECARX function ready (already connected)");
                    return true;
                }
                if (!ready.await(ECARX_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    ClusterLog.w(TAG, "ECARX connection timed out");
                    return false;
                }
                if (failure.get() != null) {
                    ClusterLog.w(TAG, "ECARX getICarFunction failed: " + failure.get());
                    return false;
                }
                ecarxFunction = fnRef.get();
            } else {
                ecarxFunction = carType.getMethod("getICarFunction").invoke(car);
            }

            if (ecarxFunction == null) {
                ClusterLog.w(TAG, "ECARX getICarFunction returned null");
                return false;
            }
            ClusterLog.i(TAG, "ECARX connected");
            return true;
        } catch (Throwable t) {
            ClusterLog.w(TAG, "ECARX connect failed: " + t);
            closeEcarx();
            return false;
        }
    }

    private static Object tryGetFunction(Class<?> carType, Object car) {
        try {
            return carType.getMethod("getICarFunction").invoke(car);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object ecarxCall(String name, Object... args) throws Exception {
        if (ecarxFunction == null) {
            return null;
        }
        Class<?> fnClass = Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction");
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = int.class;
        }
        Method method = fnClass.getMethod(name, types);
        return method.invoke(ecarxFunction, args);
    }

    /** DiPlay Fx11ThreeScreenPolicy.validate(): active + GLOBAL zone + values {0,1}。 */
    private static boolean validateEcarx() {
        try {
            Object supported = ecarxCall("isFunctionSupported",
                    ECARX_FUNCTION, ECARX_ZONE_GLOBAL);
            String status = supported == null ? "unknown" : String.valueOf(supported);
            if (!"active".equalsIgnoreCase(status)) {
                ClusterLog.w(TAG, "ECARX function 0x20320500 not active: " + status);
                return false;
            }
            Object zones = ecarxCall("getSupportedFunctionZones", ECARX_FUNCTION);
            if (!containsInt(zones, ECARX_ZONE_GLOBAL)) {
                ClusterLog.w(TAG, "ECARX global zone not supported: " + describe(zones));
                return false;
            }
            Object values = ecarxCall("getSupportedFunctionValue",
                    ECARX_FUNCTION, ECARX_ZONE_GLOBAL);
            if (!containsInt(values, 0) || !containsInt(values, 1)) {
                ClusterLog.w(TAG, "ECARX 0/1 values not supported: " + describe(values));
                return false;
            }
            return true;
        } catch (Throwable t) {
            ClusterLog.w(TAG, "ECARX validate failed: " + t);
            return false;
        }
    }

    private static Integer ecarxRead() {
        try {
            Object value = ecarxCall("getFunctionValue", ECARX_FUNCTION, ECARX_ZONE_GLOBAL);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean ecarxWrite(int value) {
        try {
            Object result = ecarxCall("setFunctionValue",
                    ECARX_FUNCTION, ECARX_ZONE_GLOBAL, value);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            ClusterLog.w(TAG, "ecarxWrite(" + value + ") failed: " + t);
            return false;
        }
    }

    private static boolean enableEcarx(Context context) {
        if (!connectEcarx(context)) {
            return false;
        }
        if (!validateEcarx()) {
            closeEcarx();
            return false;
        }
        Integer before = ecarxRead();
        if (before == null || (before != 0 && before != 1)) {
            ClusterLog.w(TAG, "ECARX unknown state: " + before);
            closeEcarx();
            return false;
        }
        restoreValue = before;
        if (!ecarxWrite(1)) {
            ClusterLog.w(TAG, "ECARX write(1) rejected");
            closeEcarx();
            return false;
        }
        for (int i = 0; i < READBACK_ATTEMPTS; i++) {
            Integer now = ecarxRead();
            if (now != null && now == 1) {
                ClusterLog.i(TAG, "ECARX read-back OK (before=" + before + ")");
                return true;
            }
            sleep(READBACK_DELAY_MS);
        }
        ClusterLog.w(TAG, "ECARX read-back did not confirm, restoring " + restoreValue);
        ecarxWrite(restoreValue);
        closeEcarx();
        return false;
    }

    private static void closeEcarx() {
        try {
            ecarxFunction = null;
            Object car = ecarxCar;
            ecarxCar = null;
            ecarxWatcher = null;
            if (car != null) {
                try {
                    Class<?> connectType = Class.forName(
                            "com.ecarx.xui.adaptapi.binder.IConnectable");
                    if (connectType.isInstance(car)) {
                        connectType.getMethod("disconnect").invoke(car);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ VHAL

    private static boolean enableVhal() {
        try {
            Object vehicle = vhal();
            if (vehicle == null) {
                ClusterLog.w(TAG, "VHAL service unavailable");
                return false;
            }
            Class<?> vehicleClass = Class.forName(
                    "android.hardware.automotive.vehicle.V2_0.IVehicle");
            Class<?> propValueClass = Class.forName(
                    "android.hardware.automotive.vehicle.V2_0.VehiclePropValue");

            // DiPlay Fx11OemOverlayBridge.validate()
            Object configs = vehicleClass.getMethod("getAllPropConfigs").invoke(vehicle);
            int readAccess = -1;
            int writeAccess = -1;
            if (configs instanceof Iterable) {
                Class<?> propConfigClass = Class.forName(
                        "android.hardware.automotive.vehicle.V2_0.VehiclePropConfig");
                for (Object cfg : (Iterable<?>) configs) {
                    int prop = ((Number) propConfigClass.getField("prop").get(cfg)).intValue();
                    int access = ((Number) propConfigClass.getField("access").get(cfg)).intValue();
                    if (prop == VHAL_READ_PROP) {
                        readAccess = access;
                    } else if (prop == VHAL_WRITE_PROP) {
                        writeAccess = access;
                    }
                }
            }
            if ((readAccess & 1) == 0 || (writeAccess & 2) == 0) {
                ClusterLog.w(TAG, "VHAL DIM props missing/inaccessible, readAccess="
                        + readAccess + " writeAccess=" + writeAccess);
                return false;
            }

            Integer before = readVhal(vehicleClass, propValueClass);
            if (before == null) {
                ClusterLog.w(TAG, "VHAL read of 0x21708B0F failed");
                return false;
            }
            restoreValue = before;
            if (!writeVhal(VHAL_MODE_OVERLAY)) {
                return false;
            }
            for (int i = 0; i < READBACK_ATTEMPTS; i++) {
                Integer now = readVhal(vehicleClass, propValueClass);
                if (now != null && now == VHAL_MODE_OVERLAY) {
                    ClusterLog.i(TAG, "VHAL read-back OK (before=" + before + ")");
                    return true;
                }
                sleep(READBACK_DELAY_MS);
            }
            ClusterLog.w(TAG, "VHAL read-back did not confirm, restoring");
            writeVhal(VHAL_MODE_STOCK);
            return false;
        } catch (Throwable t) {
            ClusterLog.w(TAG, "VHAL path unavailable: " + t);
            return false;
        }
    }

    private static Object vhal() {
        if (vhal != null) {
            return vhal;
        }
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.automotive.vehicle.V2_0.IVehicle");
            vhal = cls.getMethod("getService", String.class, boolean.class)
                    .invoke(null, "default", false);
            return vhal;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer readVhal(Class<?> vehicleClass,
            Class<?> propValueClass) throws Exception {
        Object vehicle = vhal();
        if (vehicle == null) {
            return null;
        }
        Object request = propValueClass.getConstructor().newInstance();
        propValueClass.getField("prop").setInt(request, VHAL_READ_PROP);
        propValueClass.getField("areaId").setInt(request, 0);

        final Object[] result = new Object[1];
        Class<?> callbackClass = Class.forName(
                "android.hardware.automotive.vehicle.V2_0.IVehicle$getCallback");
        Object callback = Proxy.newProxyInstance(
                callbackClass.getClassLoader() != null ? callbackClass.getClassLoader()
                        : ClusterProjectionSwitch.class.getClassLoader(),
                new Class<?>[]{callbackClass},
                (proxy, method, args) -> {
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
                                    result[0] = ((Number) ints.get(0)).intValue();
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return null;
                });
        vehicleClass.getMethod("get", propValueClass, callbackClass)
                .invoke(vehicle, request, callback);
        return result[0] instanceof Integer ? (Integer) result[0] : null;
    }

    private static boolean writeVhal(int mode) {
        try {
            Object vehicle = vhal();
            if (vehicle == null) {
                return false;
            }
            Class<?> vehicleClass = Class.forName(
                    "android.hardware.automotive.vehicle.V2_0.IVehicle");
            Class<?> propValueClass = Class.forName(
                    "android.hardware.automotive.vehicle.V2_0.VehiclePropValue");
            Object request = propValueClass.getConstructor().newInstance();
            propValueClass.getField("prop").setInt(request, VHAL_WRITE_PROP);
            propValueClass.getField("areaId").setInt(request, 0);
            Object raw = propValueClass.getField("value").get(request);
            @SuppressWarnings("unchecked")
            List<Integer> ints = (List<Integer>) raw.getClass().getField("int32Values").get(raw);
            ints.add(mode);
            Object status = vehicleClass.getMethod("set", propValueClass)
                    .invoke(vehicle, request);
            return status instanceof Number && ((Number) status).intValue() == 0;
        } catch (Throwable t) {
            ClusterLog.w(TAG, "writeVhal(" + mode + ") failed: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean containsInt(Object array, int value) {
        if (array instanceof int[]) {
            for (int v : (int[]) array) {
                if (v == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String describe(Object array) {
        if (!(array instanceof int[])) {
            return String.valueOf(array);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int v : (int[]) array) {
            sb.append(sb.length() > 1 ? ", " : "").append(v);
        }
        return sb.append("]").toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
