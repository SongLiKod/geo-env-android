package com.geoenv.browser.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.db.EnvGroup
import com.geoenv.browser.db.Environment
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.gecko.FingerprintConfig
import com.geoenv.browser.manager.EnvManager
import com.geoenv.browser.slot.SlotBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 环境列表页 ViewModel（需求 3.1）。
 *
 * 数据源三合一：Room 环境表 + 分组表 + [SlotBridge] 槽位状态，
 * 组合成 [EnvListItem] 供列表渲染；搜索/筛选/分组切换在内存中完成。
 */
@HiltViewModel
class EnvListViewModel @Inject constructor(
    private val envRepo: EnvironmentRepository,
    val envManager: EnvManager,
    private val slotBridge: SlotBridge,
    private val locator: ServiceLocator,
) : ViewModel() {

    /* ----------------------------- 筛选条件 ----------------------------- */

    enum class StatusFilter { ALL, RUNNING, STOPPED }

    data class Filter(
        val query: String = "",
        val groupId: Long? = null,
        val stage: String? = null,
        val tag: String? = null,
        val status: StatusFilter = StatusFilter.ALL,
        val sortBy: SortBy = SortBy.RECENT,
    )

    enum class SortBy { RECENT, NAME, STATUS }

    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter

    fun setQuery(q: String) { _filter.value = _filter.value.copy(query = q) }
    fun setGroup(id: Long?) { _filter.value = _filter.value.copy(groupId = id) }
    fun setStage(stage: String?) { _filter.value = _filter.value.copy(stage = stage) }
    fun setTag(tag: String?) { _filter.value = _filter.value.copy(tag = tag) }
    fun setStatus(s: StatusFilter) { _filter.value = _filter.value.copy(status = s) }
    fun setSort(s: SortBy) { _filter.value = _filter.value.copy(sortBy = s) }

    /* ----------------------------- 列表数据 ----------------------------- */

    data class EnvListItem(
        val env: Environment,
        val groupName: String? = null,
        val groupColor: String? = null,
        val slotIndex: Int? = null,
        val running: Boolean = false,
        val accountUsername: String? = null,
        val proxyName: String? = null,
        val proxyStatus: String? = null,
        val marionettePort: Int = 0,
        val harRecording: Boolean = false,
    )

    /** 5 路数据先行合并（Kotlin combine 带类型推断上限为 5 路） */
    private data class Raw(
        val envs: List<Environment>,
        val groups: List<EnvGroup>,
        val slots: Map<Int, SlotBridge.SlotState>,
        val accounts: List<com.geoenv.browser.db.Account>,
        val proxies: List<com.geoenv.browser.db.ProxyConfig>,
    )

    private val raw: StateFlow<Raw> = combine(
        envRepo.observeEnvironments(),
        envRepo.observeGroups(),
        slotBridge.states,
        locator.database.accountDao().observeAll(),
        locator.database.proxyDao().observeAll(),
    ) { envs, groups, slots, accounts, proxies ->
        Raw(envs, groups, slots, accounts, proxies)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Raw(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList()))

    val items: StateFlow<List<EnvListItem>> = combine(raw, _filter) { r, filter ->
        val groupMap = r.groups.associateBy { it.id }
        val accountMap = r.accounts.associateBy { it.envId }
        val proxyMap = r.proxies.associateBy { it.id }

        r.envs.map { env ->
            val slot = r.slots.values.firstOrNull {
                it.envId == env.id &&
                    (it.status == Environment.STATUS_RUNNING || it.status == Environment.STATUS_STARTING)
            }
            EnvListItem(
                env = env,
                groupName = env.groupId?.let { groupMap[it]?.name },
                groupColor = env.groupId?.let { groupMap[it]?.color },
                slotIndex = slot?.slotIndex,
                running = slot?.status == Environment.STATUS_RUNNING,
                accountUsername = accountMap[env.id]?.username,
                proxyName = env.proxyId?.let { proxyMap[it]?.name },
                proxyStatus = env.proxyId?.let { proxyMap[it]?.status },
                marionettePort = slot?.marionettePort ?: env.marionettePort,
                harRecording = slot?.harRecording ?: env.harRecording,
            )
        }.let { applyFilter(it, filter) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<EnvGroup>> = envRepo.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 所有出现过的标签（去重） */
    val allTags: StateFlow<List<String>> =
        envRepo.observeEnvironments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            .let { src ->
                val out = MutableStateFlow<List<String>>(emptyList())
                viewModelScope.launch {
                    src.collect { envs ->
                        out.value = envs.flatMap { it.tags.split(",") }
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted()
                    }
                }
                out
            }

    val runningCount: StateFlow<Int> = slotBridge.states
        .map { map -> map.values.count { it.status == Environment.STATUS_RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val concurrencyLimit: StateFlow<Int> = envManager.concurrencyLimit

    private fun applyFilter(list: List<EnvListItem>, f: Filter): List<EnvListItem> {
        var out = list
        if (f.query.isNotBlank()) {
            val q = f.query.trim()
            out = out.filter {
                it.env.name.contains(q, true) ||
                    it.env.project.contains(q, true) ||
                    it.env.tags.contains(q, true) ||
                    it.env.remark.contains(q, true) ||
                    it.env.versionTag.contains(q, true) ||
                    (it.accountUsername?.contains(q, true) == true)
            }
        }
        f.groupId?.let { g -> out = out.filter { it.env.groupId == g } }
        f.stage?.let { s -> out = out.filter { it.env.stage.equals(s, true) } }
        f.tag?.let { t -> out = out.filter { e -> e.env.tags.split(",").any { it.trim() == t } } }
        when (f.status) {
            StatusFilter.RUNNING -> out = out.filter { it.running }
            StatusFilter.STOPPED -> out = out.filter { !it.running }
            StatusFilter.ALL -> Unit
        }
        return when (f.sortBy) {
            SortBy.RECENT -> out.sortedByDescending { it.env.updateTime }
            SortBy.NAME -> out.sortedBy { it.env.name }
            SortBy.STATUS -> out.sortedWith(compareByDescending<EnvListItem> { it.running }.thenBy { it.env.name })
        }
    }

    /* ----------------------------- 操作 ----------------------------- */

    fun start(envId: Long) {
        viewModelScope.launch { envManager.start(envId) }
    }

    fun stop(envId: Long) {
        viewModelScope.launch { envManager.stop(envId) }
    }

    /** 打开浏览器界面（槽位 Activity） */
    fun openBrowser(envId: Long) {
        viewModelScope.launch {
            val env = envRepo.getEnvironment(envId) ?: return@launch
            val slot = envManager.start(envId) ?: return@launch
            // 等待 runtime 与扩展就绪，再拉起视图
            kotlinx.coroutines.delay(800)
            SlotBridge.openSlot(locator.appContext, slot, env.id, env.name)
        }
    }

    fun delete(envId: Long) {
        viewModelScope.launch {
            runCatching {
                envManager.stop(envId)
                envRepo.deleteEnvironment(envId)
            }
        }
    }

    fun clone(envId: Long) {
        viewModelScope.launch {
            val env = envRepo.getEnvironment(envId) ?: return@launch
            runCatching { envRepo.cloneEnvironment(envId, uniqueCloneName(env.name)) }
        }
    }

    private suspend fun uniqueCloneName(base: String): String {
        var name = "${base}_副本"
        var i = 2
        while (envRepo.getEnvironmentByName(name) != null) name = "${base}_副本${i++}"
        return name
    }

    fun createDefault(
        name: String,
        groupId: Long?,
        stage: String,
        project: String,
        tags: String,
        remark: String,
        onDone: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val id = envRepo.createEnvironment(
                    Environment(
                        name = name,
                        groupId = groupId,
                        stage = stage,
                        project = project,
                        tags = tags,
                        remark = remark,
                    ),
                    defaultFingerprintJson = FingerprintConfig.toJson(FingerprintConfig.createDefault()),
                )
                onDone(id)
            }
        }
    }

    /* ----------------------------- 分组 ----------------------------- */

    fun createGroup(name: String, color: String) {
        viewModelScope.launch {
            runCatching {
                envRepo.createGroup(name.trim(), color)
            }
        }
    }

    fun renameGroup(id: Long, name: String, color: String) {
        viewModelScope.launch {
            runCatching {
                groups.value.firstOrNull { it.id == id }?.let {
                    envRepo.updateGroup(it.copy(name = name.trim(), color = color))
                }
            }
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch {
            runCatching { envRepo.deleteGroup(id) }
        }
    }

    /** 每个分组下的环境数量 */
    fun groupCounts(): Map<Long, Int> =
        items.value.groupingBy { it.env.groupId ?: -1L }.eachCount()
}
