package com.cuenote.signal.posture

/**
 * Everything the app says out loud. Each line can have a recorded clip,
 * assets/voice/<key>.mp3 in the app; a line without one is read by the
 * phone's own text-to-speech voice instead.
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
}
