plugins {
    java
    application
    `maven-publish`
}

subprojects {
    apply(plugin = "java")

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

val mainClass = "io.papermc.paperclip.Main"

tasks.jar {
    val java6Jar = project(":java6").tasks.named("jar")
    val java17Jar = project(":java17").tasks.named("shadowJar")
    dependsOn(java6Jar, java17Jar)

    from(zipTree(java6Jar.map { it.outputs.files.singleFile }))
    from(zipTree(java17Jar.map { it.outputs.files.singleFile }))

    manifest {
        attributes(
            "Main-Class" to mainClass
        )
    }

    from(file("license.txt")) {
        into("META-INF/license")
        rename { "paperclip-LICENSE.txt" }
    }
    rename { name ->
        if (name.endsWith("-LICENSE.txt")) {
            "META-INF/license/$name"
        } else {
            name
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    val java6Sources = project(":java6").tasks.named("sourcesJar")
    val java17Sources = project(":java17").tasks.named("sourcesJar")
    dependsOn(java6Sources, java17Sources)

    from(zipTree(java6Sources.map { it.outputs.files.singleFile }))
    from(zipTree(java17Sources.map { it.outputs.files.singleFile }))

    archiveClassifier.set("sources")
}

val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")

publishing {
    publications {
        register<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])
            artifact(sourcesJar)
            withoutBuildIdentifier()

            pom {
                val repoPath = "PaperMC/Paperclip"
                val repoUrl = "https://github.com/$repoPath"

                name.set("Paperclip")
                description.set(project.description)
                url.set(repoUrl)
                packaging = "jar"

                licenses {
                    license {
                        name.set("MIT")
                        url.set("$repoUrl/blob/main/license.txt")
                        distribution.set("repo")
                    }
                }

                issueManagement {
                    system.set("GitHub")
                    url.set("$repoUrl/issues")
                }

                developers {
                    developer {
                        id.set("DemonWav")
                        name.set("Kyle Wood")
                        email.set("demonwav@gmail.com")
                        url.set("https://github.com/DemonWav")
                    }
                }

                scm {
                    url.set(repoUrl)
                    connection.set("scm:git:$repoUrl.git")
                    developerConnection.set("scm:git:git@github.com:$repoPath.git")
                }
            }
        }

        repositories {
            val url = if (isSnapshot) {
                "https://repo.papermc.io/repository/maven-snapshots/"
            } else {
                "https://repo.papermc.io/repository/maven-releases/"
            }

            maven(url) {
                credentials(PasswordCredentials::class)
                name = "paper"
            }
        }
    }
}

tasks.register("printVersion") {
    doFirst {
        println(version)
    }
}
