package com.campushub.backend.api;

import com.campushub.backend.api.view.UserSummaryView;
import com.campushub.backend.auth.dto.UpdateProfileCommand;
import com.campushub.backend.auth.service.AuthApplicationService;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final AuthApplicationService authApplicationService;
    private final RequestUserExtractor requestUserExtractor;

    public UserController(AuthApplicationService authApplicationService, RequestUserExtractor requestUserExtractor) {
        this.authApplicationService = authApplicationService;
        this.requestUserExtractor = requestUserExtractor;
    }

    @GetMapping("/me")
    public ApiResponse<UserSummaryView> me(HttpServletRequest request) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(UserSummaryView.from(authApplicationService.getProfile(currentUser.userId())));
    }

    @PutMapping("/me")
    public ApiResponse<UserSummaryView> updateProfile(
        HttpServletRequest request,
        @RequestBody UpdateProfileCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(
            UserSummaryView.from(authApplicationService.updateProfile(currentUser.userId(), currentUser.userId(), command))
        );
    }
}
