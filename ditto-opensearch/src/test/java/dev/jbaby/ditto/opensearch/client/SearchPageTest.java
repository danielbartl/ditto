package dev.jbaby.ditto.opensearch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import dev.jbaby.ditto.opensearch.OpenSearchAccessException;

class SearchPageTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void readsIdsExactSourcesAndFieldValues() {
        String response = """
                {"_scroll_id":"abc==","took":3,"timed_out":false,"_shards":{"total":1},
                 "hits":{"total":{"value":2,"relation":"eq"},"max_score":null,"hits":[
                   {"_index":"i","_id":"1","_score":null,"_source":{"name" : "ä€",  "n":1.50, "a":[{"x":{}}]},
                    "fields":{"sku":["A-1"],"other":[1]},"sort":[0]},
                   {"_index":"i","_id":"2","_score":null,"_source":{},"fields":{"sku":[5, 6]}},
                   {"_index":"i","_id":"3"}]}}
                """;

        SearchPage page = SearchPage.read(MAPPER, response.getBytes(StandardCharsets.UTF_8), "sku");

        assertThat(page.scrollId()).isEqualTo("abc==");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hits()).hasSize(3);
        assertThat(page.hits().get(0).id()).isEqualTo("1");
        // byte for byte as returned, whitespace and number formats included
        assertThat(page.hits().get(0).source()).hasToString("{\"name\" : \"ä€\",  \"n\":1.50, \"a\":[{\"x\":{}}]}");
        assertThat(page.hits().get(0).values()).containsExactly(new BsonString("A-1"));
        assertThat(page.hits().get(1).source()).hasToString("{}");
        assertThat(page.hits().get(1).values()).containsExactly(new BsonInt32(5), new BsonInt32(6));
        assertThat(page.hits().get(2).source()).isNull();
        assertThat(page.hits().get(2).values()).isEmpty();
    }

    @Test
    void rejectsAPageThatMissesShards() {
        String failed = """
                {"_scroll_id":"abc==","timed_out":false,
                 "_shards":{"total":3,"successful":2,"skipped":0,"failed":1,"failures":[
                   {"shard":1,"index":"products","node":"n1",
                    "reason":{"type":"node_disconnected_exception","reason":"node left"}},
                   {"shard":2,"index":"products","reason":{"type":"other","reason":"later"}}]},
                 "hits":{"hits":[{"_id":"1","_source":{}}]}}
                """;
        String timedOut = """
                {"timed_out":true,"_shards":{"total":1,"successful":1,"failed":0},"hits":{"hits":[]}}
                """;

        assertThatThrownBy(() -> SearchPage.read(MAPPER, failed.getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(OpenSearchAccessException.class)
                .hasMessage("Search is incomplete: 1 of 3 shards failed (index products, shard 1:"
                        + " node_disconnected_exception: node left)");
        assertThatThrownBy(() -> SearchPage.read(MAPPER, timedOut.getBytes(StandardCharsets.UTF_8), null))
                .hasMessage("Search is incomplete: it timed out");
    }

    @Test
    void countsAreCheckedToo() {
        var failed = MAPPER.readTree("""
                {"count":7,"_shards":{"total":2,"successful":1,"skipped":0,"failed":1,
                 "failures":[{"shard":0,"index":"products","reason":{"type":"x","reason":"y"}}]}}
                """);
        var complete = MAPPER.readTree("{\"count\":7,\"_shards\":{\"total\":2,\"successful\":2,\"failed\":0}}");

        assertThatThrownBy(() -> Shards.of(failed).requireComplete("Counting products", false))
                .hasMessage("Counting products is incomplete: 1 of 2 shards failed (index products, shard 0: x: y)");
        Shards.of(complete).requireComplete("Counting products", false);
    }

    @Test
    void slicesCompareByContent() {
        byte[] a = "xx{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] b = "{\"a\":1}yy".getBytes(StandardCharsets.UTF_8);

        assertThat(new Slice(a, 2, 7).sameBytes(new Slice(b, 0, 7))).isTrue();
        assertThat(new Slice(a, 2, 7)).isEqualTo(new Slice(b, 0, 7)).hasSameHashCodeAs(new Slice(b, 0, 7));
        assertThat(new Slice(a, 1, 7).sameBytes(new Slice(b, 0, 7))).isFalse();
    }
}
