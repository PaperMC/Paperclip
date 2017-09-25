package com.destroystokyo.paperclip;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

public class Agent {

    private static Instrumentation inst = null;

    public static void agentmain(final String agentArgs, final Instrumentation inst) {
        Agent.inst = inst;
    }

    static void addClassPath() {
        try {
            inst.appendToSystemClassLoaderSearch(new JarFile(Paperclip.paperJar));
        } catch (final IOException e) {
            System.err.println("Failed to add Paper jar to ClassPath");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
