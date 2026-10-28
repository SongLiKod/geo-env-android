package com.geoenv.browser.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.geoenv.browser.BuildConfig
import com.geoenv.browser.db.AppDatabase
import com.geoenv.browser.db.DatabaseMigrations
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.crypto.FieldCipher
import com.geoenv.browser.crypto.KeyStoreManager
import com.geoenv.browser.proxy.ProxyRepository
import com.geoenv.browser.slot.SlotBridge
import java.util.concurrent.Executors

/**
 * 轻量服务定位器。
 *
 * 说明：项目同时使用 Hilt（ViewModel 注入）与本定位器（Application、前台服务、
 * 槽位子进程的早期初始化）。主进程与 5 个槽位子进程都会走
 * [com.geoenv.browser.GeoEnvApp.onCreate]，因此定位器在所有进程中可用，
 * 且每个进程持有自己的单例图（Gecko 相关对象天然进程隔离）。
 *
 * Hilt 侧通过 `di/AppModule` 提供本类单例。
 */
class ServiceLocator(
    private val context: Context,
) {

    /** 应用上下文（供需要 Context 的业务组件使用） */
    val appContext: Context get() = context

    val keyStoreManager: KeyStoreManager by lazy { KeyStoreManager(context) }

    val fieldCipher: FieldCipher by lazy { FieldCipher(keyStoreManager) }

    val database: AppDatabase by lazy { buildDatabase() }

    val auditRepository: AuditRepository by lazy { AuditRepository(database) }

    val environmentRepository: EnvironmentRepository by lazy {
        EnvironmentRepository(database, fieldCipher, auditRepository, context)
    }

    val proxyRepository: ProxyRepository by lazy {
        ProxyRepository(database, auditRepository, fieldCipher)
    }

    /**
     * 主进程 <-> 槽位进程的 IPC 客户端。
     * 槽位子进程不会用到（它们是服务端），但仍会创建该对象，避免跨进程访问出错。
     */
    val slotBridge: SlotBridge by lazy { SlotBridge(context) }

    private fun buildDatabase(): AppDatabase {
        // SQLCipher：32 字节主密钥由 Android Keystore 加密后存放于私有 SharedPreferences
        val passphrase = keyStoreManager.getOrCreateDbPassphrase()
        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .openHelperFactory(factory)
            .addMigrations(*DatabaseMigrations.ALL)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DatabaseMigrations.installHooks(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    // 每次打开都确保审计"只追加"触发器存在（幂等）
                    DatabaseMigrations.installHooks(db)
                }
            })
            // 多进程（主进程 + 5 个槽位进程）共享同一数据库：
            // - SQLCipher 驱动下 WAL 跨进程不可靠，改用 TRUNCATE journal + busy_timeout
            // - enableMultiInstanceInvalidation 让各进程的 Room 失效通知互通
            .enableMultiInstanceInvalidation()
            .setQueryCallback({ _, _ ->
                // 故意不打印 SQL 参数：数据库中存在 AES-GCM 密文字段
            }, Executors.newSingleThreadExecutor())
            .build()
            .also { db -> applyMultiProcessPragmas(db) }
    }

    /** 多进程并发安全：busy_timeout + TRUNCATE journal。 */
    private fun applyMultiProcessPragmas(db: AppDatabase) {
        runCatching {
            val handle = db.openHelper.writableDatabase
            handle.query("PRAGMA busy_timeout=5000").use { c -> c.moveToFirst() }
            handle.query("PRAGMA journal_mode=TRUNCATE").use { c -> c.moveToFirst() }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("GeoEnv", "DB pragmas applied: busy_timeout=5000, journal=TRUNCATE")
            }
        }
    }

    companion object {
        @Volatile
        private var _instance: ServiceLocator? = null

        fun get(): ServiceLocator = _instance ?: error("ServiceLocator not initialized")

        fun peek(): ServiceLocator? = _instance

        fun install(locator: ServiceLocator) {
            _instance = locator
        }
    }
}
