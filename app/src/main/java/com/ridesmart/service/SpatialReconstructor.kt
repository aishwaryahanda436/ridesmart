package com.ridesmart.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Stage 2 & 3 Optimization: Spatial reconstruction for fragmented Uber fare nodes.
 * 
 * Uber Driver (2024+) splits fare (e.g., ₹84.50) into three distinct nodes:
 * 1. [₹]
 * 2. [84]
 * 3. [.50]
 * 
 * This class groups nodes by their Y-coordinate baseline (±8dp tolerance)
 * and reconstructs the horizontal text string before regex parsing.
 */
object SpatialReconstructor {

    private data class NodeData(
        val text: String,
        val bounds: Rect
    )

    fun reconstruct(nodes: List<AccessibilityNodeInfo>): List<String> {
        if (nodes.isEmpty()) return emptyList()

        // 1. Convert to lightweight data objects and capture bounds
        val dataNodes = nodes.mapNotNull { node ->
            val text = node.text?.toString()?.trim() ?: return@mapNotNull null
            if (text.isEmpty()) return@mapNotNull null
            
            val rect = Rect()
            node.getBoundsInScreen(rect)
            NodeData(text, rect)
        }

        if (dataNodes.isEmpty()) return emptyList()

        // 2. Group by Y-baseline with 8dp tolerance
        val tolerance = 24 // ~8dp on XXHDPI (3x)
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

        // 3. Sort each row horizontally and join text
        return rows.map { row ->
            row.sortedBy { it.bounds.left }
                .joinToString("") { it.text }
        }
    }
}
