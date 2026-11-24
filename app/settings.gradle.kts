pluginManagement {
    repositories {
        google()
        // Machine-local note: dl.google.com is unreliable from some networks
        // (see $ANDROID_SDK/.gradle/init.d/google-maven-mirror.gradle). The
        // mirror keeps builds working without changing anything else.
        maven { url = uri("https://maven.aliyun.com/repository/google") }
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
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        mavenCentral()
    }
}

rootProject.name = "vitals-app"
