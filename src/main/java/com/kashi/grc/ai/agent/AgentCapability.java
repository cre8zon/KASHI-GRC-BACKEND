package com.kashi.grc.ai.agent;

import java.util.List;

/**
 * Something KashiGuard AI can DO for the user (as opposed to answer), listed
 * in one place so every surface offers the same set:
 *
 *   - the AI page's "Things I can do" chips (a few, most relevant first)
 *   - the "/" menu in the message box (all of them)
 *   - the side panel, where an action relevant to the page on screen comes first
 *
 * Each entry is offered only to users who could complete it: they must have
 * the register in their own navigation ({@code source}, an AgentSource key)
 * and hold {@code permission}. A hint that leads to a refusal is worse than
 * no hint.
 *
 * Adding an action:
 *   1. add an entry to ALL below;
 *   2. detect it in AgentService (as draftPolicyAction does) and return an
 *      AgentAction with the same {@code type};
 *   3. handle that type's card in the frontend (AgentChat → ActionCard).
 * The chips and the "/" menu pick it up with no further change.
 *
 * Fields:
 *   key         stable id, also used by the frontend for icons and ordering
 *   type        the AgentAction type the answer's card carries
 *   label       short name — "Draft a policy"
 *   description one line under the label in the menu
 *   template    what selecting it puts in the message box; [square brackets]
 *               mark blanks the user fills in (Tab moves between them)
 *   slash       the "/" shortcut word — "/policy"
 *   icon        lucide icon name
 *   source      AgentSource key the user must have in their navigation
 *   permission  permission code required to complete it
 *   routes      pages where it is most relevant (route prefixes); on those it
 *               is offered first
 */
public record AgentCapability(String key, String type, String label, String description,
                              String template, String slash, String icon,
                              String source, String permission, List<String> routes) {

    public static final List<AgentCapability> ALL = List.of(
            new AgentCapability(
                    "draft_policy", "CREATE_POLICY",
                    "Draft a policy",
                    "Write a new policy for your organisation, aligned to your frameworks",
                    "Draft a [policy name] policy for [framework]",
                    "policy", "FilePlus2",
                    "POLICY", "AUDIT_CREATE",
                    List.of("/module/audit_policy", "/audit/policies"))
    );
}