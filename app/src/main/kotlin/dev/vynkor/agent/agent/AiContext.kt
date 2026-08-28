package dev.vynkor.agent.agent

/**
 * Builds the extra-context block the app prepends to an AI request:
 * project name + project files + message attachments. Pure logic — no
 * Android imports — so it is unit-testable.
 */
object AiContext {

    /** One context source: a project file or a message attachment. */
    data class Source(
        val name: String,
        val typeLabel: String,
        val sizeLabel: String,
        /** Inlined textual content, or null when binary/oversized. */
        val textContent: String?,
    )

    private const val MAX_SOURCES = 12
    private const val MAX_TOTAL_TEXT_CHARS = 24_000

    /**
     * Returns the system-role block to prepend, or null when there is
     * nothing to add (no project files, no attachments).
     */
    fun buildBlock(projectName: String?, sources: List<Source>): String? {
        val usable = sources.take(MAX_SOURCES)
        if (projectName.isNullOrBlank() && usable.isEmpty()) return null
        return buildString {
            appendLine("You are assisting inside the vynkor agent. Context follows.")
            if (!projectName.isNullOrBlank()) {
                appendLine("Project: $projectName")
            }
            if (usable.isNotEmpty()) {
                appendLine("Attached materials:")
                var remaining = MAX_TOTAL_TEXT_CHARS
                usable.forEachIndexed { i, s ->
                    appendLine("- [${i + 1}] ${s.name} (${s.typeLabel}, ${s.sizeLabel})")
                    val text = s.textContent?.take(remaining.coerceAtLeast(0)) ?: return@forEachIndexed
                    if (text.isNotBlank()) {
                        appendLine("--- begin ${s.name} ---")
                        append(text)
                        if (!text.endsWith("\n")) appendLine()
                        appendLine("--- end ${s.name} ---")
                        remaining -= text.length
                    }
                }
                if (remaining <= 0) {
                    appendLine("(Some file contents were omitted due to size limits.)")
                }
                val skipped = sources.size - usable.size
                if (skipped > 0) {
                    appendLine("$skipped more file(s) not included (limit $MAX_SOURCES).")
                }
            }
        }.trimEnd('\n')
    }
}
