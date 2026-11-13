package com.cuenote.signal.posture

/**
 * Everything the app says out loud. Each line can have a recorded clip in
 * each voice, assets/voice/<voice>/<key>.mp3 in the app; a line without one
 * is read by the phone's own text-to-speech voice instead.
 */
enum class VoiceLine(val key: String, val text: String) {
    COUNTDOWN_INTRO("countdown_intro", "Go to where you'll sing, and face the phone."),
    NOT_SEEN("not_seen", "I can't see you yet. Stand where you'll sing, facing the phone."),
    STEP_LEFT("step_left", "Step a little to your left."),
    STEP_RIGHT("step_right", "Step a little to your right."),
    STEP_BACK("step_back", "Step back a little, so I can see your head and shoulders."),
    STEP_CLOSER("step_closer", "Come a step closer."),
    IN_FRAME("in_frame", "That's good. Stand tall and hold still."),
    SIDE_NOT_SEEN("side_not_seen", "I can't see you yet. Stand where you'll sing, with your side to the phone."),
    TURN_SIDE("turn_side", "Turn so your side faces the phone."),
    STEP_FORWARD("step_forward", "Take a small step forward."),
    STEP_BACKWARD("step_backward", "Take a small step backward."),
    MOVE_AWAY("move_away", "Move a little farther from the phone, so I can see your head and hips."),
    MOVE_NEARER("move_nearer", "Move a little closer to the phone."),
    ALL_SET("all_set", "You're all set."),
    NOT_FOUND("not_found", "I couldn't find you. Ask your teacher to tap try again, or record without posture."),
    RECAL_INTRO("recal_intro", "Let's reset posture. Go to where you sing, stand tall and relaxed, and face the phone."),
    RECAL_DONE("recal_done", "Posture reset. Carry on."),
    RECAL_NOT_FOUND("recal_not_found", "I couldn't find you, so I kept the posture from before."),

    // The sound check, read aloud so nobody has to watch the screen.
    CHECK_INTRO("check_intro", "Let's check the mics. I'll tell you what to do at each step, and move on as soon as I've heard enough."),
    CHECK_STUDENT("check_student", "Teacher, stay quiet for this one. Student, sing: Do, Re, Mi, on any comfortable note."),
    CHECK_TEACHER("check_teacher", "Now the teacher. Student, stay quiet. Teacher, speak or sing: Do, Re, Mi. Speaking is fine."),
    CHECK_BIG_NOTE("check_big_note", "Student, sing your biggest comfortable note, and hold it."),
    CHECK_PIANO("check_piano", "Now the piano. Student, stay quiet. Teacher, play an exercise at your usual volume."),
    CHECK_PIANO_SOFT("check_piano_soft", "Teacher, play it again. Student, sing along softly."),
    CHECK_PIANO_LOUD("check_piano_loud", "Last one. Big chords and a big voice, both together."),
    CHECK_WRONG_MIC("check_wrong_mic", "That's the student's mic. Teacher, your turn. Go again."),
    CHECK_AGAIN("check_again", "That one didn't quite work. Have a look at the screen, then tap try again."),
    CHECK_DONE("check_done", "That's a wrap. The mics are set for your lesson."),
}

/** The two recorded voices. Each has a full set of clips under assets/voice/<key>/. */
enum class Voice(val key: String, val label: String) {
    MALE("iapetus", "Male"),
    FEMALE("erinome", "Female"),
    ;

    companion object {
        val DEFAULT = MALE
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
