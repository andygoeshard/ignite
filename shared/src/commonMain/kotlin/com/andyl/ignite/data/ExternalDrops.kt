package com.andyl.ignite.data

import kotlinx.coroutines.flow.Flow

/** Flujo de archivos soltados en la drop zone; null donde no existe (Android). */
expect fun externalDropFlow(): Flow<List<String>>?
