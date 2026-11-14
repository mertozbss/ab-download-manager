plugins{
    id(MyPlugins.kotlin)
    id(Plugins.Kotlin.serialization)
}

dependencies {
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth.core)
    implementation(libs.ktor.server.auth.apiKey)
    implementation(project(":shared:utils"))

    // security tests for the local integration API (mertozbss fork)
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
