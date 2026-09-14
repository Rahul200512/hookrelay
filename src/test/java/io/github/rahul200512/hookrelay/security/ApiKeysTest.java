package io.github.rahul200512.hookrelay.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeysTest {

    @Test
    void generatedKeysHashDeterministicallyAndCarryAPrefix() {
        var key = ApiKeys.generate();
        assertThat(key.raw()).startsWith("hr_live_").hasSizeGreaterThan(40);
        assertThat(key.hash()).isEqualTo(ApiKeys.hash(key.raw())).hasSize(64);
        assertThat(key.prefix()).isEqualTo(key.raw().substring(0, 14));
        assertThat(ApiKeys.generate().raw()).isNotEqualTo(key.raw());
    }
}
