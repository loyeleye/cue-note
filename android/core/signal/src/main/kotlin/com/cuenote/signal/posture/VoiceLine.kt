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
    IN_FRAME("in_frame", "That's good. Stay right there, nice and easy."),
    SIDE_NOT_SEEN("side_not_seen", "I can't see you yet. Stand where you'll sing, with your side to the phone."),
    TURN_SIDE("turn_side", "Turn so your side faces the phone."),
    STEP_FORWARD("step_forward", "Take a small step forward."),
    STEP_BACKWARD("step_backward", "Take a small step backward."),
    MOVE_AWAY("move_away", "Move a little farther from the phone, so I can see your head and hips."),
    MOVE_NEARER("move_nearer", "Move a little closer to the phone."),
    ALL_SET("all_set", "You're all set."),
    NOT_FOUND("not_found", "I couldn't find you. Ask your teacher to tap try again, or record without posture."),
    RECAL_INTRO("recal_intro", "Let's reset posture. Go to where you sing, face the phone, and stay nice and easy."),
    RECAL_DONE("recal_done", "Posture reset. Carry on."),
    RECAL_NOT_FOUND("recal_not_found", "I couldn't find you, so I kept the posture from before."),

    VOICE_PICKED("voice_picked", "Hi, I'll help you get set up."),

    // The sound check, read aloud so nobody has to watch the screen.
    CHECK_INTRO("check_intro", "Let's check the mics. It takes about a minute. I'll tell you what to do at each step, and move on as soon as I've heard enough."),
    CHECK_STUDENT("check_student", "Just the student for this one. Sing Do, Re, Mi, on any comfortable note."),
    CHECK_TEACHER("check_teacher", "Now just the teacher. Say or sing Do, Re, Mi. Speaking is fine."),
    CHECK_BIG_NOTE("check_big_note", "Sing a strong, easy note in the middle of your voice, and hold it."),
    CHECK_PIANO("check_piano", "Now just the piano. Play an exercise at your usual volume."),
    CHECK_PIANO_SOFT("check_piano_soft", "Teacher, play it again. Student, sing along softly."),
    CHECK_PIANO_LOUD("check_piano_loud", "Last one. Play and sing as loud as you'd get today, nothing forced."),
    CHECK_WRONG_MIC("check_wrong_mic", "I heard that on the student's mic. Check the mics aren't swapped, then try again."),
    CHECK_AGAIN("check_again", "I didn't catch enough that time. The screen shows what to check."),
    CHECK_SKIP_PIANO("check_skip_piano", "No piano today, so we'll skip those steps."),
    CHECK_DONE("check_done", "That's a wrap. The mics are set for your lesson."),
}

/** The two recorded voices. Each has a full set of clips under assets/voice/<key>/. */
enum class Voice(val key: String, val label: String) {
    // Listed in the order the chips show.
    FEMALE("erinome", "Female"),
    MALE("iapetus", "Male"),
    ;

    companion object {
        val DEFAULT = MALE
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
