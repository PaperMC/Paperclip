package io.papermc.paperclip;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

class Util {

    private Util() {}

    public static MessageDigest sha256Digest = getSha256Digest();

    private static MessageDigest getSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw fail("Could not create hashing instance", e);
        }
    }

    static byte[] readBytes(final Path file) {
        try {
            return readFully(Files.newInputStream(file));
        } catch (final IOException e) {
            throw fail("Failed to read all of the data from " + file.toAbsolutePath(), e);
        }
    }

    static byte[] readFully(final InputStream in) throws IOException {
        try (in) {
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
        }
    }

    static String readResourceText(final String path) throws IOException {
        final String p;
        if (path.startsWith("/")) {
            p = path;
        } else {
            p = "/" + path;
        }
        final InputStream stream = Util.class.getResourceAsStream(p);
        if (stream == null) {
            return null;
        }

        final StringWriter writer = new StringWriter();
        try (stream) {
            final Reader reader = new InputStreamReader(stream);
            reader.transferTo(writer);
        }

        return writer.toString();
    }

    static boolean isDataValid(final byte[] data, final byte[] hash) {
        return Arrays.equals(hash, sha256Digest.digest(data));
    }
    static boolean isFileValid(final Path file, final byte[] hash) {
        if (Files.exists(file)) {
            final byte[] fileBytes = readBytes(file);
            return isDataValid(fileBytes, hash);
        }
        return false;
    }

    static byte[] fromHex(final String s) {
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Length of hex " + s + " must be divisible by two");
        }
        try {
            final byte[] bytes = new byte[s.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                final char left = s.charAt(i * 2);
                final char right = s.charAt(i * 2 + 1);
                final byte b = (byte) ((getHexValue(left) << 4) | (getHexValue(right) & 0xF));
                bytes[i] = b;
            }
            return bytes;
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot convert non-hex string: " + s);
        }
    }

    private static int getHexValue(final char c) {
        final int i = Character.digit(c, 16);
        if (i < 0) {
            throw new IllegalArgumentException("Invalid hex char: " + c);
        }
        return i;
    }

    static RuntimeException fail(final String message, final Throwable err) {
        System.err.println(message);
        if (err != null) {
            err.printStackTrace();
        }
        System.exit(1);
        throw new InternalError();
    }

    @SuppressWarnings("unchecked")
    static <X extends Throwable> RuntimeException sneakyThrow(final Throwable ex) throws X {
        throw (X) ex;
    }

    static String endingSlash(final String dir) {
        if (dir.endsWith("/")) {
            return dir;
        }
        return dir + "/";
    }
}
