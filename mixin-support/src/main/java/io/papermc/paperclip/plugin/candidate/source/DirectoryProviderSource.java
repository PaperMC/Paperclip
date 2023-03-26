package io.papermc.paperclip.plugin.candidate.source;

import io.papermc.paperclip.mixin.Util;
import io.papermc.paperclip.plugin.candidate.PluginCandidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Loads all plugin providers in the given directory.
 */
public class DirectoryProviderSource implements ProviderSource<Path> {

    public static final DirectoryProviderSource INSTANCE = new DirectoryProviderSource();
    private final FileProviderSource fileProviderSource = new FileProviderSource("File '%s'"::formatted);

    @Override
    public Collection<PluginCandidate> getCandidates(Path context) throws Exception {
        // Sym link happy, create file if missing.
        if (!Files.isDirectory(context)) {
            Files.createDirectories(context);
        }
        final Collection<PluginCandidate> pluginCandidates = new ArrayList<>();
        Files.walk(context, 1).filter(Files::isRegularFile).forEach((path) -> {
            try {
                pluginCandidates.add(fileProviderSource.getCandidate(path));
            } catch (IllegalArgumentException ignored) {
                // Ignore initial argument exceptions
            } catch (Exception e) {
                Util.error("DirectoryProviderSource", "Error loading plugin: " + e.getMessage());
            }
        });
        return pluginCandidates;
    }
}
