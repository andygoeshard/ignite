package com.andyl.ignite.presentation.home

import androidx.compose.runtime.Composable

@Composable
actual fun DroppableArea(onFiles: (List<PendingFile>) -> Unit, content: @Composable () -> Unit) {
    content()
}
