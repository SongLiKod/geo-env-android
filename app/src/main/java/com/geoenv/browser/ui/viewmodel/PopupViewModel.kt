package com.geoenv.browser.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.db.PopupEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 弹窗 / 验证码聚合面板 ViewModel（需求 3.9.3）。
 *
 * 数据来自槽位进程的 [com.geoenv.browser.slot.SlotEngine]：
 *  - GeckoSession.PromptDelegate 捕获的 alert/confirm/prompt/beforeunload/popup/redirect/auth；
 *  - WebExtension content script 扫描到的验证码候选（DOM 启发式）。
 *
 * 多环境同时运行时，所有事件汇聚到同一张 `popup_event` 表，本页做统一聚合展示。
 */
@HiltViewModel
class PopupViewModel @Inject constructor(
    private val locator: com.geoenv.browser.di.ServiceLocator,
) : ViewModel() {

    enum class TypeFilter { ALL, CAPTCHA, DIALOG, NAVIGATION }

    private val _typeFilter = MutableStateFlow(TypeFilter.ALL)
    val typeFilter: StateFlow<TypeFilter> = _typeFilter.asStateFlow()

    private val _hideHandled = MutableStateFlow(false)
    val hideHandled: StateFlow<Boolean> = _hideHandled.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setTypeFilter(f: TypeFilter) { _typeFilter.value = f }
    fun setHideHandled(v: Boolean) { _hideHandled.value = v }
    fun setQuery(q: String) { _query.value = q }

    /** 全部弹窗事件（按时间倒序），叠加筛选 */
    val items: StateFlow<List<PopupEvent>> = combine(
        locator.database.popupDao().observeAll(1000),
        _typeFilter,
        _hideHandled,
        _query,
    ) { list, type, hideHandled, query ->
        list.filter { e ->
            val typeOk = when (type) {
                TypeFilter.ALL -> true
                TypeFilter.CAPTCHA -> e.type == "CAPTCHA"
                TypeFilter.DIALOG -> e.type in DIALOG_TYPES
                TypeFilter.NAVIGATION -> e.type in NAV_TYPES
            }
            val handledOk = !hideHandled || e.status == PopupEvent.STATUS_NEW
            val queryOk = query.isBlank() ||
                e.title.contains(query, true) ||
                e.message.contains(query, true) ||
                e.url.contains(query, true) ||
                e.envName.contains(query, true)
            typeOk && handledOk && queryOk
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 未处理数量（角标） */
    val newCount: StateFlow<Int> = locator.database.popupDao().observeNewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 按环境聚合（"所有打开环境的弹窗"） */
    data class EnvGroup(
        val envId: Long,
        val envName: String,
        val events: List<PopupEvent>,
    )

    val grouped: StateFlow<List<EnvGroup>> = items.map { list ->
        list.groupBy { it.envId to it.envName }
            .map { (k, v) -> EnvGroup(k.first, k.second.ifBlank { "环境 ${k.first}" }, v) }
            .sortedByDescending { it.events.firstOrNull()?.createTime ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /* ----------------------------- 操作 ----------------------------- */

    fun markHandled(id: Long) = updateStatus(id, PopupEvent.STATUS_HANDLED)
    fun markIgnored(id: Long) = updateStatus(id, PopupEvent.STATUS_IGNORED)
    fun markNew(id: Long) = updateStatus(id, PopupEvent.STATUS_NEW)

    private fun updateStatus(id: Long, status: String) {
        viewModelScope.launch {
            runCatching { locator.database.popupDao().updateStatus(id, status) }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            runCatching {
                items.value.forEach { locator.database.popupDao().deleteById(it.id) }
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { locator.database.popupDao().deleteById(id) }
        }
    }

    /** 把验证码文本填回对应环境的页面（经扩展执行 JS） */
    fun fillCaptcha(envId: Long, code: String) {
        viewModelScope.launch {
            val slot = locator.slotBridge.slotOf(envId)
            if (slot == null) return@launch
            val escaped = code.replace("\\", "\\\\").replace("'", "\\'")
            locator.slotBridge.send(
                slot,
                com.geoenv.browser.slot.SlotBridge.Cmd.EXEC_JS,
                org.json.JSONObject().put(
                    "code",
                    """
(function(){
  var sels=['input[type=text]','input[type=tel]','input[name*=code i]','input[name*=captcha i]','input[autocomplete=one-time-code]'];
  for(var i=0;i<sels.length;i++){
    var el=document.querySelector(sels[i]);
    if(el){ el.focus(); el.value='$escaped';
      el.dispatchEvent(new Event('input',{bubbles:true}));
      el.dispatchEvent(new Event('change',{bubbles:true}));
      return JSON.stringify({ok:true,selector:sels[i]});
    }
  }
  return JSON.stringify({ok:false});
})()
                    """.trimIndent(),
                ),
            ) {
                // 填充结果仅用于日志，UI 由刷新承担
            }
        }
    }

    companion object {
        private val DIALOG_TYPES = setOf("ALERT", "CONFIRM", "PROMPT", "BEFORE_UNLOAD")
        private val NAV_TYPES = setOf("POPUP", "POPUP_SESSION", "REDIRECT")
    }
}
