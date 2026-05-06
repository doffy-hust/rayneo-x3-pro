package com.TapLinkX3.app.routing.model

data class DashboardCandidate(
        val dashboardId: String,
        val libraryId: String,
        val title: String
)

enum class RoutingAction {
    NAVIGATE_DASHBOARD_LIST,
    NAVIGATE_DASHBOARD_DETAIL,
    NAVIGATE_AGENT_LIST,
    NAVIGATE_AGENT_DETAIL,
    NAVIGATE_CONVERSATION_NEW,
    CHAT_IN_CURRENT_CONVERSATION,
    CHAT_WITH_AGENT_SWITCH,
    DICTATE_TEXT,
    NO_OP
}

enum class RouteTarget {
    DASHBOARD_LIST,
    DASHBOARD_DETAIL,
    AGENT_LIST,
    AGENT_DETAIL,
    CONVERSATION_NEW,
    NONE
}

data class RoutingDecision(
        val action: RoutingAction,
        val routeTarget: RouteTarget,
        val dashboardId: String?,
        val agentId: String?,
        val message: String,
        val confidence: Double,
        val reasonCode: String
)

data class RoutingContext(
        val transcript: String,
        val normalizedTranscript: String,
        val locale: String,
        val currentUrl: String,
        val isInConversation: Boolean,
        val currentConversationId: String?,
        val agents: List<Map<String, String?>>,
        val dashboards: List<DashboardCandidate>
)
