package com.andyl.ignite.presentation.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey

@Serializable
data object HomeRoute : Route

@Serializable
data object HistoryRoute : Route
