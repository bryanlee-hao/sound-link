package app.soundlink.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PageHistoryTest {
    private fun path(vararg pages: String) =
        pages.fold(PageHistory()) { history, page -> history.navigate(page) }

    @Test
    fun backFollowsTheActualEntryPath() {
        val history = path("settings", "device")

        val once = history.back()
        val twice = once.back()

        assertEquals("settings", once.page)
        assertEquals("home", twice.page)
    }

    @Test
    fun samePageReachedFromHomeReturnsHome() {
        assertEquals("home", path("device").back().page)
        assertEquals("home", path("download").back().page)
    }

    @Test
    fun thirdLevelPagesUnwindOneLevelAtATime() {
        val history = path("settings", "diagnostics", "advanced")

        assertEquals(listOf("diagnostics", "settings", "home"),
            generateSequence(history.back()) { if (it.page == "home") null else it.back() }.map { it.page }.toList())
    }

    @Test
    fun homeClearsTheStack() {
        val history = path("settings", "device").navigate("home")

        assertEquals(PageHistory(), history)
        assertEquals("home", history.back().page)
    }

    @Test
    fun appDrivenJumpHasHomeAsOnlyParent() {
        val history = path("settings", "device").navigate("onboarding", resetting = true)

        assertEquals(listOf("home"), history.stack)
        assertEquals("home", history.back().page)
    }

    @Test
    fun revisitingAStackedPageUnwindsInsteadOfLooping() {
        val history = path("settings", "headphones", "settings")

        assertEquals("settings", history.page)
        assertEquals(listOf("home"), history.stack)
    }

    @Test
    fun navigatingToTheCurrentPageIsANoOp() {
        val history = path("settings")

        assertEquals(history, history.navigate("settings"))
    }

    @Test
    fun backFromHomeStaysHome() {
        assertEquals(PageHistory(), PageHistory().back())
    }

    @Test
    fun restoreDropsInvalidEntries() {
        assertEquals(PageHistory("device", listOf("home", "settings")),
            PageHistory.restore("device", listOf("home", "", "settings", "device", "settings")))
        assertEquals(PageHistory(), PageHistory.restore(null, listOf("settings")))
        assertEquals(PageHistory("onboarding"), PageHistory.restore("onboarding", null))
    }
}
