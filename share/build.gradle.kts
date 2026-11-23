plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
