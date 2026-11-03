plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lyufeng.phoneserialport.connection"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":feature:serial:commands"))
    api(project(":feature:serial:logging"))
    api(project(":feature:serial:storage"))
    api(project(":feature:serial:transfer"))
    implementation(project(":feature:serial:terminal"))
    implementation(files("libs/CH34XUartDriver-2.3.jar"))
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation(libs.androidx.core.ktx)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(libs.junit)
}
