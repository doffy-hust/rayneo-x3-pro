package com.TapLinkX3.app.routing.validation

import com.TapLinkX3.app.ChatAgent
import com.TapLinkX3.app.routing.model.DashboardCandidate
import com.TapLinkX3.app.routing.model.RouteTarget
import com.TapLinkX3.app.routing.model.RoutingAction
import com.TapLinkX3.app.routing.model.RoutingDecision

data class RoutingValidationResult(
        val accepted: Boolean,
        val sanitized: RoutingDecision,
        val error: String? = null
)

object RoutingDecisionValidator {
    fun validate(
            decision: RoutingDecision,
            agents: List<ChatAgent>,
            dashboards: List<DashboardCandidate>
    ): RoutingValidationResult {
        val clamped = decision.copy(confidence = decision.confidence.coerceIn(0.0, 1.0))
        if (clamped.action == RoutingAction.NAVIGATE_DASHBOARD_DETAIL) {
            val id = clamped.dashboardId.orEmpty()
            if (id.isBlank()) return reject(clamped, "missing_dashboard_id")
            if (dashboards.none { it.dashboardId.equals(id, ignoreCase = true) }) {
                return reject(clamped, "unknown_dashboard_id")
            }
        }
        if (clamped.action == RoutingAction.NAVIGATE_AGENT_DETAIL) {
            val id = clamped.agentId.orEmpty()
            if (id.isBlank()) return reject(clamped, "missing_agent_id")
            if (agents.none { it.id.equals(id, ignoreCase = true) }) {
                return reject(clamped, "unknown_agent_id")
            }
        }
        if (clamped.action == RoutingAction.NO_OP) {
            return RoutingValidationResult(true, clamped.copy(routeTarget = RouteTarget.NONE))
        }
        return RoutingValidationResult(true, clamped.copy(message = clamped.message.trim()))
    }

    private fun reject(decision: RoutingDecision, reason: String): RoutingValidationResult {
        return RoutingValidationResult(
                accepted = false,
                sanitized = decision.copy(
                        action = RoutingAction.NO_OP,
                        routeTarget = RouteTarget.NONE,
                        message = ""
                ),
                error = reason
        )
    }
}
