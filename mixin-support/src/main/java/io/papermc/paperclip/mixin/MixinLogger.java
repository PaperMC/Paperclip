package io.papermc.paperclip.mixin;

import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MixinLogger extends LoggerAdapterAbstract {
    private static final Map<String, ILogger> LOGGER_MAP = new ConcurrentHashMap<>();

    protected MixinLogger(String id) {
        super(id);
    }
    
    static ILogger get(String name) {
        return LOGGER_MAP.computeIfAbsent(name, MixinLogger::new);
    }

    @Override
    public String getType() {
        return "Paperclip Mixin Logger";
    }

    @Override
    public void catching(Level level, Throwable t) {
        log(level, "Catching ".concat(t.toString()), t);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        if (PaperclipLauncherBase.isDevelopment())
            Util.info(level.name(), message, params);
    }

    @Override
    public void log(Level level, String message, Throwable t) {
        if (PaperclipLauncherBase.isDevelopment())
            Util.info(level.name(), message, t);
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        log(Level.ERROR, "Throwing ".concat(t.toString()), t);

        return t;
    }
}
