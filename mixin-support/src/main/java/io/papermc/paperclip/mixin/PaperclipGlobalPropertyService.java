package io.papermc.paperclip.mixin;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

public class PaperclipGlobalPropertyService implements IGlobalPropertyService {
    @Override
    public IPropertyKey resolveKey(String name) {
        return new MixinStringPropertyKey(name);
    }

    private String keyString(IPropertyKey key) {
        return ((MixinStringPropertyKey) key).key();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) PaperclipLauncherBase.getProperties().get(keyString(key));
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        PaperclipLauncherBase.getProperties().put(keyString(key), value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        return (T) PaperclipLauncherBase.getProperties().getOrDefault(keyString(key), defaultValue);
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object o = PaperclipLauncherBase.getProperties().get(keyString(key));
        return o != null ? o.toString() : defaultValue;
    }
}
