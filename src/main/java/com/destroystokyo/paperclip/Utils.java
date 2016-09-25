/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

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
            // We don't know the final size, and we will only ever iterate over this list
            final ArrayList<Byte> bytes = new ArrayList<Byte>();
            // 16kb sounds about right, idk
            final int SIZE = 16 * 1024;
            byte[] b = new byte[SIZE];

            int read = in.read(b);
            while (read == SIZE) {
                for (byte a : b) {
                    bytes.add(a);
                }
                read = in.read(b);
            }

            // Finish copying the final bytes in, if there are any left
            if (read != -1) {
                for (int i = 0; i < read; i++) {
                    bytes.add(b[i]);
                }
            }


            final byte[] finalArray = new byte[bytes.size()];
            for (int i = 0; i < finalArray.length; i++) {
                finalArray[i] = bytes.get(i);
            }

            return finalArray;
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
