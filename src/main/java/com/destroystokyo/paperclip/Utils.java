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
import java.util.Arrays;

class Utils {

    static byte[] fromHex(String s) {
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex " + s + " must be divisible by two");
        }
        byte[] bytes = new byte[s.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            char left = s.charAt(i * 2);
            char right = s.charAt(i * 2 + 1);
            byte b = (byte) ((getValue(left) << 4) | (getValue(right) & 0xF));
            bytes[i] = b;
        }
        return bytes;
    }

    static byte[] readFully(InputStream in) throws IOException {
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

    private static int getValue(char c) {
        int i = Character.digit(c, 16);
        if (i < 0) {
            throw new IllegalArgumentException("Invalid hex char: " + c);
        }
        return i;
    }
}
