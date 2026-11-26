package com.nightcinder.helix.ui.panels

/** The three top-level areas of the panel cockpit (nav destinations / Home Space tabs). */
enum class CockpitArea(val title: String) {
    STREAM("Stream"),
    TERMINAL("Terminal"),
    WORKSPACE("Workspace"),
}

/** Tabs inside the right wing. */
enum class RightTab(val title: String) {
    WORKSPACE("Workspace"),
    ALERTS("Alerts"),
    METRICS("Metrics"),
}

/** Tabs inside the terminal (left) wing. */
enum class LeftTab(val title: String) {
    TERMINAL("Terminal"),
    SNIPPETS("Snippets"),
}

/** Immutable navigation state shared by the phone, Home Space and Full Space layouts. */
data class PanelNavState(
    val area: CockpitArea = CockpitArea.STREAM,
    val rightTab: RightTab = RightTab.WORKSPACE,
    val leftTab: LeftTab = LeftTab.TERMINAL,
) {
    fun selectArea(next: CockpitArea): PanelNavState = copy(area = next)

    /** Picking a right-wing tab also brings the workspace area to the front. */
    fun selectRightTab(tab: RightTab): PanelNavState = copy(rightTab = tab, area = CockpitArea.WORKSPACE)

    /** Picking a terminal-wing tab also brings the terminal area to the front. */
    fun selectLeftTab(tab: LeftTab): PanelNavState = copy(leftTab = tab, area = CockpitArea.TERMINAL)

    /** After a docker console / SSH tab is opened the terminal should be visible. */
    fun showTerminal(): PanelNavState = copy(area = CockpitArea.TERMINAL, leftTab = LeftTab.TERMINAL)

    /** Opens/focuses the live Alerts feed in the right wing / Workspace area. */
    fun showAlerts(): PanelNavState = selectRightTab(RightTab.ALERTS)

    /** On wide screens terminal and right wing share one split pane. */
    val isSplitArea: Boolean
        get() = area == CockpitArea.TERMINAL || area == CockpitArea.WORKSPACE

    fun toSaveable(): List<Int> = listOf(area.ordinal, rightTab.ordinal, leftTab.ordinal)

    companion object {
        fun fromSaveable(values: List<Int>): PanelNavState? {
            if (values.size != 3) return null
            return PanelNavState(
                area = CockpitArea.entries.getOrNull(values[0]) ?: return null,
                rightTab = RightTab.entries.getOrNull(values[1]) ?: return null,
                leftTab = LeftTab.entries.getOrNull(values[2]) ?: return null,
            )
        }

        /** Maps the `helix_panel_id` launch extra to a start area. */
        fun areaForPanelId(panelId: String?): CockpitArea = when (panelId) {
            "terminal" -> CockpitArea.TERMINAL
            "workspace" -> CockpitArea.WORKSPACE
            else -> CockpitArea.STREAM
        }

        /** Panel ids offered as Home Space detach actions, paired with their button labels. */
        val DETACHABLE_PANELS: List<Pair<String, String>> = listOf(
            "terminal" to "Terminal",
            "workspace" to "Workspace",
            "stream" to "Stream",
        )

        /**
         * One selected host for every selector: the dashboard selection wins. Returns the id the workspace
         * view-model must be switched to, or null when it already matches (or nothing is selected).
         */
        fun workspaceHostToSync(dashboardSelectedId: Long?, workspaceSelectedId: Long?): Long? =
            dashboardSelectedId?.takeIf { it != workspaceSelectedId }

        /** Snippets run on the active terminal tab's host, falling back to the selected host. */
        fun snippetTargetHostId(activeTabHostId: Long?, selectedHostId: Long?): Long? =
            activeTabHostId ?: selectedHostId
    }
}
