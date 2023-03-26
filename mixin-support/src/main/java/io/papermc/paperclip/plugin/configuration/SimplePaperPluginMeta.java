package io.papermc.paperclip.plugin.configuration;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public record SimplePaperPluginMeta(Path mixinConfig) {

    public static SimplePaperPluginMeta from(JarFile jarFile) {
        final Yaml yaml = new Yaml();
        JarEntry jarEntry = jarFile.getJarEntry("paper-plugin.yml");
        if (jarEntry == null)
            return null;
        try {
            Map<String, Object> loaded = yaml.load(jarFile.getInputStream(jarEntry));
            String mixin = (String) loaded.get("mixins");
            return new SimplePaperPluginMeta(Paths.get(mixin));
        } catch (Exception e) {
            return null;
        }
    }
}
