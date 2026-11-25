package app.soundlink.android

/**
 * Back navigation that follows the path the user actually took, mirroring the Mac app.
 * Home always clears the stack; app-driven jumps (`resetting`) make the destination a
 * first-level page whose only parent is home. Revisiting a page already on the stack
 * unwinds to it instead of growing a loop. Immutable: every operation returns a new value.
 */
internal data class PageHistory(val page: String = HOME, val stack: List<String> = emptyList()) {
    fun navigate(destination: String, resetting: Boolean = false): PageHistory = when {
        destination == HOME -> PageHistory(HOME)
        resetting -> PageHistory(destination, listOf(HOME))
        destination in stack -> PageHistory(destination, stack.subList(0, stack.indexOf(destination)))
        destination == page -> this
        else -> PageHistory(destination, stack + page)
    }

    fun back(): PageHistory = PageHistory(stack.lastOrNull() ?: HOME, stack.dropLast(1))

    companion object {
        const val HOME = "home"

        /** Restores a saved stack; entries that are the page itself or blank are dropped. */
        fun restore(page: String?, stack: List<String>?): PageHistory {
            val current = page?.takeIf { it.isNotBlank() } ?: HOME
            if (current == HOME) return PageHistory(HOME)
            val saved = stack.orEmpty().filter { it.isNotBlank() && it != current }.distinct()
            return PageHistory(current, saved)
        }
    }
}
