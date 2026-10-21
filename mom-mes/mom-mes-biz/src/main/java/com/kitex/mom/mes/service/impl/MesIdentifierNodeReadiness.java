package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesIdentifierNodeReadinessDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.mapper.MesIdentifierNodeDeploymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 发布、派工和新报工共用的实时前置决策；不是持久实体服务，也不替代实际生产门。
 *
 * <p>只读目录，不接受enabled开关、不装空CRUD、不缓存全局绿灯。NONE与原号成功回放
 * 保持原路径。部署通过仅允许有权限的人显式发布新草稿并现场验证，不能代表已完成验收。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MesIdentifierNodeReadiness {

    private final MesActualFactoryAccessService factoryAccess;
    private final MesIdentifierNodeDeploymentMapper deployment;
    private final PermissionService permissions;

    /** 当前实际服务账号的系统目录诊断，不自动授权或执行数据库升级。 */
    public MesIdentifierNodeReadinessDTO deployment() {
        var result = identity().setScope("DEPLOYMENT");
        try {
            String raw = deployment.missingControls();
            if (raw == null || !JSONUtil.isTypeJSONArray(raw)) {
                throw new CheckedException("数据库保护诊断未返回完整缺项数组");
            }
            result.setMissingControls(JSONUtil.toList(JSONUtil.parseArray(raw), String.class));
            result.setDatabaseReady(result.getMissingControls().isEmpty());
        } catch (RuntimeException exception) {
            // 不向终端泄漏连接凭据/SQL堆栈，也不把读取失败解释成无缺项。
            log.warn("动态节点部署目录读取失败，type={}", exception.getClass().getSimpleName());
            result.getBlockedReasons().add("无法核对数据库保护，请确认MomMES业务连接及累计V2截至290节已部署；不会自动执行SQL");
        }
        if (!result.getMissingControls().isEmpty()) {
            result.getBlockedReasons().add("数据库保护缺失或与当前代码不一致，请展开诊断核对具体对象");
        }
        for (MesReportChannelEnum channel : MesReportChannelEnum.values()) {
            if (permitted(channel)) result.getAllowedChannels().add(channel.name());
        }
        return finish(result);
    }

    /** 发布只核本模板，不要求其他角色已发布而造成第一个模板永远发不出。 */
    public MesIdentifierNodeReadinessDTO publication(MesExecutionContractDTO contract) {
        var result = deployment().setScope("PUBLISH").setAction(contract.getIdentifierNodePolicy().getAction());
        result.getAllowedChannels().clear();
        shape(contract, result);
        for (MesReportChannelEnum channel : MesReportChannelEnum.values()) {
            if (contractAllows(contract, channel)) result.getAllowedChannels().add(channel.name());
        }
        if (!permissions.hasPermission("mesExecutionTemplate:publish")) {
            result.getBlockedReasons().add("当前账号没有生产执行模板发布权限");
        }
        if (result.getAllowedChannels().isEmpty()) result.getBlockedReasons().add("该模板没有可用的在线执行渠道");
        return finish(result);
    }

    /** 动态派工仍需原派工权限；完整区段和实际角色由同事务冻结及批次校验负责。 */
    public void requireDispatch(MesExecutionContractDTO contract) {
        var result = deployment().setScope("DISPATCH").setAction(contract.getIdentifierNodePolicy().getAction());
        shape(contract, result);
        if (!permissions.hasPermission("mesTaskArrange:arrange")) result.getBlockedReasons().add("当前账号没有派工权限");
        if (java.util.Arrays.stream(MesReportChannelEnum.values()).noneMatch(channel -> contractAllows(contract, channel))) {
            result.getBlockedReasons().add("该节点没有可用的在线执行渠道");
        }
        assertReady(finish(result));
    }

    /** 精确原派工、当前实名与可信渠道的决定；提交仍重核全部原实例及生产事实。 */
    public MesIdentifierNodeReadinessDTO execution(MesTaskArrangeEntity arrange, MesExecutionContractDTO contract,
                                                  MesReportChannelEnum channel) {
        var result = deployment().setScope("REPORT").setArrangeCode(arrange.getCode())
                .setAction(contract.getIdentifierNodePolicy().getAction()).setChannel(channel.name());
        result.getAllowedChannels().clear();
        shape(contract, result);
        if (!Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(arrange.getFactoryCode(), result.getFactoryCode())) {
            result.getBlockedReasons().add("原派工不属于当前实际租户工厂");
        }
        if (MesIdentifierNodePolicyValidator.nativePolicy(contract.getIdentifierNodePolicy())) {
            var policy = contract.getIdentifierNodePolicy();
            boolean nodeMatches = policy.getFrozenNodes() != null && policy.getFrozenNodes().stream().filter(node ->
                    Objects.equals(node.getProduceTaskCode(), arrange.getProduceTaskCode())
                            && Objects.equals(node.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode())
                            && Objects.equals(node.getRouteCode(), arrange.getRouteCode())).count() == 1;
            if (!"NODE_EXECUTION_V1".equals(arrange.getRouteExecutionSchemaVersion()) || !nodeMatches
                    || !Objects.equals(policy.getAction(), MesIdentifierNodePolicyValidator.role(policy, arrange))) {
                result.getBlockedReasons().add("原生动态契约与当前派工的实际任务、稳定节点或角色不一致");
            }
        }
        for (MesReportChannelEnum candidate : MesReportChannelEnum.values()) {
            if (permitted(candidate) && contractAllows(contract, candidate)) result.getAllowedChannels().add(candidate.name());
        }
        if (!result.getAllowedChannels().contains(channel.name())) {
            result.getBlockedReasons().add("当前账号权限或原冻结契约未允许" + channel.name() + "渠道");
        }
        if (!Set.of("UN_RECEIVE", "RECEIVE", "PRODUCT").contains(Objects.toString(arrange.getStatus(), ""))) {
            result.getBlockedReasons().add("该派工未处于可准备/执行状态；暂停、关闭或完工须先受控处理");
        }
        BigDecimal remaining = contract.getRemainingQuantity();
        if (remaining == null || remaining.compareTo(BigDecimal.ONE) < 0) result.getBlockedReasons().add("原派工剩余可报数量不足一件");
        if (!Boolean.TRUE.equals(contract.getPartialReportAllowed())
                && (remaining == null || remaining.compareTo(BigDecimal.ONE) != 0)) {
            result.getBlockedReasons().add("该单件节点禁止分批但剩余量不是一件，请按实际派工量配置分批策略");
        }
        return finish(result);
    }

    /** 统一失败消息保留精确对象，不能再仅返回“建设中”。 */
    public void assertReady(MesIdentifierNodeReadinessDTO result) {
        if (!Boolean.TRUE.equals(result.getReady())) {
            throw new CheckedException(String.join("；", result.getBlockedReasons())
                    + (result.getMissingControls().isEmpty() ? "" : "：" + String.join("、", result.getMissingControls())));
        }
    }

    private MesIdentifierNodeReadinessDTO identity() {
        String factory = factoryAccess.require("read");
        return new MesIdentifierNodeReadinessDTO().setTenantId(TenantContextHolder.getTenantId().toString())
                .setFactoryCode(factory).setActorId(SecurityUtils.getUser().getId().toString());
    }

    private void shape(MesExecutionContractDTO contract, MesIdentifierNodeReadinessDTO result) {
        MesIdentifierNodePolicyValidator.nativePolicy(contract.getIdentifierNodePolicy());
        if (!"ONE_CODE".equals(contract.getReportMode())) result.getBlockedReasons().add("动态节点只支持ONE_CODE单件对象");
        if (!Set.of("BIND", "USE", "RELEASE").contains(Objects.toString(contract.getIdentifierNodePolicy().getAction(), ""))) {
            result.getBlockedReasons().add("动态节点动作未识别，不回落普通报工");
        }
        if (contract.getMaxSingleQuantity() != null && contract.getMaxSingleQuantity().compareTo(BigDecimal.ONE) < 0) {
            result.getBlockedReasons().add("单次数量上限小于一件，无法执行该动态单件节点");
        }
    }

    private boolean contractAllows(MesExecutionContractDTO contract, MesReportChannelEnum channel) {
        return contract.getAllowedTriggers() != null && contract.getAllowedTriggers().contains(channel.getTriggerCode())
                && (channel != MesReportChannelEnum.STATION || Boolean.TRUE.equals(contract.getStationAllowed()))
                && (channel != MesReportChannelEnum.MOBILE || Boolean.TRUE.equals(contract.getMobileAllowed()));
    }

    private boolean permitted(MesReportChannelEnum channel) {
        return switch (channel) {
            case STATION -> permissions.hasPermission("mesTaskReport:report");
            case MOBILE -> permissions.hasPermission("mesStationPass:report");
            case INTEGRATION -> permissions.hasPermission("mesTaskReport:integration");
            case DEVICE -> permissions.hasPermission("mesAutoReport:event", "mesAutoReport:retry");
        };
    }

    private MesIdentifierNodeReadinessDTO finish(MesIdentifierNodeReadinessDTO result) {
        result.setReady(Boolean.TRUE.equals(result.getDatabaseReady()) && result.getBlockedReasons().isEmpty());
        return result;
    }
}
