/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package io.papermc.paperclip;

import io.sigpipe.jbsdiff.InvalidHeaderException;
import io.sigpipe.jbsdiff.Patch;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.compress.compressors.CompressorException;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

record PatchData(
    String location,
    byte[] originalHash,
    byte[] patchHash,
    byte[] outputHash,
    String originalPath,
    String patchPath,
    String outputPath
) {

    static PatchData[] parse(final BufferedReader reader) throws IOException {
        var result = new PatchData[8];

        int index = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            final PatchData data = parseLine(line);
            if (data == null) {
                continue;
            }

            if (index == result.length) {
                result = Arrays.copyOf(result, index * 2);
            }
            result[index++] = data;
        }

        if (index != result.length) {
            return Arrays.copyOf(result, index);
        } else {
            return result;
        }
    }

    private static PatchData parseLine(final String line) {
        if (line.isBlank()) {
            return null;
        }
        if (line.startsWith("#")) {
            return null;
        }

        final var parts = line.split("\t");
        if (parts.length != 7) {
            throw new IllegalStateException("Invalid patch data line: " + line);
        }

        return new PatchData(
            parts[0],
            Util.fromHex(parts[1]),
            Util.fromHex(parts[2]),
            Util.fromHex(parts[3]),
            parts[4],
            parts[5],
            parts[6]
        );
    }

    void applyPatch(final Map<String, URL> urls, final Path repoDir) throws IOException {
        final Path targetDir = repoDir.resolve(this.location);

        final Path inputFile = targetDir.resolve(this.originalPath);
        final Path outputFile = targetDir.resolve(this.outputPath);

        // Short-cut if the patch is already applied
        if (Files.exists(outputFile) && Util.isFileValid(outputFile, this.outputHash)) {
            // For the classpath, use the patched file instead of the original
            urls.put(this.originalPath, outputFile.toUri().toURL());
            return;
        }

        // Verify input file is correct
        if (Files.notExists(inputFile)) {
            throw new IllegalStateException("Input file not found: " + inputFile);
        }
        if (!Util.isFileValid(inputFile, this.originalHash)) {
            throw new IllegalStateException("Hash check of input file failed for " + inputFile);
        }

        // Get and verity patch data is correct
        final InputStream patchStream = this.getClass().getResourceAsStream(this.patchPath);
        if (patchStream == null) {
            throw new IllegalStateException("Patch not found for file " + inputFile);
        }
        final byte[] patchBytes = Util.readFully(patchStream);
        if (!Util.isDataValid(patchBytes, this.patchHash)) {
            throw new IllegalStateException("Hash check of patch file failed for " + this.patchPath);
        }

        final byte[] originalBytes = Util.readBytes(inputFile);
        try (
            final OutputStream outStream =
                new BufferedOutputStream(Files.newOutputStream(outputFile, CREATE, WRITE, TRUNCATE_EXISTING))
        ) {
            // Don't move this `catch` clause to the outer try-with-resources
            // the Util.fail method never returns, so `close()` would never get called
            try {
                Patch.patch(originalBytes, patchBytes, outStream);
            } catch (final CompressorException | InvalidHeaderException | IOException e) {
                throw Util.fail("Failed to patch " + inputFile, e);
            }
        }

        if (!Util.isFileValid(outputFile, this.outputHash)) {
            throw new IllegalStateException("Patch not applied correctly for " + this.outputPath);
        }

        // For the classpath, use the patched file instead of the original
        urls.put(this.originalPath, outputFile.toUri().toURL());
    }
}
