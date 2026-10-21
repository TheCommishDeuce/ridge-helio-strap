// Pure Kotlin/JVM: every derived number (spec/02), worked out on the phone from its own store
// (D34). No Android, no SQL: the app hands it the raw data through `RawData`, so every formula
// is unit-testable on the JVM against the server's golden outputs.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.json) // derived rows carry their flags as JSON objects
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
