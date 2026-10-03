package com.ademola.esm.ticket.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddCommentRequest(
        @NotBlank @Size(max = 10_000) String body, @NotNull CommentVisibility visibility) {}
