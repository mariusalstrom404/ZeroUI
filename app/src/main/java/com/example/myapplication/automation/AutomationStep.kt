package com.example.myapplication.automation

enum class StepType {
    OPEN_APP,
    FIND,
    CLICK,
    INPUT_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    WAIT_FOR,
    BACK,
    CUSTOM
}

data class AutomationStep(
    val type: StepType,
    val selector: Selector? = null,
    val value: String? = null,
    val description: String? = null,
    val timeoutMs: Long = 10000,
    val optional: Boolean = false,
    val action: (suspend () -> Boolean)? = null
)
