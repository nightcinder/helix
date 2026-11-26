package com.nightcinder.helix.core.monitoring

import com.nightcinder.helix.core.model.MonitoringSnapshot
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.WolDevice
import kotlinx.coroutines.flow.StateFlow

interface MonitoringRepository {
    val snapshot: StateFlow<MonitoringSnapshot>
    val isLoading: StateFlow<Boolean>

    suspend fun refresh(
        hosts: List<TermixHost>,
        wolDevices: List<WolDevice> = emptyList(),
    ): Result<MonitoringSnapshot>

    fun clearData()
}
