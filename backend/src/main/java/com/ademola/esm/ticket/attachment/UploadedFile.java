package com.ademola.esm.ticket.attachment;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * A file as accepted from an upload: a safe name, a content type decided by the server, and the
 * bytes. {@link #accept} is the only way to make one, so every stored file has passed its checks.
 *
 * <p>The client's declared content type is ignored. The type comes from the file's extension, which
 * must be on the allow-list, and for binary formats the content has to start the way that format
 * does: a script renamed to {@code .png} is refused. Downloads are always served as attachments
 * with {@code nosniff}, so even an accepted file is never rendered by the browser as a page.
 */
record UploadedFile(String fileName, String contentType, byte[] content, String sha256) {

    /** 10 MB; the same limit as {@code spring.servlet.multipart.max-file-size} and the database. */
    static final int MAX_BYTES = 10 * 1024 * 1024;

    static final int MAX_NAME_LENGTH = 255;

    private record FileType(String contentType, byte[]... signatures) {

        boolean isText() {
            return signatures.length == 0;
        }

        boolean matches(byte[] content) {
            return Arrays.stream(signatures).anyMatch(signature -> startsWith(content, signature));
        }
    }

    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** Extension → type. Nothing a browser would run (HTML, SVG, scripts) is on the list. */
    private static final Map<String, FileType> TYPES = Map.ofEntries(
            Map.entry("png", new FileType("image/png", PNG)),
            Map.entry("jpg", new FileType("image/jpeg", JPEG)),
            Map.entry("jpeg", new FileType("image/jpeg", JPEG)),
            Map.entry("gif", new FileType("image/gif", ascii("GIF87a"), ascii("GIF89a"))),
            Map.entry("webp", new FileType("image/webp", ascii("RIFF"))),
            Map.entry("pdf", new FileType("application/pdf", ascii("%PDF-"))),
            Map.entry("zip", new FileType("application/zip", ZIP)),
            Map.entry(
                    "docx",
                    new FileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ZIP)),
            Map.entry("xlsx", new FileType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ZIP)),
            Map.entry(
                    "pptx",
                    new FileType("application/vnd.openxmlformats-officedocument.presentationml.presentation", ZIP)),
            Map.entry("txt", new FileType("text/plain")),
            Map.entry("log", new FileType("text/plain")),
            Map.entry("csv", new FileType("text/csv")),
            Map.entry("json", new FileType("application/json")));

    /** The extensions accepted, for error messages and the API docs. */
    static final String ACCEPTED = "png, jpg, jpeg, gif, webp, pdf, zip, docx, xlsx, pptx, txt, log, csv, json";

    static UploadedFile accept(String originalName, byte[] content) {
        if (content.length == 0) {
            throw new BusinessRuleException(ErrorCode.ATTACHMENT_EMPTY, "The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessRuleException(ErrorCode.ATTACHMENT_TOO_LARGE, "Files can be up to 10 MB");
        }
        String name = safeName(originalName);
        String extension = extension(name);
        FileType type = TYPES.get(extension);
        if (type == null) {
            throw new BusinessRuleException(
                    ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
                    "Files of this type can't be attached. Accepted: " + ACCEPTED);
        }
        boolean contentFits = type.isText() ? looksLikeText(content) : type.matches(content);
        if (!contentFits || ("webp".equals(extension) && !isWebp(content))) {
            throw new BusinessRuleException(
                    ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
                    "The file's content doesn't match its .%s extension".formatted(extension));
        }
        return new UploadedFile(name, type.contentType(), content, sha256(content));
    }

    /**
     * The last path segment, without control characters, at most 255 characters with its
     * extension kept. Browsers send a bare name, but nothing stops another client sending
     * {@code ../../etc/passwd}.
     */
    static String safeName(String originalName) {
        String name = originalName == null ? "" : originalName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) {
            name = "file";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            String extension = extension(name);
            String suffix = extension.isEmpty() || extension.length() > 10 ? "" : "." + extension;
            name = name.substring(0, MAX_NAME_LENGTH - suffix.length()) + suffix;
        }
        return name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Text files have no signature; a NUL byte near the start means it is really binary. */
    private static boolean looksLikeText(byte[] content) {
        int checked = Math.min(content.length, 8192);
        for (int i = 0; i < checked; i++) {
            if (content[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWebp(byte[] content) {
        return content.length >= 12 && Arrays.equals(content, 8, 12, ascii("WEBP"), 0, 4);
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
