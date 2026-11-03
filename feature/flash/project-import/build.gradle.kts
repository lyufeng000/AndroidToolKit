plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lyufeng.flash.projectimport"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":feature:flash:model"))
    implementation("androidx.documentfile:documentfile:1.0.1")
}
