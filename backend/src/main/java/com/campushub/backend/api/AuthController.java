package com.campushub.backend.api;

import com.campushub.backend.api.view.UserSummaryView;
import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.auth.dto.LoginCommand;
import com.campushub.backend.auth.dto.LoginResult;
import com.campushub.backend.auth.dto.RegisterCommand;
import com.campushub.backend.auth.service.AuthApplicationService;
import com.campushub.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService authApplicationService;

    public AuthController(AuthApplicationService authApplicationService) {
        this.authApplicationService = authApplicationService;
    }

    @PostMapping("/email-code")
    public ApiResponse<EmailCodeResponse> sendEmailCode(@RequestBody EmailCodeRequest body) {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode(body.email(), body.studentId());
        return ApiResponse.success(new EmailCodeResponse(issue.expiresInSeconds()));
    }

    @PostMapping("/register")
    public ApiResponse<UserSummaryView> register(@RequestBody RegisterCommand command) {
        return ApiResponse.success(UserSummaryView.from(authApplicationService.register(command)));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResultView> login(@RequestBody LoginCommand command) {
        LoginResult result = authApplicationService.login(command);
        return ApiResponse.success(new LoginResultView(
            result.token(),
            result.expiresIn(),
            UserSummaryView.from(result.user())
        ));
    }

    public record LoginResultView(String token, long expiresIn, UserSummaryView user) {
    }

    public record EmailCodeRequest(String email, String studentId) {
    }

    public record EmailCodeResponse(long expiresIn) {
    }
}
