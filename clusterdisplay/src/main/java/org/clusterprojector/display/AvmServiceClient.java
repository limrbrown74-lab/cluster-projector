package org.clusterprojector.display;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 吉利 OneOS 系统服务客户端。
 *
 * <p>通过反射连接 {@code com.geely.service.oneosapi.OneOSApiService}，
 * 获取导航服务（NaviServer）和停车/AVM 服务（ParkingServer）的 Binder。
 *
 * <p>核心用途：
 * <ul>
 *   <li>{@link #naviAddSurface}：让原厂地图渲染到我们提供的 Surface</li>
 *   <li>{@link #avmSetDisplaySurface}：把 Surface 投到仪表物理屏</li>
 * </ul>
 */
public final class AvmServiceClient {

    private static final String TAG = "AvmService";

    private static final String PKG = "com.geely.service.oneosapi";
    private static final String CLS = "com.geely.service.oneosapi.OneOSApiService";

    /** IServiceManager.getService(type) 的 type 常量（反编译自 OneOS SDK）。 */
    private static final int TYPE_NAVI = 1;
    private static final int TYPE_PARKING = 34;

    private final Context appContext;
    private ServiceCallback callback;

    private boolean binding;
    private IBinder serviceManagerBinder;
    private Object naviServer;
    private Object parkingServer;

    public interface ServiceCallback {
        void onConnected();
        void onDisconnected();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "OneOSApiService connected");
            serviceManagerBinder = service;
            resolveManagers();
            if (callback != null) callback.onConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "OneOSApiService disconnected");
            serviceManagerBinder = null;
            naviServer = null;
            parkingServer = null;
            if (callback != null) callback.onDisconnected();
        }
    };

    public AvmServiceClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setCallback(ServiceCallback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        return serviceManagerBinder != null;
    }

    public boolean isNaviReady() {
        return naviServer != null;
    }

    public boolean isParkingReady() {
        return parkingServer != null;
    }

    /** 绑定系统服务。 */
    public void connect() {
        if (binding || serviceManagerBinder != null) return;
        try {
            Intent intent = new Intent();
            intent.setClassName(PKG, CLS);
            binding = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService result=" + binding);
        } catch (Exception e) {
            Log.e(TAG, "bindService failed", e);
        }
    }

    /** 解绑系统服务。 */
    public void disconnect() {
        if (binding) {
            try {
                appContext.unbindService(connection);
            } catch (Exception ignored) {}
        }
        binding = false;
        serviceManagerBinder = null;
        naviServer = null;
        parkingServer = null;
    }

    // ------------------------------------------------------------------
    // 内部：通过反射解析 NaviServer / ParkingServer
    // ------------------------------------------------------------------

    private void resolveManagers() {
        try {
            // IServiceManager.Stub.asInterface(binder)
            Class<?> stubCls = Class.forName("com.geely.lib.oneosapi.IServiceManager$Stub");
            Method asInterface = stubCls.getMethod("asInterface", IBinder.class);
            Object serviceMgr = asInterface.invoke(null, serviceManagerBinder);
            Log.i(TAG, "IServiceManager resolved: " + (serviceMgr != null));

            // getService(TYPE_NAVI) → IBinder
            Method getService = serviceMgr.getClass().getMethod("getService", int.class);
            IBinder naviBinder = (IBinder) getService.invoke(serviceMgr, TYPE_NAVI);
            Log.i(TAG, "Navi binder: " + naviBinder);

            IBinder parkingBinder = (IBinder) getService.invoke(serviceMgr, TYPE_PARKING);
            Log.i(TAG, "Parking binder: " + parkingBinder);

            // INaviServer.Stub.asInterface(naviBinder)
            if (naviBinder != null) {
                try {
                    Class<?> naviStub = Class.forName("com.geely.lib.oneosapi.navi.INaviServer$Stub");
                    Method naviAsInterface = naviStub.getMethod("asInterface", IBinder.class);
                    naviServer = naviAsInterface.invoke(null, naviBinder);
                    Log.i(TAG, "NaviServer resolved: " + (naviServer != null));
                } catch (ClassNotFoundException e) {
                    Log.w(TAG, "INaviServer$Stub not found, use raw binder", e);
                    naviServer = naviBinder;
                }
            }

            // IParkingService.Stub.asInterface(parkingBinder)
            if (parkingBinder != null) {
                try {
                    Class<?> parkingStub = Class.forName("com.geely.lib.oneosapi.parking.IParkingService$Stub");
                    Method parkingAsInterface = parkingStub.getMethod("asInterface", IBinder.class);
                    parkingServer = parkingAsInterface.invoke(null, parkingBinder);
                    Log.i(TAG, "ParkingServer resolved: " + (parkingServer != null));
                } catch (ClassNotFoundException e) {
                    Log.w(TAG, "IParkingService$Stub not found, use raw binder", e);
                    parkingServer = parkingBinder;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveManagers failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 对外暴露的核心方法
    // ------------------------------------------------------------------

    /**
     * 让原厂地图渲染到指定 Surface。
     *
     * @param surface 目标 Surface（来自仪表 Display 上的 SurfaceView）
     * @param type    类型（0=普通，1=道路视图）
     * @param width   Surface 宽度
     * @param height  Surface 高度
     * @return 是否成功
     */
    public boolean naviAddSurface(android.view.Surface surface, int type, int width, int height) {
        if (naviServer == null) {
            Log.w(TAG, "naviAddSurface: naviServer null");
            return false;
        }
        try {
            Method m = findMethod(naviServer.getClass(), "addSurface",
                    android.view.Surface.class, int.class, int.class, int.class);
            if (m == null) {
                Log.w(TAG, "addSurface method not found");
                return false;
            }
            Object result = m.invoke(naviServer, surface, type, height, width);
            boolean ok = result instanceof Boolean && (Boolean) result;
            Log.i(TAG, "naviAddSurface result=" + ok);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "naviAddSurface failed", e);
            return false;
        }
    }

    /** 移除地图 Surface。 */
    public boolean naviRemoveSurface(android.view.Surface surface, int type) {
        if (naviServer == null) return false;
        try {
            Method m = findMethod(naviServer.getClass(), "removedSurface",
                    android.view.Surface.class, int.class);
            if (m == null) return false;
            Object result = m.invoke(naviServer, surface, type);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            Log.e(TAG, "naviRemoveSurface failed", e);
            return false;
        }
    }

    /**
     * 把 Surface 投到仪表物理屏。
     *
     * @param surface 要投屏的 Surface
     * @return 是否成功
     */
    public boolean avmSetDisplaySurface(android.view.Surface surface) {
        if (parkingServer == null) {
            Log.w(TAG, "avmSetDisplaySurface: parkingServer null");
            return false;
        }
        try {
            Method m = findMethod(parkingServer.getClass(), "avmSetDisplaySurface",
                    android.view.Surface.class);
            if (m == null) {
                Log.w(TAG, "avmSetDisplaySurface method not found");
                return false;
            }
            Object result = m.invoke(parkingServer, surface);
            boolean ok = result instanceof Boolean && (Boolean) result;
            Log.i(TAG, "avmSetDisplaySurface result=" + ok);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "avmSetDisplaySurface failed", e);
            return false;
        }
    }

    /** 打开 AVM 模式（QNX 停止渲染原厂仪表）。 */
    public boolean openAvm() {
        return callBoolNoArg("openAvm");
    }

    /** 关闭 AVM 模式（恢复原厂仪表）。 */
    public boolean closeAvm() {
        return callBoolNoArg("closeAvm");
    }

    /** 查询 AVM 是否已打开。 */
    public boolean isAvmOpened() {
        return callBoolNoArg("isAvmOpened");
    }

    private boolean callBoolNoArg(String methodName) {
        if (parkingServer == null) return false;
        try {
            Method m = findMethod(parkingServer.getClass(), methodName);
            if (m == null) return false;
            Object result = m.invoke(parkingServer);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            Log.e(TAG, methodName + " failed", e);
            return false;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        // 先精确匹配
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {}
        // 再遍历所有方法模糊匹配
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == paramTypes.length) {
                    return m;
                }
            }
        }
        return null;
    }
}
