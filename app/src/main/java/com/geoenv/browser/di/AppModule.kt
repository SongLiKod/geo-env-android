package com.geoenv.browser.di

import android.content.Context
import com.geoenv.browser.crypto.FieldCipher
import com.geoenv.browser.crypto.KeyStoreManager
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.db.AppDatabase
import com.geoenv.browser.proxy.ProxyRepository
import com.geoenv.browser.slot.SlotBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 模块：把 [ServiceLocator] 中的单例图暴露给 ViewModel / Composable。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideServiceLocator(@ApplicationContext context: Context): ServiceLocator {
        // 优先复用 Application 创建的实例，保证进程内真正单例
        return ServiceLocator.peek() ?: ServiceLocator(context).also { ServiceLocator.install(it) }
    }

    @Provides
    @Singleton
    fun provideKeyStoreManager(locator: ServiceLocator): KeyStoreManager = locator.keyStoreManager

    @Provides
    @Singleton
    fun provideFieldCipher(locator: ServiceLocator): FieldCipher = locator.fieldCipher

    @Provides
    @Singleton
    fun provideDatabase(locator: ServiceLocator): AppDatabase = locator.database

    @Provides
    @Singleton
    fun provideAuditRepository(locator: ServiceLocator): AuditRepository = locator.auditRepository

    @Provides
    @Singleton
    fun provideEnvironmentRepository(locator: ServiceLocator): EnvironmentRepository =
        locator.environmentRepository

    @Provides
    @Singleton
    fun provideProxyRepository(locator: ServiceLocator): ProxyRepository = locator.proxyRepository

    @Provides
    @Singleton
    fun provideSlotBridge(@ApplicationContext context: Context): SlotBridge =
        ServiceLocator.get().slotBridge
}
