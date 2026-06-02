package com.pirorin215.gardiaradar.di

import com.pirorin215.gardiaradar.data.RadarRepository
import com.pirorin215.gardiaradar.viewModel.RadarViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    single { RadarRepository(get(), get()) }
    viewModel { RadarViewModel(get()) }
}
