package com.destroystokyo.paperclip;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;

final class JarRunnerProvider {

    static JarRunner getJarRunner() {
        return (url, mainClass, args) -> {
            final ClassLoader cl = ClassLoader.getSystemClassLoader();
            final String name = cl.getClass().getName();
            if (!name.equals("jdk.internal.loader.ClassLoaders$AppClassLoader")) {
                System.err.println("SystemClassLoader not AppClassLoader");
                System.exit(1);
            }

            final Object ucp;
            try {
                final Field ucpField = cl.getClass().getDeclaredField("ucp");
                ucpField.setAccessible(true);
                final Method setAccessible0 = ucpField.getClass().getSuperclass().getDeclaredMethod("setAccessible0", boolean.class);
                setAccessible0.setAccessible(true);
                setAccessible0.invoke(ucpField, true);
                ucp = ucpField.get(cl);
            } catch (final NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                System.err.println("Unable to access URLClassPath field in AppClassLoader");
                e.printStackTrace();
                System.exit(1);
                return;
            }

            try {
                final Method addURL = ucp.getClass().getDeclaredMethod("addURL", URL.class);
                final Method setAccessible0 = addURL.getClass().getSuperclass().getSuperclass().getDeclaredMethod("setAccessible0", boolean.class);
                setAccessible0.setAccessible(true);
                setAccessible0.invoke(addURL, true);
                addURL.invoke(ucp, url);
            } catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                System.err.println("Unable to add jar URL to URLClassPath in AppClassLoader");
                e.printStackTrace();
                System.exit(1);
            }

            Utils.invoke(mainClass, cl, args);
        };
    }
}
