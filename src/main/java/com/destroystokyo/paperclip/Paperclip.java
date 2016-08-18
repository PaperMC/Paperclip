/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperSpigot/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import org.apache.commons.io.IOUtils;
import org.jbsdiff.Patch;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.jar.JarInputStream;

public class Paperclip {

    private final static Path cache = Paths.get("cache");
    private final static Path customPatchInfo = Paths.get("paperclip.json");
    private static MessageDigest digest;

    public static void main(String[] args) {
        final double version = Double.parseDouble(System.getProperty("java.specification.version"));
        if (version < 1.8) {
            // get mad at them
            System.err.println("Paper requires Java 8, please upgrade to it.");
            System.err.println("http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html");
            System.exit(1);
        }

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Could not create hashing instance");
            e.printStackTrace();
            System.exit(1);
        }

        final PatchData patchInfo;
        try {
            if (Files.exists(customPatchInfo)) {
                patchInfo = PatchData.parse(Files.newInputStream(customPatchInfo));
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

        final Path vanillaJar = cache.resolve("mojang_" + patchInfo.getVersion() + ".jar");
        final Path paperJar = cache.resolve("patched_" + patchInfo.getVersion() + ".jar");

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
                    Files.createDirectory(cache);
                } catch (Exception ignored) {}

                try {
                    Files.copy(patchInfo.getOriginalUrl().openStream(), vanillaJar, StandardCopyOption.REPLACE_EXISTING);
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

            if (Files.exists(paperJar)) {
                try {
                    Files.delete(paperJar);
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
                vanillaJarBytes = Files.readAllBytes(vanillaJar);
                patch = IOUtils.toByteArray(patchInfo.getPatchFile().openStream());
            } catch (IOException e) {
                System.err.println("Error patching original jar");
                e.printStackTrace();
                System.exit(1);
                return;
            }

            // Patch the jar to create the final jar to run
            try (OutputStream output = Files.newOutputStream(paperJar)) {
                Patch.patch(vanillaJarBytes, patch, output);
            } catch (Exception e) {
                System.err.println("Error patching origin jar");
                e.printStackTrace();
                System.exit(1);
            }
        }

        // Get main class info from jar
        final String main;
        try (JarInputStream inputStream = new JarInputStream(Files.newInputStream(paperJar))) {
            main = inputStream.getManifest().getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            System.err.println("Error reading from patched jar");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // Run the jar
        final URL url;
        try {
            url = paperJar.toUri().toURL();
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
        } catch (Exception e) {
            System.err.println("Error running patched jar");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static boolean checkJar(Path jar, byte[] hash) throws IOException {
        if (Files.exists(jar)) {
            final byte[] jarBytes = Files.readAllBytes(jar);
            return Arrays.equals(hash, digest.digest(jarBytes));
        }
        return false;
    }
}
