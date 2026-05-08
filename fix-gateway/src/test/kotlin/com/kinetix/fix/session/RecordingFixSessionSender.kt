package com.kinetix.fix.session

import quickfix.Message

/**
 * Test fixture: records every send and returns the configured outcome. Lives
 * in `src/test` so production code cannot accidentally depend on it.
 */
class RecordingFixSessionSender(
    initialOutcome: SendOutcome = SendOutcome.Sent,
) : FixSessionSender {
    /** Tests may flip this between calls to exercise multi-stage scenarios. */
    @Volatile
    var outcome: SendOutcome = initialOutcome

    val sentMessages: MutableList<Pair<String, Message>> = mutableListOf()

    override fun send(venue: String, message: Message): SendOutcome {
        sentMessages += venue to message
        return outcome
    }
}
