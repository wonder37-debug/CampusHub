package com.campushub.backend.auth.service;

import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.auth.dto.LoginCommand;
import com.campushub.backend.auth.dto.LoginResult;
import com.campushub.backend.auth.dto.PasswordResetCommand;
import com.campushub.backend.auth.dto.RegisterCommand;
import com.campushub.backend.auth.dto.UpdateProfileCommand;
import com.campushub.backend.auth.dto.UserProfileResponse;

public interface AuthApplicationService {

    EmailVerificationIssue sendRegistrationCode(String email, String studentId);

    UserProfileResponse register(RegisterCommand command);

    LoginResult login(LoginCommand command);

    UserProfileResponse getProfile(Long userId);

    UserProfileResponse updateProfile(Long operatorId, Long targetUserId, UpdateProfileCommand command);

    void changePassword(Long userId, String oldPassword, String newPassword);

    EmailVerificationIssue sendPasswordResetCode(String email);

    void resetPassword(PasswordResetCommand command);
}
