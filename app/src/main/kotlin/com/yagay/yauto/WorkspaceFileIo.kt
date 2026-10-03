package com.yagay.yauto

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readBoundedBytes(limit: Int, tooLargeMessage: String): ByteArray {
    require(limit > 0) { "limit must be positive" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { tooLargeMessage }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
