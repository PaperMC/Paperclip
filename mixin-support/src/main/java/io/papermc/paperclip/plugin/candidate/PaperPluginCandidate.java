package io.papermc.paperclip.plugin.candidate;

import io.papermc.paperclip.plugin.configuration.SimplePaperPluginMeta;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.jar.JarFile;

public class PaperPluginCandidate implements PluginCandidate {
    final Path source;
    final JarFile jarFile;
    final SimplePaperPluginMeta meta;

    public PaperPluginCandidate(Path source, JarFile jarFile, SimplePaperPluginMeta meta) {
        this.source = source;
        this.jarFile = jarFile;
        this.meta = meta;
    }

    @Override
    public @NotNull Path getSource() {
        return this.source;
    }

    @Override
    public JarFile file() {
        return this.jarFile;
    }

    @Override
    public SimplePaperPluginMeta getMeta() {
        return this.meta;
    }
}
