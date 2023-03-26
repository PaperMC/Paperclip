package io.papermc.paperclip.plugin.candidate.source;

import io.papermc.paperclip.plugin.candidate.PluginCandidate;

import java.util.Collection;

/**
 * A provider source is responsible for giving PluginTypes an EntrypointHandler for
 * registering providers at.
 *
 * @param <C> context
 */
public interface ProviderSource<C> {

    Collection<PluginCandidate> getCandidates(C context) throws Throwable;
}
