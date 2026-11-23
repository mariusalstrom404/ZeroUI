package com.example.myapplication.automation

import android.util.Log
import kotlinx.coroutines.delay

class FoodGorillaAutomation(private val engine: AutomationEngine) {

    companion object {
        const val PKG = "com.example.foodgorilla"
        private const val TAG = "FoodGorillaAuto"

        // Element Mapping
        val ELEMENTS = mapOf(
            "search_bar" to "testTag:search_bar",
            "cart_button" to "testTag:cart_button",
            "account_button" to "testTag:account_button",
            "menu_search_bar" to "testTag:menu_search_bar",
            "add_to_cart_confirm" to "testTag:add_to_cart_confirm",
            "go_to_checkout" to "testTag:go_to_checkout",
            "confirm_order" to "testTag:confirm_order",
            "back_button" to "description:Back"
        )
    }

    suspend fun runOrderWorkflow(restaurantName: String, itemName: String): Boolean {
        val steps = mutableListOf<AutomationStep>()

        // --- PHASE 1: SEARCH & OPEN RESTAURANT ---
        steps.add(AutomationStep(StepType.OPEN_APP, value = PKG, description = "Open Food Gorilla"))
        steps.add(AutomationStep(StepType.WAIT_FOR, selector = Selector.fromString(ELEMENTS["search_bar"]!!), description = "Wait for search bar"))
        steps.add(AutomationStep(StepType.CLICK, selector = Selector.fromString(ELEMENTS["search_bar"]!!), description = "Click search bar"))
        steps.add(AutomationStep(StepType.INPUT_TEXT, selector = Selector.fromString(ELEMENTS["search_bar"]!!), value = restaurantName, description = "Input restaurant: $restaurantName"))
        
        steps.add(AutomationStep(StepType.CUSTOM, description = "OPEN_RESTAURANT: $restaurantName") {
            Log.e("ZeroUIAutomation", "[RESTAURANT] searching text=$restaurantName")
            val selector = Selector(text = restaurantName, classNameExclude = "android.widget.EditText")
            val success = engine.clickElement(selector)
            if (success) {
                Log.e("ZeroUIAutomation", "[RESTAURANT] result TextView FOUND")
                Log.e("ZeroUIAutomation", "[RESTAURANT] click SUCCESS")
            }
            success
        })

        // --- PHASE 2: SEARCH MENU ITEM ---
        steps.add(AutomationStep(StepType.WAIT_FOR, selector = Selector.fromString(ELEMENTS["menu_search_bar"]!!), description = "Wait for menu search bar"))
        steps.add(AutomationStep(StepType.CLICK, selector = Selector.fromString(ELEMENTS["menu_search_bar"]!!), description = "Click menu search bar"))
        steps.add(AutomationStep(StepType.INPUT_TEXT, selector = Selector.fromString(ELEMENTS["menu_search_bar"]!!), value = itemName, description = "Input item: $itemName"))

        // --- PHASE 3: OPEN_MENU_ITEM ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "OPEN_MENU_ITEM: $itemName") {
            Log.e("ZeroUIAutomation", "[STEP 1][MENU_ITEM] target=$itemName")
            val selector = Selector(text = itemName, classNameExclude = "android.widget.EditText")
            
            // 1. Click the item
            val clickSuccess = engine.clickElement(selector)
            if (!clickSuccess) {
                Log.e("ZeroUIAutomation", "[FAILED] step=OPEN_MENU_ITEM reason=initial find/click failed")
                false
            } else {
                // 2. Verify screen change (Wait for Add-to-cart Dialog)
                Log.d(TAG, "[MENU_ITEM] click dispatched, waiting for dialog...")
                val dialogSelectors = listOf(
                    Selector(testTag = "add_to_cart_confirm"),
                    Selector(text = "Add to Cart"),
                    Selector(testTag = "quantity_increase")
                )
                var verified = false
                for (i in 0 until 8) {
                    engine.awaitScreenChangeOrDelay(300)
                    if (engine.finder.findAny(dialogSelectors) != null) {
                        verified = true
                        break
                    }
                }

                if (verified) {
                    Log.d(TAG, "[STEP 1][MENU_ITEM] $itemName FOUND")
                    true
                } else {
                    Log.e("ZeroUIAutomation", "[FAILED] step=OPEN_MENU_ITEM reason=dialog not appeared")
                    false
                }
            }
        })

        // --- PHASE 4: ADD_TO_CART ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "ADD_TO_CART") {
            val selector = Selector(text = "Add to Cart")
            val node = engine.waitForElement(selector, timeoutMs = 5000)
            if (node == null) {
                Log.e("ZeroUIAutomation", "[ADD_TO_CART] FAILED: text node not found")
                false
            } else {
                val clickSuccess = engine.click(node)
                if (!clickSuccess) {
                    Log.e("ZeroUIAutomation", "[ADD_TO_CART] FAILED: click failed")
                    false
                } else {
                    val dialogSelectors = listOf(
                        Selector(text = "Add to Cart"),
                        Selector(testTag = "quantity_increase")
                    )
                    var verified = false
                    for (i in 0 until 8) {
                        engine.awaitScreenChangeOrDelay(300)
                        if (engine.finder.findAny(dialogSelectors) == null) {
                            verified = true
                            break
                        }
                    }
                    verified
                }
            }
        })

        // --- PHASE 5: OPEN_CART ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "OPEN_CART") {
            val selector = Selector(contentDescription = "Cart", testTag = "cart_button")
            engine.clickElement(selector)
        })

        // --- PHASE 6: CHECKOUT ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "CHECKOUT") {
            val selector = Selector(text = "Go to Checkout", testTag = "go_to_checkout")
            val node = engine.waitForElement(selector, timeoutMs = 5000)
            if (node == null) {
                false
            } else {
                val success = engine.click(node)
                if (success) {
                    val checkoutSelectors = listOf(
                        Selector(text = "Confirm Order"),
                        Selector(text = "Delivery Details")
                    )
                    var verified = false
                    for (i in 0 until 10) {
                        engine.awaitScreenChangeOrDelay(350)
                        if (engine.finder.findAny(checkoutSelectors) != null) {
                            verified = true
                            break
                        }
                    }
                    verified
                } else {
                    false
                }
            }
        })

        // --- PHASE 7: CONFIRM_ORDER ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "CONFIRM_ORDER") {
            val selector = Selector(text = "Confirm Order", testTag = "confirm_order")
            val node = engine.waitForElement(selector, timeoutMs = 5000)
            if (node == null) false else engine.click(node)
        })

        // --- PHASE 8: COMPLETE ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "COMPLETE") {
            Log.e("ZeroUIAutomation", "[COMPLETE] Food Gorilla order workflow completed")
            true
        })

        Log.e("ZeroUIAutomation", "Starting Food Gorilla Workflow: $restaurantName -> $itemName")
        return engine.execute(steps)
    }
}
