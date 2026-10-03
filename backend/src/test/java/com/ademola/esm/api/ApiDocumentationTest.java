package com.ademola.esm.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.common.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps the human-written docs/api.md in step with the code and the committed contract. */
class ApiDocumentationTest {

    static final Path API_DOC = Path.of("..", "docs", "api.md");
    static final Path CONTRACT = Path.of("..", "docs", "openapi.json");

    @Test
    void everyErrorCodeIsDocumented() throws IOException {
        String doc = Files.readString(API_DOC);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(doc).as("docs/api.md documents %s", code).contains("`" + code.name() + "`");
        }
    }

    @Test
    void everyOperationInTheContractIsListed() throws IOException {
        String doc = Files.readString(API_DOC);
        List<String> operationIds = new ArrayList<>();
        Matcher m = Pattern.compile("\"operationId\"\\s*:\\s*\"(\\w+)\"").matcher(Files.readString(CONTRACT));
        while (m.find()) {
            operationIds.add(m.group(1));
        }

        assertThat(operationIds).isNotEmpty();
        for (String id : operationIds) {
            assertThat(doc).as("docs/api.md lists operation %s", id).contains("`" + id + "`");
        }
    }
}
