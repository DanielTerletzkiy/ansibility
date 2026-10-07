package de.terletzkiy.ansibility.run.events

/**
 * Takes the events out of a run's stderr. The callback writes each event as one line `\u001e<token> <json>\n`; the
 * text is read in chunks that may end anywhere, so [filter] keeps an unfinished frame until its newline and returns
 * everything else at once, for the console. A frame found after other text on the same line still counts; a line
 * with another token is ordinary text (a task cannot inject events without knowing the run's token). [onText] gets
 * the ordinary text in stream order with the events, for readers of the output's lines (a line printed before a frame
 * is seen before its event). One reader thread; not thread-safe.
 */
class RunEventFrames(token: String, private val onText: ((String) -> Unit)? = null, private val onEvent: (RunEvent) -> Unit) {
    private val marker = "$RS$token "
    private val pending = StringBuilder()

    /** Feeds [text]; returns the part that is no event, in order. */
    fun filter(text: String): String {
        val out = StringBuilder()
        fun pass(part: String) {
            if (part.isEmpty()) return
            out.append(part)
            onText?.invoke(part)
        }
        var rest = if (pending.isEmpty()) text else pending.append(text).toString().also { pending.setLength(0) }
        while (rest.isNotEmpty()) {
            val start = rest.indexOf(RS)
            if (start < 0) {
                pass(rest)
                break
            }
            pass(rest.substring(0, start))
            val end = rest.indexOf('\n', start)
            if (end < 0) {
                // An unfinished line from the separator on: a frame (or its start) waits for the rest.
                if (marker.startsWith(rest.substring(start)) || rest.startsWith(marker, start)) pending.append(rest, start, rest.length)
                else pass(rest.substring(start))
                break
            }
            if (rest.startsWith(marker, start)) {
                val json = rest.substring(start + marker.length, end).trimEnd('\r')
                RunEventDecoder.decode(json)?.let(onEvent)
            } else {
                pass(rest.substring(start, end + 1))
            }
            rest = rest.substring(end + 1)
        }
        return out.toString()
    }

    /** The unfinished rest at the end of the stream, as text (a frame that never got its newline is dropped). */
    fun flush(): String {
        val rest = pending.toString()
        pending.setLength(0)
        if (rest.startsWith(marker)) return ""
        if (rest.isNotEmpty()) onText?.invoke(rest)
        return rest
    }

    companion object {
        /** The ASCII record separator that starts every frame. */
        const val RS = '\u001e'
    }
}
