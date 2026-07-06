pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // includeBuild した共有モジュールにも適用されるプラグインバージョン。
    plugins {
        id("com.android.library") version "8.8.0"
        id("org.jetbrains.kotlin.android") version "2.0.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
    }
}

// 共有モジュール（権限チェック／案内 UI）を composite build で取り込む。
includeBuild("../permissioncore") {
    dependencySubstitution {
        substitute(module("com.pirorin215:permissioncore")).using(project(":"))
    }
}
includeBuild("../permissioncore-compose") {
    dependencySubstitution {
        substitute(module("com.pirorin215:permissioncore-compose")).using(project(":"))
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "GardiaRadar"
include(":app")
include(":wear")
