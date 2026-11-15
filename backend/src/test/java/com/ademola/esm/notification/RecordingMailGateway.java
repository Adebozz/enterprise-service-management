package com.ademola.esm.notification;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;

/** Replaces SMTP in integration tests: keeps what would have been sent, and can be told to fail. */
@Primary
public class RecordingMailGateway implements MailGateway {

    public record SentEmail(String from, String to, String subject, String body) {}

    private final List<SentEmail> sent = new ArrayList<>();
    private boolean failing;

    @Override
    public synchronized void send(String from, String to, String subject, String body) {
        if (failing) {
            throw new IllegalStateException("mail server unavailable");
        }
        sent.add(new SentEmail(from, to, subject, body));
    }

    public synchronized List<SentEmail> sent() {
        return List.copyOf(sent);
    }

    public synchronized void failing(boolean failing) {
        this.failing = failing;
    }

    public synchronized void reset() {
        sent.clear();
        failing = false;
    }
}
