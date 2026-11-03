plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", "D:/Data/.temp/AndroidToolKit-tests")
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
    systemProperty("maven.repo.local", "D:/Data/.temp/AndroidToolKit-robolectric/${System.getProperty("user.name")}")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED", "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
    doFirst { file("D:/Data/.temp/AndroidToolKit-tests").mkdirs() }
}

android {
    namespace = "com.example.androidtoolkit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lyufeng.androidtoolkit"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    bundle { language { enableSplit = false } }
    packaging { jniLibs.useLegacyPackaging = true }
    sourceSets.getByName("androidTest").assets.srcDir("../examples/flash")
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":feature:update"))
    implementation(project(":feature:serial"))
    implementation(project(":feature:flash"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(project(":feature:flash:project-import"))
    androidTestImplementation(project(":feature:flash:native-runtime"))
    androidTestImplementation(project(":feature:flash:compiler"))
    androidTestImplementation(project(":feature:flash:openocd"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
