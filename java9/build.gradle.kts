plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(9)
}
