package com.geoenv.browser.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.di.ServiceLocator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账号台账行（环境 + 账号 + 是否有密码） */
data class AccountRow(
    val envId: Long,
    val envName: String,
    val username: String,
    val remark: String,
    val owner: String,
    val status: String,
    /** null = 尚未设置密码（无法判断是否加密） */
    val password: String?,
)

/**
 * 账号台账列表（需求 3.2）。
 *
 * 只暴露"是否存在密文"，**从不返回密码明文**；查看/复制明文须显式走
 * [EnvDetailViewModel.revealPassword] 并写审计。
 */
@HiltViewModel
class AccountListViewModel @Inject constructor(
    private val envRepo: EnvironmentRepository,
    private val locator: ServiceLocator,
) : ViewModel() {

    val rows: StateFlow<List<AccountRow>> = combine(
        locator.database.accountDao().observeAll(),
        envRepo.observeEnvironments(),
    ) { accounts, envs ->
        val envMap = envs.associateBy { it.id }
        accounts
            .sortedByDescending { it.updateTime }
            .mapNotNull { acc ->
                val env = envMap[acc.envId] ?: return@mapNotNull null
                AccountRow(
                    envId = acc.envId,
                    envName = env.name,
                    username = acc.username,
                    remark = acc.remark,
                    owner = acc.owner,
                    status = acc.status,
                    password = acc.passwordEnc,
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(
        envId: Long,
        username: String,
        password: String?,
        remark: String,
        owner: String,
        status: String,
    ) {
        viewModelScope.launch {
            runCatching {
                val existing = envRepo.getAccount(envId)
                val acc = (existing ?: com.geoenv.browser.db.Account(envId = envId, username = username)).copy(
                    username = username,
                    remark = remark,
                    owner = owner,
                    status = status,
                    updateTime = System.currentTimeMillis(),
                )
                envRepo.saveAccount(acc, password)
            }
        }
    }
}
