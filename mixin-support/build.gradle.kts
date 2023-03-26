plugins {
    `java-library`
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    api("net.fabricmc:sponge-mixin:0.12.4+mixin.0.8.5")
    api("org.yaml:snakeyaml:1.33")

    api("org.jetbrains:annotations:23.0.0")
    api("net.sf.jopt-simple:jopt-simple:5.0.4")
}