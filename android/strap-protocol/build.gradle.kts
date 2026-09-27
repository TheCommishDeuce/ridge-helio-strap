// Pure Kotlin/JVM: the strap's wire protocol with no Android or I/O dependency, so every
// byte rule is unit-testable on the JVM. The app module supplies the radio.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core) // Flow is part of the StrapLink contract
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}


tasks.test {
    useJUnitPlatform()
}
