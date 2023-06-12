/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package io.papermc.paperclip;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public final class Main {

    private static final JsonObject VERSION = readJsonFile();

    private static JsonObject readJsonFile() {
        final JsonObject object;

        InputStreamReader reader = null;

        try {
            reader = new InputStreamReader(Main.class.getClassLoader().getResourceAsStream("version.json"), "UTF_8");
            object = Json.parse(reader).asObject();
        } catch (final IOException exc) {
            throw new RuntimeException("Failed to read version.json", exc);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return object;
    }

    public static void main(final String[] args) {
        final int javaVersion = VERSION.getInt("java_version", 17);
        final String minecraftVersion = VERSION.getString("name", "Unknown");
        if (getJavaVersion() < javaVersion) {
            System.err.printf("Minecraft %s requires running the server with Java %s or above. " +
                    "Download Java %s (or above) from https://adoptium.net/", minecraftVersion, javaVersion, javaVersion);
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
