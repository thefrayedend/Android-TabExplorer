plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
