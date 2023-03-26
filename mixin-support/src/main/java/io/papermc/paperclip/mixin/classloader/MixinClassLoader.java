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

package io.papermc.paperclip.mixin.classloader;

import io.papermc.paperclip.mixin.PaperclipLauncherBase;
import io.papermc.paperclip.mixin.PaperclipMixinService;
import io.papermc.paperclip.mixin.Util;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

/**
 * Contains a lot of Fabric Loader code,
 * a combination of net.fabricmc.loader.impl.launch.knot.KnotClassDelegate and
 * net.fabricmc.loader.impl.launch.knot.KnotClassLoader, edited for Paper needs.
 */
public class MixinClassLoader extends SecureClassLoader implements ClassLoaderAccess {

    private static final boolean LOG_CLASS_LOAD = System.getProperty("paperclip.debug_log_class_load") != null;
    private static final boolean LOG_CLASS_LOAD_ERRORS = LOG_CLASS_LOAD || System.getProperty("paperclip.debug_load_class_load_errors") != null;
    private static final boolean LOG_TRANSFORM_ERRORS = System.getProperty("paperclip.debug_load_transform_errors") != null;
    private static final boolean DISABLE_ISOLATION = System.getProperty("paperclip.disable_classpath_isolation") != null;

    private static final ClassLoader PLATFORM_CLASS_LOADER = platformClassLoader();

    private final Map<Path, Metadata> metadataCache = new ConcurrentHashMap<>();
    private final DynamicURLClassLoader urlLoader;
    private final ClassLoader parentClassLoader;
    private IMixinTransformer mixinTransformer;
    private boolean transformInitialized = false;
    private volatile Set<Path> codeSources = Collections.emptySet();
    private volatile Set<Path> validParentCodeSources = Collections.emptySet();
    private final Map<Path, String[]> allowedPrefixes = new ConcurrentHashMap<>();
    private final Set<String> parentSourcedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public MixinClassLoader() {
        super(new DynamicURLClassLoader(new URL[0]));
        this.parentClassLoader = getClass().getClassLoader();
        this.urlLoader = (DynamicURLClassLoader) getParent();
    }

    public void initializeTransformers() {
        if (transformInitialized) throw new IllegalStateException("Cannot initialize KnotClassDelegate twice!");

        mixinTransformer = PaperclipMixinService.getTransformer();

        if (mixinTransformer == null) {
            try { // reflective instantiation for older mixin versions
                @SuppressWarnings("unchecked")
                Constructor<IMixinTransformer> ctor = (Constructor<IMixinTransformer>) Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getConstructor();
                ctor.setAccessible(true);
                mixinTransformer = ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                Util.error("MixinClassloader", "Can't create Mixin transformer through reflection (only applicable for 0.8-0.8.2): %s", e);

                // both lookups failed (not received through IMixinService.offer and not found through reflection)
                throw new IllegalStateException("mixin transformer unavailable?");
            }
        }

        transformInitialized = true;
    }

    private IMixinTransformer getMixinTransformer() {
        assert mixinTransformer != null;
        return mixinTransformer;
    }

    @Override
    public URL getResource(String name) {
        Objects.requireNonNull(name);

        URL url = urlLoader.getResource(name);

        if (url == null) {
            url = parentClassLoader.getResource(name);
        }

        return url;
    }

