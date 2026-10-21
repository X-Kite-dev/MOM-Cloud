package com.kitex.mom.pms.agent.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.pms.agent.service.PmsAgentFactoryAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * 固定只读占用查询，包含删除历史；不会创建采购单、提交审批或修改已有采购事实。
 */
@RestController
@RequestMapping("/pmsAgentCommand")
public class PmsAgentCommandPresenceController {
    private final PmsAgentFactoryAccessService access;
    private final RemoteMdmFactoryAccessService factories;
    private final JdbcTemplate jdbc;

    public PmsAgentCommandPresenceController(PmsAgentFactoryAccessService access,
                                             RemoteMdmFactoryAccessService factories, JdbcTemplate jdbc) {
        this.access = access;
        this.factories = factories;
        this.jdbc = jdbc;
    }

    @Inner
    @PostMapping("/presence")
    @PreAuthorize("isAuthenticated() and @pms.hasPermission('agent:approval:execute') and @pms.hasPermission('plan_planMatMrp_edit')")
    public R<Map<String, String>> presence(@RequestBody Map<String, String> query) {
        String factory = query.get("factoryCode");
        String requestId = query.get("requestId");
        access.requireRead(factory);
        var user = SecurityUtils.getUser();
        require(user.getTenantId() != null && Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId()),
                "采购原请求占用核查身份租户不一致");
        require(requestId != null && !requestId.isBlank() && requestId.length() <= 100, "采购永久号不合法");
        R<Boolean> authorized = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission("read"));
        require(authorized != null && authorized.isOk() && Boolean.TRUE.equals(authorized.getData()),
                "无实际用户工厂读权限，不能凭请求头核查采购历史");
        Long count = jdbc.queryForObject("SELECT count(*) FROM \"mom_pms\".pms_order "
                        + "WHERE tenant_id=? AND factory_code=? AND agent_request_id=?", Long.class,
                user.getTenantId(), factory, requestId);
        require(count != null, "包含删除历史的采购原请求占用核查未得到权威结果");
        return R.ok(Map.of("status", count > 0 ? "EXISTS" : "ABSENT", "factoryCode", factory, "requestId", requestId));
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
