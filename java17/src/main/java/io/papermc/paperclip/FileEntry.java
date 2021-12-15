package io.papermc.paperclip;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

record FileEntry(byte[] hash, String id, String path) {

    static FileEntry[] parse(final BufferedReader reader) throws IOException {
        var result = new FileEntry[8];

        int index = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            final FileEntry data = parseLine(line);
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

    private static FileEntry parseLine(final String line) {
        final var parts = line.split("\t");
        if (parts.length != 3) {
            throw new IllegalStateException("Malformed library entry: " + line);
        } else {
            return new FileEntry(Util.fromHex(parts[0]), parts[1], parts[2]);
        }
    }

    void extractFile(
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final String targetName,
        final Path originalRootDir,
        final String baseDir,
        final Path outputDir
    ) throws IOException {
        for (final PatchEntry patch : patches) {
            if (patch.location().equals(targetName) && patch.outputPath().equals(this.path)) {
                // This file will be created from a patch
                return;
            }
        }

        final Path outputFile = outputDir.resolve(this.path);
        if (Files.exists(outputFile) && Util.isFileValid(outputFile, this.hash)) {
            urls.put(this.path, outputFile.toUri().toURL());
            return;
        }

        final String filePath = Util.endingSlash(baseDir) + this.path;
        InputStream fileStream = FileEntry.class.getResourceAsStream(filePath);
        if (fileStream == null) {
            // This file is not in our jar, but may be in the original
            if (originalRootDir == null) {
                // no original jar was provided (we are not running in patcher mode)
                // This is an invalid situation
                throw new IllegalStateException(this.path + " not found in our jar, and no original jar provided");
            }

            final Path originalFile = originalRootDir.resolve(filePath);
            if (Files.notExists(originalFile)) {
                throw new IllegalStateException(this.path + " not found in our jar or in the original jar");
            }

            fileStream = Files.newInputStream(originalFile);
        }

        if (!Files.isDirectory(outputFile.getParent())) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.deleteIfExists(outputFile);

        try (
            final InputStream stream = fileStream;
            final ReadableByteChannel inputChannel = Channels.newChannel(stream);
            final FileChannel outputChannel = FileChannel.open(outputFile, CREATE, WRITE, TRUNCATE_EXISTING)
        ) {
            outputChannel.transferFrom(inputChannel, 0, Long.MAX_VALUE);
        }

        if (!Util.isFileValid(outputFile, this.hash)) {
            throw new IllegalStateException("Hash check failed for extract filed " + outputFile);
        }

        urls.put(this.path, outputFile.toUri().toURL());
    }
}
