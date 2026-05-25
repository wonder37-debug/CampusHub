package com.campushub.backend.auth.service;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpVerificationEmailSender implements VerificationEmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpVerificationEmailSender(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress == null ? "" : fromAddress.trim();
    }

    @Override
    public void sendRegistrationCode(String email, String verificationCode, long expiresInSeconds) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!fromAddress.isBlank()) {
                message.setFrom(fromAddress);
            }
            message.setTo(email);
            message.setSubject("CampusHub 注册验证码");
            message.setText(buildBody(verificationCode, expiresInSeconds));
            mailSender.send(message);
        } catch (MailException exception) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "failed to send verification email");
        }
    }

    private String buildBody(String verificationCode, long expiresInSeconds) {
        long expiresInMinutes = Math.max(1L, expiresInSeconds / 60L);
        return """
            您正在注册 CampusHub。

            本次验证码为：%s
            有效期：%d 分钟

            如果这不是您的操作，请忽略此邮件。
            """.formatted(verificationCode, expiresInMinutes);
    }
}
