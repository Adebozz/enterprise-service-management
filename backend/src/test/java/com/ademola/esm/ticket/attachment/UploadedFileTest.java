package com.ademola.esm.ticket.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UploadedFileTest {

    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @Test
    void theServerDecidesTheContentTypeFromTheExtensionAndHashesTheContent() {
        UploadedFile file = UploadedFile.accept("Screenshot.PNG", PNG);

        assertThat(file.contentType()).isEqualTo("image/png");
        assertThat(file.fileName()).isEqualTo("Screenshot.PNG");
        assertThat(file.sha256()).hasSize(64).matches("[0-9a-f]+");
        assertThat(UploadedFile.accept("vpn.log", text("connect failed")).contentType())
                .isEqualTo("text/plain");
    }

    @Test
    void binaryFormatsMustStartTheWayTheirFormatDoes() {
        assertThatThrownBy(() -> UploadedFile.accept("invoice.pdf", text("<script>alert(1)</script>")))
                .isInstanceOfSatisfying(
                        BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED))
                .hasMessageContaining(".pdf");
        assertThat(UploadedFile.accept("invoice.pdf", text("%PDF-1.7 ...")).contentType())
                .isEqualTo("application/pdf");
        assertThat(UploadedFile.accept("report.docx", new byte[] {0x50, 0x4B, 0x03, 0x04, 9})
                        .contentType())
                .startsWith("application/vnd.openxmlformats");
    }

    @Test
    void webpNeedsBothItsRiffHeaderAndItsWebpMarker() {
        assertThatThrownBy(() -> UploadedFile.accept("a.webp", text("RIFF0000WAVEfmt ")))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(UploadedFile.accept("a.webp", text("RIFF0000WEBPVP8 ")).contentType())
                .isEqualTo("image/webp");
    }

    @Test
    void textFilesWithNulBytesAreReallyBinary() {
        assertThatThrownBy(() -> UploadedFile.accept("notes.txt", new byte[] {'h', 'i', 0, 1}))
                .isInstanceOf(BusinessRuleException.class);
    }

    @ParameterizedTest
    @CsvSource({"page.html", "drawing.svg", "run.sh", "setup.exe", "noextension", "archive.tar.gz"})
    void typesOffTheAllowListAreRefused(String name) {
        assertThatThrownBy(() -> UploadedFile.accept(name, text("hello")))
                .isInstanceOfSatisfying(
                        BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED));
    }

    @Test
    void emptyAndOversizedFilesAreRefused() {
        assertThatThrownBy(() -> UploadedFile.accept("a.txt", new byte[0]))
                .isInstanceOfSatisfying(
                        BusinessRuleException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ATTACHMENT_EMPTY));
        byte[] tooBig = new byte[UploadedFile.MAX_BYTES + 1];
        assertThatThrownBy(() -> UploadedFile.accept("a.txt", tooBig))
                .isInstanceOfSatisfying(
                        BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ATTACHMENT_TOO_LARGE));
    }

    @ParameterizedTest
    @CsvSource(
            delimiterString = "->",
            value = {
                "../../etc/passwd.txt -> passwd.txt",
                "C:\\Users\\me\\error.log -> error.log",
                "'  spaced.txt  ' -> spaced.txt",
                ".. -> file",
                "'' -> file"
            })
    void namesLoseAnyPath(String original, String expected) {
        assertThat(UploadedFile.safeName(original)).isEqualTo(expected);
    }

    @Test
    void controlCharactersAreDroppedAndLongNamesKeepTheirExtension() {
        assertThat(UploadedFile.safeName("bad\r\nname.txt")).isEqualTo("badname.txt");
        assertThat(UploadedFile.safeName(null)).isEqualTo("file");

        String shortened = UploadedFile.safeName("x".repeat(300) + ".pdf");
        assertThat(shortened).hasSize(255).endsWith(".pdf");
        assertThat(UploadedFile.safeName("y".repeat(300))).hasSize(255);
        assertThat(UploadedFile.safeName("z".repeat(250) + "." + "e".repeat(20)))
                .hasSize(255);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
