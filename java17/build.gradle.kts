plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
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
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
}

tasks.shadowJar {
    val prefix = "paperclip.libs"
    listOf("org.apache", "org.tukaani", "io.sigpipe").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}
