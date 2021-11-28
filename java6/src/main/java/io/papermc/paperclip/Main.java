/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package io.papermc.paperclip;

import java.lang.reflect.Method;

public final class Main {

    public static void main(final String[] args) {
        float javaVersion = Float.parseFloat(System.getProperty("java.class.version"));
        if (javaVersion < 61f) {
            System.err.println("Minecraft 1.18 requires running the server with Java 17 or above. " +
                "Download Java 17 (or above) from https://adoptium.net/");
            System.exit(1);
        }

        try {
            final Class<?> paperclipClass = Class.forName("io.papermc.paperclip.Paperclip");
            final Method mainMethod = paperclipClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }
}
