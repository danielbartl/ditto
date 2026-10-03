package dev.jbaby.ditto.comparator.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class JsonShapeTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void sealedModeSerializesWithTypeDiscriminator() {
        assertThat(mapper.writeValueAsString(ComparisonMode.full())).isEqualTo("{\"type\":\"FULL\"}");
        assertThat(mapper.writeValueAsString(ComparisonMode.sample(10))).isEqualTo("{\"type\":\"SAMPLE\",\"size\":10}");
        assertThat(mapper.readValue("{\"type\":\"SAMPLE\",\"size\":10}", ComparisonMode.class))
                .isEqualTo(ComparisonMode.sample(10));
    }

    @Test
    void ratesRoundTrip() {
        Rate exact = Rate.exact(1, 4);
        Rate estimate = new Rate.Estimate(0.5, 0.4, 0.6, 100);

        assertThat(mapper.writeValueAsString(exact)).isEqualTo("{\"kind\":\"exact\",\"count\":1,\"total\":4,\"value\":0.25}");
        assertThat(mapper.readValue(mapper.writeValueAsString(exact), Rate.class)).isEqualTo(exact);
        assertThat(mapper.readValue(mapper.writeValueAsString(estimate), Rate.class)).isEqualTo(estimate);
    }
}
