package io.selendroid.server.model;

/**
 * Created by dhimil on 25/03/14.
 */
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Parcelable;
import android.util.Log;

import org.apache.http.HttpHost;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Utility class for setting WebKit proxy used by Android WebView
 *
 */
public class ProxySettings {
    private static final String TAG = "BrowserStack-GAEProxy.ProxySettings";

    static final int PROXY_CHANGED = 193;

    private static Object getDeclaredField(Object obj, String name) throws SecurityException,
            NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        Object out = f.get(obj);

        return out;
    }

    public static Object getRequestQueue(Context ctx) throws Exception {
        Object ret = null;
        Class networkClass = Class.forName("android.webkit.Network");
        if (networkClass != null) {
            Object networkObj = invokeMethod(networkClass, "getInstance", new Object[] { ctx },
                    Context.class);
            if (networkObj != null) {
                ret = getDeclaredField(networkObj, "mRequestQueue");
            }
        }
        return ret;
    }

    private static Object invokeMethod(Object object, String methodName, Object[] params,
                                       Class... types) throws Exception {
        Object out = null;
        Class c = object instanceof Class ? (Class) object : object.getClass();
        if (types != null) {
            Method method = c.getMethod(methodName, types);
            out = method.invoke(object, params);
        } else {
            Method method = c.getMethod(methodName);
            out = method.invoke(object);
        }

        return out;
    }

    public static void resetProxy(Context ctx) throws Exception {
        Object requestQueueObject = getRequestQueue(ctx);
        if (requestQueueObject != null) {
            setDeclaredField(requestQueueObject, "mProxyHost", null);
        }
    }

    private static void setDeclaredField(Object obj, String name, Object value)
            throws SecurityException, NoSuchFieldException, IllegalArgumentException,
            IllegalAccessException {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    /**
     * Override WebKit Proxy settings
     *
     * @param ctx
     *            Android ApplicationContext
     * @param host
     * @param port
     * @return true if Proxy was successfully set
     */
    public static boolean setProxy(Context ctx, String host, int port) {
        boolean ret = false;
        setSystemProperties(host, port);

        try {
            int sdkVersion = Build.VERSION.SDK_INT;
            if (sdkVersion < 14) {

                Object requestQueueObject = getRequestQueue(ctx);
                if (requestQueueObject != null) {
                    // Create Proxy config object and set it into request Q
                    HttpHost httpHost = new HttpHost(host, port, "http");

                    setDeclaredField(requestQueueObject, "mProxyHost", httpHost);
                    ret = true;
                }

            } else if (sdkVersion > 18) {
                ret = setKitKatWebViewProxy(ctx, host, port);
            } else {
                ret = setICSProxy(host, port);
            }
        } catch (Exception e) {
            Log.e(TAG, "error setting up webkit proxying", e);
        }
        return ret;
    }

    private static boolean setICSProxy(String host, int port) throws ClassNotFoundException,
            NoSuchMethodException, IllegalArgumentException, InstantiationException,
            IllegalAccessException, InvocationTargetException {
        Class webViewCoreClass = Class.forName("android.webkit.WebViewCore");
        Class proxyPropertiesClass = Class.forName("android.net.ProxyProperties");
        if (webViewCoreClass != null && proxyPropertiesClass != null) {
            Method m = webViewCoreClass.getDeclaredMethod("sendStaticMessage", Integer.TYPE,
                    Object.class);
            Constructor c = proxyPropertiesClass.getConstructor(String.class, Integer.TYPE,
                    String.class);
            m.setAccessible(true);
            c.setAccessible(true);
            Object properties = c.newInstance(host, port, null);
            m.invoke(null, PROXY_CHANGED, properties);
            return true;
        }
        return false;

    }

    public static boolean setKitKatWebViewProxy(Context appContext, String host, int port) {
        try {
            Class applictionCls = Class.forName("android.app.Application");
            Field loadedApkField = applictionCls.getDeclaredField("mLoadedApk");
            loadedApkField.setAccessible(true);
            Object loadedApk = loadedApkField.get(appContext);
            Class loadedApkCls = Class.forName("android.app.LoadedApk");
            Field receiversField = loadedApkCls.getDeclaredField("mReceivers");
            receiversField.setAccessible(true);
            Map receivers = (Map) receiversField.get(loadedApk);
            for (Object receiverMap : receivers.values()) {
                for (Object rec : ((Map) receiverMap).keySet()) {
                    Class clazz = rec.getClass();
                    if (clazz.getName().contains("ProxyChangeListener")) {
                        Method onReceiveMethod = clazz.getDeclaredMethod("onReceive", Context.class, Intent.class);
                        Intent intent = new Intent(android.net.Proxy.PROXY_CHANGE_ACTION);
                        final String CLASS_NAME = "android.net.ProxyProperties";
                        Class cls = Class.forName(CLASS_NAME);
                        Constructor constructor = cls.getConstructor(String.class, Integer.TYPE, String.class);
                        constructor.setAccessible(true);
                        Object proxyProperties = constructor.newInstance(host, port, null);
                        intent.putExtra("proxy", (Parcelable) proxyProperties);
                        onReceiveMethod.invoke(rec, appContext, intent);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.i(TAG, e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void setSystemProperties(String host, int port) {

        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port + "");

        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port + "");

    }
}
