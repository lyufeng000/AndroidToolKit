plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lyufeng.phoneserialport.storage"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":feature:serial:core"))
    implementation(project(":feature:serial:commands"))
    implementation(project(":feature:serial:terminal"))
    api("androidx.datastore:datastore-preferences:1.1.1")
    api("androidx.room:room-runtime:2.7.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
}
