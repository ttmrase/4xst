pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "crownfall"

include(":core", ":ai")

/**
 * :app は Android SDK がある環境でのみ include する（docs/02 §0）。
 * 1) -PwithAndroid=true/false が最優先
 * 2) 未指定なら ANDROID_HOME / ANDROID_SDK_ROOT が実在ディレクトリを指すときだけ include
 */
val withAndroid: Boolean =
    providers.gradleProperty("withAndroid").orNull?.let { it.toBoolean() }
        ?: listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { name ->
            providers.environmentVariable(name).orNull
                ?.takeIf { it.isNotBlank() }
                ?.let { file(it).isDirectory } == true
        }

if (withAndroid) {
    include(":app")
}
