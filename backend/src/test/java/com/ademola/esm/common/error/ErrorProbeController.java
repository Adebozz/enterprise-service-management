package com.ademola.esm.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that raise each category of error, so {@link ErrorHandlingIT} can verify the
 * response contract without depending on real business features. Lives in test sources only.
 */
@RestController
@RequestMapping("/test/errors")
class ErrorProbeController {

    record ProbeRequest(
            @NotBlank String title, @Size(max = 5) String code) {}

    static class ProbeNotFound extends ResourceNotFoundException {
        ProbeNotFound() {
            super("Probe", "42");
        }
    }

    @PostMapping("/validation")
    void validation(@Valid @RequestBody ProbeRequest request) {}

    @GetMapping("/not-found")
    void notFound() {
        throw new ProbeNotFound();
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("secret internal detail: jdbc:postgresql://db-host/esm");
    }
}
