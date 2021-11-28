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
        if (getJavaVersion() < 17) {
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

    private static int getJavaVersion() {
        final String version = System.getProperty("java.class.version");
        final String[] parts = version.split("\\.");

        if (parts.length == 0) {
            throw new IllegalStateException("Could not determine version of the current JVM");
        }

        // class file versions: 1.1 = 45, 1.2 = 46, ..., 18 = 62
        return Integer.parseInt(parts[0]) - 44;
    }
}
