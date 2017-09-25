/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2017 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

class Utils {

    static byte[] fromHex(final String s) {
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex " + s + " must be divisible by two");
        }
        final byte[] bytes = new byte[s.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            final char left = s.charAt(i * 2);
            final char right = s.charAt(i * 2 + 1);
            final byte b = (byte) ((getValue(left) << 4) | (getValue(right) & 0xF));
            bytes[i] = b;
        }
        return bytes;
    }

    static byte[] readFully(final InputStream in) throws IOException {
        try {
            // In a test this was 12 ms quicker than a ByteBuffer
            // and for some reason that matters here.
            byte[] buffer = new byte[16 * 1024];
            int off = 0;
            int read;
            while ((read = in.read(buffer, off, buffer.length - off)) != -1) {
                off += read;
                if (off == buffer.length) {
                    buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }
            }
            return Arrays.copyOfRange(buffer, 0, off);
        } finally {
            in.close();
        }
    }

    static void invoke(final String mainClass, final String[] args) {
        Agent.addClassPath();
        try {
            final Class<?> cls = Class.forName(mainClass, true, ClassLoader.getSystemClassLoader());
            final Method m = cls.getMethod("main", String[].class);

            m.invoke(null, new Object[] {args});
        } catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
            System.err.println("Error running patched jar");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int getValue(final char c) {
        int i = Character.digit(c, 16);
        if (i < 0) {
            throw new IllegalArgumentException("Invalid hex char: " + c);
        }
        return i;
    }
}
