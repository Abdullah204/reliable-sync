pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads a matching JDK when the requested toolchain (Java 21) is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The generic engine (retries, cursors, dead letters, run log) comes from my private
// connector-foundations library, published to GitHub Packages. Reading it needs a token with
// read:packages in PACKAGES_TOKEN. With a sibling checkout at ../connector-foundations, Gradle
// builds against that instead and no token is needed.
val localFoundations = file("../connector-foundations/settings.gradle.kts").exists()
        && providers.gradleProperty("useLocalFoundations").getOrElse("true").toBoolean()
if (localFoundations) {
    includeBuild("../connector-foundations")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        if (!localFoundations) {
            maven("https://maven.pkg.github.com/Abdullah204/connector-foundations") {
                name = "connectorFoundations"
                content { includeGroup("io.github.abdullah204.foundations") }
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").getOrElse("token")
                    password = providers.environmentVariable("PACKAGES_TOKEN").orNull
                }
            }
        }
    }
}

rootProject.name = "reliable-sync"

include(
    "mocks:acme-crm-mock",
    "services:reliable-sync",
)
