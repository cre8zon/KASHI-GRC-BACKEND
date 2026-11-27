package com.kashi.grc.ai.policy;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts American spellings to British in generated policy text.
 *
 * ── WHY THIS IS CODE, NOT A PROMPT LINE ──────────────────────────────────────
 * The organisation profile's spelling setting reaches the model and is only
 * half obeyed: a draft that writes "licence" and "modelling" also writes
 * "analyzes" and "unauthorized". A model's spelling drifts with whatever it is
 * paraphrasing. A deterministic pass after generation cannot drift, costs no
 * tokens and no latency, and makes the setting mean what it says.
 *
 * ── WHAT IT TOUCHES ──────────────────────────────────────────────────────────
 * Text only. HTML tags and attributes, <code>/<pre> content, [[PLACEHOLDERS]]
 * and {{variables}} pass through untouched, so markup, identifiers and the
 * resolver's tokens are never altered.
 *
 * ── WHAT IT CHANGES ──────────────────────────────────────────────────────────
 *   -ize / -ization family → -ise / -isation   (organize → organise)
 *   -yze family            → -yse              (analyze → analyse)
 *   a fixed list of words  → British form      (behavior → behaviour,
 *                                                center → centre, …)
 * Case is preserved: Organize → Organise, ORGANIZE → ORGANISE.
 *
 * ── WHAT IT DELIBERATELY LEAVES ALONE ────────────────────────────────────────
 *   size, prize, seize, capsize, citizen … — words where -ize is not a suffix.
 *   license / program — British uses both forms depending on meaning (licence
 *   the noun, license the verb; programme generally, program for software), so
 *   no rule can choose correctly without reading the sentence.
 *   Proper nouns that happen to match (World Health Organization) — rare in a
 *   policy; listed in KEEP when found.
 */
public final class BritishSpelling {

    private BritishSpelling() {}

    /** Stems where "iz" is part of the word, not the -ize suffix. */
    private static final Set<String> NOT_A_SUFFIX = Set.of(
            "s", "pr", "se", "caps", "ma", "ass", "res", "downs", "ups", "overs", "outs", "gr");

