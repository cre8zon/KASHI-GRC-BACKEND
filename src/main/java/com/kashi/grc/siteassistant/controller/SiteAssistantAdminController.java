package com.kashi.grc.siteassistant.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.enquiry.service.EnquiryService;
import com.kashi.grc.siteassistant.dto.SiteChatDtos.*;
import com.kashi.grc.siteassistant.service.SiteAssistantIndexer;
import com.kashi.grc.siteassistant.service.SiteAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The website assistant inside the Enquiries inbox: a handed-over chat's
 * transcript and the team's reply, the questions the assistant could not
 * answer, and the indexed pages. Same permissions as the inbox (platform
 * side; /v1/admin/** is also SIDE_SYSTEM at the URL layer).
 */
@RestController
@RequestMapping("/v1/admin/site-assistant")
@PreAuthorize(SiteAssistantAdminController.VIEW)
@Tag(name = "Website assistant — inbox")
@RequiredArgsConstructor
public class SiteAssistantAdminController {

    static final String VIEW   = "hasAuthority('" + EnquiryService.PERM_VIEW + "')";
    static final String MANAGE = "hasAuthority('" + EnquiryService.PERM_MANAGE + "')";

    private final SiteAssistantService service;
    private final SiteAssistantIndexer indexer;
    private final UtilityService       utilityService;

    @GetMapping("/enquiries/{enquiryId}/chat")
    @Operation(summary = "The chat an enquiry came from: AI summary and transcript")
    public ApiResponse<AdminChatView> chat(@PathVariable Long enquiryId) {
        return ApiResponse.success(service.adminChat(enquiryId));
    }

    @PostMapping("/enquiries/{enquiryId}/reply")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Reply to the visitor: shown in their chat and sent by email")
    public ApiResponse<AdminChatView> reply(@PathVariable Long enquiryId, @RequestBody ReplyRequest body) {
        return ApiResponse.success(service.reply(enquiryId, body == null ? null : body.body(),
                utilityService.getLoggedInDataContext().getId()));
    }

    @GetMapping("/unanswered")
    @Operation(summary = "Questions the assistant could not answer from the website (newest first)")
    public ApiResponse<List<UnansweredItem>> unanswered(@RequestParam(defaultValue = "30") int days,
                                                        @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.success(service.unanswered(days, limit));
    }

    @GetMapping("/pages")
    @Operation(summary = "Website pages the assistant answers from")
    public ApiResponse<List<Map<String, Object>>> pages() {
        return ApiResponse.success(indexer.pages());
    }

    @PostMapping("/reindex-posts")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Index every published post (blog, glossary, resources) for the assistant")
    public ApiResponse<Map<String, Object>> reindexPosts() {
        return ApiResponse.success(Map.of("queued", indexer.reindexAllPosts()));
    }
}
