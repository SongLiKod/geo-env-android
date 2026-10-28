# ---- GeoEnv ProGuard 规则 ----

# GeckoView
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keep,allowobfuscation,allowshrinking @dagger.hilt.EntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.internal.ComponentEntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.internal.GeneratedEntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.EarlyEntryPoint class *

# Hilt 生成的组件树 / 注入基类（位于应用包 com.geoenv.browser.*，而非 dagger.hilt.**）。
# 这些类由 @HiltAndroidApp / @AndroidEntryPoint 在编译期生成，运行时通过
# ComponentSupplier / builders 以组件管理器方式实例化；R8 full mode 会把它们判定为
# “无直接引用”而剔除，导致 super.onCreate() 抛 ClassNotFoundException（一打开就闪退）。
# 因此按 Hilt 的命名约定整体保留（仅应用包内，不影响其余代码混淆）。
-keep class com.geoenv.browser.Hilt_* { *; }
-keep class * extends com.geoenv.browser.Hilt_* { *; }
-keep class com.geoenv.browser.*_GeneratedInjector { *; }
-keep class com.geoenv.browser.*_HiltComponents { *; }
-keep class com.geoenv.browser.*_HiltComponents$* { *; }
-keep class com.geoenv.browser.Dagger* { *; }
-keep class com.geoenv.browser.Dagger*$* { *; }
-keep class com.geoenv.browser.*_ComponentTreeDeps { *; }
-keep class com.geoenv.browser.*_HiltModules { *; }
-keep class com.geoenv.browser.*_HiltModules$* { *; }
-keep class com.geoenv.browser.*_Factory { *; }
-keep class com.geoenv.browser.*_MembersInjector { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class com.geoenv.browser.db.** { *; }
-dontwarn androidx.room.paging.**

# SQLCipher（新版 net.zetetic:sqlcipher-android 的包名是 net.zetetic.database.**；
# 旧的 net.sqlcipher.** 仅作兼容保留。AAR 自带 consumer 规则亦会保护 net.zetetic.**）
-keep class net.zetetic.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.* { *; }

# Gson 序列化模型
# 所有被 Gson 反射按“字段名”读写的模型必须保留原始字段名，否则：
#  1) R8 full mode 会把 data class 字段重命名为 a/b/c，Gson 读写键名错乱；
#  2) 导出包需与 scripts/export_env.py（Python 按字段名解析）双向兼容，
#     字段名一变即破坏“跨设备导入”这一验收项。
# 覆盖清单：
#   - gecko.FingerprintConfig（+嵌套）与 gecko.FingerprintIssue（Report.issues 元素）
#   - db.**（Entities.kt 全部 Room 实体，含 ThemeConfig；BackupManager 序列化）
#   - manager.ReportManager 的嵌套模型 Report / RuntimeObservation / CheckLine
-keepclassmembers class com.geoenv.browser.gecko.FingerprintConfig { *; }
-keep class com.geoenv.browser.gecko.FingerprintConfig { *; }
-keepclassmembers class com.geoenv.browser.gecko.FingerprintConfig$* { *; }
-keepclassmembers class com.geoenv.browser.gecko.FingerprintIssue { *; }
-keep class com.geoenv.browser.gecko.FingerprintIssue { *; }
-keepclassmembers class com.geoenv.browser.db.** { *; }
-keep class com.geoenv.browser.db.** { *; }
-keepclassmembers class com.geoenv.browser.manager.ReportManager$* { *; }
-keep class com.geoenv.browser.manager.ReportManager$* { *; }
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Timber
-dontwarn org.jetbrains.annotations.**

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Compose
-dontwarn androidx.compose.**

# 保留 BuildConfig
-keep class com.geoenv.browser.BuildConfig { *; }

# 启动崩溃诊断（临时，确认根因后移除）：完整保留，确保未捕获异常处理器
# 在 super.onCreate() 之前可靠安装，不受 R8 内联/成员剔除影响。
-keep class com.geoenv.browser.CrashLogger { *; }
