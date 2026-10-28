package com.geoenv.browser.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.db.ProxyConfig
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.manager.ProxyManager
import com.geoenv.browser.proxy.ProxyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 代理管理 ViewModel（需求 3.4）：代理 CRUD、连通检测、代理池批量分配。
 */
@HiltViewModel
class ProxyViewModel @Inject constructor(
    private val proxyRepo: ProxyRepository,
    val proxyManager: ProxyManager,
    private val locator: ServiceLocator,
) : ViewModel() {

    val proxies: StateFlow<List<ProxyConfig>> = proxyRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 逐条检测进度（id -> 结果摘要） */
    private val _checking = MutableStateFlow<Set<Long>>(emptySet())
    val checking: StateFlow<Set<Long>> = _checking.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun msg(s: String) {
        _messages.tryEmit(s)
    }

    /* ----------------------------- CRUD ----------------------------- */

    fun add(
        name: String,
        type: String,
        host: String,
        port: Int,
        username: String?,
        password: String?,
        remark: String,
    ) {
        viewModelScope.launch {
            runCatching {
                proxyRepo.add(
                    ProxyConfig(
                        name = name,
                        type = type,
                        host = host,
                        port = port,
                        username = username?.ifBlank { null },
                        authRequired = !password.isNullOrBlank(),
                        remark = remark,
                    ),
                    password = password?.ifBlank { null },
                )
                msg("代理已新增")
            }.onFailure { msg("新增失败：${it.message}") }
        }
    }

    fun update(
        id: Long,
        name: String,
        type: String,
        host: String,
        port: Int,
        username: String?,
        password: String?,
        remark: String,
    ) {
        viewModelScope.launch {
            runCatching {
                val cur = proxyRepo.get(id) ?: error("代理不存在")
                proxyRepo.update(
                    cur.copy(
                        name = name,
                        type = type,
                        host = host,
                        port = port,
                        username = username?.ifBlank { null },
                        remark = remark,
                        updateTime = System.currentTimeMillis(),
                    ),
                    password = password?.takeIf { it.isNotBlank() },
                )
                msg("代理已更新")
            }.onFailure { msg("更新失败：${it.message}") }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching {
                proxyRepo.delete(id)
                msg("代理已删除")
            }.onFailure { msg("删除失败：${it.message}") }
        }
    }

    /* ----------------------------- 连通检测 ----------------------------- */

    fun checkOne(id: Long) {
        viewModelScope.launch {
            val p = proxyRepo.get(id) ?: return@launch
            _checking.value = _checking.value + id
            val r = proxyManager.check(p)
            _checking.value = _checking.value - id
            msg(
                if (r.ok) "✔ ${p.name} 连通 ${r.latencyMs}ms" +
                    (r.exitIp?.let { "（出口 $it）" } ?: "")
                else "✘ ${p.name} 失败：${r.error}",
            )
        }
    }

    fun checkAll() {
        viewModelScope.launch {
            val list = proxyRepo.getAll()
            if (list.isEmpty()) { msg("代理池为空"); return@launch }
            _checking.value = list.map { it.id }.toSet()
            val results = proxyManager.checkAll(list)
            _checking.value = emptySet()
            val ok = results.count { it.ok }
            msg("批量检测完成：$ok / ${results.size} 可用")
        }
    }

    /* ----------------------------- 代理池 ----------------------------- */

    private val _strategy = MutableStateFlow(ProxyManager.PoolStrategy.ROUND_ROBIN)
    val strategy: StateFlow<ProxyManager.PoolStrategy> = _strategy.asStateFlow()

    fun setStrategy(s: ProxyManager.PoolStrategy) {
        _strategy.value = s
    }

    /** 把代理池批量分配给"有代理需求"的环境（当前列表全部） */
    fun assignPool(proxyIds: List<Long>, onlyHealthy: Boolean, envIds: List<Long>? = null) {
        viewModelScope.launch {
            runCatching {
                val pool = if (onlyHealthy) {
                    locator.database.proxyDao().getHealthy().filter { it.id in proxyIds }
                } else {
                    proxyRepo.getAll().filter { it.id in proxyIds }
                }
                if (pool.isEmpty()) { msg("没有可用的代理（请先检测）"); return@runCatching }
                val targets = envIds ?: locator.database.envDao().getAll().map { it.id }
                if (targets.isEmpty()) { msg("没有可分配的环境"); return@runCatching }
                val mapping = proxyManager.assignPool(
                    envIds = targets,
                    proxyIds = pool.map { it.id },
                    strategy = _strategy.value,
                )
                msg("已为 ${mapping.size} 个环境分配代理（${pool.size} 个入池）")
            }.onFailure { msg("分配失败：${it.message}") }
        }
    }

    fun unbindAll() {
        viewModelScope.launch {
            runCatching { proxyManager.unbindAllEnvironments(); msg("已解绑全部代理") }
                .onFailure { msg("解绑失败：${it.message}") }
        }
    }

    /** 批量检测结果流（供 UI 显示最新延迟/状态） */
    val checkResults: SharedFlow<ProxyManager.CheckResult> = proxyManager.results
}
