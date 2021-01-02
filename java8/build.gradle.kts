plugins {
    java
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
}

tasks.shadowJar {
    relocate("org", "paperclip.libs.org")
    relocate("io.sigpipe", "paperclip.libs.io.sigpipe")
}
