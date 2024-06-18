/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package io.papermc.paperclip;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.jar.Manifest;

public final class Main {

    public static void main(final String[] args) {
        final Manifest manifest = manifest();
        final String javaVerString = manifest.getMainAttributes().getValue("Java-Runtime-Major-Version");
        if (javaVerString == null) {
            throw new RuntimeException("Manifest missing Java-Runtime-Major-Version");
        }
        final int javaVer;
        try {
            javaVer = Integer.parseInt(javaVerString);
        } catch (final NumberFormatException e) {
            throw new RuntimeException("Failed to parse Java-Runtime-Major-Version", e);
        }
        final String mcVer = manifest.getMainAttributes().getValue("Minecraft-Version");
        if (mcVer == null) {
            throw new RuntimeException("Manifest missing Minecraft-Version");
        }
        if (getJavaVersion() < javaVer) {
            System.err.printf(
                    "Minecraft %s requires running the server with Java %s or above. " +
                            "For information on how to update Java, see https://docs.papermc.io/misc/java-install\n",
                    mcVer,
                    javaVer
            );
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

    @SuppressWarnings("ThrowFromFinallyBlock")
    private static Manifest manifest() {
        final InputStream in = Main.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF");
        if (in == null) {
            throw new RuntimeException("Failed to locate manifest");
        }
        try {
            return new Manifest(in);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to read manifest", e);
        } finally {
            try {
                in.close();
            } catch (final IOException e) {
                throw new RuntimeException("Exception closing stream", e);
            }
        }
    }

    private static int getJavaVersion() {
        final String version = System.getProperty("java.specification.version");
        final String[] parts = version.split("\\.");

        final String errorMsg = "Could not determine version of the current JVM";
        if (parts.length == 0) {
            throw new IllegalStateException(errorMsg);
        }

        if (parts[0].equals("1")) {
            if (parts.length < 2) {
                throw new IllegalStateException(errorMsg);
            }
            return Integer.parseInt(parts[1]);
        } else {
            return Integer.parseInt(parts[0]);
        }
    }
}
