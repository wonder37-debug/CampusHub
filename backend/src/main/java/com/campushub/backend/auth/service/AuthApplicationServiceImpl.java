package com.campushub.backend.auth.service;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.auth.dto.LoginCommand;
import com.campushub.backend.auth.dto.LoginResult;
import com.campushub.backend.auth.dto.RegisterCommand;
import com.campushub.backend.auth.dto.UpdateProfileCommand;
import com.campushub.backend.auth.dto.UserProfileResponse;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.security.TokenPayload;
import com.campushub.backend.common.security.TokenService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthApplicationServiceImpl implements AuthApplicationService {

    private static final int DEFAULT_CREDIT_SCORE = 100;
    private static final long TOKEN_EXPIRES_IN_SECONDS = 3600L;

    private final UserRepository userRepository;
    private final VerificationCodeService verificationCodeService;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthApplicationServiceImpl(
        UserRepository userRepository,
        VerificationCodeService verificationCodeService,
        TokenService tokenService
    ) {
        this.userRepository = userRepository;
        this.verificationCodeService = verificationCodeService;
        this.tokenService = tokenService;
    }

    @Override
    public EmailVerificationIssue sendRegistrationCode(String email, String studentId) {
        validateEmail(email);
        if (!isBlank(studentId) && userRepository.findByStudentId(studentId.trim()).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "studentId already registered");
        }
        if (userRepository.findByEmail(email.trim()).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "email already registered");
        }
        return verificationCodeService.issueCode(email.trim(), emptyToNull(studentId));
    }

    @Override
    public UserProfileResponse register(RegisterCommand command) {
        validateRegisterCommand(command);

        if (!verificationCodeService.verify(command.email(), command.verificationCode())) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "verification code is invalid");
        }
        if (userRepository.findByStudentId(command.studentId()).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "studentId already registered");
        }
        if (userRepository.findByEmail(command.email()).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "email already registered");
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User(
            null,
            command.email().trim(),
            command.studentId().trim(),
            passwordEncoder.encode(command.password()),
            resolveNickname(command.nickname()),
            emptyToNull(command.avatarUrl()),
            UserRole.USER,
            UserStatus.ACTIVE,
            DEFAULT_CREDIT_SCORE,
            now,
            now
        );

        return UserProfileResponse.from(userRepository.save(user));
    }

    @Override
    public LoginResult login(LoginCommand command) {
        validateLoginCommand(command);

        User user = userRepository.findByLoginId(command.loginId().trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_FAILED, "loginId or password is incorrect"));

        if (user.getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "user has been banned");
        }
        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "loginId or password is incorrect");
        }

        Instant expiresAt = Instant.now().plusSeconds(TOKEN_EXPIRES_IN_SECONDS);
        String token = tokenService.generateToken(new TokenPayload(user.getId(), user.getRole(), expiresAt));
        return new LoginResult(token, TOKEN_EXPIRES_IN_SECONDS, UserProfileResponse.from(user));
    }

    @Override
    public UserProfileResponse getProfile(Long userId) {
        User user = findUserById(userId);
        return UserProfileResponse.from(user);
    }

    @Override
    public UserProfileResponse updateProfile(Long operatorId, Long targetUserId, UpdateProfileCommand command) {
        if (!Objects.equals(operatorId, targetUserId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "cannot update another user's profile");
        }
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "updateProfile command must not be null");
        }

        User user = findUserById(targetUserId);
        user.setNickname(resolveNickname(command.nickname()));
        user.setAvatarUrl(emptyToNull(command.avatarUrl()));
        user.setUpdatedAt(LocalDateTime.now());
        return UserProfileResponse.from(userRepository.save(user));
    }

    private User findUserById(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "user not found"));
    }

    private void validateRegisterCommand(RegisterCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "register command must not be null");
        }
        if (isBlank(command.email())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "email must not be blank");
        }
        validateEmail(command.email());
        if (isBlank(command.verificationCode())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "verificationCode must not be blank");
        }
        if (isBlank(command.studentId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "studentId must not be blank");
        }
        if (isBlank(command.password()) || command.password().length() < 8) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "password must be at least 8 characters");
        }
    }

    private void validateLoginCommand(LoginCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "login command must not be null");
        }
        if (isBlank(command.loginId()) || isBlank(command.password())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "loginId and password must not be blank");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void validateEmail(String email) {
        if (isBlank(email) || !email.contains("@")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "email must be a valid address");
        }
    }

    private String resolveNickname(String nickname) {
        return isBlank(nickname) ? "匿名校友" : nickname.trim();
    }

    private String emptyToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
