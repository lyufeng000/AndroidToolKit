plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lyufeng.flash"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.named("main") {
        // The native runtime packages these files from their existing locations.
        assets.setSrcDirs(emptyList<String>())
        jniLibs.setSrcDirs(emptyList<String>())
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", "D:/Data/.temp/AndroidToolKit-tests")
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
}

dependencies {
    api(project(":feature:flash:model"))
    implementation(project(":feature:flash:project-import"))
    implementation(project(":feature:flash:native-runtime"))
    implementation(project(":feature:flash:compiler"))
    implementation(project(":feature:flash:openocd"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
}
