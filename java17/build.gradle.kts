plugins {
    java
    id("com.github.johnrengelman.shadow") version "7.1.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    implementation(project(":mixin-support"))
    implementation("io.sigpipe:jbsdiff:1.0")
}

tasks.shadowJar {
    val prefix = "paperclip.libs"
    // Spongepowered mixin relocation breaks service loaders, and is generally unneeded.
    listOf("org.apache", "org.tukaani", "io.sigpipe", "com.google", "joptsimple", "org.objectweb", "org.jetbrains", "org.intellij", "org.yaml").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}
