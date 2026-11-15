package com.ademola.esm.notification;

/** Sends one plain-text email. Behind an interface so tests can record what would be sent. */
interface MailGateway {

    void send(String from, String to, String subject, String body);
}
