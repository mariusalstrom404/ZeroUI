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
        override suspend fun type(id: Int, text: String, submit: Boolean): Boolean { log += "type $id $text"; advance(); return true }
        override suspend fun scroll(down: Boolean): Boolean { log += "scroll"; advance(); return true }
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
}
