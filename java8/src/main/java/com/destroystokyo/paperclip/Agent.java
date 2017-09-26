package com.destroystokyo.paperclip;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

public class Agent {

    static void addClassPath() {
        final ClassLoader loader = ClassLoader.getSystemClassLoader();
        if (!(loader instanceof URLClassLoader)) {
            throw new RuntimeException("System ClassLoader is not URLClassLoader");
        }
        try {
            final Method addURL = getAddMethod(loader);
            if (addURL == null) {
                System.err.println("Unable to find method to add Paper jar to System ClassLoader");
                System.exit(1);
            }
            addURL.setAccessible(true);
            addURL.invoke(loader, Paperclip.paperJar.toURI().toURL());
        } catch (final IllegalAccessException | InvocationTargetException | MalformedURLException e) {
            System.err.println("Unable to add Paper Jar to System ClassLoader");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Method getAddMethod(final Object o) {
        Class<?> clazz = o.getClass();
        Method m = null;
        while (m == null) {
            try {
                m = clazz.getDeclaredMethod("addURL", URL.class);
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
                if (clazz == null) {
                    return null;
                }
            }
        }
        return m;
    }
}
