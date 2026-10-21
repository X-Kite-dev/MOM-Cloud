package com.kitex.mom.mes.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工艺建议来源取证：只读工单下达时冻结标准，不用现行主数据覆盖历史。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAgentProcess")
public class MesProcessProposalEvidenceController {

    private final MesActualFactoryAccessService factoryAccess;

    private final MesTaskMapper tasks;

    private final ObjectMapper mapper;
    private final RemoteMdmFactoryAccessService factories;

    @PostMapping("/source")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<Map<String, Object>> source(@Valid @RequestBody Query query) throws Exception {
        String factory = factoryAccess.require("read");
        var user = SecurityUtils.getUser();
        if (user.getTenantId() == null || !Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId())) {
            throw new CheckedException("工艺来源身份租户与请求上下文不一致");
        }
        R<Boolean> authorization = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission("read"));
        if (authorization == null || !authorization.isOk() || !Boolean.TRUE.equals(authorization.getData())) {
            throw new CheckedException("没有实际用户工厂读权限，不能仅凭factory-code读取工艺来源");
        }
        if (!factory.equals(query.factoryCode()))
            throw new CheckedException("工艺建议工厂不在实际授权范围");
        MesTaskEntity task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getFactoryCode,
                factory).eq(MesTaskEntity::getCode, query.taskCode()).last("LIMIT 2"));
        if (task == null || task.getProcessParameterStandardSnapshot() == null || task.getProcessParameterSnapshotFingerprint() == null) {
            throw new CheckedException("工单没有完整下达冻结工艺参数标准，不能形成工艺优化提案");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factoryCode", factory);
        result.put("taskCode", task.getCode());
        result.put("taskId", String.valueOf(task.getId()));
        result.put("processRouteId", String.valueOf(task.getProcessRouteId()));
        result.put("processRouteCode", task.getProcessRouteCode());
        result.put("materialCode", task.getMatCode());
        result.put("taskStatus", task.getStatus());
        result.put("actualFinishTime", task.getFinishTime());
        result.put("completedQuantity", task.getCompleteNum());
        result.put("frozenStandard", mapper.readTree(task.getProcessParameterStandardSnapshot()));
        result.put("frozenFingerprint", task.getProcessParameterSnapshotFingerprint());
        return R.ok(result);
    }

    public record Query(@NotBlank String factoryCode, @NotBlank String taskCode) {
    }
}
