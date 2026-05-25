package com.campushub.backend.auth.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class VerificationEmailConfiguration {

    @Bean
    public VerificationEmailSender verificationEmailSender(
        ObjectProvider<JavaMailSender> mailSenderProvider,
        @Value("${spring.mail.username:${app.mail.from:}}") String fromAddress
    ) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            return new LoggingVerificationEmailSender();
        }
        return new SmtpVerificationEmailSender(mailSender, fromAddress);
    }
}
