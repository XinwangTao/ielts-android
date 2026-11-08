pluginManagement {
    repositories {
        // 官方源优先：GitHub Actions 访问阿里云镜像会超时（networkTimeout=10s），
        // 曾经导致 KSP 插件在 CI 上直接解析失败；镜像留作国内本地环境的兜底。
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "ielts-android"
include(":app")
