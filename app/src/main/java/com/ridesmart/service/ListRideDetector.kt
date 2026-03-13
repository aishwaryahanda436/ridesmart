package com.ridesmart.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * Detects and extracts ride cards from scrollable list containers.
 *
 * Modern ride-sharing apps show rides in multiple formats:
 *   - RecyclerView (traditional Android): Trip Radar, Opportunity lists
 *   - LazyColumn (Jetpack Compose): Stacked ride offers, ride queues
 *   - Dynamic ride cards: Map overlay cards
 *
 * This detector identifies list containers in the accessibility tree,
 * groups children into logical card boundaries, and extracts text from
 * each card independently so that multiple rides can be parsed in one pass.
 *
 * Works on non-rooted devices using only public AccessibilityService APIs.
 */
object ListRideDetector {

    private const val TAG = "RideSmart"

    // Class names that indicate scrollable list containers
    private val LIST_CLASS_NAMES = setOf(
        "androidx.recyclerview.widget.RecyclerView",
        "android.support.v7.widget.RecyclerView",
        "android.widget.ListView",
        "android.widget.ScrollView",
        "android.widget.HorizontalScrollView"
    )

    // Compose LazyColumn/LazyRow typically report these class names
    private val COMPOSE_LIST_CLASS_NAMES = setOf(
        "android.view.View"  // Compose nodes report as generic View
    )

    // Resource ID patterns that indicate list containers in ride apps
    private val LIST_VIEW_ID_PATTERNS = listOf(
        "trip_radar", "ride_list", "request_list", "offer_list",
        "recycler", "ride_card", "opportunity", "stacked",
        "lazy_column", "trip_list", "ride_queue"
    )

    // Minimum Y-gap (pixels) between card boundaries to split cards
    private const val CARD_GAP_THRESHOLD = 40

    // Minimum signals to consider a card group as a ride offer
    private const val MIN_CARD_SIGNALS = 1

    /** Lightweight holder for text with screen bounds. */
    private data class TextWithBounds(val text: String, val bounds: Rect)

    /**
     * Scans the accessibility tree for list containers and extracts
     * individual ride card text groups.
     *
     * @param root The root accessibility node to search from
     * @return List of card text groups, where each inner list represents one ride card
     */
    fun detectRideCards(root: AccessibilityNodeInfo): List<List<String>> {
        val listNodes = mutableListOf<AccessibilityNodeInfo>()
        findListContainers(root, listNodes, depth = 0)

        if (listNodes.isEmpty()) return emptyList()

        val allCards = mutableListOf<List<String>>()
        for (listNode in listNodes) {
            val cards = extractCardsFromContainer(listNode)
            allCards.addAll(cards)
        }

        Log.d(TAG, "📋 ListRideDetector found ${allCards.size} cards from ${listNodes.size} containers")
        return allCards
    }

    /**
     * Checks whether a node is a scrollable list container.
     */
    fun isListContainer(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName?.toString()?.lowercase() ?: ""

        // Direct class name match
        if (className in LIST_CLASS_NAMES) return true

        // Compose lazy lists: generic View class + scrollable flag
        if (className in COMPOSE_LIST_CLASS_NAMES && node.isScrollable) return true

        // View ID pattern match
        if (LIST_VIEW_ID_PATTERNS.any { viewId.contains(it) }) return true

        // Scrollable node with multiple children (likely a list)
        if (node.isScrollable && node.childCount >= 2) return true

        return false
    }

    /**
     * Recursively finds list container nodes in the accessibility tree.
     */
    private fun findListContainers(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 10) return

        if (isListContainer(node)) {
            result.add(node)
            // Don't recurse into a list container — we'll process its children directly
            return
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findListContainers(child, result, depth + 1)
        }
    }

    /**
     * Extracts individual card groups from a list container.
     * Groups children by Y-coordinate boundaries to separate cards.
     */
    private fun extractCardsFromContainer(listNode: AccessibilityNodeInfo): List<List<String>> {
        val childCount = listNode.childCount
        if (childCount == 0) return emptyList()

        val cardGroups = mutableListOf<List<String>>()

        for (i in 0 until childCount) {
            val child = listNode.getChild(i) ?: continue
            val cardTexts = mutableListOf<String>()
            collectTextFromNode(child, cardTexts, depth = 0)

            if (cardTexts.isNotEmpty() && hasRideSignals(cardTexts)) {
                cardGroups.add(cardTexts)
            }
        }

        // If direct children didn't yield cards, try spatial grouping
        if (cardGroups.isEmpty()) {
            return extractCardsBySpatialGrouping(listNode)
        }

        return cardGroups
    }

    /**
     * Fallback: Groups text nodes by Y-coordinate into card boundaries.
     * Used when list children don't correspond to individual ride cards.
     */
    private fun extractCardsBySpatialGrouping(listNode: AccessibilityNodeInfo): List<List<String>> {
        val allTexts = mutableListOf<TextWithBounds>()
        collectTextWithBounds(listNode, allTexts, depth = 0)

        if (allTexts.isEmpty()) return emptyList()

        // Sort by Y coordinate
        val sorted = allTexts.sortedBy { it.bounds.top }

        // Group by Y-gaps
        val groups = mutableListOf<MutableList<String>>()
        var currentGroup = mutableListOf<String>()
        var lastBottom = sorted.first().bounds.bottom

        for (item in sorted) {
            val gap = item.bounds.top - lastBottom
            if (gap > CARD_GAP_THRESHOLD && currentGroup.isNotEmpty()) {
                groups.add(currentGroup)
                currentGroup = mutableListOf()
            }
            currentGroup.add(item.text)
            lastBottom = maxOf(lastBottom, item.bounds.bottom)
        }
        if (currentGroup.isNotEmpty()) groups.add(currentGroup)

        return groups.filter { hasRideSignals(it) }
    }

    /**
     * Recursively collects text from a node subtree.
     */
    private fun collectTextFromNode(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        depth: Int
    ) {
        if (depth > 8) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrBlank()) texts.add(text)
        else if (!desc.isNullOrBlank()) texts.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextFromNode(child, texts, depth + 1)
        }
    }

    /**
     * Recursively collects text with screen bounds for spatial grouping.
     */
    private fun collectTextWithBounds(
        node: AccessibilityNodeInfo,
        results: MutableList<TextWithBounds>,
        depth: Int
    ) {
        if (depth > 8) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim()
            ?: node.contentDescription?.toString()?.trim()

        if (!text.isNullOrBlank()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            results.add(TextWithBounds(text, rect))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextWithBounds(child, results, depth + 1)
        }
    }

    /**
     * Checks if a text group contains ride offer signals.
     */
    private fun hasRideSignals(texts: List<String>): Boolean {
        val combined = texts.joinToString(" ").lowercase()
        var signals = 0

        if (Regex("""[₹]\d+""").containsMatchIn(combined)) signals++
        if (combined.contains("km")) signals++
        if (combined.contains("min")) signals++
        if (combined.contains("away")) signals++
        if (combined.contains("trip") || combined.contains("ride")) signals++
        if (combined.contains("accept") || combined.contains("match") ||
            combined.contains("confirm")) signals++

        return signals >= MIN_CARD_SIGNALS
    }
}
