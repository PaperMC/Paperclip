package io.papermc.paperclip.plugin;

import io.papermc.paperclip.plugin.candidate.PluginCandidate;
import io.papermc.paperclip.plugin.candidate.source.DirectoryProviderSource;
import io.papermc.paperclip.plugin.candidate.source.PluginFlagProviderSource;
import joptsimple.OptionSet;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

public final class PluginCandidateLoader {
    final Collection<PluginCandidate> candidates = new ArrayList<>();

    public Collection<Path> load(OptionSet optionSet) {
        final Path pluginDirectory = ((File) optionSet.valueOf("plugins")).toPath();
        try {
            this.candidates.addAll(DirectoryProviderSource.INSTANCE.getCandidates(pluginDirectory));
        } catch (Exception e) {}
        @SuppressWarnings("unchecked")
        java.util.List<File> files = (java.util.List<File>) optionSet.valuesOf("add-plugin");
        this.candidates.addAll(PluginFlagProviderSource.INSTANCE.getCandidates(files));
        return candidates.stream().map(PluginCandidate::getSource).toList();
    }

    public Collection<PluginCandidate> getPluginCandidates() {
        return this.candidates;
    }
}
