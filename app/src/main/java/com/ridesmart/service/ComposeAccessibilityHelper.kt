package com.ridesmart.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * Helper for parsing Jetpack Compose accessibility trees.
 *
 * Compose UI nodes differ from traditional Android Views:
 *   - All nodes report className as "android.view.View"
 *   - Semantic properties are exposed via contentDescription
 *   - Text is set via semantics text property (appears as node.text)
 *   - testTag is exposed via viewIdResourceName when configured
 *   - mergeDescendants causes child text to merge into parent
 *   - Tree structure is flatter — fewer nesting levels
 *
 * Strategies for handling Compose UI:
 *   1. Use contentDescription as primary text source when text is null
 *   2. Detect merged semantics nodes (text != null but no children have text)
 *   3. Use viewIdResourceName for testTag-based element identification
 *   4. Fall back to spatial reconstruction for fragmented Compose trees
 */
object ComposeAccessibilityHelper {

    private const val TAG = "RideSmart"

    // Compose nodes always report this class name
    private const val COMPOSE_VIEW_CLASS = "android.view.View"

    // Maximum tree depth for Compose (trees tend to be deep but narrow)
    private const val MAX_COMPOSE_DEPTH = 15

    /**
     * Detects whether the accessibility tree appears to be from a Compose UI.
     *
     * Heuristic: If most visible nodes have className "android.view.View"
     * and lack standard Android widget class names, it's likely Compose.
     */
    fun isComposeTree(root: AccessibilityNodeInfo): Boolean {
        var genericViewCount = 0
        var widgetCount = 0
        countNodeTypes(root, 0) { className ->
            if (className == COMPOSE_VIEW_CLASS) genericViewCount++
            else if (className.startsWith("android.widget.") ||
                className.startsWith("androidx.")) widgetCount++
        }
        // If >70% are generic Views and there are at least 5 nodes, likely Compose
        val total = genericViewCount + widgetCount
        return total >= 5 && genericViewCount.toDouble() / total > 0.7
    }

    /**
     * Collects text from a Compose accessibility tree with enhanced strategies.
     *
     * Unlike traditional View trees, Compose trees require:
     *   - Deeper traversal (mergeDescendants creates composite nodes)
     *   - contentDescription as primary fallback
     *   - Role-based filtering (buttons, text fields, etc.)
     *
     * @param root Root node of the Compose tree
     * @return List of text strings extracted from semantic nodes
     */
    fun collectComposeText(root: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        traverseComposeTree(root, results, depth = 0)
        return results
    }

    /**
     * Extracts text from a Compose tree with view ID (testTag) metadata.
     * Returns pairs of (viewId, text) for element identification.
     */
    fun collectComposeTextWithIds(root: AccessibilityNodeInfo): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        traverseComposeTreeWithIds(root, results, depth = 0)
        return results
    }

    private fun traverseComposeTree(
        node: AccessibilityNodeInfo,
        results: MutableList<String>,
        depth: Int
    ) {
        if (depth > MAX_COMPOSE_DEPTH) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        // Compose mergeDescendants: parent has merged text, skip children
        if (!text.isNullOrBlank()) {
            results.add(text)
            // If this node has text and is a "merged" semantic node,
            // children may be redundant — but we still scan them
            // in case they have additional unique text
        } else if (!desc.isNullOrBlank()) {
            // contentDescription is the primary semantic property in Compose
            results.add(desc)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseComposeTree(child, results, depth + 1)
        }
    }

    private fun traverseComposeTreeWithIds(
        node: AccessibilityNodeInfo,
        results: MutableList<Pair<String, String>>,
        depth: Int
    ) {
        if (depth > MAX_COMPOSE_DEPTH) return
        if (!node.isVisibleToUser) return

        val viewId = node.viewIdResourceName?.toString() ?: ""
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        val effectiveText = when {
            !text.isNullOrBlank() -> text
            !desc.isNullOrBlank() -> desc
            else -> null
        }

        if (effectiveText != null) {
            results.add(Pair(viewId, effectiveText))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseComposeTreeWithIds(child, results, depth + 1)
        }
    }

    /**
     * Attempts to find ride-offer-related Compose semantic nodes by testTag patterns.
     *
     * Many Compose apps tag ride offer elements with identifiable testTags like:
     *   - "fare_text", "trip_distance", "pickup_address", etc.
     *
     * @param root Root of the Compose accessibility tree
     * @param tagPatterns Patterns to search for in viewIdResourceName
     * @return Map of matched tag pattern to extracted text value
     */
    fun findNodesByTagPattern(
        root: AccessibilityNodeInfo,
        tagPatterns: List<String>
    ): Map<String, String> {
        val matches = mutableMapOf<String, String>()
        searchByTag(root, tagPatterns, matches, depth = 0)
        return matches
    }

    private fun searchByTag(
        node: AccessibilityNodeInfo,
        patterns: List<String>,
        matches: MutableMap<String, String>,
        depth: Int
    ) {
        if (depth > MAX_COMPOSE_DEPTH) return

        val viewId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.trim()
            ?: node.contentDescription?.toString()?.trim()

        if (viewId.isNotBlank() && !text.isNullOrBlank()) {
            for (pattern in patterns) {
                if (viewId.contains(pattern.lowercase())) {
                    matches[pattern] = text
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchByTag(child, patterns, matches, depth + 1)
        }
    }

    private fun countNodeTypes(
        node: AccessibilityNodeInfo,
        depth: Int,
        callback: (String) -> Unit
    ) {
        if (depth > 6) return // Only need a shallow sample
        val className = node.className?.toString() ?: ""
        if (className.isNotBlank()) callback(className)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            countNodeTypes(child, depth + 1, callback)
        }
    }
}
