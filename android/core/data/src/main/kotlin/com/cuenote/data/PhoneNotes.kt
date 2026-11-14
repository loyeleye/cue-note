package com.cuenote.data

import com.cuenote.pipeline.cards.CloudIds

/**
 * The phone's own notes, for a lesson the teacher keeps on the phone: no
 * card writer in the cloud ever sees it, so the best guesses the lesson
 * screen shows before cards ([LessonOutline.guesses]) become cards of their
 * own, kept on the phone. Then the setlist, practice takes and suggested
 * practice work for that lesson as for any other.
 *
 * A note is her words only (the sentence or two of hers that carry the
 * point), with no cue and no summary: the phone doesn't put words in her
 * mouth. The student can add their own words, which become the note's cue
 * and are never written over. Card ids are the ones the cloud would give
 * the same feedback block, so takes recorded on a note stay with it if the
 * lesson is ever made into cards in the cloud.
 *
 * Pure, so it is tested on the JVM.
 */
object PhoneNotes {
    fun fromGuesses(
        lessonId: String,
        exercises: List<ExerciseEntity>,
        takes: List<TakeEntity>,
        feedback: List<FeedbackEntity>,
    ): List<CardEntity> =
        LessonOutline.build(exercises, takes, feedback, emptyList())
            .flatMap { it.guesses }
            .map { g -> card(lessonId, g.feedback, g.excerpt) }

    fun card(lessonId: String, f: FeedbackEntity, words: String) = CardEntity(
        id = CloudIds.feedback(f.id),
        lessonId = lessonId,
        localId = f.id,
        takeId = f.takeId?.let(CloudIds::take),
        spanStartMs = f.spanStartMs,
        spanEndMs = f.spanEndMs,
        cue = "",
        category = null,
        herWords = words,
        practiceFlag = null,
        barStart = null,
        barEnd = null,
        transcript = f.words.trim(),
        editedByTeacher = false,
        updatedAt = "",
        source = CardEntity.SOURCE_PHONE,
    )

    /** The student's words for a note: one line, trimmed; blank clears them. */
    fun ownWords(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_OWN_WORDS)

    const val MAX_OWN_WORDS = 200
}
