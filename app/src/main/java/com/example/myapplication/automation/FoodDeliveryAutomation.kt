package com.example.myapplication.automation

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Orders from real food-delivery apps (Foodpanda, Uber Eats…) that ZeroUI has no element IDs for:
 * everything is found by on-screen text, in English or Chinese.
 *
 * Flow: search for the restaurant with the app's search bar → scroll the results and tap it →
 * scroll the menu to the dish (or fall back to the menu's own search) → tap it → "Add to cart".
 * It stops at the cart; checkout and payment are left to the user.
 */
class FoodDeliveryAutomation(private val engine: AutomationEngine) {

    enum class Result {
        ADDED,
        /** "Add to cart" was tapped but no cart indicator appeared afterwards. */
        ADDED_UNVERIFIED,
        /** The dish has required choices (size, crust…) so "Add to cart" stays disabled. */
        NEEDS_OPTIONS,
        SEARCH_FAILED,
        RESTAURANT_NOT_FOUND,
        ITEM_NOT_FOUND,
        ADD_FAILED
    }

    companion object {
        private const val LOG = "ZeroUIAutomation"
        private const val RESULT_SCROLLS = 10
        private const val MENU_SCROLLS = 30

        private val ADD_TO_CART = listOf(
            "Add to cart", "Add to basket", "Add to order", "Add item",
            "加入購物車", "加到購物車", "加入購物籃", "新增至購物車"
        )
        private val CART_INDICATORS = listOf(
            // Not a bare "Cart"/"購物車": that tab or icon is on screen whether or not we added anything.
            "View your cart", "View cart", "View basket", "Go to cart", "Added to cart",
            "查看購物車", "前往購物車"
        )
    }

    suspend fun order(packageName: String, restaurant: String, item: String): Result {
        Log.e(LOG, "[ORDER] app=$packageName restaurant=$restaurant item=$item")

        // 1. Search for the restaurant.
        delay(1500) // let the launched app draw its home screen
        engine.dismissPopups(packageName)
        engine.report("Searching for “$restaurant”")
        if (!engine.typeIntoApp(restaurant, submit = true, packageName = packageName, timeoutMs = 15000)) {
            return Result.SEARCH_FAILED
        }
        delay(1500) // results load after the search key

        // 2. Open the restaurant from the results.
        if (!openRestaurant(packageName, restaurant)) return Result.RESTAURANT_NOT_FOUND
        delay(2000)
        engine.dismissPopups(packageName)

        // 3. Find the dish on the menu and open it.
        if (!openItem(packageName, item)) return Result.ITEM_NOT_FOUND
        delay(1200)

        // 4. Add it to the cart.
        return addToCart(packageName)
    }

    /** Taps the restaurant in the search results; retries once if we're still on the search screen. */
    private suspend fun openRestaurant(packageName: String, restaurant: String): Boolean {
        val selector = Selector(text = restaurant, classNameExclude = "EditText")
        repeat(2) { attempt ->
            engine.report("Looking for “$restaurant” in the results")
            val clicked = engine.clickElement(
                selector, timeoutMs = 25000, scrollIfNotFound = true,
                packageName = packageName, maxScrolls = RESULT_SCROLLS
            )
            if (!clicked) return false
            // Tapping a search *suggestion* only runs the search; the shop is then in the results.
            delay(2500)
            if (!onSearchScreen(packageName, restaurant)) {
                Log.e(LOG, "[ORDER] opened restaurant (attempt ${attempt + 1})")
                return true
            }
            Log.e(LOG, "[ORDER] still on the search screen after tapping “$restaurant”")
        }
        return false
    }

    private fun onSearchScreen(packageName: String, restaurant: String): Boolean =
        engine.finder.textFields(packageName).any { it.text?.contains(restaurant, ignoreCase = true) == true }

    /** Scrolls the menu to [item] and taps it; falls back to the restaurant's menu search. */
    private suspend fun openItem(packageName: String, item: String): Boolean {
        val selector = Selector(text = item, classNameExclude = "EditText")
        engine.report("Scrolling the menu for “$item”")
        if (engine.clickElement(
                selector, timeoutMs = 60000, scrollIfNotFound = true,
                packageName = packageName, maxScrolls = MENU_SCROLLS
            )
        ) return true

        Log.e(LOG, "[ORDER] “$item” not found by scrolling, trying the menu search")
        engine.report("Searching the menu for “$item”")
        if (!engine.typeIntoApp(item, submit = true, packageName = packageName, timeoutMs = 8000)) return false
        delay(1500)
        return engine.clickElement(
            selector, timeoutMs = 15000, scrollIfNotFound = true,
            packageName = packageName, maxScrolls = RESULT_SCROLLS
        )
    }

    private suspend fun addToCart(packageName: String): Result {
        val selectors = ADD_TO_CART.map { Selector(text = it) }
        engine.report("Adding to cart")
        if (!engine.clickAny(selectors, timeoutMs = 8000, packageName = packageName)) {
            // Present but disabled means the dish needs choices the user has to make.
            val disabled = selectors.any { sel ->
                engine.findNode(sel, packageName)?.let { !(engine.finder.clickTarget(it) ?: it).isEnabled } == true
            }
            return if (disabled) Result.NEEDS_OPTIONS else Result.ADD_FAILED
        }

        engine.report("Checking the cart")
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            if (CART_INDICATORS.any { engine.findNode(Selector(text = it), packageName) != null }) return Result.ADDED
        }
        return Result.ADDED_UNVERIFIED
    }
}
