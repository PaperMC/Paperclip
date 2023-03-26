/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperclip.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.jar.Manifest;
import java.util.zip.ZipError;

/**
 *  Contains fabric licensed code, a combination of many small utility classes.
 */
public final class Util {
    public static final Path LOADER_CODE_SOURCE = getCodeSource(Util.class);

    public static String getClassFileName(String className) {
        return className.replace('.', '/').concat(".class");
    }

    public static Path normalizePath(Path path) {
        if (Files.exists(path)) {
            return normalizeExistingPath(path);
        } else {
            return path.toAbsolutePath().normalize();
        }
    }

    public static Path normalizeExistingPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path getCodeSource(Class<?> cls) {
        CodeSource cs = cls.getProtectionDomain().getCodeSource();
        if (cs == null) return null;

        return asPath(cs.getLocation());
    }

    public static URL asUrl(Path path) throws MalformedURLException {
        return path.toUri().toURL();
    }

    public static Path asPath(URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw Util.wrap(e);
        }
    }

    public static final class WrappedException extends RuntimeException {
        public WrappedException(Throwable cause) {
            super(cause);
        }
    }

    public static RuntimeException wrap(Throwable exc) {
        if (exc instanceof RuntimeException) return (RuntimeException) exc;

        exc = unwrap(exc);
        if (exc instanceof RuntimeException) return (RuntimeException) exc;

        return new WrappedException(exc);
    }

    private static Throwable unwrap(Throwable exc) {
        if (exc instanceof WrappedException
                || exc instanceof UncheckedIOException
                || exc instanceof ExecutionException
                || exc instanceof CompletionException) {
            Throwable ret = exc.getCause();
            if (ret != null) return unwrap(ret);
        }

        return exc;
    }

    public static class UrlConversionException extends Exception {
        public UrlConversionException() {
            super();
        }

        public UrlConversionException(String s) {
            super(s);
        }

        public UrlConversionException(Throwable t) {
            super(t);
        }

        public UrlConversionException(String s, Throwable t) {
            super(s, t);
        }
    }

    public static Path getCodeSource(URL url, String localPath) throws UrlConversionException {
        try {
            URLConnection connection = url.openConnection();

            if (connection instanceof JarURLConnection) {
                return asPath(((JarURLConnection) connection).getJarFileURL());
            } else {
                String path = url.getPath();

                if (path.endsWith(localPath)) {
                    return asPath(new URL(url.getProtocol(), url.getHost(), url.getPort(), path.substring(0, path.length() - localPath.length())));
                } else {
                    throw new UrlConversionException("Could not figure out code source for file '" + localPath + "' in URL '" + url + "'!");
                }
            }
        } catch (Exception e) {
            throw new UrlConversionException(e);
        }
    }

    public static Manifest readManifest(URL codeSourceUrl) throws IOException, URISyntaxException {
        Path path = Util.asPath(codeSourceUrl);

        if (Files.isDirectory(path)) {
            return readManifest(path);
        } else {
            URLConnection connection = new URL("jar:" + codeSourceUrl.toString() + "!/").openConnection();

            if (connection instanceof JarURLConnection) {
                return ((JarURLConnection) connection).getManifest();
            }

            try (Util.FileSystemDelegate jarFs = Util.getJarFileSystem(path, false)) {
                return readManifest(jarFs.get().getRootDirectories().iterator().next());
            }
        }
    }

    public static Manifest readManifest(Path basePath) throws IOException {
        Path path = basePath.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.exists(path)) return null;

        try (InputStream stream = Files.newInputStream(path)) {
            return new Manifest(stream);
        }
    }

    public static class FileSystemDelegate implements AutoCloseable {
        private final FileSystem fileSystem;
        private final boolean owner;

        public FileSystemDelegate(FileSystem fileSystem, boolean owner) {
            this.fileSystem = fileSystem;
            this.owner = owner;
        }

        public FileSystem get() {
            return fileSystem;
        }

        @Override
        public void close() throws IOException {
            if (owner) {
                fileSystem.close();
            }
        }
    }

    private static final Map<String, String> jfsArgsCreate = Collections.singletonMap("create", "true");
    private static final Map<String, String> jfsArgsEmpty = Collections.emptyMap();


    public static FileSystemDelegate getJarFileSystem(Path path, boolean create) throws IOException {
        return getJarFileSystem(path.toUri(), create);
    }

    public static FileSystemDelegate getJarFileSystem(URI uri, boolean create) throws IOException {
        URI jarUri;

        try {
            jarUri = new URI("jar:" + uri.getScheme(), uri.getHost(), uri.getPath(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }

        boolean opened = false;
        FileSystem ret = null;

        try {
            ret = FileSystems.getFileSystem(jarUri);
        } catch (FileSystemNotFoundException ignore) {
            try {
                ret = FileSystems.newFileSystem(jarUri, create ? jfsArgsCreate : jfsArgsEmpty);
                opened = true;
            } catch (FileSystemAlreadyExistsException ignore2) {
                ret = FileSystems.getFileSystem(jarUri);
            } catch (IOException | ZipError e) {
                throw new IOException("Error accessing " + uri + ": " + e, e);
            }
        }

        return new FileSystemDelegate(ret, opened);
    }

    public static void error(String marker, String message) {
        print("ERROR", marker, message);
    }

    public static void error(String marker, String message, Throwable t) {
        print("ERROR", marker, message, t);
    }

    public static void info(String marker, String message, Throwable t) {
        print("INFO", marker, message, t);
    }

    public static void info(String marker, String message, Object... objects) {
        print("INFO", marker, message, objects);
    }

    public static void warn(String marker, String message, Object... objects) {
        print("WARN", marker, message, objects);
    }

    private static void print(String prefix, String marker, String message, Object... objects) {
        System.out.println("["+prefix+"] " + "["+marker+"] " + String.format(message, objects));
    }
}
