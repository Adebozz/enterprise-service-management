package com.ademola.esm.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReferenceNumberGeneratorTest {

    @Test
    void zeroPadsToSixDigitsWithTypePrefix() {
        assertThat(ReferenceNumberGenerator.format(WorkItemType.INCIDENT, 1)).isEqualTo("INC-000001");
        assertThat(ReferenceNumberGenerator.format(WorkItemType.SERVICE_REQUEST, 42))
                .isEqualTo("REQ-000042");
    }

    @Test
    void growsBeyondSixDigitsInsteadOfWrapping() {
        assertThat(ReferenceNumberGenerator.format(WorkItemType.INCIDENT, 1_234_567))
                .isEqualTo("INC-1234567");
    }
}
