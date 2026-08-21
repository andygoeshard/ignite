package com.andyl.ignite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.andyl.ignite.presentation.history.HistoryEntry
import com.andyl.ignite.presentation.home.HomeEntry
import com.andyl.ignite.presentation.navigation.HistoryRoute
import com.andyl.ignite.presentation.navigation.HomeRoute
import com.andyl.ignite.presentation.theme.IgniteTheme
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Composable
fun App() {
    IgniteTheme {
        IgniteRoot()
    }
}

@Composable
private fun IgniteRoot() {
    val navConfiguration = remember {
        SavedStateConfiguration {
            serializersModule = SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(HomeRoute::class)
                    subclass(HistoryRoute::class)
                }
            }
        }
    }

    val backStack = rememberNavBackStack(navConfiguration, HomeRoute)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeRoute> {
                HomeEntry(onNavigateToHistory = { backStack.add(HistoryRoute) })
            }
            entry<HistoryRoute> {
                HistoryEntry(onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}