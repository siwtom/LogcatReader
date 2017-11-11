package com.dp.logcat

data class Log(val date: String,
               val time: String,
               val pid: String,
               val tid: String,
               private val priority: String,
               val tag: String,
               val msg: String) {
    val isAssert get() = priority == "A"
    val isDebug get() = priority == "D"
    val isError get() = priority == "E"
    val isInfo get() = priority == "I"
    val isVerbose get() = priority == "V"
    val isWarning get() = priority == "W"
}

object LogFactory {
    fun newLog(metadata: String, msg: String): Log {
        val date: String
        val time: String
        val pid: String
        val tid: String
        val priority: String
        val tag: String

        val trimmed = metadata.substring(1, metadata.length - 1).trim()
        var startIndex = 0

        val skipSpaces = {
            while (trimmed[startIndex] == ' ') {
                startIndex++
            }
        }

        var index = trimmed.indexOf(' ', startIndex)
        date = trimmed.substring(startIndex, index)
        startIndex = index + 1

        index = trimmed.indexOf(' ', startIndex)
        time = trimmed.substring(startIndex, index)
        startIndex = index + 1

        skipSpaces()

        index = trimmed.indexOf(':', startIndex)
        pid = trimmed.substring(startIndex, index)
        startIndex = index + 1

        skipSpaces()

        index = trimmed.indexOf(' ', startIndex)
        tid = trimmed.substring(startIndex, index)
        startIndex = index + 1

        index = trimmed.indexOf('/', startIndex)
        priority = trimmed.substring(startIndex, index)
        startIndex = index + 1

        tag = trimmed.substring(startIndex, trimmed.length).trim()

        return Log(date, time, pid, tid, priority, tag, msg)
    }
}