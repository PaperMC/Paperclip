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

import io.papermc.paperclip.mixin.classloader.MixinClassLoader;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Mainly inspired by FabricLauncherBase.
 */
public final class PaperclipLauncherBase {
    static MixinClassLoader classLoader;
    static boolean mixinReady;
    static final Map<String, Object> properties = new HashMap<>();

    public static boolean isDevelopment() {
        return false;
    }

    public static byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
        if (runTransformers) {
            return classLoader.getPreMixinClassBytes(name);
        } else {
            return classLoader.getRawClassBytes(name);
        }
    }

    public static InputStream getResourceAsStream(String name) {
        return classLoader.getResourceAsStream(name);
    }

    public static boolean isClassLoaded(String name) {
        return classLoader.isClassLoaded(name);
    }

    public static Map<String, Object> getProperties() {
        return properties;
    }

    public static void addToClasspath(Path path, String... allowedPrefixes) {
        classLoader.setAllowedPrefixes(path, allowedPrefixes);
        classLoader.addCodeSource(path);
    }

    public static ClassLoader getTargetClassLoader() {
        return PaperclipLauncherBase.classLoader;
    }

    public static void setClassLoader(MixinClassLoader classLoader) {
        PaperclipLauncherBase.classLoader = classLoader;
    }

    public static void finishMixinBootstrapping() {
        if (mixinReady) {
            throw new RuntimeException("Must not call finishMixinBootstrapping() twice!");
        }

        try {
            Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
            m.setAccessible(true);
            m.invoke(null, MixinEnvironment.Phase.INIT);
            m.invoke(null, MixinEnvironment.Phase.DEFAULT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        mixinReady = true;
    }
}
