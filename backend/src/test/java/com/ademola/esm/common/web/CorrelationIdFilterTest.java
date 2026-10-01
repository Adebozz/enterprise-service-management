package com.ademola.esm.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesIdWhenHeaderIsAbsentAndExposesItInMdcDuringRequest() throws Exception {
        AtomicReference<String> idSeenDuringRequest = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (FilterChain)
                (req, res) -> idSeenDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        String responseId = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(responseId).isNotBlank();
        assertThat(idSeenDuringRequest.get()).isEqualTo(responseId);
    }

    @Test
    void reusesWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "client-abc-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-abc-12345");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "short",
                "has spaces in it",
                "inject\nfake-log-line",
                "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            })
    void replacesUnsafeOrMalformedIncomingId(String unsafe) {
        assertThat(CorrelationIdFilter.resolveCorrelationId(unsafe))
                .isNotEqualTo(unsafe)
                .matches("[0-9a-f-]{36}");
    }

    @Test
    void clearsMdcAfterRequestEvenWhenDownstreamThrows() {
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() ->
                        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain))
                .hasMessage("boom");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
