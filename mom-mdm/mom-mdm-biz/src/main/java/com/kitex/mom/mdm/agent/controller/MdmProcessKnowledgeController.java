package com.kitex.mom.mdm.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.agent.dto.MdmProcessKnowledgeDTO;
import com.kitex.mom.mdm.service.MdmProcessParameterStandardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 独立AI读取正式工艺知识的内部入口；内部标记与实名来源权限必须同时满足。
 * 真实租户和工厂READ授权在服务层再次校验，不以请求头替代用户授权。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/agent/knowledge/process-standards")
public class MdmProcessKnowledgeController {

    private final MdmProcessParameterStandardService service;

    @Inner
    @PostMapping("/search")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_view')")
    public R<MdmProcessKnowledgeDTO.SourcePage> search(
            @Valid @RequestBody MdmProcessKnowledgeDTO.SearchQuery query) {
        return R.ok(service.searchKnowledgeSources(query));
    }

    @Inner
    @PostMapping("/verify")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_view')")
    public R<MdmProcessKnowledgeDTO.SourceDetail> verify(
            @Valid @RequestBody MdmProcessKnowledgeDTO.VerifyQuery query) {
        return R.ok(service.verifyKnowledgeSource(query));
    }
}
