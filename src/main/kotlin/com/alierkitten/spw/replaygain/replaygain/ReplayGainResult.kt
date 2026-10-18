package com.alierkitten.spw.replaygain.replaygain

/**
 * Result of a ReplayGain 2.0 (EBU R128) measurement.
 *
 * @property integratedLoudness measured integrated loudness in LUFS
 * @property truePeakDb measured true peak in dBFS
 * @property trackGainDb gain to apply so the track reaches [referenceLoudness]
 * @property trackPeakLinear true peak as a linear amplitude (used by REPLAYGAIN_TRACK_PEAK)
 * @property referenceLoudness reference loudness in LUFS used for the calculation
 */
data class ReplayGainResult(
    val integratedLoudness: Double,
    val truePeakDb: Double,
    val trackGainDb: Double,
    val trackPeakLinear: Double,
    val referenceLoudness: Double
)
