package io.github.lcebot.mivolumeenhanced

/**
 * Restarts the scoped processes through su, so changes to the hooks take effect without a reboot.
 * The system restarts SystemUI and the two apps' services by itself.
 */
object RootShell {
    sealed interface Result {
        data object NoRoot : Result
        data class Done(val restarted: List<String>, val notRunning: List<String>) : Result
    }

    /** Blocking; call it off the main thread. */
    fun restart(packages: List<String>): Result {
        if (!run("id").let { it.first == 0 && "uid=0" in it.second }) return Result.NoRoot
        val processes = run("ps -A -o PID,NAME").second.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.getOrNull(0)?.toIntOrNull()?.let { pid -> pid to parts.getOrElse(1) { "" } }
        }
        val restarted = mutableListOf<String>()
        val notRunning = mutableListOf<String>()
        for (pkg in packages) {
            // Every process of the package, including secondary ones such as "pkg:remote".
            val pids = processes.filter { (_, name) -> name == pkg || name.startsWith("$pkg:") }.map { it.first }
            if (pids.isEmpty() || run("kill -15 ${pids.joinToString(" ")}").first != 0) notRunning += pkg else restarted += pkg
        }
        return Result.Done(restarted, notRunning)
    }

    private fun run(command: String): Pair<Int, String> = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() to output
    } catch (_: Exception) {
        -1 to ""
    }
}
