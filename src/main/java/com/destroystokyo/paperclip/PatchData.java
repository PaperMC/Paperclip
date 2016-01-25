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
import java.nio.charset.StandardCharsets;

public class PatchData {
    private final URL patchFile;
    private final URL originalUrl;
    private final byte[] originalHash;
    private final byte[] patchedHash;

    public PatchData(JSONObject obj) {
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

    public static PatchData parse(InputStream in) throws IOException {
        try {
            Object obj = new JSONParser().parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
            return new PatchData((JSONObject) obj);
        } catch (ParseException | ClassCastException e) {
            throw new IllegalArgumentException("Invalid json", e);
        }
    }
}
