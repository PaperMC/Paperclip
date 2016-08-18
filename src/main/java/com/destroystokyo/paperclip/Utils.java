/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperSpigot/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

public final class Utils {

    public static byte[] fromHex(String s) {
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

    private static int getValue(char c) {
        int i = Character.digit(c, 16);
        if (i < 0) {
            throw new IllegalArgumentException("Invalid hex char: " + c);
        }
        return i;
    }
}
