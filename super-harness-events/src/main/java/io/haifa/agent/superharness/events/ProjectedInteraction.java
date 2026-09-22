package io.haifa.agent.superharness.events;

import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionState;

public record ProjectedInteraction(
        String requestId, InteractionKind kind, InteractionState state, String actionOrReason) {}
