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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.jar.JarInputStream;

public class Paperclip {

    private final static File cache = new File("cache");
    private final static File vanillaJar = new File(cache, "original.jar");
    private final static File paperJar = new File(cache, "patched.jar");
    private final static File customPatchInfo = new File("paperclip.json");
    private static MessageDigest digest;

    // TODO: handle these exceptions more...gracefully
    public static void main(String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchAlgorithmException, CompressorException, InvalidHeaderException {
        digest = MessageDigest.getInstance("SHA-256");

        final PatchData patchInfo;
        try {
            if (customPatchInfo.exists()) {
                patchInfo = PatchData.parse(new FileInputStream(customPatchInfo));
            } else {
                patchInfo = PatchData.parse(Paperclip.class.getResource("/patch.json").openStream());
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid patch file");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        final boolean vanillaValid = checkJar(vanillaJar, patchInfo.getOriginalHash());
        final boolean paperValid = checkJar(paperJar, patchInfo.getPatchedHash());

        if (!paperValid) {
            if (!vanillaValid) {
                System.out.println("Downloading original jar...");
                try {
                    FileUtils.forceMkdir(cache);
                    FileUtils.forceDelete(vanillaJar);
                } catch (Exception ignored) {}

                FileUtils.copyURLToFile(patchInfo.getOriginalUrl(), vanillaJar);

                // Only continue from here if the downloaded jar is correct
                if (!checkJar(vanillaJar, patchInfo.getOriginalHash())) {
                    System.err.println("Invalid original jar, quitting.");
                    System.exit(1);
                }
            }

            if (paperJar.exists()) {
                FileUtils.forceDelete(paperJar);
            }

            System.out.println("Patching original jar...");
            final byte[] vanillaJarBytes = Files.readAllBytes(vanillaJar.toPath());

            final byte[] patch = Utils.readFully(patchInfo.getPatchFile().openStream());

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

    private static boolean checkJar(File jar, byte[] hash) throws IOException {
        if (jar.exists()) {
            final byte[] jarBytes = Files.readAllBytes(jar.toPath());
            return Arrays.equals(hash, digest.digest(jarBytes));
        }
        return false;
    }
}
