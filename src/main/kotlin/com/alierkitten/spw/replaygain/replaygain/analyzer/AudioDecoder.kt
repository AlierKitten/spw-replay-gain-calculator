package com.alierkitten.spw.replaygain.replaygain.analyzer

import java.io.File

/**
 * Opens MP3 / FLAC / OGG(Vorbis) files as streaming [PcmChunkSource]s.
 *
 * All three decoders are used directly (no `javax.sound.sampled` SPI), because
 * SPI discovery runs on the host's platform class loader and cannot see the
 * providers bundled inside this plugin:
 *
 * - MP3  -> [Mp3Decoder]  (jlayer, bundled via `mp3spi`)
 * - FLAC -> [FlacDecoder] (JFLAC, pure Java)
 * - OGG  -> [VorbisDecoder] (JOrbis, bundled via `vorbisspi`)
 */
object AudioDecoder {

    fun open(path: String): PcmChunkSource {
        val file = File(path)
        require(file.isFile) { "File not found: $path" }
        val name = file.name.lowercase()
        return when {
            name.endsWith(".mp3") -> Mp3Decoder.open(file)
            name.endsWith(".flac") -> FlacDecoder.open(file)
            name.endsWith(".oga") || name.endsWith(".ogg") -> VorbisDecoder.open(file)
            else -> throw IllegalStateException(
                "不支持的音频格式（仅支持 MP3 / FLAC / OGG）: ${file.name}"
            )
        }
    }
}
