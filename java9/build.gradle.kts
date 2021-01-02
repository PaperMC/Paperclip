plugins {
    java
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(9)
}

dependencies {
    implementation(project(":java8"))
}
