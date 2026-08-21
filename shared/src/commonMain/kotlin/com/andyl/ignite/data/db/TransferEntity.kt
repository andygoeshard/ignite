package com.andyl.ignite.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.andyl.ignite.domain.model.Transfer

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val sizeBytes: Long,
    val direction: String,
    val peerName: String,
    val peerHost: String,
    val status: String,
    val progress: Float,
    val createdAt: Long,
)

fun TransferEntity.toDomain(): Transfer = Transfer(
    id = id,
    fileName = fileName,
    sizeBytes = sizeBytes,
    direction = Transfer.Direction.valueOf(direction),
    peerName = peerName,
    peerHost = peerHost,
    status = Transfer.Status.valueOf(status),
    progress = progress,
    createdAt = createdAt,
)

fun Transfer.toEntity(): TransferEntity = TransferEntity(
    id = id,
    fileName = fileName,
    sizeBytes = sizeBytes,
    direction = direction.name,
    peerName = peerName,
    peerHost = peerHost,
    status = status.name,
    progress = progress,
    createdAt = createdAt,
)
