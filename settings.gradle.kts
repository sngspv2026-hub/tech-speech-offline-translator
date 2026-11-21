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
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TechSpeechTranslator"

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")
include(":engine:api")
include(":engine:audio")
include(":engine:asr")
include(":engine:mt")
include(":engine:tts")
include(":engine:packmanager")
include(":feature:live")
include(":feature:conversation")
include(":feature:text")
include(":feature:glossary")
include(":feature:history")
include(":feature:settings")
