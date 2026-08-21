package com.andyl.ignite.presentation.home

import androidx.compose.runtime.Composable

/**
 * Wraps [content] so that files dragged from the OS onto the area are
 * delivered via [onFiles]. On Desktop this wires a native drag & drop target;
 * on other platforms it's a passthrough (files are picked via the picker).
 */
@Composable
expect fun DroppableArea(onFiles: (List<PendingFile>) -> Unit, content: @Composable () -> Unit)
