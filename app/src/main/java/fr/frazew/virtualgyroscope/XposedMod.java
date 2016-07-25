package fr.frazew.virtualgyroscope;

import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.SparseArray;

import de.robv.android.xposed.XposedBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import fr.frazew.virtualgyroscope.hooks.SensorChangeHook;
import fr.frazew.virtualgyroscope.hooks.SystemSensorManagerHook;

public class XposedMod implements IXposedHookLoadPackage {

    public static final SparseArray<SensorModel> sensorsToEmulate = new SparseArray<SensorModel>() {{
        put(Sensor.TYPE_ROTATION_VECTOR, new SensorModel(Sensor.TYPE_ROTATION_VECTOR, "VirtualSensor RotationVector", -1, 0.01F, -1, -1));
        put(Sensor.TYPE_GYROSCOPE, new SensorModel(Sensor.TYPE_GYROSCOPE, "VirtualSensor Gyroscope", -1, 0.01F, -1, (float)Math.PI));
        put(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, new SensorModel(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, "VirtualSensor GeomagneticRotationVector", -1, 0.01F, -1, -1));
        put(Sensor.TYPE_GRAVITY, new SensorModel(Sensor.TYPE_GRAVITY, "VirtualSensor Gravity", -1, 0.01F, -1, -1));
        put(Sensor.TYPE_LINEAR_ACCELERATION, new SensorModel(Sensor.TYPE_LINEAR_ACCELERATION, "VirtualSensor LinearAcceleration", 4242, 0.01F, -1, -1)); // Had to use another handle as it broke the magnetic sensor's readings (?!)
    }};

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if(lpparam.packageName.equals("android")) {
            hookPackageFeatures(lpparam);
        }
        hookSensorValues(lpparam);
        addSensors(lpparam);

