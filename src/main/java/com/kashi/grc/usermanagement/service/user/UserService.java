package com.kashi.grc.usermanagement.service.user;

import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.usermanagement.dto.request.*;
import com.kashi.grc.usermanagement.dto.response.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface UserService {

    UserResponse createUser(UserCreateRequest request);
    Map<String, Object> bulkUpload(MultipartFile file, Long defaultRoleId, boolean sendWelcomeEmails);
    UserResponse getUserById(Long userId);
    PaginatedResponse<UserResponse> listUsers(PageDetails pageDetails, String side, boolean noRoles, Long vendorId);
    UserResponse updateUser(Long userId, UserUpdateRequest request);
    void deleteUser(Long userId);
    UserResponse suspendUser(Long userId);
    UserResponse activateUser(Long userId);
    Map<String, Object> updateStatus(Long userId, UserStatusRequest request);
    Map<String, Object> getResponsibilities(Long userId, boolean includeDelegations);
    Map<String, Object> deactivateUser(Long userId, UserDeactivateRequest request);
    UserResponse reactivateUser(Long userId, UserReactivateRequest request);
    Map<String, Object> changePassword(ChangePasswordRequest request);
    UserAccessSummary getAccessSummary(Long userId);
    Map<String, Object> getActivityLog(Long userId, String startDate, String endDate, String actionType, int limit);
    void savePreferences(Map<String, String> prefs);
    Map<String, String> getPreferences();
}