    @Override
    public URL findResource(String name) {
        Objects.requireNonNull(name);

        return urlLoader.findResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        Objects.requireNonNull(name);

        InputStream inputStream = urlLoader.getResourceAsStream(name);

        if (inputStream == null) {
            inputStream = parentClassLoader.getResourceAsStream(name);
        }

        return inputStream;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Objects.requireNonNull(name);

        final Enumeration<URL> resources = urlLoader.getResources(name);

        if (!resources.hasMoreElements()) {
            return parentClassLoader.getResources(name);
        }

        return resources;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLockFwd(name)) {
            Class<?> c = findLoadedClassFwd(name);

            if (c == null) {
                if (name.startsWith("java.")) { // fast path for java.** (can only be loaded by the platform CL anyway)
                    c = PLATFORM_CLASS_LOADER.loadClass(name);
                } else {
                    c = tryLoadClass(name, false); // try local load

                    if (c == null) { // not available locally, try system class loader
                        String fileName = Util.getClassFileName(name);
                        URL url = parentClassLoader.getResource(fileName);

                        if (url == null) { // no .class file
                            try {
                                c = PLATFORM_CLASS_LOADER.loadClass(name);
                                if (LOG_CLASS_LOAD) Util.info("MixinClassLoader", "loaded resources-less class %s from platform class loader");
                            } catch (ClassNotFoundException e) {
                                if (LOG_CLASS_LOAD_ERRORS) Util.warn("MixinClassLoader", "can't find class %s", name);
                                throw e;
                            }
                        } else if (!isValidParentUrl(url, fileName)) { // available, but restricted
                            // The class would technically be available, but the game provider restricted it from being
                            // loaded by setting validParentUrls and not including "url". Typical causes are:
                            // - accessing classes too early (game libs shouldn't be used until Loader is ready)
                            // - using jars that are only transient (deobfuscation input or pass-through installers)
                            String msg = String.format("can't load class %s at %s as it hasn't been exposed to the game",
                                    name, getCodeSource(url, fileName));
                            if (LOG_CLASS_LOAD_ERRORS) Util.warn("MixinClassLoader", msg);
                            throw new ClassNotFoundException(msg);
                        } else { // load from system cl
                            if (LOG_CLASS_LOAD) Util.info("MixinClassLoader", "loading class %s using the parent class loader", name);
                            c = parentClassLoader.loadClass(name);
                        }
                    } else if (LOG_CLASS_LOAD) {
                        Util.info("MixinClassLoader", "loaded class %s", name);
                    }
                }
            }

            if (resolve) {
                resolveClassFwd(c);
            }

            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return tryLoadClass(name, false);
    }

    @Override
    public void addUrlFwd(URL url) {
        urlLoader.addURL(url);
    }

    @Override
    public URL findResourceFwd(String name) {
        return urlLoader.findResource(name);
    }

    @Override
    public Package getPackageFwd(String name) {
        return super.getPackage(name);
    }

    @Override
    public Package definePackageFwd(String name, String specTitle, String specVersion, String specVendor,
                                    String implTitle, String implVersion, String implVendor, URL sealBase) throws IllegalArgumentException {
        return super.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
    }

    @Override
    public Object getClassLoadingLockFwd(String name) {
        return super.getClassLoadingLock(name);
    }

    @Override
    public Class<?> findLoadedClassFwd(String name) {
        return super.findLoadedClass(name);
    }

    @Override
    public Class<?> defineClassFwd(String name, byte[] b, int off, int len, CodeSource cs) {
        return super.defineClass(name, b, off, len, cs);
    }

    @Override
    public void resolveClassFwd(Class<?> cls) {
        super.resolveClass(cls);
    }

    public void addCodeSource(Path path) {
        path = Util.normalizeExistingPath(path);

        synchronized (this) {
            Set<Path> codeSources = this.codeSources;
            if (codeSources.contains(path)) return;

            Set<Path> newCodeSources = new HashSet<>(codeSources.size() + 1, 1);
            newCodeSources.addAll(codeSources);
            newCodeSources.add(path);

            this.codeSources = newCodeSources;
        }

        try {
            addUrlFwd(Util.asUrl(path));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        if (LOG_CLASS_LOAD_ERRORS) Util.info("MixinClassLoader", "added code source %s", path);
    }

    public void setAllowedPrefixes(Path codeSource, String... prefixes) {
        codeSource = Util.normalizeExistingPath(codeSource);

        if (prefixes.length == 0) {
            allowedPrefixes.remove(codeSource);
        } else {
            allowedPrefixes.put(codeSource, prefixes);
        }
    }

    public void setValidParentClassPath(Collection<Path> paths) {
        Set<Path> validPaths = new HashSet<>(paths.size(), 1);

        for (Path path : paths) {
            validPaths.add(Util.normalizeExistingPath(path));
        }

        this.validParentCodeSources = validPaths;
    }

    public Manifest getManifest(Path codeSource) {
        return getMetadata(Util.normalizeExistingPath(codeSource)).manifest;
    }

    public boolean isClassLoaded(String name) {
        synchronized (getClassLoadingLockFwd(name)) {
            return findLoadedClassFwd(name) != null;
        }
    }

    public Class<?> loadIntoTarget(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLockFwd(name)) {
            Class<?> c = findLoadedClassFwd(name);

            if (c == null) {
                c = tryLoadClass(name, true);

                if (c == null) {
                    throw new ClassNotFoundException("can't find class "+name);
                } else if (LOG_CLASS_LOAD) {
                    Util.info("MixinClassLoader", "loaded class %s into target", name);
                }
            }

            resolveClassFwd(c);

            return c;
        }
    }

