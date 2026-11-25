plugins {
    id("reliable-sync.java-conventions")
    application
}

description = "Incremental, crash-safe sync of Acme CRM customers and invoices into PostgreSQL."

dependencies {
    implementation(libs.foundations.sync)
    implementation(libs.jackson.jsr310)
    runtimeOnly(libs.logback)

    testImplementation(libs.foundations.testing)
    testImplementation(project(":mocks:acme-crm-mock"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass = "io.github.abdullah204.reliablesync.ReliableSyncMain"
    applicationName = "reliable-sync"
}
