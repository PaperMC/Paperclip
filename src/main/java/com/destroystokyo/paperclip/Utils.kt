/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

@file:JvmName("Utils")
package com.destroystokyo.paperclip

import org.apache.commons.io.IOUtils
import org.apache.commons.io.output.ByteArrayOutputStream
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.io.BufferedReader

import java.io.InputStream
import java.io.InputStreamReader

fun String.fromHex(): ByteArray {
    if (length % 2 != 0) {
        throw IllegalArgumentException("Hex $this must be divisible by two")
    }
    val bytes = ByteArray(length / 2)
    for (i in bytes.indices) {
        val left = this[i * 2]
        val right = this[i * 2 + 1]
        val b = (left.getValue() shl 4 or (right.getValue() and 0xF)).toByte()
        bytes[i] = b
    }
    return bytes
}

fun InputStream.readFully(): ByteArray {
    val out = ByteArrayOutputStream()
    IOUtils.copy(this, out)
    return out.toByteArray()
}

fun Char.getValue(): Int {
    val i = Character.digit(this, 16)
    if (i < 0) {
        throw IllegalArgumentException("Invalid hex char: $this")
    }
    return i
}

fun InputStream.parse(): PatchData {
    try {
        val obj = JSONParser().parse(BufferedReader(InputStreamReader(this, Charsets.UTF_8)))
        return PatchData(obj as JSONObject)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid json", e)
    }
}

fun error(s: String, e: Throwable): Nothing {
    e.printStackTrace()
    error(s)
}
