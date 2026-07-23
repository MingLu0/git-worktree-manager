plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.purringlabs.gitworktree"
version = "1.1.27"

val generatedVersionDir = layout.buildDirectory.dir("generated/version")

val generateVersionFile by tasks.registering {
    val pluginVersion = project.version.toString()
    val outputFile = generatedVersionDir.map { it.file("com/purringlabs/gitworktree/gitworktreemanager/BuildInfo.kt") }
    inputs.property("pluginVersion", pluginVersion)
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.parentFile.mkdirs()
        outputFile.get().asFile.writeText(
            """
            package com.purringlabs.gitworktree.gitworktreemanager

            internal object BuildInfo {
                const val PLUGIN_VERSION: String = "$pluginVersion"
            }
            """.trimIndent()
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir(generatedVersionDir)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateVersionFile)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    source(generatedVersionDir)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:

        composeUI()

        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            <b>What's new</b>
            <ul>
              <li>Fix telemetry pluginVersion reporting so New Relic events carry the real version instead of "unknown"</li>
              <li>Update marketplace description and README to highlight Claude Code session resume as a core differentiator</li>
            </ul>
""".trimIndent()
    }

    signing {
        certificateChainFile = file("chain.crt")
        privateKeyFile = file("private.pem")
        password = providers.environmentVariable("PLUGIN_SIGNING_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("INTELLIJ_PLATFORM_PUBLISHING_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
