/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

@file:JvmName("Paperclip")
package com.destroystokyo.paperclip

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.jbsdiff.Patch

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.net.MalformedURLException
import java.net.URL
import java.net.URLClassLoader
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import java.util.jar.JarInputStream

private val cache = File("cache")
private val customPatchInfo = File("paperclip.json")
private var digest: MessageDigest? = null

fun main(args: Array<String>) {
    val version = System.getProperty("java.specification.version").toDouble()
    if (version < 1.8) {
        // get mad at them
        error("Paper requires Java 8, please upgrade to it.\n" +
            "http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html")
    }

    try {
        digest = MessageDigest.getInstance("SHA-256")
    } catch (e: NoSuchAlgorithmException) {
        error("Could not create hashing instance", e)
    }

    val patchInfo = try {
        if (customPatchInfo.exists()) {
            FileInputStream(customPatchInfo).parse()
        } else {
            object {}.javaClass.getResource("/patch.json").openStream().parse()
        }
    } catch (e: IllegalArgumentException) {
        error("Invalid patch file", e)
    } catch (e: IOException) {
        error("Error reading patch file", e)
    }

    val vanillaJar = File(cache, "mojang_${patchInfo.version}.jar")
    val paperJar = File(cache, "patched_${patchInfo.version}.jar")

    val (vanillaValid, paperValid) = try {
        checkJar(vanillaJar, patchInfo.originalHash) to checkJar(paperJar, patchInfo.patchedHash)
    } catch (e: IOException) {
        error("Error reading jar", e)
    }

    if (!paperValid) {
        if (!vanillaValid) {
            println("Downloading original jar...")
            try {
                FileUtils.forceMkdir(cache)
                FileUtils.forceDelete(vanillaJar)
            } catch (ignored: Exception) {}

            try {
                FileUtils.copyURLToFile(patchInfo.originalUrl, vanillaJar)
            } catch (e: IOException) {
                error("Error downloading original jar", e)
            }

            // Only continue from here if the downloaded jar is correct
            try {
                if (!checkJar(vanillaJar, patchInfo.originalHash)) {
                    error("Invalid original jar, quitting.")
                }
            } catch (e: IOException) {
                error("Error reading jar", e)
            }
        }

        if (paperJar.exists()) {
            try {
                FileUtils.forceDelete(paperJar)
            } catch (e: IOException) {
                error("Error deleting invalid jar", e)
            }
        }

        println("Patching original jar...")
        val (vanillaJarBytes, patch) = try {
            IOUtils.toByteArray(vanillaJar.toURI()) to patchInfo.patchFile.openStream().readFully()
        } catch (e: IOException) {
            error("Error patching original jar", e)
        }

        // Patch the jar to create the final jar to run
        FileOutputStream(paperJar).use {
            Patch.patch(vanillaJarBytes, patch, it)
        }
    }

    // Get main class info from jar
    val main = FileInputStream(paperJar).use { fs ->
        JarInputStream(fs).use { js ->
            js.manifest.mainAttributes.getValue("Main-Class")
        }
    }

    // Run the jar
    val url = try {
        paperJar.toURI().toURL()
    } catch (e: MalformedURLException) {
        error("Error reading path to patched jar", e)
    }

    val loader = ClassLoader.getSystemClassLoader() as? URLClassLoader ?: error("SystemClassLoader not URLClassLoader")

    // Add the url to the current system classloader
    val addUrl = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
    addUrl.isAccessible = true
    addUrl.invoke(loader, url)

    val cls: Class<*>
    val m: Method
    try {
        cls = Class.forName(main, true, loader)
        m = cls.getMethod("main", Array<String>::class.java)

        // commons-logging requires this because it isn't well-behaved >.>
        Thread.currentThread().contextClassLoader = loader

        m.invoke(null, args)
    } catch (e: Exception) {
        error("Error running patched jar", e)
    }
}

private fun checkJar(jar: File, hash: ByteArray): Boolean {
    if (jar.exists()) {
        val jarBytes = IOUtils.toByteArray(jar.toURI())
        return Arrays.equals(hash, digest!!.digest(jarBytes))
    }
    return false
}
