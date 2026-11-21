package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.notifications.DeliveryQueue.Outcome.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.goldman.alerting.control.notifications.DeliveryQueue.Job;
import com.goldman.alerting.control.notifications.DeliveryQueue.Outcome;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class SmtpTransport implements ChannelTransport {
    private final JavaMailSender smtp;
    private final String from;

    SmtpTransport(JavaMailSender smtp, @Value("${notifications.from:alerts@example.test}") String from) {
        this.smtp = smtp;
        this.from = from;
    }

    @Override public String type() { return "smtp"; }

    @Override public void validate(JsonNode config) {
        if (config.size() != 1 || !config.has("recipientField")) throw new IllegalArgumentException("Unexpected SMTP option");
    }

    @Override public Prepared prepare(JsonNode config, Alert alert) { return new Prepared(alert.fullText()); }

    @Override public Outcome send(Job job) {
        try {
            new InternetAddress(job.recipient(), true).validate();
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(job.recipient());
            message.setSubject(job.title().replaceAll("[\\r\\n]", " "));
            message.setText(job.message());
            smtp.send(message);
            return SENT;
        } catch (AddressException | MailParseException | MailAuthenticationException e) {
            return PERMANENT_FAILURE;
        } catch (MailSendException e) {
            for (var failed : e.getMessageExceptions()) {
                for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SMTPAddressFailedException address && address.getReturnCode() >= 500
                            || cause instanceof SMTPSendFailedException send && send.getReturnCode() >= 500) {
                        return PERMANENT_FAILURE;
                    }
                }
            }
            return TRANSIENT_FAILURE;
        } catch (MailException e) {
            return TRANSIENT_FAILURE;
        }
    }
}
