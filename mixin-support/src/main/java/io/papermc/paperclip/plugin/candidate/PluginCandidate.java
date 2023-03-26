package io.papermc.paperclip.plugin.candidate;

import io.papermc.paperclip.plugin.configuration.SimplePaperPluginMeta;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.jar.JarFile;

public interface PluginCandidate {
    @NotNull
    Path getSource();

    default Path getFileName() {
        return this.getSource().getFileName();
    }

    default Path getParentSource() {
        return this.getSource().getParent();
    }

    JarFile file();

    SimplePaperPluginMeta getMeta();
}
