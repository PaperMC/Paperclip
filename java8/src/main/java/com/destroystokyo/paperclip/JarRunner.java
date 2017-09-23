package com.destroystokyo.paperclip;

import java.net.URL;

public interface JarRunner {

    void runJar(final URL jar, final String mainClass, final String[] args);
}