        // Simple Pokémon GO hook, trying to understand why it doesn't understand the values from the virtual sensors.
        if(lpparam.packageName.contains("nianticlabs.pokemongo")) {
            Class<?> sensorMgrNiantic = XposedHelpers.findClass("com.nianticlabs.nia.sensors.NianticSensorManager", lpparam.classLoader);
            XposedBridge.hookAllMethods(sensorMgrNiantic, "onSensorChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    //XposedBridge.log("VirtualSensor: Pokémon GO onSensorChanged with sensor type " + (android.os.Build.VERSION.SDK_INT >= 20 ? ((SensorEvent) (param.args[0])).sensor.getStringType() : ((SensorEvent) (param.args[0])).sensor.getType()));
                    if (((SensorEvent) (param.args[0])).sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                        float[] values = ((SensorEvent) (param.args[0])).values;
                        //XposedBridge.log("VirtualSensor: Pokémon GO gyroscope values are x=" + values[0] + ",y=" + values[1] + ",z=" + values[2]);
                    }
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void hookPackageFeatures(final LoadPackageParam lpparam) {
        if (Build.VERSION.SDK_INT >= 21) {
            Class<?> pkgMgrSrv = XposedHelpers.findClass("com.android.server.SystemConfig", lpparam.classLoader);
            XposedBridge.hookAllMethods(pkgMgrSrv, "getAvailableFeatures", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Map<String, FeatureInfo> mAvailableFeatures = (Map<String, FeatureInfo>) param.getResult();
                    int openGLEsVersion = (int) XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader), "getInt", "ro.opengles.version", FeatureInfo.GL_ES_VERSION_UNDEFINED);
                    if (!mAvailableFeatures.containsKey(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                        FeatureInfo gyro = new FeatureInfo();
                        gyro.name = PackageManager.FEATURE_SENSOR_GYROSCOPE;
                        gyro.reqGlEsVersion = openGLEsVersion;
                        mAvailableFeatures.put(PackageManager.FEATURE_SENSOR_GYROSCOPE, gyro);
                    }
                    XposedHelpers.setObjectField(param.thisObject, "mAvailableFeatures", mAvailableFeatures);
                    param.setResult(mAvailableFeatures);
                }
            });
        } else {
            Class<?> pkgMgrSrv = XposedHelpers.findClass("com.android.server.pm.PackageManagerService", lpparam.classLoader);
            XposedBridge.hookAllMethods(pkgMgrSrv, "getSystemAvailableFeatures", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.getResult() != null) {
                        Object mPackages = XposedHelpers.getObjectField(param.thisObject, "mPackages");
                        synchronized (mPackages) {
                            Map<String, FeatureInfo> mAvailableFeatures = (Map<String, FeatureInfo>) XposedHelpers.getObjectField(param.thisObject, "mAvailableFeatures");
                            int openGLEsVersion = (int) XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader), "getInt", "ro.opengles.version", FeatureInfo.GL_ES_VERSION_UNDEFINED);
                            if (!mAvailableFeatures.containsKey(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                                FeatureInfo gyro = new FeatureInfo();
                                gyro.name = PackageManager.FEATURE_SENSOR_GYROSCOPE;
                                gyro.reqGlEsVersion = openGLEsVersion;
                                mAvailableFeatures.put(PackageManager.FEATURE_SENSOR_GYROSCOPE, gyro);
                            }
                            XposedHelpers.setObjectField(param.thisObject, "mAvailableFeatures", mAvailableFeatures);
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(pkgMgrSrv, "hasSystemFeature", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!(boolean)param.getResult() && (String)param.args[0] == PackageManager.FEATURE_SENSOR_GYROSCOPE) {
                        Object mPackages = XposedHelpers.getObjectField(param.thisObject, "mPackages");
                        synchronized (mPackages) {
                            Map<String, FeatureInfo> mAvailableFeatures = (Map<String, FeatureInfo>) param.getResult();
                            int openGLEsVersion = (int) XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader), "getInt", "ro.opengles.version", FeatureInfo.GL_ES_VERSION_UNDEFINED);
                            FeatureInfo gyro = new FeatureInfo();
                            gyro.name = PackageManager.FEATURE_SENSOR_GYROSCOPE;
                            gyro.reqGlEsVersion = openGLEsVersion;
                            mAvailableFeatures.put(PackageManager.FEATURE_SENSOR_GYROSCOPE, gyro);
                            XposedHelpers.setObjectField(param.thisObject, "mAvailableFeatures", mAvailableFeatures);
                            param.setResult(true);
                        }
                    }
                }
            });
        }
    }

    private void hookSensorValues(final LoadPackageParam lpparam) {
        if (Build.VERSION.SDK_INT >= 18) {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager$SensorEventQueue", lpparam.classLoader, "dispatchSensorEvent", int.class, float[].class, int.class, long.class, new SensorChangeHook.API18Plus(lpparam));
        } else {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager$ListenerDelegate", lpparam.classLoader, "onSensorChangedLocked", Sensor.class, float[].class, long[].class, int.class, new SensorChangeHook.API1617(lpparam));
        }
    }

    @SuppressWarnings("unchecked")
    private void addSensors(final LoadPackageParam lpparam) {

        // SystemSensorManager constructor hook, starting from SDK16
        if (Build.VERSION.SDK_INT == 16 || Build.VERSION.SDK_INT == 17) XposedHelpers.findAndHookConstructor("android.hardware.SystemSensorManager", lpparam.classLoader, android.os.Looper.class, new SystemSensorManagerHook.API1617(lpparam));
        else if (Build.VERSION.SDK_INT > 17 && Build.VERSION.SDK_INT < 23) XposedHelpers.findAndHookConstructor("android.hardware.SystemSensorManager", lpparam.classLoader, android.content.Context.class, android.os.Looper.class, new SystemSensorManagerHook.API18Plus(lpparam));
        else XposedHelpers.findAndHookConstructor("android.hardware.SystemSensorManager", lpparam.classLoader, android.content.Context.class, android.os.Looper.class, new SystemSensorManagerHook.API23Plus(lpparam));

        // registerListenerImpl hook
        if (Build.VERSION.SDK_INT <= 18) {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", lpparam.classLoader, "registerListenerImpl", android.hardware.SensorEventListener.class, android.hardware.Sensor.class, int.class, android.os.Handler.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[1] == null) return;
                    SensorEventListener listener = (SensorEventListener) param.args[0];

                    // We check that the listener isn't of type VirtualSensorListener. Although that should not happen, it would probably be nasty.
                    if (sensorsToEmulate.indexOfKey(((Sensor) param.args[1]).getType()) >= 0 && !(listener instanceof VirtualSensorListener)) {
                        SensorEventListener specialListener = new VirtualSensorListener(listener, ((Sensor) param.args[1]));
                        XposedHelpers.callMethod(param.thisObject, "registerListenerImpl",
                                specialListener,
                                XposedHelpers.callMethod(param.thisObject, "getDefaultSensor", Sensor.TYPE_ACCELEROMETER),
                                XposedHelpers.callStaticMethod(android.hardware.SensorManager.class, "getDelay", param.args[2]),
                                (android.os.Handler) param.args[3]
                        );
                        XposedHelpers.callMethod(param.thisObject, "registerListenerImpl",
                                specialListener,
                                XposedHelpers.callMethod(param.thisObject, "getDefaultSensor", Sensor.TYPE_MAGNETIC_FIELD),
                                XposedHelpers.callStaticMethod(android.hardware.SensorManager.class, "getDelay", param.args[2]),
                                (android.os.Handler) param.args[3]
                        );

                        param.args[0] = specialListener;
                    }
                }
            });
        } else {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", lpparam.classLoader, "registerListenerImpl", android.hardware.SensorEventListener.class, android.hardware.Sensor.class, int.class, android.os.Handler.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[1] == null) return;
                    SensorEventListener listener = (SensorEventListener) param.args[0];

                    // We check that the listener isn't of type VirtualSensorListener. Although that should not happen, it would probably be nasty.
                    if (sensorsToEmulate.indexOfKey(((Sensor) param.args[1]).getType()) >= 0 && !(listener instanceof VirtualSensorListener)) {
                        SensorEventListener specialListener = new VirtualSensorListener(listener, ((Sensor) param.args[1]));
                        XposedHelpers.callMethod(param.thisObject, "registerListenerImpl",
                                specialListener,
                                XposedHelpers.callMethod(param.thisObject, "getDefaultSensor", Sensor.TYPE_ACCELEROMETER),
                                XposedHelpers.callStaticMethod(android.hardware.SensorManager.class, "getDelay", param.args[2]),
                                (android.os.Handler) param.args[3],
                                0,
                                0
                        );
                        XposedHelpers.callMethod(param.thisObject, "registerListenerImpl",
                                specialListener,
                                XposedHelpers.callMethod(param.thisObject, "getDefaultSensor", Sensor.TYPE_MAGNETIC_FIELD),
                                XposedHelpers.callStaticMethod(android.hardware.SensorManager.class, "getDelay", param.args[2]),
                                (android.os.Handler) param.args[3],
                                0,
                                0
                        );

                        param.args[0] = specialListener;
                    }
                }
            });
        }

        // This hook does not need to change depending on the SDK version
        XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", lpparam.classLoader, "unregisterListenerImpl", android.hardware.SensorEventListener.class, android.hardware.Sensor.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                for (Map.Entry<Object, Object> entry : ((HashMap<Object, Object>) XposedHelpers.getObjectField(param.thisObject, "mSensorListeners")).entrySet()) {
                    SensorEventListener listener = (SensorEventListener) entry.getKey();

                    if (listener instanceof VirtualSensorListener) {
                        VirtualSensorListener specialListener = (VirtualSensorListener) listener;
                        if (specialListener.getRealListener() == (android.hardware.SensorEventListener) param.args[0]) {
                            XposedHelpers.callMethod(param.thisObject, "unregisterListenerImpl", listener, (Sensor) null);
                        }
                    }
                }
            }
        });
    }
}