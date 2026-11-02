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

        override suspend fun read() = screens[index]
        override suspend fun tap(id: Int): Boolean { log += "tap $id"; advance(); return true }
        override suspend fun type(id: Int, text: String, submit: Boolean): Boolean { log += "type $id $text"; advance(); return true }
        override suspend fun scroll(down: Boolean): Boolean { log += "scroll"; advance(); return true }
        override suspend fun back() { log += "back"; advance() }
        override suspend fun pause() { log += "pause"; advance() }
    }

    private class ScriptedLlm(vararg actions: ScreenAction) : ScreenLlm {
        private val actions = ArrayDeque(actions.toList())
        val histories = mutableListOf<List<String>>()
        val screens = mutableListOf<String>()
        override suspend fun nextScreenAction(goal: String, history: List<String>, screen: String): ScreenAction {
            histories += history
            screens += screen
            return actions.removeFirstOrNull() ?: ScreenAction.Done("finished")
        }
    }

    private fun screen(vararg nodes: ScreenNode) = Screen("app", nodes.toList())
    private fun button(id: Int, label: String) = ScreenNode(id, label, tappable = true)
    private val neverAsked = AgentUser { error("should not ask the user") }

    private fun run(device: ScreenDevice, llm: ScreenLlm, user: AgentUser = neverAsked, maxSteps: Int = 15) =
        runBlocking { ScreenAgent(device, llm, user).run("goal", maxSteps) }

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
        val outcome = run(device, ScriptedLlm(ScreenAction.Tap(0)), AgentUser { "yes" })
        assertTrue(outcome is ScreenAgent.Outcome.Done)
        assertEquals(listOf("tap 0"), device.log)
    }

    @Test
    fun anythingShortOfAYesDeclines() {
        listOf("maybe later", "no", "what's the weather").forEach { reply ->
            val device = FakeDevice(screen(button(0, "Delete")))
            assertEquals(ScreenAgent.Outcome.Declined, run(device, ScriptedLlm(ScreenAction.Tap(0)), AgentUser { reply }))
            assertTrue(device.log.isEmpty())
        }
    }

    @Test
    fun noReplyToAConfirmationTapsNothing() {
        val device = FakeDevice(screen(button(0, "Delete")))
        assertEquals(ScreenAgent.Outcome.NoAnswer, run(device, ScriptedLlm(ScreenAction.Tap(0)), AgentUser { null }))
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
        run(FakeDevice(screen(button(0, "Small"), button(1, "Large"))), llm, AgentUser { "large" })
        assertTrue(llm.histories[1].single().contains("large"))
    }

    @Test
    fun cancellingAQuestionStops() {
        val outcome = run(FakeDevice(screen(button(0, "Small"))), ScriptedLlm(ScreenAction.Ask("Which?")), AgentUser { "cancel" })
        assertEquals(ScreenAgent.Outcome.Declined, outcome)
    }

    @Test
    fun questionsAreNotCountedAsBeingStuck() {
        val llm = ScriptedLlm(
            ScreenAction.Ask("1?"), ScreenAction.Ask("2?"), ScreenAction.Ask("3?"), ScreenAction.Ask("4?"), ScreenAction.Done("ok")
        )
        assertEquals(ScreenAgent.Outcome.Done("ok"), run(FakeDevice(screen(button(0, "A"))), llm, AgentUser { "A" }))
    }

    @Test
    fun anUnchangingScreenGivesUp() {
        val device = FakeDevice(screen(button(0, "Dead button")))
        val llm = ScriptedLlm(ScreenAction.Tap(0), ScreenAction.Tap(0), ScreenAction.Tap(0), ScreenAction.Tap(0))
        assertTrue(run(device, llm) is ScreenAgent.Outcome.GaveUp)
        assertEquals(3, device.log.size)
    }

    @Test
    fun stepsAreCapped() {
        val screens = Array(30) { screen(button(0, "Page $it")) }
        val llm = ScreenLlm { _, _, _ -> ScreenAction.Tap(0) }
        val device = FakeDevice(*screens)
        assertEquals(ScreenAgent.Outcome.OutOfSteps, run(device, llm, maxSteps = 5))
        assertEquals(5, device.log.size)
    }

    @Test
    fun llmOutageStopsTheRun() {
        val llm = ScreenLlm { _, _, _ -> throw LlmUnavailableException("offline") }
        assertEquals(ScreenAgent.Outcome.Unavailable, run(FakeDevice(screen(button(0, "A"))), llm))
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
