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
            System.err.println("마인크래프트 1.19는 Java 17이상이 필요해요. " +
                "https://adoptium.net/ 에서 java 17을 다운로드받아주세요.");
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
