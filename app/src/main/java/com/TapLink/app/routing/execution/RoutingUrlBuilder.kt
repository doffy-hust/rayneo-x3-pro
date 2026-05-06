package com.TapLinkX3.app.routing.execution

import android.net.Uri

object RoutingUrlBuilder {
    fun buildDashboardListUrl(origin: String): String = "$origin/cognition/dashboards"

    fun buildDashboardDetailUrl(origin: String, libraryId: String, dashboardId: String): String? {
        val lid = libraryId.trim()
        val did = dashboardId.trim()
        if (lid.isBlank() || did.isBlank()) return null
        return "$origin/cognition/libraries/${Uri.encode(lid)}/dashboards/${Uri.encode(did)}"
    }

    fun buildAgentListUrl(origin: String): String = "$origin/assistant/agents"

    fun buildAgentDetailUrl(origin: String, agentId: String): String? {
        val aid = agentId.trim()
        if (aid.isBlank()) return null
        return "$origin/assistant/agents/${Uri.encode(aid)}"
    }
}
