package com.geoenv.browser.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.let {
            val t = object : TypeToken<List<String>>() {}.type
            runCatching { gson.fromJson<List<String>>(it, t) }.getOrNull()
        }
}

@Database(
    entities = [
        EnvGroup::class,
        Environment::class,
        Account::class,
        FingerprintSnapshot::class,
        ProxyConfig::class,
        AuditLog::class,
        ThemeConfig::class,
        HostsRule::class,
        HarRecord::class,
        PopupEvent::class,
        BackupRecord::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun envDao(): EnvironmentDao
    abstract fun groupDao(): EnvGroupDao
    abstract fun accountDao(): AccountDao
    abstract fun snapshotDao(): FingerprintSnapshotDao
    abstract fun proxyDao(): ProxyDao
    abstract fun auditDao(): AuditLogDao
    abstract fun themeDao(): ThemeConfigDao
    abstract fun hostsDao(): HostsRuleDao
    abstract fun harDao(): HarDao
    abstract fun popupDao(): PopupDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME = "geoenv.db"
        const val VERSION = 1
    }
}
