package com.iiot.alert;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "alerts.enabled", havingValue = "true", matchIfMissing = true)
public class AlertConfiguration {
    @Bean
    ThreadPoolTaskScheduler alertScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); // SMTP must not delay detection or the simulator.
        scheduler.setThreadNamePrefix("anomaly-alert-");
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = "alerts.gmail.enabled", havingValue = "true")
    JavaMailSender gmailAlertMailSender(@Value("${alerts.gmail.username:}") String username,
            @Value("${alerts.gmail.app-password:}") String password,
            @Value("${alerts.gmail.to:}") String recipient) {
        validateAddress(username);
        validateAddress(recipient);
        if (password.isBlank()) throw new IllegalArgumentException("GMAIL_APP_PASSWORD is required for Gmail alerts");
        var sender = new JavaMailSenderImpl();
        sender.setHost("smtp.gmail.com");
        sender.setPort(587);
        sender.setUsername(username);
        sender.setPassword(password.replace(" ", ""));
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "true");
        properties.setProperty("mail.smtp.starttls.required", "true");
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        return sender;
    }

    private static void validateAddress(String value) {
        try {
            if (value.isBlank() || value.contains("\r") || value.contains("\n")) throw new IllegalArgumentException();
            var addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1) throw new IllegalArgumentException();
            addresses[0].validate();
        }
        catch (Exception ignored) {
            throw new IllegalArgumentException("GMAIL_USERNAME and ALERT_EMAIL_TO must each contain one valid email address");
        }
    }
}
