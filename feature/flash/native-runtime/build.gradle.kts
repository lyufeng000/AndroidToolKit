plugins {
    alias(libs.plugins.android.library)
}

val nativeSources = projectDir.parentFile.resolve("src/main")

android {
    namespace = "com.lyufeng.flash.runtime"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.named("main") {
        assets.setSrcDirs(listOf(nativeSources.resolve("assets")))
        jniLibs.setSrcDirs(listOf(nativeSources.resolve("jniLibs")))
    }
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    api(project(":feature:flash:model"))
}
