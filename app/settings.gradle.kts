// dl.google.com is unreliable from some networks (this repo was developed on
// one), so local builds fall back to a Google Maven mirror. CI keeps only the
// official repositories: a mirror hiccup must never fail a release build.
// The environment is checked inline because pluginManagement is configured
// before any top-level val exists.
pluginManagement {
    repositories {
        google()
        if (System.getenv("CI") == null) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS also tolerates the SDK's init script, which appends a
    // Google Maven mirror to project repositories.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        if (System.getenv("CI") == null) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        mavenCentral()
    }
}

rootProject.name = "vitals-app"
