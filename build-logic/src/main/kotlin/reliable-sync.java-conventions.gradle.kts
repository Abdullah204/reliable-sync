// Shared conventions for every Java module in this repo:
// Java 21 toolchain, strict compiler warnings, JUnit 5 with readable test output.
plugins {
    java
}

group = "io.github.abdullah204"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing",
        // Lock/guard resources are deliberately unused inside their try block.
        "-Xlint:-try","-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
