package com.andyl.ignite.data

import java.io.InputStream

/**
 * Abre el contenido de un ítem a enviar. En Android el picker devuelve
 * content:// URIs de MediaStore que NO son archivos del filesystem; en
 * desktop son paths normales. Null si no se puede leer.
 */
expect fun openTransferStream(path: String): InputStream?

/** Nombre y tamaño en bytes del ítem a enviar; null si no se puede leer. */
expect fun transferMeta(path: String): Pair<String, Long>?
