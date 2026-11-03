pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/google") {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/google") {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
        google()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "AndroidToolKit"
include(":app")
include(":feature:home")
include(":feature:update")
include(":feature:serial")
include(":feature:serial:core")
include(":feature:serial:terminal")
include(":feature:serial:transfer")
include(":feature:serial:storage")
include(":feature:serial:commands")
include(":feature:serial:logging")
include(":feature:serial:connection")
include(":feature:flash")
include(":feature:flash:model")
include(":feature:flash:project-import")
include(":feature:flash:native-runtime")
include(":feature:flash:compiler")
include(":feature:flash:openocd")
