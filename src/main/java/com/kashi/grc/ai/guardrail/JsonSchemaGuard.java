package com.kashi.grc.ai.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a model response against a minimal schema, with no new dependency.
 *
 * ── WHY A SUBSET AND NOT REAL JSON SCHEMA ────────────────────────────────────
 * Full JSON Schema means another library in the tree, and your GRC customers
 * read that tree. What is actually needed to keep a structured generation
 * trustworthy is small: are the required keys present, are they the right type,
 * are arrays non-empty where emptiness would be meaningless, are enum values
 * from the allowed set. That subset is a hundred lines of Jackson and no
 * supply-chain conversation.
 *
 * Schema format stored in ai_prompt_templates.response_schema:
 * {
 *   "type": "object",
 *   "required": ["title", "sections"],
 *   "properties": {
 *     "title":      { "type": "string",  "minLength": 3 },
 *     "sections":   { "type": "array",   "minItems": 1, "items": { "type": "object",
 *                       "required": ["heading","body"] } },
 *     "confidence": { "type": "number",  "minimum": 0, "maximum": 1 },
 *     "status":     { "type": "string",  "enum": ["DRAFT","REVIEW"] }
 *   }
 * }
 *
 * ── EXTRACTION BEFORE VALIDATION ─────────────────────────────────────────────
 * Models wrap JSON in prose and fences no matter how firmly you ask them not to.
 * extractJson() strips that first, because failing a response for its packaging
 * when the payload was correct wastes a repair round-trip and the customer's
 * money.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonSchemaGuard {

    private final ObjectMapper mapper;

    public record ValidationResult(boolean valid, List<String> errors, JsonNode parsed) {
        public String errorSummary() { return String.join("; ", errors); }
    }

    /** Pull the JSON payload out of whatever the model wrapped it in. */
    public String extractJson(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end   = s.lastIndexOf("```");
            if (start > 0 && end > start) s = s.substring(start + 1, end).trim();
        }

        // Outermost braces/brackets — drops any surviving preamble or sign-off.
        int objStart = s.indexOf('{'), objEnd = s.lastIndexOf('}');
        int arrStart = s.indexOf('['), arrEnd = s.lastIndexOf(']');
        boolean objectFirst = objStart >= 0 && (arrStart < 0 || objStart < arrStart);

        if (objectFirst && objEnd > objStart)      return s.substring(objStart, objEnd + 1);
        if (!objectFirst && arrEnd > arrStart)     return s.substring(arrStart, arrEnd + 1);
        return s;
    }

    public ValidationResult validate(String rawResponse, String schemaJson) {
        List<String> errors = new ArrayList<>();
        JsonNode parsed;

        try {
            parsed = mapper.readTree(extractJson(rawResponse));
        } catch (Exception e) {
            return new ValidationResult(false, List.of("response is not valid JSON: " + e.getMessage()), null);
        }

        if (schemaJson == null || schemaJson.isBlank()) return new ValidationResult(true, List.of(), parsed);

        try {
            validateNode(parsed, mapper.readTree(schemaJson), "$", errors);
        } catch (Exception e) {
            log.warn("[AI-GUARD] schema itself is unparseable, skipping validation: {}", e.getMessage());
            return new ValidationResult(true, List.of(), parsed);
        }
        return new ValidationResult(errors.isEmpty(), errors, parsed);
    }

    private void validateNode(JsonNode node, JsonNode schema, String path, List<String> errors) {
        String type = schema.path("type").asText(null);

        if (type != null && !typeMatches(node, type)) {
            errors.add(path + " expected " + type + " but was " + node.getNodeType().toString().toLowerCase());
            return;   // type is wrong; deeper checks would only add noise
        }

        for (JsonNode req : schema.path("required")) {
            String key = req.asText();
            if (!node.has(key) || node.get(key).isNull()) errors.add(path + "." + key + " is required but missing");
        }

        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(e -> {
                if (node.has(e.getKey())) validateNode(node.get(e.getKey()), e.getValue(), path + "." + e.getKey(), errors);
            });
        }

        if (node.isArray()) {
            int min = schema.path("minItems").asInt(0);
            if (node.size() < min) errors.add(path + " needs at least " + min + " item(s), had " + node.size());
            JsonNode itemSchema = schema.path("items");
            if (itemSchema.isObject()) {
                for (int i = 0; i < node.size(); i++) validateNode(node.get(i), itemSchema, path + "[" + i + "]", errors);
            }
        }

        if (node.isTextual()) {
            int min = schema.path("minLength").asInt(0);
            if (node.asText().length() < min) errors.add(path + " must be at least " + min + " characters");
            JsonNode enumNode = schema.path("enum");
            if (enumNode.isArray()) {
                boolean ok = false;
                for (JsonNode allowed : enumNode) if (allowed.asText().equals(node.asText())) { ok = true; break; }
                if (!ok) errors.add(path + " value '" + node.asText() + "' is not one of the allowed values");
            }
        }

        if (node.isNumber()) {
            if (schema.has("minimum") && node.asDouble() < schema.get("minimum").asDouble())
                errors.add(path + " below minimum " + schema.get("minimum").asDouble());
            if (schema.has("maximum") && node.asDouble() > schema.get("maximum").asDouble())
                errors.add(path + " above maximum " + schema.get("maximum").asDouble());
        }
    }

    private boolean typeMatches(JsonNode node, String type) {
        return switch (type) {
            case "object"  -> node.isObject();
            case "array"   -> node.isArray();
            case "string"  -> node.isTextual();
            case "number"  -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            default -> true;
        };
    }

    /** Instruction appended to a repair round-trip. Naming the errors doubles the fix rate. */
    public String buildRepairInstruction(List<String> errors, String schemaJson) {
        return """
               Your previous response failed validation with these errors:
               %s

               Return ONLY corrected JSON matching this schema. No commentary, no code fences.
               %s
               """.formatted("- " + String.join("\n- ", errors), schemaJson == null ? "" : schemaJson);
    }
}
