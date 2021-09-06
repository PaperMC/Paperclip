/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package io.papermc.paperclip;

public final class Paperclip {

    public static void main(final String[] args) {
        System.err.println("Minecraft 1.17 requires running the server with Java 16 or above. " +
            "Download Java 16 (or above) from https://adoptium.net/. For more information, see " +
            "https://paper.readthedocs.io/en/latest/java-update/");
        System.exit(1);
    }
}
