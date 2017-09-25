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
            final Method addURL = ((URLClassLoader) loader).getClass().getSuperclass().getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            addURL.invoke(loader, Paperclip.paperJar.toURI().toURL());
        } catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException | MalformedURLException e) {
            System.err.println("Unable to add Paper Jar to System ClassLoader");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
