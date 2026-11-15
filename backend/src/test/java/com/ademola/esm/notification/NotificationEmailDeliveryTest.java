package com.ademola.esm.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** The pieces between the outbox and a mail server, which integration tests replace. */
class NotificationEmailDeliveryTest {

    @Test
    @SuppressWarnings("unchecked")
    void smtpGatewaySendsAPlainTextMessage() {
        JavaMailSender sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);

        new SmtpMailGateway(provider).send("Desk <desk@esm.local>", "rita@example.com", "Subject", "Body");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("rita@example.com");
        assertThat(sent.getValue().getFrom()).isEqualTo("Desk <desk@esm.local>");
        assertThat(sent.getValue().getSubject()).isEqualTo("Subject");
        assertThat(sent.getValue().getText()).isEqualTo("Body");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAMailServerSendingFailsSoTheOutboxRecordsIt() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

        assertThatThrownBy(() -> new SmtpMailGateway(provider).send("a", "b", "c", "d"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    void theJobKeepsSendingWhileBatchesComeBackFull() {
        NotificationEmailService emails = mock(NotificationEmailService.class);
        when(emails.sendPendingBatch())
                .thenReturn(NotificationEmailService.BATCH_SIZE, NotificationEmailService.BATCH_SIZE, 3);

        new NotificationEmailJob(emails).sendPending();

        verify(emails, times(3)).sendPendingBatch();
    }

    @Test
    void longMessagesAreShortenedForTheSubject() {
        Notification notification = new Notification(
                new NotificationDraft(UUID.randomUUID(), NotificationKind.COMMENTED, "x".repeat(600)),
                UUID.randomUUID(),
                null,
                Instant.EPOCH,
                true);

        assertThat(notification.getMessage()).hasSize(500).endsWith("…");
        assertThat(NotificationEmailService.subject(notification)).hasSize(120).endsWith("…");
        notification.markRead(Instant.EPOCH.plusSeconds(5));
        notification.markRead(Instant.EPOCH.plusSeconds(9));
        assertThat(notification.getReadAt()).as("the first read counts").isEqualTo(Instant.EPOCH.plusSeconds(5));
    }
}
