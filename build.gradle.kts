import org.jetbrains.changelog.Changelog

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Bez kompatibilnich mostu na vychozi metody rozhrani platformy (ToolWindowFactory),
        // jinak trida "prepisuje" i zastarale a experimentalni metody, ktere vubec nepouziva
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

/**
 * Lokalni instalace IntelliJ (preklad, runIde, verifyPlugin): vlastnost localIdePath (napr. v ~/.gradle/gradle.properties),
 * jinak standardni umisteni na Windows. Kdyz zadna neni, stahne se IntelliJ IDEA ve verzi platformVersion.
 */
fun localIdePath(): String? {
    val candidates = listOfNotNull(
        providers.gradleProperty("localIdePath").orNull,
        providers.environmentVariable("LOCALAPPDATA").orNull?.let { "$it/Programs/IntelliJ IDEA Ultimate" },
    )
    return candidates.firstOrNull { file(it).resolve("build.txt").exists() }
}

dependencies {
    // snakeyaml, markdown (org.intellij.markdown) a gson poskytuje platforma IntelliJ, nebaluji se
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        val localIde = localIdePath()
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion").get())
        }
        // Od 2026.3 je JCEF samostatny bundled plugin
        bundledPlugin("com.intellij.modules.jcef")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
        // Change notes of the released version (or Unreleased) from CHANGELOG.md
        changeNotes = provider { project.version.toString() }.map { pluginVersion ->
            with(project.changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
    // Optional signing; without CERTIFICATE_CHAIN and PRIVATE_KEY the plugin is published unsigned
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        // Marketplace token: PUBLISH_TOKEN (CI secret) or the publishToken property (e.g. ~/.gradle/gradle.properties)
        token = providers.environmentVariable("PUBLISH_TOKEN").orElse(providers.gradleProperty("publishToken"))
        // Pre-release label selects the channel: 1.2.0-beta.1 -> beta, 1.2.0 -> default
        channels = provider { project.version.toString() }.map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            val localIde = localIdePath()
            if (localIde != null) local(localIde) else recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    // Release tags have no "v" prefix (the release draft uses the bare version)
    versionPrefix = ""
}

tasks.publishPlugin {
    dependsOn(tasks.patchChangelog)
}

tasks.runIde {
    // ./gradlew runIde -PrunIdeProject=<cesta k projektu s .kanbn> otevre rovnou dany projekt
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}
