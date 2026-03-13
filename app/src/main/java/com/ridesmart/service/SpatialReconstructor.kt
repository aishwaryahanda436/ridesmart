package com.ridesmart.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Stage 2 & 3 Optimization: Spatial reconstruction for fragmented UI nodes.
 *
 * Uber Driver (2024+) splits fare (e.g., ₹84.50) into three distinct nodes:
 * 1. [₹]
 * 2. [84]
 * 3. [.50]
 *
 * This class groups nodes by their Y-coordinate baseline (±8dp tolerance)
 * and reconstructs the horizontal text string before regex parsing.
 *
 * Enhanced with:
 *   - contentDescription fallback when text is null (Compose/custom views)
 *   - Card-level grouping for ride list detection (Trip Radar, stacked offers)
 *   - View ID metadata extraction for debugging and element identification
 */
object SpatialReconstructor {

    private data class NodeData(
        val text: String,
        val bounds: Rect,
        val viewId: String = "",
        val className: String = ""
    )

    // Y-tolerance for row grouping in dp (~8dp)
    private const val ROW_TOLERANCE_DP = 8

    // Y-gap threshold for separating card groups (ride cards in a list)
    private const val CARD_GAP_THRESHOLD = 80

    // Horizontal pixel gap below which nodes are considered truly adjacent (no space inserted)
    private const val HORIZONTAL_ADJACENCY_PX = 4

    fun reconstruct(nodes: List<AccessibilityNodeInfo>, density: Float = 3f): List<String> {
        if (nodes.isEmpty()) return emptyList()

        val dataNodes = toNodeData(nodes)
        if (dataNodes.isEmpty()) return emptyList()

        val tolerance = (ROW_TOLERANCE_DP * density).toInt()
        return groupIntoRows(dataNodes, tolerance)
    }

    /**
     * Reconstructs text grouped into card-level blocks.
     * Each inner list represents text lines from a single ride card.
     *
     * Used for parsing ride lists where multiple offers appear in the same
     * accessibility tree (Trip Radar, stacked offers, ride queues).
     */
    fun reconstructAsCards(nodes: List<AccessibilityNodeInfo>, density: Float = 3f): List<List<String>> {
        if (nodes.isEmpty()) return emptyList()

        val dataNodes = toNodeData(nodes)
        if (dataNodes.isEmpty()) return emptyList()

        // Sort by Y coordinate
        val sorted = dataNodes.sortedBy { it.bounds.top }

        // Split into card groups by Y-gap
        val cardGroups = mutableListOf<MutableList<NodeData>>()
        var currentGroup = mutableListOf<NodeData>()
        var lastBottom = sorted.first().bounds.bottom

        for (node in sorted) {
            val gap = node.bounds.top - lastBottom
            if (gap > CARD_GAP_THRESHOLD && currentGroup.isNotEmpty()) {
                cardGroups.add(currentGroup)
                currentGroup = mutableListOf()
            }
            currentGroup.add(node)
            lastBottom = maxOf(lastBottom, node.bounds.bottom)
        }
        if (currentGroup.isNotEmpty()) cardGroups.add(currentGroup)

        // Reconstruct rows within each card group
        val tolerance = (ROW_TOLERANCE_DP * density).toInt()
        return cardGroups.map { group -> groupIntoRows(group, tolerance) }
    }

    /**
     * Converts AccessibilityNodeInfo list to lightweight NodeData objects.
     * Uses text first, falls back to contentDescription for Compose/custom views.
     */
    private fun toNodeData(nodes: List<AccessibilityNodeInfo>): List<NodeData> {
        return nodes.mapNotNull { node ->
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val effectiveText = when {
                !text.isNullOrBlank() -> text
                !desc.isNullOrBlank() -> desc
                else -> return@mapNotNull null
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)
            NodeData(
                text = effectiveText,
                bounds = rect,
                viewId = node.viewIdResourceName?.toString() ?: "",
                className = node.className?.toString() ?: ""
            )
        }
    }

    /**
     * Groups node data into horizontal rows by Y-baseline proximity.
     */
    private fun groupIntoRows(dataNodes: List<NodeData>, tolerance: Int = 24): List<String> {
        val rows = mutableListOf<MutableList<NodeData>>()
        val sortedByY = dataNodes.sortedBy { it.bounds.centerY() }

        for (node in sortedByY) {
            var foundRow = false
            for (row in rows) {
                val rowCenterY = row.map { it.bounds.centerY() }.average().toInt()
                if (abs(node.bounds.centerY() - rowCenterY) <= tolerance) {
                    row.add(node)
                    foundRow = true
                    break
                }
            }
            if (!foundRow) {
                rows.add(mutableListOf(node))
            }
        }

        // Sort each row horizontally and join text with smart spacing
        return rows.map { row ->
            val sorted = row.sortedBy { it.bounds.left }
            buildString {
                sorted.forEachIndexed { i, node ->
                    if (i > 0 && node.bounds.left > sorted[i - 1].bounds.right + HORIZONTAL_ADJACENCY_PX) {
                        append(' ')
                    }
                    append(node.text)
                }
            }
        }
    }
}
