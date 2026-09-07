package com.kashi.grc.ai.prompt;

import com.kashi.grc.ai.guardrail.GuardrailException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{variable}} substitution, with missing variables treated as errors.
 *
 * ── WHY STRICT ───────────────────────────────────────────────────────────────
 * A permissive renderer that substitutes empty string for an absent variable
 * produces prompts like
 *
 *     "Draft an access control policy for  operating in the  sector,
 *      subject to ."
 *
 * The model does not complain. It writes a fluent, generic, entirely useless
 * policy, and it looks like a model quality problem for as long as it takes
 * somebody to print the actual prompt. Failing loudly at render time turns a
 * subtle quality bug into an obvious wiring bug, which is a trade worth making
 * every time.
 *
 * ── OPTIONAL VARIABLES ARE EXPLICIT ──────────────────────────────────────────
 * {{?var}} renders empty when absent. Marking optionality in the template is
 * fine; inferring it from a null is not.
 */
@Component
public class PromptRenderer {

    private static final Pattern VAR = Pattern.compile("\\{\\{(\\??)([a-zA-Z0-9_.]+)}}");

    public String render(String template, Map<String, Object> variables) {
        if (template == null) return null;
        Map<String, Object> vars = variables == null ? Map.of() : variables;

        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();

        while (m.find()) {
            boolean optional = "?".equals(m.group(1));
            String  name     = m.group(2);
            Object  value    = resolve(vars, name);

            if (value == null || (value instanceof String s && s.isBlank())) {
                if (!optional) missing.add(name);
                m.appendReplacement(out, "");
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(stringify(value)));
            }
        }
        m.appendTail(out);

        if (!missing.isEmpty()) {
            throw new GuardrailException("AI_PROMPT_MISSING_VARIABLES",
                    "Prompt variables were not supplied: " + String.join(", ", missing),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("missing", missing));
        }
        return out.toString();
    }

    /** Dotted paths so a nested context map can be addressed as {{org.legalName}}. */
    @SuppressWarnings("unchecked")
    private Object resolve(Map<String, Object> vars, String path) {
        if (!path.contains(".")) return vars.get(path);
        Object cur = vars;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = ((Map<String, Object>) map).get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    /** Lists render as "- item" bullets — the shape models follow most reliably. */
    private String stringify(Object v) {
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) sb.append("- ").append(o).append('\n');
            return sb.toString().trim();
        }
        return String.valueOf(v);
    }

    /** Variables a template references, for the admin editor's validation hint. */
    public List<String> declaredVariables(String template) {
        List<String> found = new ArrayList<>();
        if (template == null) return found;
        Matcher m = VAR.matcher(template);
        while (m.find()) if (!found.contains(m.group(2))) found.add(m.group(2));
        return found;
    }

    /** Convenience builder so call sites read as a fluent context assembly. */
    public static Map<String, Object> vars() { return new LinkedHashMap<>(); }
}
