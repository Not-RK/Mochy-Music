// The Deezer source module: Lossless FLAC & high bitrate MP3 streaming from Deezer with proxy & ARL support.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
