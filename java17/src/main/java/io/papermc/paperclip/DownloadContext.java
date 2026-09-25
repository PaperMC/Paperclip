package io.papermc.paperclip;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

record DownloadContext(byte[] hash, URL url, String fileName) {

    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_ATTEMPTS = 3;

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

        for (int attempt = 1; ; attempt++) {
            try {
                this.downloadTo(outputFile);
            } catch (final IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    System.err.println("Failed to download " + this.fileName);
                    e.printStackTrace();
                    System.exit(1);
                }
                System.err.println("Failed to download " + this.fileName + " (attempt " + attempt + " of " + MAX_ATTEMPTS + "): " + e);
                continue;
            }

            // A connection dropped mid-body reads as end of stream, so a truncated file only shows up here
            if (Util.isFileValid(outputFile, this.hash)) {
                return;
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw new IllegalStateException("Hash check failed for downloaded file " + this.fileName);
            }
            System.err.println("Hash check failed for downloaded file " + this.fileName + " (attempt " + attempt + " of " + MAX_ATTEMPTS + ")");
        }
    }

    private void downloadTo(final Path outputFile) throws IOException {
        final URLConnection connection = this.url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);

        try (
            final ReadableByteChannel source = Channels.newChannel(connection.getInputStream());
            final FileChannel fileChannel = FileChannel.open(outputFile, CREATE, WRITE, TRUNCATE_EXISTING)
        ) {
            fileChannel.transferFrom(source, 0, Long.MAX_VALUE);
        }
    }
}
