package com.pirorin215.gardiaradar.di

import com.pirorin215.gardiaradar.data.AppSettingsRepository
import com.pirorin215.gardiaradar.data.BatterySessionRepository
import com.pirorin215.gardiaradar.data.RadarRepository
import com.pirorin215.gardiaradar.service.RadarNotificationManager
import com.pirorin215.gardiaradar.service.WearMessageSender
import com.pirorin215.gardiaradar.service.WearableDataHost
import com.pirorin215.gardiaradar.viewModel.AppSettingsViewModel
import com.pirorin215.gardiaradar.viewModel.RadarConnectionManager
import com.pirorin215.gardiaradar.viewModel.RadarViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    single { AppSettingsRepository(get()) }
    single { BatterySessionRepository(get()) }
    single { WearMessageSender(get(), get()) }
    single { WearableDataHost(get()) }
    single { RadarNotificationManager(get(), get(), get(), get()) }
    single { RadarRepository(get(), get(), get(), get(), get(), get()) }
    single { RadarConnectionManager(get(), get(), get(), get(), get()) }

    viewModel { RadarViewModel(get(), get(), get()) }
    viewModel { AppSettingsViewModel(get()) }
}
