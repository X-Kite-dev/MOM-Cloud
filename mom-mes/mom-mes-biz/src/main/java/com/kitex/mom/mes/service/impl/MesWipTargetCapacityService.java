package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipQuantityRules.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.Capacity;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesExecutionTemplateService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/** 调用者已验证实际工厂、冻结后继和MDM引用；本服务统一观察目标实际数量和原执行策略。 */
@Service
@RequiredArgsConstructor
public class MesWipTargetCapacityService {
    private final MesTaskArrangeMapper arranges;
    private final MesWipNextStationsMapper facts;
    private final MesExecutionTemplateService contracts;

    @Transactional(propagation = Propagation.MANDATORY)
    public Capacity observe(
            MesWipTransferEntity transfer,
            String arrangeId,
            String nodeCode,
            String worksiteCode,
            String workshopCode) {
        Long tenant = TenantContextHolder.getTenantId();
        need(
                transfer != null
                        && tenant != null
                        && tenant.equals(transfer.getTenantId())
                        && code(transfer.getFactoryCode()),
                "目标容量缺少当前租户原单");
        String factory = transfer.getFactoryCode();
        var a =
                arranges.selectOne(
                        new LambdaQueryWrapper<MesTaskArrangeEntity>()
                                .eq(MesTaskArrangeEntity::getTenantId, tenant)
                                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                                .eq(MesTaskArrangeEntity::getDelFlag, "0")
                                .eq(MesTaskArrangeEntity::getId, id(arrangeId)));
        var report = facts.report(tenant, factory, transfer.getSourceReportId());
        need(
                report != null
                        && tenant.equals(report.getTenantId())
                        && factory.equals(report.getFactoryCode())
                        && "0".equals(report.getDelFlag())
                        && Objects.equals(report.getId(), transfer.getSourceReportId())
                        && "EFFECTIVE".equals(report.getStatus())
                        && code(report.getTaskCode())
                        && Objects.equals(report.getMatCode(), transfer.getMatCode())
                        && Objects.equals(
                                report.getProduceTaskCode(), transfer.getProduceTaskCode())
                        && Objects.equals(report.getArrangeCode(), transfer.getArrangeCode()),
                "配送原报工来源不可核验");
        need(
                a != null
                        && tenant.equals(a.getTenantId())
                        && factory.equals(a.getFactoryCode())
                        && "0".equals(a.getDelFlag())
                        && Objects.equals(a.getId(), id(arrangeId))
                        && report.getTaskCode().equals(a.getTaskCode())
                        && transfer.getMatCode().equals(a.getMatCode())
                        && nodeCode.equals(a.getSourceRouteNodeCode())
                        && Set.of("UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE")
                                .contains(Objects.toString(a.getStatus(), ""))
                        && code(a.getCode())
                        && workshopCode.equals(a.getWorkshopCode()),
                "目标派工已关闭或工单、产品、节点、车间发生变化");
        var candidate =
                new MesWipNextStationsDTO.Station(
                        arrangeId,
                        a.getCode(),
                        a.getProduceTaskCode(),
                        a.getSourceRouteNodeCode(),
                        a.getRouteName(),
                        a.getStatus(),
                        a.getWorkshopCode(),
                        a.getWorkshopName(),
                        a.getActualWorksiteCode(),
                        a.getPlannedWorksiteCode(),
                        a.getPlannedWorksiteName(),
                        a.getWorksiteCodes(),
                        null,
                        null);
        need(worksites(candidate).contains(worksiteCode), "目标派工实际/计划工位已变化");
        var planned = number(a.getNoticeNum(), false);
        var completed = number(a.getCompleteNum(), true);
        var contract = contracts.contract(a);
        need(
                contract != null
                        && Objects.equals(contract.getArrangeCode(), a.getCode())
                        && contract.getOverReportAllowed() != null,
                "目标派工冻结执行策略不完整");
        boolean over = contract.getOverReportAllowed();
        var rate = over ? number(contract.getOverReportRate(), true) : BigDecimal.ZERO;
        need(rate.compareTo(BigDecimal.ONE) <= 0, "冻结超报比例不正确");
        var maximum = number(planned.multiply(BigDecimal.ONE.add(rate)), false);
        need(completed.compareTo(maximum) <= 0, "目标派工已报数量超过冻结执行上限");
        var remaining = number(maximum.subtract(completed), true);
        need(
                same(planned, contract.getNoticeQuantity())
                        && same(completed, contract.getCompletedQuantity())
                        && same(remaining, contract.getRemainingQuantity()),
                "目标执行策略数量与实际派工不守恒");
        var quantity = delivery(transfer.getRemainingQuantity());
        need(
                quantity.compareTo(remaining) <= 0,
                "本次配送数量超过目标派工当前待报工余量：" + remaining.toPlainString());
        need(code(transfer.getMatUnit()), "原转运单位未明确");
        return new Capacity(
                planned.toPlainString(),
                completed.toPlainString(),
                maximum.toPlainString(),
                remaining.toPlainString(),
                quantity.toPlainString(),
                transfer.getMatUnit(),
                over,
                rate.toPlainString());
    }
}
