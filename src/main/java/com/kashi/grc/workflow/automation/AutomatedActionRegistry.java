package com.kashi.grc.workflow.automation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry of all AutomatedActionHandler beans.
 *
 * Spring injects every @Component that implements AutomatedActionHandler.
 * WorkflowEngineService calls dispatch() when a SYSTEM step fires.
 *
 * ADDING A NEW ACTION:
 *   Just create a new @Component implementing AutomatedActionHandler.
 *   No changes to this class or WorkflowEngineService are needed.
 *
 * STARTUP VALIDATION:
 *   Logs all registered action keys at INFO level. Duplicate keys cause
 *   an IllegalStateException at startup — fail fast over silent override.
 */
@Slf4j
@Service
public class AutomatedActionRegistry {

    private final Map<String, AutomatedActionHandler> handlers;

    public AutomatedActionRegistry(List<AutomatedActionHandler> handlerList) {
        this.handlers = handlerList.stream().collect(
                Collectors.toMap(
                        AutomatedActionHandler::actionKey,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate AutomatedActionHandler key: " + a.actionKey() +
                                            " — " + a.getClass().getSimpleName() + " vs " + b.getClass().getSimpleName());
                        }
                )
        );
        log.info("[AUTOMATION] Registered {} automated action(s): {}",
                handlers.size(), handlers.keySet());
    }

    /**
     * Dispatch to the handler registered for the given action key.
     *
     * @param actionKey  value from workflow_steps.automated_action
     * @param ctx        context built by WorkflowEngineService
     * @return true if the action succeeded (step should auto-approve+advance);
     *         false if it failed (step stays IN_PROGRESS);
     *         empty if no handler is registered for this key (logs a warning)
     */
    public Optional<Boolean> dispatch(String actionKey, AutomatedActionContext ctx) {
        AutomatedActionHandler handler = handlers.get(actionKey);
        if (handler == null) {
            log.warn("[AUTOMATION] No handler registered for action key '{}' on step '{}' — " +
                            "step will stay IN_PROGRESS. Register a @Component implementing " +
                            "AutomatedActionHandler with actionKey() == '{}'",
                    actionKey, ctx.getStep().getName(), actionKey);
            return Optional.empty();
        }
        log.info("[AUTOMATION] Dispatching '{}' | stepName='{}' | instanceId={}",
                actionKey, ctx.getStep().getName(), ctx.getWorkflowInstance().getId());
        boolean result = handler.execute(ctx);
        log.info("[AUTOMATION] '{}' completed | success={} | instanceId={}",
                actionKey, result, ctx.getWorkflowInstance().getId());
        return Optional.of(result);
    }

    /** Returns all registered action keys — used by the admin UI dropdown. */
    public List<String> registeredKeys() {
        return List.copyOf(handlers.keySet());
    }
}