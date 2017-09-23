package com.destroystokyo.paperclip;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

final class JarRunnerProvider {

    static JarRunner getJarRunner() {
        return (url, mainClass, args) -> {
            if (!(ClassLoader.getSystemClassLoader() instanceof URLClassLoader)) {
                System.err.println("SystemClassLoader not URLClassLoader");
                System.exit(1);
            }
            final URLClassLoader loader = (URLClassLoader) Paperclip.class.getClassLoader();

            // Add the url to the current system classloader
            final Method addUrl;
            try {
                addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addUrl.setAccessible(true);
                addUrl.invoke(loader, url);
            } catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                System.err.println("Error adding jar to the SystemClassLoader");
                e.printStackTrace();
                System.exit(1);
            }

            Utils.invoke(mainClass, loader, args);
        };
    }
}
