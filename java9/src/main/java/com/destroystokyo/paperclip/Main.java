package com.destroystokyo.paperclip;

public class Main {

    public static void main(final String[] args) {
        System.out.println("WARNING: Java 9 currently causes significant issue for a lot of Bukkit plugins due to new ClassLoading semantics.");
        System.out.println("WARNING: For the time being, it is recommended to stick with Java 8 until plugins are updated.");

        Paperclip.run(args);
    }
}
