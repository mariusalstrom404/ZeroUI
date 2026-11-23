package com.example.myapplication.automation

import com.example.myapplication.nlp.LlmUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAgentTest {

    private class FakeDevice(vararg screens: Screen) : ScreenDevice {
        private val screens = screens.toList()
        private var index = 0
        val log = mutableListOf<String>()

        /** Moves on after each action; stays on the last screen, or forever if there is only one. */
        private fun advance() { if (index < screens.lastIndex) index++ }

        override suspend fun read(previous: Screen?) = screens[index]
        override suspend fun tap(id: Int): Boolean { log += "tap $id"; advance(); return true }
        override suspend fun longPress(id: Int): Boolean { log += "long_press $id"; advance(); return true }
        override suspend fun type(id: Int, text: String, submit: Boolean): Boolean { log += "type $id $text"; advance(); return true }
        override suspend fun scroll(down: Boolean): Boolean { log += "scroll"; advance(); return true }
        override suspend fun scroll(down: Boolean, nodeId: Int?): Boolean { log += "scroll ${if (down) "down" else "up"} ${nodeId ?: "main"}"; advance(); return true }
        override suspend fun scrollHorizontal(left: Boolean, nodeId: Int?): Boolean { log += "scroll_${if (left) "left" else "right"} ${nodeId ?: "main"}"; advance(); return true }
        override suspend fun back() { log += "back"; advance() }
        override suspend fun pause() { log += "pause"; advance() }
    }

    private class ScriptedLlm(turns: List<ScreenTurn>) : ScreenLlm {
        constructor(vararg actions: ScreenAction) : this(actions.map { ScreenTurn(it) })

        private val turns = ArrayDeque(turns)
        val histories = mutableListOf<List<String>>()
        val screens = mutableListOf<String>()
        override suspend fun nextScreenAction(goal: String, history: List<String>, screen: String): ScreenTurn {
            histories += history
            screens += screen
            return turns.removeFirstOrNull() ?: ScreenTurn(ScreenAction.Done("finished"))
        }
    }

    private fun screen(vararg nodes: ScreenNode) = Screen("app", nodes.toList())
    private fun button(id: Int, label: String) = ScreenNode(id, label, tappable = true)
    private val neverAsked = AgentUser { _, _ -> error("should not ask the user") }
    private fun answers(reply: String?) = AgentUser { _, _ -> reply }

    private fun run(device: ScreenDevice, llm: ScreenLlm, user: AgentUser = neverAsked) =
        runBlocking { ScreenAgent(device, llm, user).run("goal") }

    @Test
    fun tapsThenFinishes() {
        val device = FakeDevice(screen(button(0, "Menu")), screen(button(0, "Dark mode")))
        val outcome = run(device, ScriptedLlm(ScreenAction.Tap(0), ScreenAction.Done("Done it")))
        assertEquals(ScreenAgent.Outcome.Done("Done it"), outcome)
        assertEquals(listOf("tap 0"), device.log)
    }

    @Test
    fun paymentTapIsNeverPerformed() {
        val device = FakeDevice(screen(button(0, "Pay now")))
        val outcome = run(device, ScriptedLlm(ScreenAction.Tap(0)))
        assertEquals(ScreenAgent.Outcome.HandedOver("Pay now"), outcome)
        assertTrue(device.log.isEmpty())
    }

    @Test
    fun paymentScreenStopsBeforeAskingTheLlm() {
        val llm = ScriptedLlm()
        val outcome = run(FakeDevice(screen(button(0, "Next"), ScreenNode(1, "Card number", editable = true))), llm)
        assertEquals(ScreenAgent.Outcome.HandedOver("the payment screen"), outcome)
        assertTrue(llm.screens.isEmpty())
    }

    @Test
    fun consequentialTapRunsOnlyAfterAYes() {
        val device = FakeDevice(screen(button(0, "Send")), screen(button(0, "Sent")))
        val outcome = run(device, ScriptedLlm(ScreenAction.Tap(0)), answers("yes"))
        assertTrue(outcome is ScreenAgent.Outcome.Done)
        assertEquals(listOf("tap 0"), device.log)
    }

    @Test
    fun anythingShortOfAYesDeclines() {
        listOf("maybe later", "no", "what's the weather").forEach { reply ->
            val device = FakeDevice(screen(button(0, "Delete")))
            assertEquals(ScreenAgent.Outcome.Declined, run(device, ScriptedLlm(ScreenAction.Tap(0)), answers(reply)))
            assertTrue(device.log.isEmpty())
        }
    }

    @Test
    fun noReplyToAConfirmationTapsNothing() {
        val device = FakeDevice(screen(button(0, "Delete")))
        assertEquals(ScreenAgent.Outcome.NoAnswer, run(device, ScriptedLlm(ScreenAction.Tap(0)), answers(null)))
        assertTrue(device.log.isEmpty())
    }

    @Test
    fun aTapOnAChildOfAPayButtonIsBlocked() {
        val child = ScreenNode(0, "HK$ 120", tapLabels = listOf("HK$ 120", "Pay"))
        val device = FakeDevice(screen(child))
        assertEquals(ScreenAgent.Outcome.HandedOver("Pay"), run(device, ScriptedLlm(ScreenAction.Tap(0))))
        assertTrue(device.log.isEmpty())
    }

    @Test
    fun privateFieldsAreHandedOver() {
        val field = ScreenNode(0, "Email address", editable = true)
        val device = FakeDevice(screen(field))
        val outcome = run(device, ScriptedLlm(ScreenAction.Type(0, "me@example.com", false)))
        assertEquals(ScreenAgent.Outcome.HandedOver("Email address"), outcome)
        assertTrue(device.log.isEmpty())
    }

    @Test
    fun inventedElementIsReportedBack() {
        val device = FakeDevice(screen(button(0, "Menu")))
        val llm = ScriptedLlm(ScreenAction.Tap(99), ScreenAction.Done("ok"))
        run(device, llm)
        assertTrue(device.log.isEmpty())
        assertTrue(llm.histories[1].single().contains("no element 99"))
    }

    @Test
    fun answersReachTheNextDecision() {
        val llm = ScriptedLlm(ScreenAction.Ask("Small or large?"), ScreenAction.Done("ok"))
        run(FakeDevice(screen(button(0, "Small"), button(1, "Large"))), llm, answers("large"))
        assertTrue(llm.histories[1].single().contains("large"))
    }

    @Test
    fun cancellingAQuestionStops() {
        val outcome = run(FakeDevice(screen(button(0, "Small"))), ScriptedLlm(ScreenAction.Ask("Which?")), answers("cancel"))
        assertEquals(ScreenAgent.Outcome.Declined, outcome)
    }

    @Test
    fun questionsAreNotCountedAsBeingStuck() {
        val llm = ScriptedLlm(
            ScreenAction.Ask("1?"), ScreenAction.Ask("2?"), ScreenAction.Ask("3?"), ScreenAction.Ask("4?"), ScreenAction.Done("ok")
        )
        assertEquals(ScreenAgent.Outcome.Done("ok"), run(FakeDevice(screen(button(0, "A"))), llm, answers("A")))
    }

    @Test
    fun anUnchangingScreenGivesUp() {
        val device = FakeDevice(screen(button(0, "Dead button")))
        val llm = ScriptedLlm(ScreenAction.Tap(0), ScreenAction.Tap(0), ScreenAction.Tap(0), ScreenAction.Tap(0))
        assertTrue(run(device, llm) is ScreenAgent.Outcome.GaveUp)
        assertEquals(3, device.log.size)
    }

    @Test
    fun longTasksAreNotCutOff() {
        val screens = Array(41) { screen(button(0, "Page $it")) }
        val llm = ScriptedLlm(*Array(40) { ScreenAction.Tap(0) })
        val device = FakeDevice(*screens)
        assertEquals(ScreenAgent.Outcome.Done("finished"), run(device, llm))
        assertEquals(40, device.log.size)
    }

    @Test
    fun llmOutageStopsTheRun() {
        val llm = ScreenLlm { _, _, _ -> throw LlmUnavailableException("offline") }
        assertEquals(ScreenAgent.Outcome.Unavailable, run(FakeDevice(screen(button(0, "A"))), llm))
    }

    @Test
    fun plannedStepsRunWithoutAskingTheLlmAgain() {
        val form = screen(ScreenNode(0, "From", editable = true), ScreenNode(1, "To", editable = true), button(2, "Go"))
        val filled = screen(ScreenNode(0, "Taipei", editable = true), ScreenNode(1, "To", editable = true), button(2, "Go"))
        val both = screen(ScreenNode(0, "Taipei", editable = true), ScreenNode(1, "Tainan", editable = true), button(2, "Go"))
        val results = screen(button(0, "Results"))
        val device = FakeDevice(form, filled, both, results)
        val llm = ScriptedLlm(listOf(
            ScreenTurn(ScreenAction.Type(0, "Taipei", false), listOf(ScreenAction.Type(1, "Tainan", false), ScreenAction.Tap(2))),
            ScreenTurn(ScreenAction.Done("ok"))
        ))
        assertEquals(ScreenAgent.Outcome.Done("ok"), run(device, llm))
        assertEquals(listOf("type 0 Taipei", "type 1 Tainan", "tap 2"), device.log)
        assertEquals(2, llm.screens.size)
    }

    @Test
    fun plannedStepsFindTheirElementAfterRenumbering() {
        val first = screen(button(0, "Close"), button(1, "Search"))
        // The popup is gone, so "Search" is now element 0.
        val second = screen(button(0, "Search"), button(1, "Home"))
        val device = FakeDevice(first, second, screen(button(0, "Search box")))
        val llm = ScriptedLlm(listOf(ScreenTurn(ScreenAction.Tap(0), listOf(ScreenAction.Tap(1)))))
        run(device, llm)
        assertEquals(listOf("tap 0", "tap 0"), device.log)
    }

    @Test
    fun aPlannedStepWhoseElementIsGoneGoesBackToTheLlm() {
        val device = FakeDevice(screen(button(0, "Menu"), button(1, "Cart")), screen(button(0, "Settings")))
        val llm = ScriptedLlm(listOf(ScreenTurn(ScreenAction.Tap(0), listOf(ScreenAction.Tap(1))), ScreenTurn(ScreenAction.Done("ok"))))
        assertEquals(ScreenAgent.Outcome.Done("ok"), run(device, llm))
        assertEquals(listOf("tap 0"), device.log)
        assertTrue(llm.histories[1].last().contains("skipped"))
    }

    @Test
    fun plannedStepsAreStillGuarded() {
        val device = FakeDevice(screen(button(0, "Size L"), button(1, "Pay now")), screen(button(0, "Size L ✓"), button(1, "Pay now")))
        val llm = ScriptedLlm(listOf(ScreenTurn(ScreenAction.Tap(0), listOf(ScreenAction.Tap(1)))))
        assertEquals(ScreenAgent.Outcome.HandedOver("Pay now"), run(device, llm))
        assertEquals(listOf("tap 0"), device.log)
    }

    @Test
    fun choicesAndConfirmationsAreOfferedAsButtons() {
        val offered = mutableListOf<List<String>>()
        val user = AgentUser { _, choices -> offered += choices; "Confirm" }
        val device = FakeDevice(screen(button(0, "Send")), screen(button(0, "Sent")))
        val llm = ScriptedLlm(ScreenAction.Ask("Which?", listOf("A", "B")), ScreenAction.Tap(0))
        run(device, llm, user)
        assertEquals(listOf(listOf("A", "B"), listOf("Confirm", "Cancel")), offered)
    }

    @Test
    fun describeListsFlagsAndNumbers() {
        val text = screen(
            ScreenNode(0, "Add", tappable = true, enabled = false),
            ScreenNode(1, null, viewId = "pin_box", editable = true, password = true)
        ).describe()
        assertTrue(text, text.contains("[0] tap disabled \"Add\""))
        assertTrue(text, text.contains("[1] type password id=pin_box"))
    }

    @Test
    fun describeIncludesRichStateOverlayRegionAndScrollHints() {
        val rich = Screen(
            app = "com.example.shop",
            nodes = listOf(
                ScreenNode(0, null, tappable = true, region = "top-right", viewId = "btn_close"),
                ScreenNode(1, "Dark mode", tappable = true, checked = false, role = "switch"),
                ScreenNode(2, "Pizza", tappable = true, selected = true, role = "tab", region = "bottom-bar"),
                ScreenNode(3, "Sushi", editable = true, hint = "Search food"),
                ScreenNode(4, null, scrollable = true, scrollDirs = listOf("left", "right"))
            ),
            isPopupOverlay = true,
            scrollSummary = "at_bottom, can_scroll_up"
        )
        val text = rich.describe()
        assertTrue(text, text.contains("Overlay: dialog/popup"))
        assertTrue(text, text.contains("Scroll: at_bottom, can_scroll_up"))
        assertTrue(text, text.contains("[0] tap top-right id=btn_close"))
        assertTrue(text, text.contains("[1] tap switch checked=off \"Dark mode\""))
        assertTrue(text, text.contains("[2] tap tab selected bottom-bar \"Pizza\""))
        assertTrue(text, text.contains("[3] type \"Sushi\" hint=\"Search food\""))
        assertTrue(text, text.contains("[4] scroll dirs=left,right"))
    }

    @Test
    fun togglingSwitchChangesFingerprint() {
        val before = screen(ScreenNode(0, "Dark mode", tappable = true, checked = false, role = "switch"))
        val after = screen(ScreenNode(0, "Dark mode", tappable = true, checked = true, role = "switch"))
        assertTrue(before.fingerprint != after.fingerprint)
    }

    @Test
    fun detectsAndBreaksPageLoops() {
        val pageA = screen(button(0, "Details"))
        val pageB = screen(button(0, "Back"))
        val device = FakeDevice(pageA, pageB, pageA, pageB, pageA, pageB, pageA, pageB)
        val llm = ScriptedLlm(
            ScreenAction.Tap(0),
            ScreenAction.Back,
            ScreenAction.Tap(0),
            ScreenAction.Back,
            ScreenAction.Tap(0),
            ScreenAction.Back,
            ScreenAction.Tap(0)
        )
        val outcome = run(device, llm)
        assertTrue(outcome is ScreenAgent.Outcome.GaveUp)
        // When returning to pageA the second time, history includes the loop warning.
        assertTrue(llm.histories.any { h -> h.any { it.contains("already visited") } })
    }

    @Test
    fun supportsLongPressHorizontalAndTargetedScroll() {
        val s0 = screen(button(0, "Item"), ScreenNode(1, null, scrollable = true))
        val device = FakeDevice(s0, screen(button(0, "Menu")), screen(button(0, "Cat 2")), screen(button(0, "More")))
        val llm = ScriptedLlm(
            ScreenAction.LongPress(0),
            ScreenAction.ScrollLeft(1),
            ScreenAction.ScrollDown(1),
            ScreenAction.Done("ok")
        )
        assertEquals(ScreenAgent.Outcome.Done("ok"), run(device, llm))
        assertEquals(listOf("long_press 0", "scroll_left 1", "scroll down 1"), device.log)
    }

    @Test
    fun plannedStepMatchesByUniqueViewIdWhenLabelChanges() {
        val first = screen(button(0, "Add"), ScreenNode(1, "Cart (1)", viewId = "cart_btn", tappable = true))
        val second = screen(button(0, "Added"), ScreenNode(2, "Cart (2)", viewId = "cart_btn", tappable = true))
        val third = screen(button(0, "Your cart"))
        val device = FakeDevice(first, second, third)
        val llm = ScriptedLlm(listOf(
            ScreenTurn(ScreenAction.Tap(0), listOf(ScreenAction.Tap(1))),
            ScreenTurn(ScreenAction.Done("ok"))
        ))
        assertEquals(ScreenAgent.Outcome.Done("ok"), run(device, llm))
        assertEquals(listOf("tap 0", "tap 2"), device.log)
        assertEquals(2, llm.screens.size)
    }

    @Test
    fun pauseForUserPreservesHistoryAndCanResume() {
        val loginScreen = Screen("com.example.bank", listOf(button(0, "Sign in")))
        val homeScreen = Screen("com.example.bank", listOf(button(0, "Settings")))
        val doneScreen = Screen("com.example.bank", listOf(button(0, "Dark mode")))

        val firstAgent = ScreenAgent(
            device = FakeDevice(loginScreen),
            llm = ScriptedLlm(ScreenAction.PauseForUser("Please sign in on screen.")),
            user = neverAsked
        )
        val paused = runBlocking { firstAgent.run("Enable dark mode") }
        assertEquals(ScreenAgent.Outcome.PausedForUser("Please sign in on screen."), paused)
        assertEquals("com.example.bank", firstAgent.lastApp)
        assertTrue(firstAgent.lastHistory.any { it.contains("Paused for user") })

        val resumeLlm = ScriptedLlm(ScreenAction.Tap(0), ScreenAction.Done("Dark mode opened"))
        val resumeDevice = FakeDevice(homeScreen, doneScreen)
        val resumedAgent = ScreenAgent(resumeDevice, resumeLlm, neverAsked)
        val outcome = runBlocking {
            resumedAgent.run("Enable dark mode", firstAgent.lastHistory + "User finished signing in.")
        }
        assertEquals(ScreenAgent.Outcome.Done("Dark mode opened"), outcome)
        assertTrue(resumeLlm.histories.first().any { it.contains("User finished signing in.") })
        assertEquals(listOf("tap 0"), resumeDevice.log)
    }

    @Test
    fun learnsSafeNavigationHintsAndInjectsStoredHints() {
        val memory = AppMemoryStore()
        val popup = Screen("com.example.food", listOf(button(0, "Skip")), isPopupOverlay = true)
        val home = Screen("com.example.food", listOf(button(0, "Search food")))
        val searchBox = Screen("com.example.food", listOf(ScreenNode(0, "Search", editable = true)))
        val options = Screen("com.example.food", listOf(button(0, "Large"), button(1, "Small")))

        val firstRun = ScreenAgent(
            device = FakeDevice(popup, home, searchBox, options),
            llm = ScriptedLlm(
                ScreenAction.Tap(0),
                ScreenAction.Tap(0),
                ScreenAction.Type(0, "secret query 12345", true),
                ScreenAction.Ask("Which size?", listOf("Large", "Small")),
                ScreenAction.Done("All set")
            ),
            user = answers("Large"),
            hintsProvider = { memory.hintsFor(it) },
            onLearned = { pkg, learning -> memory.record(pkg, learning) }
        )
        assertEquals(ScreenAgent.Outcome.Done("All set"), runBlocking { firstRun.run("Order food") })

        val entry = memory.entryFor("com.example.food")!!
        assertEquals(listOf("Skip"), entry.dismissLabels)
        assertEquals(listOf("Search food"), entry.searchEntries)
        assertTrue(entry.playbooks.single().contains("Type <input>"))
        assertTrue(!entry.playbooks.single().contains("secret query"))

        // Second run receives the learned hints via the 4-arg nextScreenAction overload.
        var receivedHints: String? = null
        val hintingLlm = object : ScreenLlm {
            override suspend fun nextScreenAction(goal: String, history: List<String>, screen: String) =
                ScreenTurn(ScreenAction.Done("ok"))
            override suspend fun nextScreenAction(goal: String, history: List<String>, screen: String, appHints: String?): ScreenTurn {
                receivedHints = appHints
                return ScreenTurn(ScreenAction.Done("ok"))
            }
        }
        val secondRun = ScreenAgent(
            device = FakeDevice(home),
            llm = hintingLlm,
            user = neverAsked,
            hintsProvider = { memory.hintsFor(it) }
        )
        assertEquals(ScreenAgent.Outcome.Done("ok"), runBlocking { secondRun.run("Order again") })
        assertTrue(receivedHints.orEmpty().contains("Skip"))
        assertTrue(receivedHints.orEmpty().contains("Search food"))
    }
}
