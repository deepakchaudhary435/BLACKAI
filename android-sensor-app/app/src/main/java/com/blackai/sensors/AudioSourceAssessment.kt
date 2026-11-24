package com.blackai.sensors

/**
 * A cautious interpretation of YAMNet event labels.
 *
 * This is acoustic-scene estimation only. It cannot authenticate whether a voice
 * came from a nearby person, a television, a radio, or a loudspeaker.
 */
data class AudioSourceAssessment(
    val category: Category,
    val mediaScore: Float,
    val speechScore: Float,
    val explanation: String
) {
    enum class Category {
        MEDIA_LIKE,
        SPEECH_LIKE,
        MIXED_OR_AMBIGUOUS,
        UNKNOWN
    }

    companion object {
        private const val MIN_SCORE = 0.20f

        fun from(predictions: List<YamnetAudioClassifier.Prediction>): AudioSourceAssessment {
            val mediaTerms = listOf("television", "radio", "music", "video")
            val speechTerms = listOf(
                "speech", "conversation", "narration", "child speech",
                "male speech", "female speech", "shout", "screaming"
            )

            fun scoreFor(terms: List<String>): Float =
                predictions.asSequence()
                    .filter { prediction ->
                        val label = prediction.label.lowercase()
                        terms.any { term -> label.contains(term) }
                    }
                    .maxOfOrNull { it.score.coerceIn(0f, 1f) } ?: 0f

            val media = scoreFor(mediaTerms)
            val speech = scoreFor(speechTerms)
            val category = when {
                media >= MIN_SCORE && speech >= MIN_SCORE -> Category.MIXED_OR_AMBIGUOUS
                media >= MIN_SCORE -> Category.MEDIA_LIKE
                speech >= MIN_SCORE -> Category.SPEECH_LIKE
                else -> Category.UNKNOWN
            }
            val explanation = when (category) {
                Category.MEDIA_LIKE ->
                    "मीडिया-जैसी ध्वनि का संकेत; आवाज़ का स्रोत प्रमाणित नहीं।"
                Category.SPEECH_LIKE ->
                    "बोलने जैसी ध्वनि मिली; वास्तविक व्यक्ति होने की पुष्टि नहीं।"
                Category.MIXED_OR_AMBIGUOUS ->
                    "मीडिया-जैसी और बोलने जैसी ध्वनियाँ दोनों मिलीं; स्रोत अनिश्चित।"
                Category.UNKNOWN ->
                    "आवाज़ का स्रोत स्पष्ट नहीं; इसे अनदेखा न करें।"
            }
            return AudioSourceAssessment(category, media, speech, explanation)
        }
    }
}
