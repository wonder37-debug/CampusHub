package com.campushub.backend.api;

import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;
import com.campushub.backend.notification.service.NotificationApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationApplicationService notificationApplicationService;
    private final RequestUserExtractor requestUserExtractor;

    public NotificationController(
        NotificationApplicationService notificationApplicationService,
        RequestUserExtractor requestUserExtractor
    ) {
        this.notificationApplicationService = notificationApplicationService;
        this.requestUserExtractor = requestUserExtractor;
    }

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> list(
        HttpServletRequest request,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(
            notificationApplicationService.list(
                currentUser.userId(),
                new NotificationQuery(unreadOnly, new PageQuery(page, size))
            )
        );
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> markAsRead(HttpServletRequest request, @PathVariable Long notificationId) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        notificationApplicationService.markAsRead(currentUser.userId(), notificationId);
        return ApiResponse.success();
    }
}
