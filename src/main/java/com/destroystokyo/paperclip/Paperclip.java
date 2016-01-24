/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperSpigot/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.io.FileUtils;
import org.jbsdiff.InvalidHeaderException;
import org.jbsdiff.Patch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.jar.JarInputStream;

public class Paperclip {

    private final static File cache = new File("cache");
    private final static File vanillaJar = new File(cache, "vanilla.jar");
    private final static File paperJar = new File(cache, "paperspigot.jar");

    // Custom patches, if provided
    private final static File customPatch = new File(cache, "custom.patch");
    private final static File customInfo = new File(cache, "custom.info");

    private static boolean isCustom = customPatch.exists() && customInfo.exists();

    private static MessageDigest digest;

    // TODO: handle these exceptions more...gracefully
    public static void main(String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchAlgorithmException, CompressorException, InvalidHeaderException {
        digest = MessageDigest.getInstance("SHA-256");

        if ((customInfo.exists() || customPatch.exists()) && !isCustom) {
            System.out.println("Warning: a custom patch file was provided, but a custom info file was not. Running default instead.");
        }

        // TODO: switch to an actual data format at some point. I'm just feeling lazy atm
        String[] customInfo = new String[] { "", "", "" }; // prevent NPE's
        if (isCustom) {
            customInfo = readFile(Paperclip.customInfo).split("\\n");
            if (customInfo.length != 3) {
                System.err.println("Warning: custom info does not contain three lines. Running default instead.");
                isCustom = false;
            }
        }

        final boolean vanillaValid = checkJar(vanillaJar, Data.vanillaMinecraftHash, customInfo[1]);
        final boolean paperValid = checkJar(paperJar, Data.paperMinecraftHash, customInfo[0]);

        if (!paperValid) {
            if (!vanillaValid) {
                System.out.println("Downloading vanilla jar...");
                try {
                    FileUtils.forceMkdir(cache);
                    FileUtils.forceDelete(vanillaJar);
                } catch (Exception ignored) {}

                if (isCustom) {
                    FileUtils.copyURLToFile(new URL(customInfo[2]), vanillaJar);
                } else {
                    FileUtils.copyURLToFile(new URL("https://s3.amazonaws.com/Minecraft.Download/versions/1.8.8/minecraft_server.1.8.8.jar"), vanillaJar);
                }

                // Only continue from here if the downloaded jar is correct
                if (!checkJar(vanillaJar, Data.vanillaMinecraftHash, customInfo[1])) {
                    System.err.println("Vanilla server jar could not be downloaded successfully, quitting.");
                    System.exit(1);
                }
            }

            try {
                FileUtils.forceDelete(paperJar);
            } catch (Exception ignored) {}

            System.out.println("Patching vanilla jar...");
            final byte[] vanillaJarBytes = Files.readAllBytes(vanillaJar.toPath());

            final byte[] patch;
            if (isCustom) {
                // Get the patch data from the custom file the user provided
                patch = Files.readAllBytes(customPatch.toPath());
            } else {
                // Get the patch data that is included in the jar
                try (
                        final ByteArrayOutputStream os = new ByteArrayOutputStream();
                        final InputStream in = Paperclip.class.getClassLoader().getResourceAsStream("paperMC.patch")
                ) {
                    final byte[] buffer = new byte[4096];
                    int read;
                    for (;;) {
                        read = in.read(buffer);
                        if (read <= 0) {
                            break;
                        }
                        os.write(buffer, 0, read);
                    }
                    patch = os.toByteArray();
                }
            }

            // Patch the jar to create the final jar to run
            try (final FileOutputStream jarOutput = new FileOutputStream(paperJar)) {
                Patch.patch(vanillaJarBytes, patch, jarOutput);
            }
        }

        // Get main class info from jar
        final String main;
        try (
                final FileInputStream fs = new FileInputStream(paperJar);
                final JarInputStream js = new JarInputStream(fs)
        ) {
            main = js.getManifest().getMainAttributes().getValue("Main-Class");
        }

        // Run the jar
        final URL url = paperJar.toURI().toURL();
        final URLClassLoader loader = new URLClassLoader(new URL[] {url}, Paperclip.class.getClassLoader());
        final Class<?> cls = Class.forName(main, true, loader);
        final Method m = cls.getMethod("main", String[].class);

        // commons-logging requires this because it isn't well-behaved >.>
        Thread.currentThread().setContextClassLoader(loader);

        m.invoke(null, new Object[] {args});
    }

    private static boolean checkJar(File jar, byte[] defaultHash, String customHash) throws IOException {
        if (jar.exists()) {
            final byte[] jarBytes = Files.readAllBytes(jar.toPath());

            if (isCustom) {
                return customHash.equalsIgnoreCase(toHex(digest.digest(jarBytes)));
            } else {
                return Arrays.equals(defaultHash, digest.digest(jarBytes));
            }
        }
        return false;
    }

    private static String toHex(final byte[] hash) {
        final StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte aHash : hash) {
            sb.append(String.format("%02X", aHash & 0xFF));
        }
        return sb.toString();
    }

    private static String readFile(final File file) throws IOException {
        final byte[] encoded = Files.readAllBytes(file.toPath());
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(encoded)).toString();
    }
}
