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

val mainClass = "io.papermc.paperclip.Paperclip"
val agentClass = "io.papermc.paperclip.Agent"

tasks.jar {
    val java6Jar = project(":java6").tasks.named("jar")
    val java9Jar = project(":java9").tasks.named("jar")
    val java16Jar = project(":java16").tasks.named("shadowJar")
    dependsOn(java6Jar, java9Jar, java16Jar)

    from(zipTree(java6Jar.map { it.outputs.files.singleFile }))
    from(zipTree(java9Jar.map { it.outputs.files.singleFile })) {
        exclude("**/META-INF/**")
        into("META-INF/versions/9")
    }
    from(zipTree(java16Jar.map { it.outputs.files.singleFile })) {
        exclude("**/META-INF/**")
        into("META-INF/versions/16")
    }

    manifest {
        attributes(
            "Main-Class" to mainClass,
            "Multi-Release" to "true",
            "Launcher-Agent-Class" to agentClass,
            "Premain-Class" to agentClass
        )
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    val java6Sources = project(":java6").tasks.named("sourcesJar")
    val java9Sources = project(":java9").tasks.named("sourcesJar")
    val java16Sources = project(":java16").tasks.named("sourcesJar")
    dependsOn(java6Sources, java9Sources, java16Sources)

    from(zipTree(java6Sources.map { it.outputs.files.singleFile }))
    from(zipTree(java9Sources.map { it.outputs.files.singleFile })) {
        exclude("**/META-INF/**")
        into("META-INF/versions/9")
    }
    from(zipTree(java16Sources.map { it.outputs.files.singleFile })) {
        exclude("**/META-INF/**")
        into("META-INF/versions/16")
    }

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
                "https://papermc.io/repo/repository/maven-snapshots/"
            } else {
                "https://papermc.io/repo/repository/maven-releases/"
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
