package io.ktor.client.plugins.compression

import io.ktor.util.ContentEncoder

fun ContentEncodingConfig.brotli(quality: Float? = null) {
    val encoder: ContentEncoder = BrotliEncoder
    customEncoder(encoder, quality)
}
