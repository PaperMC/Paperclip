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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
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

    public static void main(String[] args) {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Could not create hashing instance");
            e.printStackTrace();
            System.exit(1);
        }

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
        } catch (IOException e) {
            System.err.println("Error reading patch file");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        final boolean vanillaValid;
        final boolean paperValid;
        try {
            vanillaValid = checkJar(vanillaJar, patchInfo.getOriginalHash());
            paperValid = checkJar(paperJar, patchInfo.getPatchedHash());
        } catch (IOException e) {
            System.err.println("Error reading jar");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        if (!paperValid) {
            if (!vanillaValid) {
                System.out.println("Downloading original jar...");
                try {
                    FileUtils.forceMkdir(cache);
                    FileUtils.forceDelete(vanillaJar);
                } catch (Exception ignored) {}

                try {
                    FileUtils.copyURLToFile(patchInfo.getOriginalUrl(), vanillaJar);
                } catch (IOException e) {
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
                } catch (IOException e) {
                    System.err.println("Error reading jar");
                    e.printStackTrace();
                    System.exit(1);
                }
            }

            if (paperJar.exists()) {
                try {
                    FileUtils.forceDelete(paperJar);
                } catch (IOException e) {
                    System.err.println("Error deleting invalid jar");
                    e.printStackTrace();
                    System.exit(1);
                }
            }

            System.out.println("Patching original jar...");
            final byte[] vanillaJarBytes;
            final byte[] patch;
            try {
                vanillaJarBytes = Files.readAllBytes(vanillaJar.toPath());
                patch = Utils.readFully(patchInfo.getPatchFile().openStream());
            } catch (IOException e) {
                System.err.println("Error patching original jar");
                e.printStackTrace();
                System.exit(1);
                return;
            }

            // Patch the jar to create the final jar to run
            try (final FileOutputStream jarOutput = new FileOutputStream(paperJar)) {
                Patch.patch(vanillaJarBytes, patch, jarOutput);
            } catch (IOException | InvalidHeaderException | CompressorException e) {
                System.err.println("Error patching origin jar");
                e.printStackTrace();
                System.exit(1);
            }
        }

        // Get main class info from jar
        final String main;
        try (
                final FileInputStream fs = new FileInputStream(paperJar);
                final JarInputStream js = new JarInputStream(fs)
        ) {
            main = js.getManifest().getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            System.err.println("Error reading from patched jar");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // Run the jar
        final URL url;
        try {
            url = paperJar.toURI().toURL();
        } catch (MalformedURLException e) {
            System.err.println("Error reading path to patched jar");
            e.printStackTrace();
            System.exit(1);
            return;
        }
        final URLClassLoader loader = new URLClassLoader(new URL[] {url}, Paperclip.class.getClassLoader());
        final Class<?> cls;
        final Method m;
        try {
            cls = Class.forName(main, true, loader);
            m = cls.getMethod("main", String[].class);

            // commons-logging requires this because it isn't well-behaved >.>
            Thread.currentThread().setContextClassLoader(loader);

            m.invoke(null, new Object[] {args});
        } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            System.err.println("Error running patched jar");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static boolean checkJar(File jar, byte[] hash) throws IOException {
        if (jar.exists()) {
            final byte[] jarBytes = Files.readAllBytes(jar.toPath());
            return Arrays.equals(hash, digest.digest(jarBytes));
        }
        return false;
    }
}
