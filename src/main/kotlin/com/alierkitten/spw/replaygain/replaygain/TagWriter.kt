package com.alierkitten.spw.replaygain.replaygain

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Frames
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.util.Locale

data class ReplayGainTags(
    val trackGainDb: Double,
    val trackPeakLinear: Double,
    val referenceLoudness: Double
)

/**
 * Writes ReplayGain tags directly into audio files.
 *
 * - MP3: ID3v2 `TXXX:REPLAYGAIN_*` frames
 * - FLAC: Vorbis comments (via [FlacTag])
 * - OGG: Vorbis comments
 */
class TagWriter {

    fun supports(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in SUPPORTED_EXTENSIONS
    }

    fun write(path: String, tags: ReplayGainTags): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        return runCatching {
            val audioFile = AudioFileIO.read(file)
            val tag = prepareTag(audioFile)
            val values = linkedMapOf(
                REPLAYGAIN_TRACK_GAIN to formatGain(tags.trackGainDb),
                REPLAYGAIN_TRACK_PEAK to formatPeak(tags.trackPeakLinear),
                REPLAYGAIN_REFERENCE_LOUDNESS to formatReference(tags.referenceLoudness)
            )
            writeValues(tag, values)
            audioFile.commit()
            true
        }.getOrElse { false }
    }

    /**
     * Removes all ReplayGain tags written by this plugin from a file, if present: track gain,
     * track peak and reference loudness. Returns `true` when at least one tag was found and
     * deleted, `false` when there was nothing to remove or the file is unsupported/unreadable.
     */
    fun removeReplayGainTags(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        return runCatching {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return@runCatching false
            val removed = when (tag) {
                is AbstractID3v2Tag -> removeId3ReplayGain(tag)
                is VorbisCommentTag -> removeVorbisReplayGain(tag)
                is FlacTag -> removeVorbisReplayGain(tag)
                else -> false
            }
            if (removed) audioFile.commit()
            removed
        }.getOrElse { false }
    }

    private fun removeId3ReplayGain(tag: AbstractID3v2Tag): Boolean {
        val frameId = ID3v24Frames.FRAME_ID_USER_DEFINED_INFO
        val retained = mutableListOf<TagField>()
        val existing = tag.getFrameOfType(frameId)
        var removed = false
        while (existing.hasNext()) {
            val frame = existing.next() as? AbstractID3v2Frame ?: continue
            val description = (frame.body as? FrameBodyTXXX)?.description
            if (description != null && description.uppercase(Locale.US).startsWith(REPLAYGAIN_PREFIX)) {
                removed = true
                continue
            }
            retained += frame
        }
        if (removed) tag.setFrame(frameId, retained)
        return removed
    }

    private fun removeVorbisReplayGain(tag: VorbisCommentTag): Boolean {
        var removed = false
        for (id in ALL_REPLAYGAIN_FIELDS) {
            if (tag.getFields(id).isNotEmpty()) {
                tag.deleteField(id)
                removed = true
            }
        }
        return removed
    }

    private fun removeVorbisReplayGain(tag: FlacTag): Boolean {
        var removed = false
        for (id in ALL_REPLAYGAIN_FIELDS) {
            if (tag.getFields(id).isNotEmpty()) {
                tag.deleteField(id)
                removed = true
            }
        }
        return removed
    }

    /**
     * ID3v2.2 `TXX` frames are awkward to build, so promote such tags to v2.4 first.
     */
    private fun prepareTag(audioFile: AudioFile): Tag {
        val tag = audioFile.tagOrCreateAndSetDefault
        if (tag is ID3v22Tag) {
            val converted = ID3v24Tag(tag)
            audioFile.setTag(converted)
            return converted
        }
        return tag
    }

    private fun writeValues(tag: Tag, values: Map<String, String>) {
        when (tag) {
            is FlacTag -> values.forEach { (key, value) -> tag.setField(key, value) }
            is VorbisCommentTag -> values.forEach { (key, value) -> tag.setField(key, value) }
            is AbstractID3v2Tag -> writeId3(tag, values)
            else -> throw UnsupportedOperationException("Unsupported tag type: ${tag.javaClass.name}")
        }
    }

    private fun writeId3(tag: AbstractID3v2Tag, values: Map<String, String>) {
        val frameId = ID3v24Frames.FRAME_ID_USER_DEFINED_INFO // "TXXX"
        val retained = mutableListOf<TagField>()

        // Keep unrelated TXXX frames (e.g. MusicBrainz) and drop previous ReplayGain ones.
        val existing = tag.getFrameOfType(frameId)
        while (existing.hasNext()) {
            val frame = existing.next() as? AbstractID3v2Frame ?: continue
            val description = (frame.body as? FrameBodyTXXX)?.description
            if (description != null && description.uppercase(Locale.US).startsWith(REPLAYGAIN_PREFIX)) {
                continue
            }
            retained += frame
        }

        values.forEach { (key, value) ->
            val body = FrameBodyTXXX()
            body.description = key
            body.text = value
            val frame = if (tag is ID3v23Tag) {
                ID3v23Frame(ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO)
            } else {
                ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
            }
            frame.body = body
            retained += frame
        }

        tag.setFrame(frameId, retained)
    }

    private fun formatGain(value: Double) = String.format(Locale.US, "%+.2f dB", value)

    private fun formatPeak(value: Double) = String.format(Locale.US, "%.6f", value)

    private fun formatReference(value: Double) = String.format(Locale.US, "%.1f LUFS", value)

    companion object {
        private const val REPLAYGAIN_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
        private const val REPLAYGAIN_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
        private const val REPLAYGAIN_REFERENCE_LOUDNESS = "REPLAYGAIN_REFERENCE_LOUDNESS"
        private const val REPLAYGAIN_PREFIX = "REPLAYGAIN_"

        private val ALL_REPLAYGAIN_FIELDS = listOf(
            REPLAYGAIN_TRACK_GAIN,
            REPLAYGAIN_TRACK_PEAK,
            REPLAYGAIN_REFERENCE_LOUDNESS
        )

        val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "ogg")
    }
}
