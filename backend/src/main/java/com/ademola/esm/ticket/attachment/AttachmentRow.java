package com.ademola.esm.ticket.attachment;

/** An attachment with its uploader's name, read in one query. */
record AttachmentRow(Attachment attachment, String uploaderName) {}
