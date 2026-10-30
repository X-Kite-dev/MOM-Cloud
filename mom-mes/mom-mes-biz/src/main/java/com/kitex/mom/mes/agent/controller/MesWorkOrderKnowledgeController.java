package com.kitex.mom.mes.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.agent.dto.MesWorkOrderKnowledgeDTO;
import com.kitex.mom.mes.agent.service.MesWorkOrderKnowledgeQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单冻结知识仅供固定实名内部链路读取；内部标记不是来源授权。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAgentKnowledge")
@Inner
@PreAuthorize("@pms.hasPermission('ai:knowledge:view') && @pms.hasPermission('mesTask:show')")
public class MesWorkOrderKnowledgeController {
    private final MesWorkOrderKnowledgeQueryService service;

    /**
     * 分页发现当前授权工厂的工单身份；候选不等于已核验的知识正文。
     */
    @PostMapping("/candidates")
    public R<MesWorkOrderKnowledgeDTO.CandidatePage> candidates(
            @Valid @RequestBody MesWorkOrderKnowledgeDTO.CandidateQuery query) {
        return R.ok(service.candidates(query));
    }

    /**
     * 按唯一工单读取原冻结依据；无正文或校验失败时仅返回适用状态与缺口。
     */
    @PostMapping("/evidence")
    public R<MesWorkOrderKnowledgeDTO.Evidence> evidence(@Valid @RequestBody MesWorkOrderKnowledgeDTO.Query query) {
        return R.ok(service.evidence(query));
    }
}
