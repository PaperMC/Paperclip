package io.papermc.paperclip.mixin;

import io.papermc.paperclip.plugin.PluginCandidateLoader;
import io.papermc.paperclip.plugin.candidate.PluginCandidate;
import io.papermc.paperclip.plugin.configuration.SimplePaperPluginMeta;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

import java.util.HashMap;
import java.util.Map;

public class PaperclipMixinBootstrap {
    private PaperclipMixinBootstrap() {}

    public static final PluginCandidateLoader PLUGIN_CANDIDATE_LOADER = new PluginCandidateLoader();

    public static void init() {
        System.setProperty("mixin.service", PaperclipMixinService.class.getName());
        System.setProperty("mixin.bootstrapService", PaperclipMixinServiceBootstrap.class.getName());
        MixinBootstrap.init();


        final Map<String, PluginCandidate> uniqueCheck = new HashMap<>();
        for (final PluginCandidate pluginCandidate : PLUGIN_CANDIDATE_LOADER.getPluginCandidates()) {
            SimplePaperPluginMeta meta = pluginCandidate.getMeta();
            if (meta == null)
                return;
            final String configFile = meta.mixinConfig().toString();
            final PluginCandidate prev = uniqueCheck.putIfAbsent(configFile, pluginCandidate);
            if (prev != null) throw new RuntimeException(String.format("Duplicate mixin config named %s used by plugins %s and %s", configFile, prev.getSource().getFileName(), pluginCandidate.getSource().getFileName()));

            try {
                Mixins.addConfiguration(configFile);
            } catch (final Throwable e) {
                throw new RuntimeException(String.format("Failed to handle mixin config for %s", pluginCandidate.getSource().getFileName()), e);
            }
        }
    }
}
