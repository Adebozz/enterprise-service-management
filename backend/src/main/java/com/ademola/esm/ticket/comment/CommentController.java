package com.ademola.esm.ticket.comment;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets/{id}/comments")
@Tag(name = "Ticket comments")
class CommentController {

    private final CommentService commentService;

    CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    @Operation(summary = "The ticket's conversation, oldest first. Internal notes only for support staff")
    List<CommentResponse> thread(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return commentService.thread(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a public comment or (support staff) an internal note")
    CommentResponse add(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody AddCommentRequest request) {
        return commentService.add(user, id, request);
    }
}
