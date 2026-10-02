package dev.jbaby.ditto.cli.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.cli.CliUsageException;

class ChangeSpecTest {

    @Test
    void parsesFieldAndDocumentChanges() {
        assertThat(ChangeSpec.parseAll(" modify:price:0.05, drop-field:meta.version:1 ,shuffle-arrays:0.5,add-docs:0.02"))
                .containsExactly(
                        new ChangeSpec(ChangeSpec.Kind.MODIFY, "price", 0.05),
                        new ChangeSpec(ChangeSpec.Kind.DROP_FIELD, "meta.version", 1.0),
                        new ChangeSpec(ChangeSpec.Kind.SHUFFLE_ARRAYS, null, 0.5),
                        new ChangeSpec(ChangeSpec.Kind.ADD_DOCS, null, 0.02));
        assertThat(ChangeSpec.parseAll("")).isEmpty();
        assertThat(ChangeSpec.parse("int-to-double:qty:1")).hasToString("int-to-double:qty:1.0");
    }

    @Test
    void rejectsMalformedSpecs() {
        assertThatThrownBy(() -> ChangeSpec.parse("explode:x:1")).isInstanceOf(CliUsageException.class)
                .hasMessageContaining("Unknown change 'explode'");
        assertThatThrownBy(() -> ChangeSpec.parse("modify:0.5")).hasMessageContaining("modify:<path>:<fraction>");
        assertThatThrownBy(() -> ChangeSpec.parse("delete-docs:x:0.5")).hasMessageContaining("delete-docs:<fraction>");
        assertThatThrownBy(() -> ChangeSpec.parse("modify:a:1.5")).hasMessageContaining("between 0 and 1");
        assertThatThrownBy(() -> ChangeSpec.parse("modify:a:abc")).hasMessageContaining("must be a number");
    }
}
