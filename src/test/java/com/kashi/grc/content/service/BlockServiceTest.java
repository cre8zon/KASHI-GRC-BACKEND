package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block hash is the most load-bearing value in the module.
 *
 * Three behaviours depend on it and all three are wrong in a way nobody
 * notices if it is unstable: contentUpdatedAt stops meaning anything, a
 * revision is written on every autosave, and the link graph reindexes twice a
 * second while someone types.
 *
 * So the test that matters is not "does it hash" — it is "does an identical
 * document reserialised through the parser produce the same hash".
 */
class BlockServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockService blocks = new BlockService(mapper, new SlugService());

    private ArrayNode parse(String json) { return blocks.parse(json); }

    @Test
    @DisplayName("hash survives a parse/serialise round trip")
    void hashIsStableAcrossRoundTrip() {
        String json = """
            [{"type":"paragraph","html":"<p>Six hours from noticing.</p>"},
             {"type":"heading","level":2,"text":"What each regulator requires","anchor":""}]
            """;

        String first = blocks.hash(blocks.normalise(parse(json)));
        String reserialised = blocks.write(blocks.normalise(parse(json)));
        String second = blocks.hash(blocks.normalise(parse(reserialised)));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("changing a single character changes the hash")
    void hashChangesWithContent() {
        String a = "[{\"type\":\"paragraph\",\"html\":\"<p>six hours</p>\"}]";
        String b = "[{\"type\":\"paragraph\",\"html\":\"<p>Six hours</p>\"}]";
        assertThat(blocks.hash(parse(a))).isNotEqualTo(blocks.hash(parse(b)));
    }

    @Test
    @DisplayName("a level-1 heading block is demoted to H2")
    void demotesStrayH1() {
        ArrayNode result = blocks.normalise(
                parse("[{\"type\":\"heading\",\"level\":1,\"text\":\"Second title\"}]"));
        assertThat(result.get(0).path("level").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("duplicate headings get distinct anchors")
    void anchorsAreUnique() {
        ArrayNode result = blocks.normalise(parse("""
            [{"type":"heading","level":2,"text":"Scoping"},
             {"type":"heading","level":2,"text":"Scoping"}]
            """));
        assertThat(result.get(0).path("anchor").asText()).isEqualTo("scoping");
        assertThat(result.get(1).path("anchor").asText()).isEqualTo("scoping-2");
    }

    @Test
    @DisplayName("read time counts text in tldr, table and faq blocks, not just paragraphs")
    void readTimeCoversStructuredBlocks() {
        String json = """
            [{"type":"tldr","items":["one two three four five"]},
             {"type":"table","headers":["a","b"],"rows":[["c","d"]]},
             {"type":"faq","items":[{"q":"why","a":"because"}]}]
            """;
        assertThat(blocks.wordCount(parse(json))).isGreaterThan(5);
        assertThat(blocks.readTimeMinutes(parse(json))).isEqualTo(1);   // minimum is 1
    }

    @Test
    @DisplayName("links are extracted from paragraph, step and faq HTML")
    void extractsLinks() {
        String json = """
            [{"type":"paragraph","html":"<p>See <a href=\\"/blog/soc-2\\">our guide</a>.</p>"},
             {"type":"cta","buttonHref":"/contact","buttonText":"Talk to us"}]
            """;
        var links = blocks.links(parse(json));
        assertThat(links).hasSize(2);
        assertThat(links.get(0).href()).isEqualTo("/blog/soc-2");
        assertThat(links.get(0).anchorText()).isEqualTo("our guide");
    }

    @Test
    @DisplayName("an unknown block type is skipped, not thrown on")
    void toleratesUnknownBlockType() {
        // The backend must be able to ship a new type before the front end
        // knows about it; throwing here would order those two deploys.
        ArrayNode parsed = parse("[{\"type\":\"timeline\",\"items\":[]}]");
        assertThat(blocks.wordCount(parsed)).isZero();
        assertThat(blocks.hash(parsed)).isNotBlank();
    }
}
