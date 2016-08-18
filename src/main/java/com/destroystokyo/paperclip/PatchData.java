/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 * https://github.com/PaperSpigot/Paper
 *
 * MIT License
 */

package com.destroystokyo.paperclip;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PatchData {
    private final URL patchFile;
    private final URL originalUrl;
    private final byte[] originalHash;
    private final byte[] patchedHash;
    private final String version;

    public PatchData(JSONObject obj) {
        final String patch = (String) obj.get("patch");
        Path patchPath = Paths.get(patch);
        // First try and parse the patch as a uri
        URL patchFile = PatchData.class.getResource("/" + patch);
        if (Files.exists(patchPath)) {
            try {
                patchFile = patchPath.toUri().toURL();
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
        this.version = (String) obj.get("version");
    }

    public URL getPatchFile() {
        return patchFile;
    }

    public URL getOriginalUrl() {
        return originalUrl;
    }

    public byte[] getOriginalHash() {
        return originalHash;
    }

    public byte[] getPatchedHash() {
        return patchedHash;
    }

    public String getVersion() {
        return version;
    }

    public static PatchData parse(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")))) {
            Object obj = new JSONParser().parse(reader);
            return new PatchData((JSONObject) obj);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid json", e);
        }
    }
}
