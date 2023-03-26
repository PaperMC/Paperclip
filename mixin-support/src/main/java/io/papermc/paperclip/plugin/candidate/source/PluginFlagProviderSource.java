package io.papermc.paperclip.plugin.candidate.source;

import io.papermc.paperclip.mixin.Util;
import io.papermc.paperclip.plugin.candidate.PluginCandidate;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Registers providers at the provided files in the add-plugin argument.
 */
public class PluginFlagProviderSource implements ProviderSource<List<File>> {

    public static final PluginFlagProviderSource INSTANCE = new PluginFlagProviderSource();
    private final FileProviderSource providerSource = new FileProviderSource("File '%s' specified through 'add-plugin' argument"::formatted);

    @Override
    public Collection<PluginCandidate> getCandidates(List<File> context) {
        Collection<PluginCandidate> pluginCandidates = new ArrayList<>();
        for (File file : context) {
            try {
                pluginCandidates.add(this.providerSource.getCandidate(file.toPath()));
            } catch (Exception e) {
                Util.error("PluginFlagProviderSource", "Error loading plugin: " + e.getMessage());
            }
        }
        return pluginCandidates;
    }
}
