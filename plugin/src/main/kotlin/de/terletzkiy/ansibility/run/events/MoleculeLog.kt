package de.terletzkiy.ansibility.run.events

/**
 * Reads Molecule's stage lines from its output: `INFO     [default > converge] Executing` and `… Executed: Successful`
 * (Molecule 25+), or `INFO     Running default > converge` (older). Pure; a line at a time, without its newline.
 */
object MoleculeLog {
    private val CURRENT = Regex("""\[([^\]>\s]+) > ([^\]\s]+)] (Executing|Executed: (.+?))\s*$""")
    private val OLDER = Regex("""\bRunning ([^\s>]+) > ([^\s]+)\s*$""")
    private val ANSI = Regex("""\u001b\[[0-9;]*m""")

    fun stage(line: String, time: Double = System.currentTimeMillis() / 1000.0): RunEvent.Stage? {
        val text = ANSI.replace(line, "")
        CURRENT.find(text)?.let { match ->
            val result = match.groupValues[4].takeIf { match.groupValues[3] != "Executing" }
            return RunEvent.Stage(time, match.groupValues[1], match.groupValues[2], result)
        }
        OLDER.find(text)?.let { return RunEvent.Stage(time, it.groupValues[1], it.groupValues[2], null) }
        return null
    }
}

/** Splits a stream into lines for [onLine]: the text is kept until its newline arrives. One reader thread. */
class LineSplitter(private val onLine: (String) -> Unit) {
    private val pending = StringBuilder()

    fun feed(text: String) {
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start)
            if (end < 0) break
            pending.append(text, start, end)
            onLine(pending.toString().trimEnd('\r'))
            pending.setLength(0)
            start = end + 1
        }
        if (start < text.length) pending.append(text, start, text.length)
    }

    fun flush() {
        if (pending.isNotEmpty()) onLine(pending.toString())
        pending.setLength(0)
    }
}
