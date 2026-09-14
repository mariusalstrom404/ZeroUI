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
                Log.e("ZeroUIAutomation", "[MENU_ITEM] click dispatched, waiting for dialog...")
                var verified = false
                for (i in 0 until 5) {
                    delay(800)
                    val foundDialog = engine.findNode(Selector(testTag = "add_to_cart_confirm")) != null || 
                                    engine.findNode(Selector(text = "Add to Cart")) != null ||
                                    engine.findNode(Selector(testTag = "quantity_increase")) != null
                    
                    if (foundDialog) {
                        verified = true
                        break
                    }
                    Log.e("ZeroUIAutomation", "[MENU_ITEM] retry verification ${i+1}/5")
                }

                if (verified) {
                    Log.e("ZeroUIAutomation", "[STEP 1][MENU_ITEM] $itemName FOUND")
                    Log.e("ZeroUIAutomation", "[MENU_ITEM] click VERIFIED SUCCESS")
                    true
                } else {
                    Log.e("ZeroUIAutomation", "[MENU_ITEM] click dispatched but screen unchanged")
                    Log.e("ZeroUIAutomation", "[FAILED] step=OPEN_MENU_ITEM reason=dialog not appeared")
                    false
                }
            }
        })

        // --- PHASE 4: ADD_TO_CART ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "ADD_TO_CART") {
            Log.e("ZeroUIAutomation", "[ADD_TO_CART] searching text contains \"Add to Cart\"")
            val selector = Selector(text = "Add to Cart")
            
            // 1. Find and Click
            val node = engine.waitForElement(selector, timeoutMs = 5000)
            if (node == null) {
                Log.e("ZeroUIAutomation", "[ADD_TO_CART] FAILED: text node not found")
                false
            } else {
                Log.e("ZeroUIAutomation", "[ADD_TO_CART] text node FOUND")
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                Log.e("ZeroUIAutomation", "[ADD_TO_CART] node bounds=${bounds.toShortString()}")
                
                val clickSuccess = engine.clickElement(selector)
                Log.e("ZeroUIAutomation", "[ADD_TO_CART] ACTION_CLICK result=$clickSuccess")
                
                if (!clickSuccess) {
                    Log.e("ZeroUIAutomation", "[ADD_TO_CART] FAILED: click failed")
                    false
                } else {
                    // 2. Verify
                    Log.e("ZeroUIAutomation", "[ADD_TO_CART] reacquire root")
                    var verified = false
                    for (i in 0 until 5) {
                        delay(800)
                        // Check if dialog is gone (no "Add to Cart" text and no quantity button)
                        val stillVisible = engine.findNode(Selector(text = "Add to Cart")) != null || 
                                          engine.findNode(Selector(testTag = "quantity_increase")) != null
                        
                        Log.e("ZeroUIAutomation", "[ADD_TO_CART] dialog still visible=$stillVisible")
                        if (!stillVisible) {
                            verified = true
                            break
                        }
                        Log.e("ZeroUIAutomation", "[ADD_TO_CART] retry verification ${i+1}/5")
                    }

                    if (verified) {
                        Log.e("ZeroUIAutomation", "[ADD_TO_CART] VERIFIED SUCCESS")
                        true
                    } else {
                        Log.e("ZeroUIAutomation", "[ADD_TO_CART] click dispatched but dialog still visible")
                        Log.e("ZeroUIAutomation", "[ADD_TO_CART] FAILED")
                        false
                    }
                }
            }
        })

        // --- PHASE 5: OPEN_CART ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "OPEN_CART") {
            Log.e("ZeroUIAutomation", "[STEP 3][CART] Searching Cart button")
            // Try description first as requested
            val selector = Selector(contentDescription = "Cart", testTag = "cart_button")
            val success = engine.clickElement(selector)
            if (success) {
                Log.e("ZeroUIAutomation", "[STEP 3][CART] Cart FOUND")
                Log.e("ZeroUIAutomation", "[STEP 3][CART] click SUCCESS")
            } else {
                Log.e("ZeroUIAutomation", "[FAILED] step=OPEN_CART reason=cart button not found")
            }
            success
        })

        // --- PHASE 6: CHECKOUT ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "CHECKOUT") {
            Log.e("ZeroUIAutomation", "[CHECKOUT] searching Go to Checkout")
            val selector = Selector(text = "Go to Checkout", testTag = "go_to_checkout")
            
            // 1. Wait and Click
            val node = engine.waitForElement(selector, timeoutMs = 5000)
            if (node == null) {
                Log.e("ZeroUIAutomation", "[CHECKOUT] byText = NOT FOUND")
                false
            } else {
                Log.e("ZeroUIAutomation", "[CHECKOUT] byText = FOUND")
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                Log.e("ZeroUIAutomation", "[CHECKOUT] exactNode bounds=${bounds.toShortString()}")
                
                val success = engine.clickElement(selector)
                if (success) {
                    Log.e("ZeroUIAutomation", "[CHECKOUT] click DISPATCHED")
                    
                    // 2. Verify transition to Checkout Screen
                    Log.e("ZeroUIAutomation", "[CHECKOUT] reacquire root for verification")
                    var verified = false
                    for (i in 0 until 5) {
                        delay(1000)
                        val foundCheckout = engine.findNode(Selector(text = "Confirm Order")) != null || 
                                          engine.findNode(Selector(text = "Delivery Details")) != null
                        
                        if (foundCheckout) {
                            verified = true
                            break
                        }
                        Log.e("ZeroUIAutomation", "[CHECKOUT] retry verification ${i+1}/5")
                    }
                    
                    if (verified) {
                        Log.e("ZeroUIAutomation", "[STEP 4][CHECKOUT] Go to Checkout FOUND")
                        Log.e("ZeroUIAutomation", "[CHECKOUT] VERIFIED SUCCESS")
                        true
                    } else {
                        Log.e("ZeroUIAutomation", "[CHECKOUT] FAILED: checkout screen not detected")
                        false
                    }
                } else {
                    Log.e("ZeroUIAutomation", "[CHECKOUT] FAILED: click failed")
                    false
                }
            }
        })

        // --- PHASE 7: CONFIRM_ORDER ---
        steps.add(AutomationStep(StepType.CUSTOM, description = "CONFIRM_ORDER") {
            Log.e("ZeroUIAutomation", "[ORDER] searching Confirm Order")
            val selector = Selector(text = "Confirm Order", testTag = "confirm_order")
            
            val node = engine.waitForElement(selector, timeoutMs = 5000)
            if (node == null) {
                Log.e("ZeroUIAutomation", "[ORDER] byText = NOT FOUND")
                false
            } else {
                val success = engine.clickElement(selector)
                if (success) {
                    Log.e("ZeroUIAutomation", "[ORDER] Confirm Order FOUND")
                    Log.e("ZeroUIAutomation", "[STEP 5][ORDER] click SUCCESS")
                    true
                } else {
                    Log.e("ZeroUIAutomation", "[ORDER] FAILED: click failed")
                    false
                }
            }
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
