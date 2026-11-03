plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
}

layout.buildDirectory.set(file("D:/Data/.temp/AndroidToolKit-build/root"))
subprojects {
    layout.buildDirectory.set(file("D:/Data/.temp/AndroidToolKit-build/${project.path.trim(':').replace(':', '/')}"))
}
