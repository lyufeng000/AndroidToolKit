plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lyufeng.phoneserialport.core"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
