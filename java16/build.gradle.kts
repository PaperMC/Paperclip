plugins {
    java
    id("com.github.johnrengelman.shadow") version "7.1.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(16))
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(16)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":java9"))
    implementation("io.sigpipe:jbsdiff:1.0")
}

tasks.shadowJar {
    val prefix = "paperclip.libs"
    listOf("org.apache", "org.tukaani", "io.sigpipe").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    dependencies {
        exclude(project(":java9"))
    }
}
