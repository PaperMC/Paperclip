/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;

class PatchData {
    private final URL patchFile;
    private final URL originalUrl;
    private final byte[] originalHash;
    private final byte[] patchedHash;
    private final String version;

    private PatchData(JSONObject obj) {
        final String patch = (String) obj.get("patch");
        // First try and parse the patch as a uri
        URL patchFile = PatchData.class.getResource("/" + patch);
        if (new File(patch).exists()) {
            try {
                patchFile = new File(patch).toURI().toURL();
            } catch (MalformedURLException ignored) {}
        }
        if (patchFile == null) {
            throw new IllegalArgumentException("Couldn't find " + patch);
        }
        this.patchFile = patchFile;
        try {
            this.originalUrl = new URL((String) obj.get("sourceUrl"));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
        this.originalHash = Utils.fromHex((String) obj.get("originalHash"));
        this.patchedHash = Utils.fromHex((String) obj.get("patchedHash"));
        this.version = ((String) obj.get("version"));
    }

    URL getPatchFile() {
        return patchFile;
    }

    URL getOriginalUrl() {
        return originalUrl;
    }

    byte[] getOriginalHash() {
        return originalHash;
    }

    byte[] getPatchedHash() {
        return patchedHash;
    }

    String getVersion() {
        return version;
    }

    static PatchData parse(InputStream in) throws IOException {
        try {
            Object obj = new JSONParser().parse(new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8"))));
            return new PatchData((JSONObject) obj);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid json", e);
        }
    }
}
