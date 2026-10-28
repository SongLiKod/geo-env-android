// GeoEnv 根构建脚本
// 版本全部锁定（见 docs/geo-env-tech.md §7：Gecko 版本锁定，不自动升级）
plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("com.google.dagger.hilt.android") version "2.58" apply false
}
