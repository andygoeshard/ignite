package com.andyl.ignite.presentation.home

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.size

/**
 * Converts a FileKit [PlatformFile] into a [PendingFile]. Uses the file path,
 * name and size so it can be streamed later without holding the file open.
 */
suspend fun PlatformFile.toPendingFile(): PendingFile = PendingFile(
    path = path,
    name = name,
    sizeBytes = size(),
)