    /**
     * Whole words to keep exactly as written (lower case) — proper nouns that
     * match a rule. Empty today; add to it when one turns up in a draft.
     */
    private static final Set<String> KEEP = Set.of();

    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("behavior", "behaviour"),   Map.entry("behaviors", "behaviours"),
            Map.entry("behavioral", "behavioural"),
            Map.entry("color", "colour"),         Map.entry("colors", "colours"),
            Map.entry("favor", "favour"),         Map.entry("favorable", "favourable"),
            Map.entry("honor", "honour"),         Map.entry("labor", "labour"),
            Map.entry("neighbor", "neighbour"),   Map.entry("rumor", "rumour"),
            Map.entry("center", "centre"),        Map.entry("centers", "centres"),
            Map.entry("centered", "centred"),     Map.entry("datacenter", "datacentre"),
            Map.entry("meter", "metre"),          Map.entry("liter", "litre"),
            Map.entry("defense", "defence"),      Map.entry("defenses", "defences"),
            Map.entry("offense", "offence"),      Map.entry("offenses", "offences"),
            Map.entry("pretense", "pretence"),
            Map.entry("catalog", "catalogue"),    Map.entry("catalogs", "catalogues"),
            Map.entry("dialog", "dialogue"),      Map.entry("analog", "analogue"),
            Map.entry("fulfill", "fulfil"),       Map.entry("fulfills", "fulfils"),
            Map.entry("fulfillment", "fulfilment"),
            Map.entry("enroll", "enrol"),         Map.entry("enrollment", "enrolment"),
            Map.entry("enrolls", "enrols"),
            Map.entry("installment", "instalment"),
            Map.entry("modeling", "modelling"),   Map.entry("modeled", "modelled"),
            Map.entry("labeled", "labelled"),     Map.entry("labeling", "labelling"),
            Map.entry("canceled", "cancelled"),   Map.entry("canceling", "cancelling"),
            Map.entry("traveled", "travelled"),   Map.entry("traveling", "travelling"),
            Map.entry("leveled", "levelled"),     Map.entry("signaled", "signalled"),
            Map.entry("signaling", "signalling"), Map.entry("totaled", "totalled"),
            Map.entry("fueled", "fuelled"),       Map.entry("counseling", "counselling"),
            Map.entry("aging", "ageing"),         Map.entry("gray", "grey"),
            Map.entry("artifact", "artefact"),    Map.entry("artifacts", "artefacts"),
            Map.entry("inquiry", "enquiry"),      Map.entry("inquiries", "enquiries"),
            Map.entry("acknowledgment", "acknowledgement"));

    private static final Pattern TAG    = Pattern.compile("<[^>]*>");
    private static final Pattern TOKEN  = Pattern.compile("\\[\\[[^\\]]*]]|\\{\\{[^}]*}}");
    private static final Pattern WORD   = Pattern.compile("[A-Za-z][A-Za-z']*");
    private static final Pattern IZE    = Pattern.compile(
            "^([A-Za-z]+?)(iz)(e|es|ed|ing|ation|ations|ational|er|ers|able)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YZE    = Pattern.compile(
            "^([A-Za-z]+?)(yz)(e|es|ed|ing|er|ers)$", Pattern.CASE_INSENSITIVE);

    /** HTML in, HTML out; only text between tags is changed. */
    public static String html(String html) {
        if (html == null || html.isEmpty()) return html;
        StringBuilder out = new StringBuilder(html.length());
        Matcher tag = TAG.matcher(html);
        int pos = 0;
        int codeDepth = 0;
        while (tag.find()) {
            String text = html.substring(pos, tag.start());
            out.append(codeDepth > 0 ? text : text(text));
            String t = tag.group().toLowerCase();
            if (t.startsWith("<code") || t.startsWith("<pre")) codeDepth++;
            else if ((t.startsWith("</code") || t.startsWith("</pre")) && codeDepth > 0) codeDepth--;
            out.append(tag.group());
            pos = tag.end();
        }
        String tail = html.substring(pos);
        out.append(codeDepth > 0 ? tail : text(tail));
        return out.toString();
    }

    /** Plain text; [[PLACEHOLDERS]] and {{variables}} are left exactly as they are. */
    public static String text(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        Matcher tok = TOKEN.matcher(s);
        int pos = 0;
        while (tok.find()) {
            out.append(words(s.substring(pos, tok.start())));
            out.append(tok.group());
            pos = tok.end();
        }
        out.append(words(s.substring(pos)));
        return out.toString();
    }

    private static String words(String s) {
        Matcher m = WORD.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int pos = 0;
        while (m.find()) {
            out.append(s, pos, m.start());
            out.append(convert(m.group()));
            pos = m.end();
        }
        out.append(s.substring(pos));
        return out.toString();
    }

    static String convert(String word) {
        String lower = word.toLowerCase();
        if (KEEP.contains(lower)) return word;

        String mapped = WORDS.get(lower);
        if (mapped != null) return matchCase(word, mapped);

        Matcher iz = IZE.matcher(word);
        if (iz.matches() && !NOT_A_SUFFIX.contains(iz.group(1).toLowerCase())) {
            return iz.group(1) + (Character.isUpperCase(iz.group(2).charAt(0)) ? "IS" : "is") + iz.group(3);
        }
        Matcher yz = YZE.matcher(word);
        if (yz.matches()) {
            return yz.group(1) + (Character.isUpperCase(yz.group(2).charAt(0)) ? "YS" : "ys") + yz.group(3);
        }
        return word;
    }

    /** Copy the case pattern of `original` onto `replacement`. */
    private static String matchCase(String original, String replacement) {
        if (original.equals(original.toUpperCase()) && original.length() > 1) return replacement.toUpperCase();
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }
}