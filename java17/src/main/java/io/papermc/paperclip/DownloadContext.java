package io.papermc.paperclip;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

record DownloadContext(byte[] hash, URL url, String fileName) {

    public Path getOutputFile(final Path outputDir) {
        final Path cacheDir = outputDir.resolve("cache");
        return cacheDir.resolve(this.fileName);
    }

    public static DownloadContext parseLine(final String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        final String[] parts = line.split("\t");
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid download-context line: " + line);
        }

        try {
            return new DownloadContext(Util.fromHex(parts[0]), URI.create(parts[1]).toURL(), parts[2]);
        } catch (final MalformedURLException e) {
            throw new IllegalStateException("Unable to parse URL in download-context", e);
        }
    }

    public void download(final Path outputDir) throws IOException {
        final Path outputFile = this.getOutputFile(outputDir);
        if (Files.exists(outputFile) && Util.isFileValid(outputFile, this.hash)) {
            return;
        }

        if (!Files.isDirectory(outputFile.getParent())) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.deleteIfExists(outputFile);

        System.out.println("Downloading " + this.fileName);

        try (
            final ReadableByteChannel source = Channels.newChannel(this.url.openStream());
            final FileChannel fileChannel = FileChannel.open(outputFile, CREATE, WRITE, TRUNCATE_EXISTING)
        ) {
            fileChannel.transferFrom(source, 0, Long.MAX_VALUE);
        } catch (final IOException e) {
            System.err.println("Failed to download " + this.fileName);
            e.printStackTrace();
            System.exit(1);
        }

        if (!Util.isFileValid(outputFile, this.hash)) {
            throw new IllegalStateException("Hash check failed for downloaded file " + this.fileName);
        }
    }
}
