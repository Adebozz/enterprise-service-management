package com.ademola.esm.ticket.attachment;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.comment.CommentVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tickets/{id}/attachments")
@Tag(name = "Ticket attachments")
class AttachmentController {

    private final AttachmentService attachmentService;

    AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping
    @Operation(
            operationId = "listAttachments",
            summary = "The ticket's files, oldest first. Internal files only for support staff handling it")
    List<AttachmentResponse> list(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return attachmentService.list(user, id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            operationId = "addAttachment",
            summary = "Attach a file (up to 10 MB; " + UploadedFile.ACCEPTED + "). Internal: support staff only")
    AttachmentResponse add(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "PUBLIC") CommentVisibility visibility)
            throws IOException {
        return attachmentService.add(user, id, visibility, file.getOriginalFilename(), file.getBytes());
    }

    /**
     * Always a download, never displayed inline: with {@code nosniff} (set for every response), a
     * browser won't treat an uploaded file as a page of this site.
     */
    @GetMapping("/{attachmentId}/content")
    @Operation(
            operationId = "downloadAttachment",
            summary = "Download a file",
            responses =
                    @ApiResponse(
                            responseCode = "200",
                            description = "The file",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                            schema = @Schema(type = "string", format = "binary"))))
    ResponseEntity<byte[]> download(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID id, @PathVariable UUID attachmentId) {
        AttachmentService.Download download = attachmentService.download(user, id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .cacheControl(CacheControl.noStore())
                .body(download.content());
    }

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteAttachment", summary = "Remove a file (whoever added it, or an admin)")
    void delete(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id, @PathVariable UUID attachmentId) {
        attachmentService.delete(user, id, attachmentId);
    }
}
