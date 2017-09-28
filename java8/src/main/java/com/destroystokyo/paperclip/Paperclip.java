/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2017 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.jar.JarInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.jbsdiff.InvalidHeaderException;
import org.jbsdiff.Patch;

class Paperclip {

    private final static File cache = new File("cache");
    private static MessageDigest digest;
    private final static File customPatchInfo = new File("paperclip.json");

    static File paperJar;

    static void run(final String[] args) {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Could not create hashing instance");
            e.printStackTrace();
            System.exit(1);
        }

        final PatchData patchInfo;
        try (final InputStream is = getConfig()) {
            patchInfo = PatchData.parse(is);
        } catch (final IllegalArgumentException e) {
            System.err.println("Invalid patch file");
            e.printStackTrace();
            System.exit(1);
            return;
        } catch (final IOException e) {
            System.err.println("Error reading patch file");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        final File vanillaJar = new File(cache, "mojang_" + patchInfo.getVersion() + ".jar");
        paperJar = new File(cache, "patched_" + patchInfo.getVersion() + ".jar");

        final boolean vanillaValid;
        final boolean paperValid;
        try {
            vanillaValid = checkJar(vanillaJar, patchInfo.getOriginalHash());
            paperValid = checkJar(paperJar, patchInfo.getPatchedHash());
        } catch (final IOException e) {
            System.err.println("Error reading jar");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        if (!paperValid) {
            if (!vanillaValid) {
                System.out.println("Downloading original jar...");
                //noinspection ResultOfMethodCallIgnored
                cache.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                vanillaJar.delete();

                try (final InputStream stream = patchInfo.getOriginalUrl().openStream()) {
                    final ReadableByteChannel rbc = Channels.newChannel(stream);
                    try (final FileOutputStream fos = new FileOutputStream(vanillaJar)) {
                        fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                    }
                } catch (final IOException e) {
                    System.err.println("Error downloading original jar");
                    e.printStackTrace();
                    System.exit(1);
                }

                // Only continue from here if the downloaded jar is correct
                try {
                    if (!checkJar(vanillaJar, patchInfo.getOriginalHash())) {
                        System.err.println("Invalid original jar, quitting.");
                        System.exit(1);
                    }
                } catch (final IOException e) {
                    System.err.println("Error reading jar");
                    e.printStackTrace();
                    System.exit(1);
                }
            }

            if (paperJar.exists()) {
                if (!paperJar.delete()) {
                    System.err.println("Error deleting invalid jar");
                    System.exit(1);
                }
            }

            System.out.println("Patching original jar...");
            final byte[] vanillaJarBytes;
            final byte[] patch;
            try {
                vanillaJarBytes = getBytes(vanillaJar);
                patch = Utils.readFully(patchInfo.getPatchFile().openStream());
            } catch (final IOException e) {
                System.err.println("Error patching original jar");
                e.printStackTrace();
                System.exit(1);
                return;
            }

            // Patch the jar to create the final jar to run
            try (final FileOutputStream jarOutput = new FileOutputStream(paperJar)) {
                Patch.patch(vanillaJarBytes, patch, jarOutput);
            } catch (final CompressorException | InvalidHeaderException | IOException e) {
                System.err.println("Error patching origin jar");
                e.printStackTrace();
                System.exit(1);
            }
        }

        // Exit if user has set `paperclip.patchonly` system property to `true`
        if (Boolean.getBoolean("paperclip.patchonly")) {
            System.exit(0);
        }

        // Get main class info from jar
        final String main;
        try (final FileInputStream fs = new FileInputStream(paperJar);
             final JarInputStream js = new JarInputStream(fs)) {
            main = js.getManifest().getMainAttributes().getValue("Main-Class");
        } catch (final IOException e) {
            System.err.println("Error reading from patched jar");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // Run the jar
        Utils.invoke(main, args);
    }

    private static InputStream getConfig() throws IOException {
        if (customPatchInfo.exists()) {
            return new FileInputStream(customPatchInfo);
        } else {
            return Paperclip.class.getResource("/patch.json").openStream();
        }
    }

    private static byte[] getBytes(final File file) throws IOException {
        final byte[] b = new byte[(int) file.length()];

        try (final FileInputStream fis = new FileInputStream(file)) {
            if (fis.read(b) != b.length) {
                System.err.println("Error reading all the data from " + file.getAbsolutePath());
                System.exit(1);
            }
        }

        return b;
    }

    private static boolean checkJar(final File jar, final byte[] hash) throws IOException {
        if (jar.exists()) {
            final byte[] jarBytes = getBytes(jar);
            return Arrays.equals(hash, digest.digest(jarBytes));
        }
        return false;
    }
}
