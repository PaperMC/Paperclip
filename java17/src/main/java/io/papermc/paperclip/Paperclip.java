package io.papermc.paperclip;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Paperclip {

    public static void main(final String[] args) {
        if (Path.of("").toAbsolutePath().toString().contains("!")) {
            System.err.println("Paperclip may not run in a directory containing '!'. Please rename the affected folder.");
            System.exit(1);
        }

        final URL[] classpathUrls = setupClasspath();

        final ClassLoader parentClassLoader = Paperclip.class.getClassLoader().getParent();
        final URLClassLoader classLoader = new URLClassLoader(classpathUrls, parentClassLoader);

        final String mainClassName = findMainClass();
        System.out.println("Starting " + mainClassName);

        final Thread runThread = new Thread(() -> {
            try {
                final Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
                final MethodHandle mainHandle = MethodHandles.lookup()
                    .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                    .asFixedArity();
                mainHandle.invoke((Object) args);
            } catch (final Throwable t) {
                throw Util.sneakyThrow(t);
            }
        }, "ServerMain");
        runThread.setContextClassLoader(classLoader);
        runThread.start();
    }

    private static URL[] setupClasspath() {
        final var repoDir = Path.of(System.getProperty("bundlerRepoDir", ""));

        final PatchData[] patches = findPatches();
        final DownloadContext downloadContext = findDownloadContext();
        if (patches != null && downloadContext == null) {
            throw new IllegalArgumentException("patches.list file found without a corresponding original-url file");
        }

        final Path baseFile;
        if (downloadContext != null) {
            try {
                downloadContext.download(repoDir);
            } catch (final IOException e) {
                throw Util.fail("Failed to download original jar", e);
            }
            baseFile = downloadContext.getOutputFile(repoDir);
        } else {
            baseFile = null;
        }

        final Map<String, URL> classpathUrls = extractAndApplyPatches(baseFile, patches, repoDir);

        // Exit if user has set `paperclip.patchonly` system property to `true`
        if (Boolean.getBoolean("paperclip.patchonly")) {
            System.exit(0);
        }

        return classpathUrls.values().toArray(new URL[0]);
    }

    private static PatchData[] findPatches() {
        final InputStream patchListStream = Paperclip.class.getResourceAsStream("/META-INF/patches.list");
        if (patchListStream == null) {
            return null;
        }

        try (patchListStream) {
            return PatchData.parse(new BufferedReader(new InputStreamReader(patchListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read patches.list file", e);
        }
    }

    private static DownloadContext findDownloadContext() {
        final String line;
        try {
            line = Util.readResourceText("/META-INF/download-context");
        } catch (final IOException e) {
            throw Util.fail("Failed to read download-context file", e);
        }

        return DownloadContext.parseLine(line);
    }

    private static FileEntry[] findVersionEntries() {
        return findFileEntries("versions.list");
    }
    private static FileEntry[] findLibraryEntries() {
        return findFileEntries("libraries.list");
    }
    private static FileEntry[] findFileEntries(final String fileName) {
        final InputStream libListStream = Paperclip.class.getResourceAsStream("/META-INF/" + fileName);
        if (libListStream == null) {
            return null;
        }

        try (libListStream) {
            return FileEntry.parse(new BufferedReader(new InputStreamReader(libListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read " + fileName + " file", e);
        }
    }

    private static String findMainClass() {
        final String mainClassName = System.getProperty("bundlerMainClass");
        if (mainClassName != null) {
            return mainClassName;
        }

        try {
            return Util.readResourceText("/META-INF/main-class");
        } catch (final IOException e) {
            throw Util.fail("Failed to read main-class file", e);
        }
    }

    private static Map<String, URL> extractAndApplyPatches(final Path originalJar, final PatchData[] patches, final Path repoDir) {
        if (originalJar == null && patches != null) {
            throw new IllegalArgumentException("Patch data found without patch target");
        }

        // First extract any non-patch files
        final Map<String, URL> urls = extractFiles(originalJar, repoDir);

        // Next apply any patches that we have
        applyPatches(urls, patches, repoDir);

        return urls;
    }

    private static Map<String, URL> extractFiles(final Path originalJar, final Path repoDir) {
        final var urls = new HashMap<String, URL>();

        try {
            final FileSystem originalJarFs;
            if (originalJar == null) {
                originalJarFs = null;
            } else {
                originalJarFs = FileSystems.newFileSystem(originalJar);
            }

            try {
                final Path originalRootDir;
                if (originalJarFs == null) {
                    originalRootDir = null;
                } else {
                    originalRootDir = originalJarFs.getPath("/");
                }

                final FileEntry[] versionEntries = findVersionEntries();
                extractEntries(urls, originalRootDir, repoDir, versionEntries, "versions");

                final FileEntry[] libraryEntries = findLibraryEntries();
                extractEntries(urls, originalRootDir, repoDir, libraryEntries, "libraries");
            } finally {
                if (originalJarFs != null) {
                    originalJarFs.close();
                }
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to extract jar files", e);
        }

        return urls;
    }

    private static void extractEntries(
        final Map<String, URL> urls,
        final Path originalRootDir,
        final Path repoDir,
        final FileEntry[] entries,
        final String targetName
    ) throws IOException {
        if (entries == null) {
            return;
        }

        final String targetPath = "/META-INF/" + targetName;
        final Path targetDir = repoDir.resolve(targetName);

        for (final FileEntry entry : entries) {
            entry.extractFile(urls, originalRootDir, targetPath, targetDir);
        }
    }

    private static void applyPatches(final Map<String, URL> urls, final PatchData[] patches, final Path repoDir) {
        if (patches == null) {
            return;
        }

        try {
            for (final PatchData patch : patches) {
                patch.applyPatch(urls, repoDir);
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to apply patches", e);
        }
    }
}
