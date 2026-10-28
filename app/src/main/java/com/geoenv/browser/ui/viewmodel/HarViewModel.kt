package com.geoenv.browser.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.db.Environment
import com.geoenv.browser.db.HarRecord
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.slot.SlotBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * HAR 抓包记录 ViewModel（需求 3.9.2）：
 * 列出全部环境的抓包记录，导出 / 删除。
 *
 * 录制由环境详情页触发（START_HAR / STOP_HAR），停止时由槽位进程落盘
 * `filesDir/har/<envId>_<id>.har` 并写入 har_record 表。
 */
@HiltViewModel
class HarViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locator: ServiceLocator,
) : ViewModel() {

    val records: StateFlow<List<HarRecord>> = locator.database.harDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val envs: StateFlow<List<Environment>> = locator.environmentRepository.observeEnvironments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 当前正在录制的环境 id（槽位状态） */
    val recordingEnvIds: StateFlow<Set<Long>> =
        locator.slotBridge.states
            .map { map ->
                map.values.filter { it.harRecording && it.envId != null }.map { it.envId!! }.toSet()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 导出（复制到 filesDir/export/ 并返回可分享路径） */
    fun export(record: HarRecord, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching {
                withContext(Dispatchers.IO) {
                    val src = File(record.filePath)
                    if (!src.exists()) error("HAR 文件不存在（可能已被清理）")
                    val dir = File(context.filesDir, "export").apply { mkdirs() }
                    val dst = File(dir, src.name)
                    src.copyTo(dst, overwrite = true)
                    dst.absolutePath
                }
            })
        }
    }

    /** 删除记录（同时删除文件） */
    fun delete(record: HarRecord, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    File(record.filePath).delete()
                    locator.database.harDao().deleteById(record.id)
                }
            }
            onDone()
        }
    }

    /** 通过槽位停止录制（供列表页快捷操作） */
    fun stopRecording(envId: Long) {
        viewModelScope.launch {
            val slot = locator.slotBridge.slotOf(envId) ?: return@launch
            locator.slotBridge.send(slot, SlotBridge.Cmd.STOP_HAR)
        }
    }
}
