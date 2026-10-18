import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.alierkitten.spw.replaygain"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // Provided by the Salt Player host
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    kapt(libs.spw.workshop.api)

    // Bundled into the .spmod
    implementation(libs.jaudiotagger)
    implementation(libs.sqlite.jdbc)
}

spmod {
    PluginClass = "com.alierkitten.spw.replaygain.MainPlugin"
    PluginId = "com.alierkitten.spw.replaygain"
    PluginName = "ReplayGain Calculator"
    PluginDescription = "Measures EBU R128 loudness with FFmpeg and writes ReplayGain tags to MP3/FLAC/OGG metadata."
    PluginVersion = "1.0.0"
    PluginProvider = "alierkitten"
    PluginOpenSourceUrl = "https://github.com/AlierKitten/spw-replay-gain-calculator"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ)
}
