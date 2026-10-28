pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // 允许 init.d 镜像脚本注入的仓库与本仓库共存
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // GeckoView 固定版本本地仓库（离线可重复构建）
        maven { url = uri("file://${rootDir}/repo") }
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // GeckoView 官方仓库（本地仓库缺文件时兜底）
        maven { url = uri("https://maven.mozilla.org/maven2") }
    }
}

rootProject.name = "GeoEnv"
include(":app")
