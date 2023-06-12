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

    targetCompatibility = JavaVersion.VERSION_1_6
    sourceCompatibility = JavaVersion.VERSION_1_6

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs = listOf("-Xlint:-options")
}


tasks.shadowJar {
    val prefix = "paperclip.libs"
    listOf("com.eclipsesource").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}