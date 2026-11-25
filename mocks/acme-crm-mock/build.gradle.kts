plugins {
    id("reliable-sync.java-conventions")
    `java-library`
    application
}

description = "Fake 'Acme CRM' REST API with cursor pagination, rate limiting and injectable failures."

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback)
}

application {
    mainClass = "io.github.abdullah204.mock.acme.AcmeCrmMockMain"
}
