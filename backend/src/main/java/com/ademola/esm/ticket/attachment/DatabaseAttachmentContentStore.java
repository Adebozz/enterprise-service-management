package com.ademola.esm.ticket.attachment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps attachment bytes in the {@code attachment_contents} table. The file and its description
 * commit or roll back together, so there are never orphaned files or rows pointing at nothing.
 * Plain JDBC: there is nothing to map, and the bytes are only ever read one file at a time.
 */
@Component
class DatabaseAttachmentContentStore implements AttachmentContentStore {

    private final JdbcTemplate jdbc;

    DatabaseAttachmentContentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(UUID attachmentId, byte[] content) {
        jdbc.update("insert into attachment_contents (attachment_id, content) values (?, ?)", attachmentId, content);
    }

    @Override
    public Optional<byte[]> get(UUID attachmentId) {
        return jdbc
                .query(
                        "select content from attachment_contents where attachment_id = ?",
                        (rs, row) -> rs.getBytes(1),
                        attachmentId)
                .stream()
                .findFirst();
    }

    @Override
    public void delete(UUID attachmentId) {
        jdbc.update("delete from attachment_contents where attachment_id = ?", attachmentId);
    }
}
