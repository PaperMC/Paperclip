plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
}

rootProject.name = "paperclip"
include("java6", "java17")
