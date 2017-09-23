package com.destroystokyo.paperclip;

public final class Main {

    public static void main(final String[] args) {
        final double version = Double.parseDouble(System.getProperty("java.specification.version"));
        if (version < 1.8) {
            // get mad at them
            System.err.println("Paper requires Java 8, please upgrade to it.");
            System.err.println("http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html");
            System.exit(1);
        }

        Paperclip.run(args);
    }
}
