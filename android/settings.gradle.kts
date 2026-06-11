pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MacRemote"

// 协议核心是独立的纯 JVM 构建，复合引入；
// 这样不装 Android SDK 也能单独测试：gradle -p core test
includeBuild("core")
include(":app")
