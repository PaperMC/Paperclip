/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip

import org.json.simple.JSONObject
import java.io.File
import java.net.MalformedURLException
import java.net.URL

class PatchData(obj: JSONObject) {

    val patchFile: URL
    val originalUrl: URL
    val originalHash: ByteArray
    val patchedHash: ByteArray
    val version: String

    init {
        val patch = obj["patch"] as String
        // First try and parse the patch as a uri
        var patchFile: URL? = PatchData::class.java.getResource("/$patch")
        if (File(patch).exists()) {
            try {
                patchFile = File(patch).toURI().toURL()
            } catch (ignored: MalformedURLException) {
            }

        }
        if (patchFile == null) {
            throw IllegalArgumentException("Couldn't find $patch")
        }
        this.patchFile = patchFile
        try {
            this.originalUrl = URL(obj["sourceUrl"] as String)
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("Invalid URL", e)
        }

        this.originalHash = (obj["originalHash"] as String).fromHex()
        this.patchedHash = (obj["patchedHash"] as String).fromHex()
        this.version = obj["version"] as String
    }
}
