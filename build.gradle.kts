plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.purringlabs.gitworktree"
version = "1.1.9"

repositories {
    // Needed for optional Compose UI testing dependencies (androidx artifacts)
    google()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

sourceSets {
    create("uiTest") {
        kotlin.srcDir("src/uiTest/kotlin")
        resources.srcDir("src/uiTest/resources")
        compileClasspath += sourceSets["main"].output + configurations["testCompileClasspath"]
        runtimeClasspath += output + compileClasspath + configurations["testRuntimeClasspath"]
    }
}

configurations {
    // Wire up uiTest* configurations
    named("uiTestImplementation") {
        extendsFrom(configurations["testImplementation"])
    }
    named("uiTestRuntimeOnly") {
        extendsFrom(configurations["testRuntimeOnly"])
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

    // Optional Compose Desktop UI tests live in a separate sourceSet/task (uiTest) so they
    // don't interfere with IntelliJ platform tests/classpath.
    "uiTestImplementation"(kotlin("test"))
    "uiTestImplementation"("org.jetbrains.compose.ui:ui-test-junit4:1.8.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Fixes:
            - Fix crash during long-running operations when the tool window leaves composition (ForgottenCoroutineScopeException)
            - Improve delete-worktree errors when Git worktree metadata is broken (.git missing) with actionable guidance (git worktree prune)
            - Avoid potential state update races during refresh/delete flows by serializing ViewModel state updates
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

    // Optional: run with ./gradlew uiTest -DenableDesktopComposeUiTests=true
    // These tests need a Java 21 runtime because the plugin is built for Java 21.
    register<Test>("uiTest") {
        description = "Runs optional Compose Desktop UI tests"
        group = "verification"
        testClassesDirs = sourceSets["uiTest"].output.classesDirs
        classpath = sourceSets["uiTest"].runtimeClasspath
        shouldRunAfter("test")

        // Prefer the JetBrains Runtime (JBR) shipped with the IntelliJ distribution Gradle downloads.
        // We locate it from the Gradle cache rather than requiring a local JDK 21 installation.
        val jbrJava = fileTree(gradle.gradleUserHomeDir).matching {
            include("caches/**/transforms/**/transformed/ideaIU-*/jbr/**/bin/java")
        }.files.firstOrNull()

        if (jbrJava != null) {
            executable = jbrJava.absolutePath
        } else {
            // Keep the build green by skipping unless explicitly configured.
            enabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
