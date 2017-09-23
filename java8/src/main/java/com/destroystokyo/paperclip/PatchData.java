/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2017 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

class PatchData {
    private final URL patchFile;
    private final URL originalUrl;
    private final byte[] originalHash;
    private final byte[] patchedHash;
    private final String version;

    private PatchData(final JSONObject obj) {
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
        } catch (final MalformedURLException e) {
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

    static PatchData parse(final InputStream in) throws IOException {
        try {
            final Object obj = new JSONParser().parse(new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8"))));
            return new PatchData((JSONObject) obj);
        } catch (final Exception e) {
            throw new IllegalArgumentException("Invalid json", e);
        }
    }
}
