package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 工艺实施回采的原MES工单选择目录。仅按原路线读取小摘要，包含完工工单，
 * 不查询或解析整批冻结JSON；候选是否采用目标发布版仍须由精确source核对。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mesTask:show')")
public class MesProcessEffectCandidateQueryService {
    private final MesTaskMapper tasks;
    private final MesActualFactoryAccessService factoryAccess;
    private final RemoteMdmFactoryAccessService factories;

    /** count与记录保持同一只读快照；显式按实名租户/工厂/原路线及逻辑删除范围读取。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public CandidatePage candidates(CandidateQuery command) {
        require(command != null, "请提供工艺实施工单查询");
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = factoryAccess.require("read");
        require(user != null && user.getId() != null && user.getId() > 0 && tenant != null && tenant > 0
                        && Objects.equals(user.getTenantId(), tenant) && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && text(factory, 100) && factory.equals(FactoryContextHolder.getFactoryCode())
                        && factory.equals(command.factoryCode()), "实施工单须在实名授权租户与实际工厂内查询");
        R<Boolean> access = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission("read"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()), "当前实名没有该工厂READ权限或授权无法核验");
        Long routeId = id(command.routeId());
        long current = command.current() == null ? 1 : command.current();
        require(current >= 1 && current <= 100000, "请选择有效工单页码");
        require(command.keyword() == null || command.keyword().length() <= 100
                && command.keyword().codePoints().noneMatch(Character::isISOControl), "工单关键词不能超过100字或含控制字符");
        var query = new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getDelFlag, "0").eq(MesTaskEntity::getProcessRouteId, routeId)
                .select(MesTaskEntity::getId, MesTaskEntity::getCode, MesTaskEntity::getStatus,
                        MesTaskEntity::getMatCode, MesTaskEntity::getMatName, MesTaskEntity::getProcessRouteCode,
                        MesTaskEntity::getFinishTime)
                .orderByDesc(MesTaskEntity::getId);
        if (StringUtils.hasText(command.keyword())) {
            String keyword = command.keyword().trim();
            query.and(part -> part.like(MesTaskEntity::getCode, keyword)
                    .or().like(MesTaskEntity::getMatCode, keyword).or().like(MesTaskEntity::getMatName, keyword));
        }
        Page<MesTaskEntity> page = tasks.selectPage(new Page<>(current, 10), query);
        return new CandidatePage("MES_PROCESS_EFFECT_CANDIDATES_V1", tenant.toString(), factory, routeId.toString(),
                current, 10, Long.toString(page.getTotal()), page.getRecords().stream().map(this::candidate).toList());
    }

    private Candidate candidate(MesTaskEntity task) {
        require(task.getId() != null && task.getId() > 0 && text(task.getCode(), 100)
                        && optional(task.getStatus(), 40) && optional(task.getMatCode(), 100)
                        && optional(task.getMatName(), 500) && optional(task.getProcessRouteCode(), 100), "原工单身份摘要无法核对");
        return new Candidate(task.getId().toString(), task.getCode(), task.getStatus(), task.getMatCode(),
                task.getMatName(), task.getProcessRouteCode(), task.getFinishTime() != null);
    }

    private static Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "请选择原提案的完整工艺路线");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new CheckedException("工艺路线主键超出范围");
        }
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.trim()) && optional(value, max);
    }

    private static boolean optional(String value, int max) {
        return value == null || value.length() <= max && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    /** 路线来自AI已经核对的原提案，仍只允许当前实名工厂读取；不是适用性批准。 */
    public record CandidateQuery(@NotBlank @Size(max = 100) String factoryCode,
                                 @NotBlank @Size(max = 19) String routeId,
                                 @Size(max = 100) String keyword, @Min(1) @Max(100000) Long current) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Candidate(String taskId, String taskCode, String status, String materialCode,
                            String materialName, String routeCode, boolean hasFinishRecord) {
    }

    /** 主键和total均按文本传递，不把同路线目录标为目标版已核对。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CandidatePage(String version, String tenantId, String factoryCode, String routeId,
                                long current, long size, String total, List<Candidate> records) {
    }
}
