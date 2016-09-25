/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
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
            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            final ReadableByteChannel readChannel = Channels.newChannel(in);
            final WritableByteChannel writeChannel = Channels.newChannel(stream);

            final ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);

            while (readChannel.read(buffer) >= 0 || buffer.position() > 0) {
                buffer.flip();
                writeChannel.write(buffer);
                buffer.compact();
            }

            return stream.toByteArray();
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
