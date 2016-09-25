/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import org.jbsdiff.Patch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.jar.JarInputStream;

public class Paperclip {

    private final static File cache = new File("cache");
    private final static File customPatchInfo = new File("paperclip.json");
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

        final File vanillaJar = new File(cache, "mojang_" + patchInfo.getVersion() + ".jar");
        final File paperJar = new File(cache, "patched_" + patchInfo.getVersion() + ".jar");

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
                //noinspection ResultOfMethodCallIgnored
                cache.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                vanillaJar.delete();

                InputStream stream = null;
                FileOutputStream fos = null;
                try {
                    stream = patchInfo.getOriginalUrl().openStream();
                    final ReadableByteChannel rbc = Channels.newChannel(stream);
                    fos = new FileOutputStream(vanillaJar);
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                } catch (IOException e) {
                    System.err.println("Error downloading original jar");
                    e.printStackTrace();
                    System.exit(1);
                } finally {
                    try {
                        if (stream != null) {
                            stream.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        if (fos != null) {
                            fos.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
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
            } catch (IOException e) {
                System.err.println("Error patching original jar");
                e.printStackTrace();
                System.exit(1);
                return;
            }

            // Patch the jar to create the final jar to run
            FileOutputStream jarOutput = null;
            try {
                jarOutput = new FileOutputStream(paperJar);
                Patch.patch(vanillaJarBytes, patch, jarOutput);
            } catch (Exception e) {
                System.err.println("Error patching origin jar");
                e.printStackTrace();
                System.exit(1);
            } finally {
                if (jarOutput != null) {
                    try {
                        jarOutput.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // Get main class info from jar
        final String main;
        FileInputStream fs = null;
        JarInputStream js = null;
        try {
            fs = new FileInputStream(paperJar);
            js = new JarInputStream(fs);
            main = js.getManifest().getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            System.err.println("Error reading from patched jar");
            e.printStackTrace();
            System.exit(1);
            return;
        } finally {
            if (fs != null) {
                try {
                    fs.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (js != null) {
                try {
                    js.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
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

        if (!(ClassLoader.getSystemClassLoader() instanceof URLClassLoader)) {
            System.err.println("SystemClassLoader not URLClassLoader");
            System.exit(1);
        }
        final URLClassLoader loader = (URLClassLoader) ClassLoader.getSystemClassLoader();

        // Add the url to the current system classloader
        final Method addUrl;
        try {
            addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(loader, url);
        } catch (Exception e) {
            System.err.println("Error adding jar to the SystemClassLoader");
            e.printStackTrace();
            System.exit(1);
        }

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

    private static byte[] getBytes(final File file) throws IOException {
        final byte[] b = new byte[(int) file.length()];

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);

            //noinspection ResultOfMethodCallIgnored
            fis.read(b);
        } finally {
            if (fis != null) {
                fis.close();
            }
        }

        return b;
    }

    private static boolean checkJar(File jar, byte[] hash) throws IOException {
        if (jar.exists()) {
            final byte[] jarBytes = getBytes(jar);
            return Arrays.equals(hash, digest.digest(jarBytes));
        }
        return false;
    }
}
