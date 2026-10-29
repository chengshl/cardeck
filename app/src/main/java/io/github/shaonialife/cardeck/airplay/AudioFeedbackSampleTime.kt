package io.github.shaonialife.cardeck.airplay

/**
 * T86 read-only feedback clock selector for B233 audio diagnostics.
 *
 * A live B233 PCM session reports its audio playback clock as stream type=100.
 * T82 only inspected type=102 (AAC), so PCM traces incorrectly showed
 * sampleFirst=-1 despite receiving advancing sampleTime values.
 *
 * No connection/ownership/RTSP/RTP/RTCP behavior changes. All values remain
 * in-memory counters; no receiver identifiers, raw payloads or timestamps
 * are retained. Prefer type=102 when both exist to preserve T82 selection,
 * then accept type=100 when 102 has no usable clock.
 */
internal object AudioFeedbackSampleTime {
    fun fromParsedFeedback(root: Map<*, *>?): Long? {
        val streams = root?.get("streams") as? List<*> ?: return null
        for (wantedType in listOf(102L, 100L)) {
            for (entry in streams) {
                val stream = entry as? Map<*, *> ?: continue
                val type = (stream["type"] as? Number)?.toLong() ?: continue
                if (type != wantedType) continue
                val sample = (stream["sampleTime"] as? Number)?.toLong() ?: continue
                if (sample >= 0L) return sample
            }
        }
        return null
    }
}
