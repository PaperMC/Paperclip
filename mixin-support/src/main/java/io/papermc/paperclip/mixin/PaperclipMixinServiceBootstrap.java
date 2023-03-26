package io.papermc.paperclip.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public class PaperclipMixinServiceBootstrap implements IMixinServiceBootstrap {
    @Override
    public String getName() {
        return "Paperclip";
    }

    @Override
    public String getServiceClassName() {
        return "io.papermc.paperclip.mixin.PaperclipMixinService";
    }

    @Override
    public void bootstrap() {

    }
}
