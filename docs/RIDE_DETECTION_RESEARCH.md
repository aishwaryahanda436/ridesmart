# RideSmart — Ride Detection Technical Research Report

## 1. Root Cause Analysis: Uber Detection Failure

### Why Accessibility Stopped Reading UI Elements

After investigating the Uber Driver app's evolution and Android accessibility framework behavior, the root causes for detection failure are:

#### 1.1 Jetpack Compose Migration
Uber has been progressively migrating its driver app to **Jetpack Compose**. Compose's accessibility tree differs fundamentally from traditional View-based UI:

- **All nodes report `className = "android.view.View"`** — no widget-specific class names
- **Text is exposed via Compose semantics** — `contentDescription` becomes the primary text source
- **`mergeDescendants`** causes child text to merge into parent nodes, flattening the tree
- **Deeper nesting** — Compose trees can be 12-15 levels deep vs 6-8 for Views
- **No `viewIdResourceName`** unless `testTag` is explicitly set in code

#### 1.2 Custom Canvas Rendering
Uber uses custom `Canvas`-drawn views for:
- Fare amounts (rendered as graphics, not text nodes)
- Map overlays and route previews
- Progress indicators and animations

These elements produce **zero accessibility nodes** unless the developer explicitly adds `contentDescription` annotations.

#### 1.3 Fragmented Text Nodes
Uber splits fare displays (e.g., ₹84.50) into separate nodes:
1. `[₹]` 2. `[84]` 3. `[.50]`

This requires **spatial reconstruction** — grouping nodes by Y-coordinate and joining horizontally — which the `SpatialReconstructor` class handles.

#### 1.4 Secure View Flags
Uber may apply `FLAG_SECURE` to sensitive windows, which:
- Blocks `AccessibilityService.takeScreenshot()` (returns black bitmap)
- Does NOT block accessibility node reading
- Does block `MediaProjection` screen capture

#### 1.5 `importantForAccessibility` Suppression
Setting `importantForAccessibility = "no"` or `View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` hides entire subtrees from the accessibility API. Uber may use this on ride offer UI to prevent third-party reading.

### Mitigation Strategy (Implemented)
- Increased tree depth from 8 → 12 for Compose trees
- Added `contentDescription` as fallback text source in `SpatialReconstructor`
- Created `ComposeAccessibilityHelper` for Compose-specific tree parsing
- Added OCR fallback for when accessibility tree is empty or blocked
- Hybrid approach: accessibility for detection, OCR for extraction

---

## 2. Ride List Detection Methods

### 2.1 RecyclerView Detection via AccessibilityService

RecyclerView exposes these accessibility properties:
- `className = "androidx.recyclerview.widget.RecyclerView"`
- `isScrollable = true`
- Direct children correspond to individual list items (ViewHolders)

**Detection strategy:** Identify RecyclerView containers and iterate their children to extract individual ride cards.

### 2.2 Jetpack Compose LazyColumn Detection

LazyColumn nodes appear as:
- `className = "android.view.View"` (generic)
- `isScrollable = true`
- Multiple children with consistent vertical spacing

**Detection heuristic:** Generic View + scrollable flag + multiple children indicates Compose list.

### 2.3 Spatial Card Grouping

When list container detection fails, `SpatialReconstructor.reconstructAsCards()` groups nodes by Y-coordinate gaps:

1. Sort all text nodes by Y-coordinate
2. Detect Y-gaps larger than threshold (80px)
3. Split into card groups at gap boundaries
4. Parse each group as an independent ride card

---

## 3. Accessibility Parsing for RecyclerView

The `ListRideDetector` class implements a three-level approach:

1. **Direct children scanning** — Each RecyclerView child is a ViewHolder containing one ride card
2. **Spatial grouping fallback** — When children don't map to cards, use Y-coordinate clustering
3. **Signal-based filtering** — Only process card groups containing fare/distance signals

---

## 4. Compose UI Detection Techniques

### 4.1 Tree Detection
Heuristic: >70% of nodes are generic "android.view.View" → likely Compose.

### 4.2 Text Extraction
Priority: text > contentDescription for each node.

### 4.3 TestTag-Based Element Search
Compose testTag maps to viewIdResourceName — can search for known patterns.

---

## 5. OCR Fallback Strategy

### When to Use OCR
1. **Empty accessibility tree** — Uber blocks all node data
2. **Accessibility signals present but parsing fails** — Hybrid detection
3. **Trip Radar / ride list screens** — Multiple cards need full-screen OCR
4. **Canvas-rendered text** — No accessibility nodes available

### Architecture
```
Accessibility Event → Node Collection → Spatial Reconstruction
    ↓ (empty or failed)
OCR Fallback → Screenshot → ML Kit → Text Extraction → Parsing
```

---

## 6. Architecture Improvements

### 6.1 Hybrid Detection Pipeline
```
Event → Node Collection → [
    Accessibility Parser (fast)
    ↓ failed?
    Spatial Reconstruction (medium)
    ↓ failed?
    Fallback Generic Parser
    ↓ failed?
    OCR Screenshot (slow but reliable)
]
```

### 6.2 Multi-Card Processing
- `UberOcrEngine.parseMultipleCards()` splits node groups by fare boundaries
- `SpatialReconstructor.reconstructAsCards()` groups by Y-coordinate gaps
- `ListRideDetector.detectRideCards()` extracts from list containers

### 6.3 Enhanced Screen State Detection
New `ScreenState` values:
- `RIDE_LIST` — Multiple fare signals without accept button (scrollable list)
- `TRIP_RADAR` — "Trip Radar" / "See all requests" / "Opportunity" keywords

### 6.4 Scroll Event Processing
Added `TYPE_VIEW_SCROLLED` to accessibility events to detect:
- User scrolling through ride lists
- New ride cards appearing in RecyclerView
- LazyColumn loading new items

---

## 7. Performance Optimizations

- Skip invisible nodes (`isVisibleToUser` check) — eliminates ~30% of nodes
- Depth cap at 12 (up from 8) — handles Compose trees without runaway
- Uber content-changed throttle: 300ms
- OCR fallback cooldown: 2000ms
- Screenshot cooldown: 800ms
- Duplicate fare suppression: 15s cooldown
- Immediate `safeRecycle()` of child nodes after processing
- Singleton parser instances (no per-event allocation)

---

## 8. Implementation Summary

### New Files
1. **`ListRideDetector.kt`** — RecyclerView/LazyColumn ride card detection
2. **`ComposeAccessibilityHelper.kt`** — Jetpack Compose tree parsing

### Modified Files
1. **`ScreenState.kt`** — Added `RIDE_LIST` and `TRIP_RADAR`
2. **`SpatialReconstructor.kt`** — Card grouping, contentDescription fallback
3. **`RideSmartService.kt`** — Enhanced node collection, list detection, scroll events
4. **`UberOcrEngine.kt`** — Multi-card parsing, Trip Radar detection, full-screen OCR
5. **`RideDataParser.kt`** — Updated screen state detection
6. **`accessibility_service_config.xml`** — Added `typeViewScrolled` event type
