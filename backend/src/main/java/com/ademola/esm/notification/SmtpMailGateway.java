package com.ademola.esm.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends through Spring's {@link JavaMailSender}, which Spring Boot creates once {@code
 * spring.mail.host} is set. Without it, sending fails, and the outbox records the failure.
 */
@Component
class SmtpMailGateway implements MailGateway {

    private final ObjectProvider<JavaMailSender> sender;

    SmtpMailGateway(ObjectProvider<JavaMailSender> sender) {
        this.sender = sender;
    }

    @Override
    public void send(String from, String to, String subject, String body) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            throw new IllegalStateException("Email is enabled but no mail server is configured (spring.mail.host)");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mail.send(message);
    }
}
