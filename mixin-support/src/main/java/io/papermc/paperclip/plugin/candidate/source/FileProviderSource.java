package io.papermc.paperclip.plugin.candidate.source;

import io.papermc.paperclip.plugin.candidate.PaperPluginCandidate;
import io.papermc.paperclip.plugin.candidate.PluginCandidate;
import io.papermc.paperclip.plugin.configuration.SimplePaperPluginMeta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.jar.JarFile;

/**
 * Loads a plugin provider at the given plugin jar file path.
 */
public class FileProviderSource {

    private final Function<Path, String> contextChecker;

    public FileProviderSource(Function<Path, String> contextChecker) {
        this.contextChecker = contextChecker;
    }

    public PluginCandidate getCandidate(Path context) {
        String source = this.contextChecker.apply(context);

        if (Files.notExists(context)) {
            throw new IllegalArgumentException(source + " does not exist, cannot load a plugin from it!");
        }

        if (!Files.isRegularFile(context)) {
            throw new IllegalArgumentException(source + " is not a file, cannot load a plugin from it!");
        }

        if (!context.getFileName().toString().endsWith(".jar")) {
            throw new IllegalArgumentException(source + " is not a jar file, cannot load a plugin from it!");
        }

        try {
            JarFile file = new JarFile(context.toFile(), true, JarFile.OPEN_READ, JarFile.runtimeVersion());
            return new PaperPluginCandidate(context.toAbsolutePath(), file, SimplePaperPluginMeta.from(file));
        } catch (Exception exception) {
            throw new RuntimeException(source + " failed to load!", exception);
        }
    }

}
