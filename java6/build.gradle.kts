plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation("com.eclipsesource.minimal-json:minimal-json:0.9.5")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(6)
    options.compilerArgs = listOf("-Xlint:-options")
}


tasks.shadowJar {
    val prefix = "paperclip.libs"
    listOf("com.eclipsesource").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    minimize()

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}