    /**
     * Check if an url is loadable by the parent class loader.
     *
     * <p>This handles explicit parent url whitelisting by {@link #validParentCodeSources} or shadowing by {@link #codeSources}
     */
    private boolean isValidParentUrl(URL url, String fileName) {
        if (url == null) return false;
        if (DISABLE_ISOLATION) return true;
        if (!hasRegularCodeSource(url)) return true;

        Path codeSource = getCodeSource(url, fileName);
        Set<Path> validParentCodeSources = this.validParentCodeSources;

        if (validParentCodeSources != null) { // explicit whitelist (in addition to platform cl classes)
            return validParentCodeSources.contains(codeSource) || PLATFORM_CLASS_LOADER.getResource(fileName) != null;
        } else { // reject urls shadowed by this cl
            return !codeSources.contains(codeSource);
        }
    }

    Class<?> tryLoadClass(String name, boolean allowFromParent) throws ClassNotFoundException {
        if (name.startsWith("java.")) {
            return null;
        }

        if (!allowedPrefixes.isEmpty() && !DISABLE_ISOLATION) { // check prefix restrictions (allows exposing libraries partially during startup)
            String fileName = Util.getClassFileName(name);
            URL url = getResource(fileName);

            if (url != null && hasRegularCodeSource(url)) {
                Path codeSource = getCodeSource(url, fileName);
                String[] prefixes = allowedPrefixes.get(codeSource);

                if (prefixes != null) {
                    assert prefixes.length > 0;
                    boolean found = false;

                    for (String prefix : prefixes) {
                        if (name.startsWith(prefix)) {
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        String msg = "class "+name+" is currently restricted from being loaded";
                        if (LOG_CLASS_LOAD_ERRORS) Util.warn("MixinClassLoader", msg);
                        throw new ClassNotFoundException(msg);
                    }
                }
            }
        }

        if (!allowFromParent && !parentSourcedClasses.isEmpty()) { // propagate loadIntoTarget behavior to its nested classes
            int pos = name.length();

            while ((pos = name.lastIndexOf('$', pos - 1)) > 0) {
                if (parentSourcedClasses.contains(name.substring(0, pos))) {
                    allowFromParent = true;
                    break;
                }
            }
        }

        byte[] input = getPostMixinClassByteArray(name, allowFromParent);
        if (input == null) return null;

        // The class we're currently loading could have been loaded already during Mixin initialization triggered by `getPostMixinClassByteArray`.
        // If this is the case, we want to return the instance that was already defined to avoid attempting a duplicate definition.
        Class<?> existingClass = findLoadedClassFwd(name);

        if (existingClass != null) {
            return existingClass;
        }

        if (allowFromParent) {
            parentSourcedClasses.add(name);
        }

        Metadata metadata = getMetadata(name);

        int pkgDelimiterPos = name.lastIndexOf('.');

        if (pkgDelimiterPos > 0) {
            // TODO: package definition stub
            String pkgString = name.substring(0, pkgDelimiterPos);

            if (getPackageFwd(pkgString) == null) {
                try {
                    definePackageFwd(pkgString, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException e) { // presumably concurrent package definition
                    if (getPackageFwd(pkgString) == null) throw e; // still not defined?
                }
            }
        }

        return defineClassFwd(name, input, 0, input.length, metadata.codeSource);
    }

    private Metadata getMetadata(String name) {
        String fileName = Util.getClassFileName(name);
        URL url = getResource(fileName);
        if (url == null || !hasRegularCodeSource(url)) return Metadata.EMPTY;

        return getMetadata(getCodeSource(url, fileName));
    }

    private Metadata getMetadata(Path codeSource) {
        return metadataCache.computeIfAbsent(codeSource, (Path path) -> {
            Manifest manifest = null;
            CodeSource cs = null;
            Certificate[] certificates = null;

            try {
                if (Files.isDirectory(path)) {
                    manifest = Util.readManifest(path);
                } else {
                    URLConnection connection = new URL("jar:" + path.toUri().toString() + "!/").openConnection();

                    if (connection instanceof JarURLConnection) {
                        manifest = ((JarURLConnection) connection).getManifest();
                        certificates = ((JarURLConnection) connection).getCertificates();
                    }

                    if (manifest == null) {
                        try (Util.FileSystemDelegate jarFs = Util.getJarFileSystem(path, false)) {
                            manifest = Util.readManifest(jarFs.get().getRootDirectories().iterator().next());
                        }
                    }

                    // TODO
					/* JarEntry codeEntry = codeSourceJar.getJarEntry(filename);

					if (codeEntry != null) {
						cs = new CodeSource(codeSourceURL, codeEntry.getCodeSigners());
					} */
                }
            } catch (IOException | FileSystemNotFoundException e) {
                if (PaperclipLauncherBase.isDevelopment()) {
                    Util.warn("MixinClassLoader", "Failed to load manifest", e);
                }
            }

            if (cs == null) {
                try {
                    cs = new CodeSource(Util.asUrl(path), certificates);
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }

            return new Metadata(manifest, cs);
        });
    }

    private byte[] getPostMixinClassByteArray(String name, boolean allowFromParent) {
        byte[] transformedClassArray = getPreMixinClassByteArray(name, allowFromParent);

        if (!transformInitialized || !canTransformClass(name)) {
            return transformedClassArray;
        }

        try {
            return getMixinTransformer().transformClassBytes(name, name, transformedClassArray);
        } catch (Throwable t) {
            String msg = String.format("Mixin transformation of %s failed", name);
            if (LOG_TRANSFORM_ERRORS) Util.warn("MixinClassLoader", msg, t);

            throw new RuntimeException(msg, t);
        }
    }

    public byte[] getPreMixinClassBytes(String name) {
        return getPreMixinClassByteArray(name, true);
    }

    /**
     * Runs all the class transformers except mixin. (No other transformers on Paper!)
     */
    private byte[] getPreMixinClassByteArray(String name, boolean allowFromParent) {
        // some of the transformers rely on dot notation
        name = name.replace('/', '.');

        if (!transformInitialized || !canTransformClass(name)) {
            try {
                return getRawClassByteArray(name, allowFromParent);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load class file for '" + name + "'!", e);
            }
        }

        try {
            return getRawClassByteArray(name, allowFromParent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load class file for '" + name + "'!", e);
        }
    }

    private static boolean canTransformClass(String name) {
        name = name.replace('/', '.');
        // Blocking Fabric Loader classes is no longer necessary here as they don't exist on the modding class loader
        return /* !"net.fabricmc.api.EnvType".equals(name) && !name.startsWith("net.fabricmc.loader.") && */ !name.startsWith("org.apache.logging.log4j");
    }

    public byte[] getRawClassBytes(String name) throws IOException {
        return getRawClassByteArray(name, true);
    }

    private byte[] getRawClassByteArray(String name, boolean allowFromParent) throws IOException {
        name = Util.getClassFileName(name);
        URL url = findResourceFwd(name);

        if (url == null) {
            if (!allowFromParent) return null;

            url = parentClassLoader.getResource(name);

            if (!isValidParentUrl(url, name)) {
                if (LOG_CLASS_LOAD) Util.info("refusing to load class %s at %s from parent class loader", name, getCodeSource(url, name));

                return null;
            }
        }

        try (InputStream inputStream = url.openStream()) {
            int a = inputStream.available();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(a < 32 ? 32768 : a);
            byte[] buffer = new byte[8192];
            int len;

            while ((len = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, len);
            }

            return outputStream.toByteArray();
        }
    }

    private static boolean hasRegularCodeSource(URL url) {
        return url.getProtocol().equals("file") || url.getProtocol().equals("jar");
    }

    private static Path getCodeSource(URL url, String fileName) {
        try {
            return Util.normalizeExistingPath(Util.getCodeSource(url, fileName));
        } catch (Util.UrlConversionException e) {
            throw Util.wrap(e);
        }
    }

    private static ClassLoader platformClassLoader() {
        try {
            return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null); // Java 9+ only
        } catch (NoSuchMethodException e) {
            return new ClassLoader(null) { }; // fall back to boot cl
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static final class Metadata {
        static final Metadata EMPTY = new Metadata(null, null);

        final Manifest manifest;
        final CodeSource codeSource;

        Metadata(Manifest manifest, CodeSource codeSource) {
            this.manifest = manifest;
            this.codeSource = codeSource;
        }
    }

    private static final class DynamicURLClassLoader extends URLClassLoader {
        private DynamicURLClassLoader(URL[] urls) {
            super(urls, new DummyClassLoader());
        }

        @Override
        public void addURL(URL url) {
            super.addURL(url);
        }

        static {
            registerAsParallelCapable();
        }
    }

    static class DummyClassLoader extends ClassLoader {
        private static final Enumeration<URL> NULL_ENUMERATION = new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return false;
            }

            @Override
            public URL nextElement() {
                return null;
            }
        };

        static {
            registerAsParallelCapable();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            throw new ClassNotFoundException(name);
        }

        @Override
        public URL getResource(String name) {
            return null;
        }

        @Override
        public Enumeration<URL> getResources(String var1) throws IOException {
            return NULL_ENUMERATION;
        }
    }
}
