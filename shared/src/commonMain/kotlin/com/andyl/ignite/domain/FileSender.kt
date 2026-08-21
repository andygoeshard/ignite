package com.andyl.ignite.domain

import com.andyl.ignite.domain.model.Device

/**
 * Streams a local file to a peer device over HTTP and reports progress.
 */
interface FileSender {
    /**
     * Sends the file at [localPath] to [target], emitting a float in 0f..1f
     * as the transfer progresses. Throws on failure.
     */
    suspend fun send(target: Device, localPath: String, fileName: String, sizeBytes: Long): kotlinx.coroutines.flow.Flow<Float>
}
