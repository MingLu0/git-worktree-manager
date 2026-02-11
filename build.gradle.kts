plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.purringlabs.gitworktree"
version = "1.1.2"

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
            Fixes:
            - Improve Create Worktree UX when the target worktree folder already exists (prompt early; default opens existing)
            - Improve Create Worktree UX when branch name already exists (prompt early; require rename)
            - Treat existing/registered worktree as success during Create Worktree (opens existing worktree instead of failing)
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
