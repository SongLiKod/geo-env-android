package com.geoenv.browser.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.db.Account
import com.geoenv.browser.db.Environment
import com.geoenv.browser.db.EnvGroup
import com.geoenv.browser.db.FingerprintSnapshot
import com.geoenv.browser.db.HostsRule
import com.geoenv.browser.db.ProxyConfig
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.gecko.FingerprintConfig
import com.geoenv.browser.gecko.FingerprintIssue
import com.geoenv.browser.gecko.FingerprintValidator
import com.geoenv.browser.manager.EnvManager
import com.geoenv.browser.manager.MarionetteManager
import com.geoenv.browser.manager.ReportManager
import com.geoenv.browser.slot.SlotBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 环境详情 / 指纹配置 / 快照 / Hosts / Marionette / 检测报告 统一 ViewModel。
 *
 * 以 `envId` 为参数（[setEnv]），后续所有 Flow 随之切换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EnvDetailViewModel @Inject constructor(
    private val envRepo: EnvironmentRepository,
    private val locator: ServiceLocator,
    val envManager: EnvManager,
    private val marionetteManager: MarionetteManager,
    private val reportManager: ReportManager,
) : ViewModel() {

    private val _envId = MutableStateFlow(0L)
    val envId: StateFlow<Long> = _envId.asStateFlow()

    fun setEnv(id: Long) {
        if (_envId.value != id) _envId.value = id
    }

    /* ----------------------------- 数据流 ----------------------------- */

    val environment: StateFlow<Environment?> =
        _envId.flatMapLatest { id -> if (id <= 0L) flowOf(null) else envRepo.observeEnvironment(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val account: StateFlow<Account?> =
        _envId.flatMapLatest { id -> if (id <= 0L) flowOf(null) else envRepo.observeAccount(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val snapshots: StateFlow<List<FingerprintSnapshot>> =
        _envId.flatMapLatest { id -> if (id <= 0L) flowOf(emptyList()) else locator.database.snapshotDao().observeByEnv(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hostsRules: StateFlow<List<HostsRule>> =
        _envId.flatMapLatest { id -> if (id <= 0L) flowOf(emptyList()) else envRepo.observeHostsRules(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 槽位状态（运行中才有） */
    val slotState: StateFlow<SlotBridge.SlotState?> =
        kotlinx.coroutines.flow.combine(_envId, locator.slotBridge.states) { id, map ->
            map.values.firstOrNull { it.envId == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 全部分组（基本信息编辑用） */
    val groups: StateFlow<List<EnvGroup>> = envRepo.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部代理（绑定代理用） */
    val proxies: StateFlow<List<ProxyConfig>> = locator.database.proxyDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 绑定 / 解绑代理（写审计；运行中需重启生效） */
    fun bindProxy(proxyId: Long?) {
        val id = _envId.value
        viewModelScope.launch {
            runCatching {
                locator.proxyRepository.bindEnvironment(id, proxyId)
                msg(if (proxyId != null) "代理已绑定，重启环境生效" else "已解除代理绑定")
            }.onFailure { msg("绑定代理失败：${it.message}") }
        }
    }

    /** 重命名（带重名校验） */
    fun rename(name: String) {
        val id = _envId.value
        viewModelScope.launch {
            runCatching { envRepo.renameEnvironment(id, name) }
                .onFailure { msg("重命名失败：${it.message}") }
        }
    }

    /* ----------------------------- 事件 ----------------------------- */

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun msg(s: String) {
        _messages.tryEmit(s)
    }

    /* ----------------------------- 环境字段 ----------------------------- */

    fun updateEnvironment(transform: (Environment) -> Environment) {
        val env = environment.value ?: return
        viewModelScope.launch {
            runCatching { envRepo.updateEnvironment(transform(env)) }
                .onFailure { msg("保存失败：${it.message}") }
        }
    }

    fun delete() {
        val id = _envId.value
        viewModelScope.launch {
            runCatching {
                envManager.stop(id)
                envRepo.deleteEnvironment(id)
                msg("已删除")
            }.onFailure { msg("删除失败：${it.message}") }
        }
    }

    /* ----------------------------- 指纹 ----------------------------- */

    /** 当前编辑中的指纹（未保存） */
    private val _draft = MutableStateFlow<FingerprintConfig?>(null)
    val draft: StateFlow<FingerprintConfig?> = _draft.asStateFlow()

    private val _issues = MutableStateFlow<List<FingerprintIssue>>(emptyList())
    val issues: StateFlow<List<FingerprintIssue>> = _issues.asStateFlow()

    fun loadDraft() {
        val env = environment.value ?: return
        _draft.value = FingerprintConfig.fromJson(env.fingerprintJson)
        validateDraft()
    }

    fun updateDraft(transform: (FingerprintConfig) -> FingerprintConfig) {
        val cur = _draft.value ?: return
        _draft.value = transform(cur)
        validateDraft()
    }

    private fun validateDraft() {
        val cur = _draft.value ?: return
        _issues.value = FingerprintValidator.validate(cur)
    }

    val hasBlockingError: Boolean
        get() = FingerprintValidator.hasError(_issues.value)

    /** 保存指纹（有 ERROR 时拒绝） */
    fun saveFingerprint() {
        val id = _envId.value
        val cur = _draft.value ?: return
        if (FingerprintValidator.hasError(_issues.value)) {
            msg("指纹参数存在冲突，无法保存")
            return
        }
        viewModelScope.launch {
            runCatching {
                envRepo.updateFingerprint(id, FingerprintConfig.toJson(cur))
                val env = envRepo.getEnvironment(id)
                if (env?.status == Environment.STATUS_RUNNING) {
                    msg("指纹已保存，需重启环境生效")
                } else {
                    msg("指纹已保存")
                }
            }.onFailure { msg("保存失败：${it.message}") }
        }
    }

    /** 恢复为一套默认自洽指纹 */
    fun resetDraftToDefault() {
        _draft.value = FingerprintConfig.createDefault()
        validateDraft()
    }

    /* ----------------------------- 快照 ----------------------------- */

    fun saveSnapshot(name: String, remark: String = "") {
        val id = _envId.value
        val cur = _draft.value ?: environment.value?.let { FingerprintConfig.fromJson(it.fingerprintJson) } ?: return
        viewModelScope.launch {
            runCatching {
                envRepo.saveSnapshot(id, name, FingerprintConfig.toJson(cur), remark)
                msg("快照已保存 [$name]")
            }.onFailure { msg("保存快照失败：${it.message}") }
        }
    }

    fun restoreSnapshot(snapshotId: Long) {
        viewModelScope.launch {
            runCatching {
                envRepo.restoreSnapshot(snapshotId)
                loadDraft()
                msg("已还原快照，重启环境生效")
            }.onFailure { msg("还原失败：${it.message}") }
        }
    }

    fun deleteSnapshot(snapshotId: Long) {
        viewModelScope.launch {
            runCatching { envRepo.deleteSnapshot(snapshotId) }
        }
    }

    /* ----------------------------- 账号台账 ----------------------------- */

    fun saveAccount(username: String, password: String?, remark: String, owner: String, status: String) {
        val envId = _envId.value
        viewModelScope.launch {
            runCatching {
                val existing = envRepo.getAccount(envId)
                val acc = (existing ?: Account(envId = envId, username = username)).copy(
                    username = username,
                    remark = remark,
                    owner = owner,
                    status = status,
                    updateTime = System.currentTimeMillis(),
                )
                envRepo.saveAccount(acc, password)
                msg("账号已保存（密码加密存储）")
            }.onFailure { msg("保存账号失败：${it.message}") }
        }
    }

    fun revealPassword(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { envRepo.getAccountPassword(_envId.value) }.getOrNull())
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            runCatching { envRepo.deleteAccount(_envId.value) }
        }
    }

    /* ----------------------------- Hosts 劫持 ----------------------------- */

    fun addHostsRule(domain: String, ip: String, enabled: Boolean, remark: String) {
        val id = _envId.value
        viewModelScope.launch {
            runCatching {
                envRepo.addHostsRule(
                    HostsRule(envId = id, domain = domain.trim(), ip = ip.trim(), enabled = enabled, remark = remark)
                )
                syncHostsToSlot(id)
                msg("Hosts 规则已添加")
            }.onFailure { msg("添加失败：${it.message}") }
        }
    }

    fun deleteHostsRule(ruleId: Long) {
        val id = _envId.value
        viewModelScope.launch {
            runCatching {
                envRepo.deleteHostsRule(ruleId)
                syncHostsToSlot(id)
            }
        }
    }

    /** 热更新槽位里的 hosts（无需重启） */
    private suspend fun syncHostsToSlot(envId: Long) {
        val slot = locator.slotBridge.slotOf(envId) ?: return
        val rules = envRepo.hostsRulesFor(envId)
        val arr = org.json.JSONArray()
        rules.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("domain", it.domain)
                    .put("ip", it.ip)
                    .put("enabled", it.enabled),
            )
        }
        locator.slotBridge.send(slot, SlotBridge.Cmd.SET_HOSTS, org.json.JSONObject().put("rules", arr))
    }

    fun setHostsEnabled(enabled: Boolean) {
        updateEnvironment { it.copy(hostsHijackEnabled = enabled) }
        viewModelScope.launch { syncHostsToSlot(_envId.value) }
    }

    /* ----------------------------- Marionette ----------------------------- */

    private val _portError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val portError: SharedFlow<String> = _portError.asSharedFlow()

    fun configureMarionette(enabled: Boolean, portText: String) {
        val id = _envId.value
        viewModelScope.launch {
            runCatching {
                val requested = portText.trim().toIntOrNull() ?: 0
                val port = marionetteManager.configure(id, enabled, requested)
                msg(
                    if (enabled) "Marionette 已开启，端口 $port（变更需重启环境生效）"
                    else "Marionette 已关闭"
                )
            }.onFailure {
                _portError.tryEmit(it.message ?: "端口配置失败")
            }
        }
    }

    /** 运行中的 Marionette 连接信息（供 SDK 页） */
    fun marionetteEndpoint() = marionetteManager.endpointOf(_envId.value)

    /* ----------------------------- 检测报告 ----------------------------- */

    private val _report = MutableStateFlow<ReportManager.Report?>(null)
    val report: StateFlow<ReportManager.Report?> = _report.asStateFlow()

    private val _reportLoading = MutableStateFlow(false)
    val reportLoading: StateFlow<Boolean> = _reportLoading.asStateFlow()

    fun generateReport() {
        val id = _envId.value
        viewModelScope.launch {
            _reportLoading.value = true
            runCatching { _report.value = reportManager.buildReport(id) }
                .onFailure { msg("生成报告失败：${it.message}") }
            _reportLoading.value = false
        }
    }

    fun exportReport(onPath: (String) -> Unit) {
        val r = _report.value ?: return
        viewModelScope.launch {
            runCatching {
                val f = reportManager.exportMarkdown(r)
                onPath(f.absolutePath)
                msg("报告已导出：${f.name}")
            }.onFailure { msg("导出失败：${it.message}") }
        }
    }

    /* ----------------------------- HAR / 便捷操作 ----------------------------- */

    fun startHar(name: String) {
        val id = _envId.value
        viewModelScope.launch {
            val slot = locator.slotBridge.slotOf(id)
            if (slot == null) { msg("请先启动环境"); return@launch }
            locator.slotBridge.send(slot, SlotBridge.Cmd.START_HAR, org.json.JSONObject().put("name", name)) { r ->
                if (r.optBoolean("ok")) msg("HAR 录制已开始") else msg("HAR 启动失败：${r.optString("error")}")
            }
        }
    }

    fun stopHar() {
        val id = _envId.value
        viewModelScope.launch {
            val slot = locator.slotBridge.slotOf(id)
            if (slot == null) return@launch
            locator.slotBridge.send(slot, SlotBridge.Cmd.STOP_HAR) { r ->
                msg("HAR 录制已停止，共 ${r.optInt("entries")} 条")
            }
        }
    }

    fun clearStorage() {
        val id = _envId.value
        viewModelScope.launch {
            val slot = locator.slotBridge.slotOf(id)
            if (slot == null) { msg("环境未运行"); return@launch }
            locator.slotBridge.send(slot, SlotBridge.Cmd.CLEAR_STORAGE)
            msg("已清理站点数据（Cookie/缓存/LocalStorage）")
        }
    }

    fun navigate(url: String) {
        val id = _envId.value
        viewModelScope.launch {
            val slot = locator.slotBridge.slotOf(id)
            if (slot == null) { msg("环境未运行"); return@launch }
            locator.slotBridge.send(slot, SlotBridge.Cmd.NAVIGATE, org.json.JSONObject().put("url", url))
        }
    }

    fun screenshot(onPath: (String?) -> Unit) {
        val id = _envId.value
        viewModelScope.launch {
            val slot = locator.slotBridge.slotOf(id)
            if (slot == null) { onPath(null); return@launch }
            locator.slotBridge.send(slot, SlotBridge.Cmd.SCREENSHOT) { r ->
                onPath(if (r.optBoolean("ok")) r.optString("path") else null)
            }
        }
    }

    /** 快捷启停 */
    fun toggleRunning() {
        val env = environment.value ?: return
        viewModelScope.launch {
            if (envManager.slotOf(env.id) != null) envManager.stop(env.id) else envManager.start(env.id)
        }
    }

    /** 启动环境并打开浏览器界面 */
    fun openBrowser() {
        val env = environment.value ?: return
        viewModelScope.launch {
            val slot = envManager.start(env.id) ?: return@launch
            kotlinx.coroutines.delay(800)
            SlotBridge.openSlot(locator.appContext, slot, env.id, env.name)
        }
    }
}
