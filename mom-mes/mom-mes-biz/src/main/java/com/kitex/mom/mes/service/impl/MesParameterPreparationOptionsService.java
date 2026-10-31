package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 仅提供选择项；准备命令时仍须完整核验冻结工程包及实际工位。 */
@Service
@RequiredArgsConstructor
public class MesParameterPreparationOptionsService {
    private final MesActualFactoryAccessService access;
    private final MesTaskMapper tasks;
    private final MesTaskArrangeMapper arrangements;
    private final MesWorksiteMoldMapper bindings;
    private static final Set<String> ACTIVE = Set.of("RECEIVE", "PRODUCT", "PAUSE");

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesParameterDispatchContextDTO.Options query(
            MesParameterDispatchContextDTO.OptionsQuery q) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(
                q != null && tenant != null && Objects.equals(factory, q.getFactoryCode()),
                "候选查询与实际工厂不一致");
        exact(q.getTaskCode());
        optional(q.getArrangeCode());
        optional(q.getDeviceCode());
        require(q.getDeviceCode() == null || q.getArrangeCode() != null, "请先选择具体派工");
        var roots =
                tasks.selectList(
                        new LambdaQueryWrapper<MesTaskEntity>()
                                .select(
                                        MesTaskEntity::getTenantId,
                                        MesTaskEntity::getFactoryCode,
                                        MesTaskEntity::getCode,
                                        MesTaskEntity::getStatus)
                                .eq(MesTaskEntity::getTenantId, tenant)
                                .eq(MesTaskEntity::getFactoryCode, factory)
                                .eq(MesTaskEntity::getCode, q.getTaskCode())
                                .last("LIMIT 2"));
        require(roots.size() == 1, "工单不存在或编码不唯一");
        var task = roots.get(0);
        require(
                Objects.equals(task.getTenantId(), tenant)
                        && Objects.equals(task.getFactoryCode(), factory)
                        && Objects.equals(task.getCode(), q.getTaskCode()),
                "工单候选身份不一致");
        if (task.getStatus() == null
                || !Set.of("ISSUE", "DISPATCH", "PRODUCT").contains(task.getStatus()))
            return result(q, List.of(), List.of(), false, "当前工单尚未下达或已经结束");
        var rows =
                arrangements.selectList(
                        new LambdaQueryWrapper<MesTaskArrangeEntity>()
                                .select(
                                        MesTaskArrangeEntity::getTenantId,
                                        MesTaskArrangeEntity::getFactoryCode,
                                        MesTaskArrangeEntity::getCode,
                                        MesTaskArrangeEntity::getTaskCode,
                                        MesTaskArrangeEntity::getRouteCode,
                                        MesTaskArrangeEntity::getRouteName,
                                        MesTaskArrangeEntity::getSourceRouteNodeCode,
                                        MesTaskArrangeEntity::getActualWorksiteCode,
                                        MesTaskArrangeEntity::getWorksiteCodes,
                                        MesTaskArrangeEntity::getPlannedDeviceCode,
                                        MesTaskArrangeEntity::getStatus)
                                .eq(MesTaskArrangeEntity::getTenantId, tenant)
                                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                                .eq(MesTaskArrangeEntity::getTaskCode, q.getTaskCode())
                                .eq(
                                        q.getArrangeCode() != null,
                                        MesTaskArrangeEntity::getCode,
                                        q.getArrangeCode())
                                .in(MesTaskArrangeEntity::getStatus, ACTIVE)
                                .isNotNull(MesTaskArrangeEntity::getActualWorksiteCode)
                                .ne(MesTaskArrangeEntity::getActualWorksiteCode, "")
                                .orderByAsc(MesTaskArrangeEntity::getCode)
                                .last(q.getArrangeCode() == null ? "LIMIT 101" : "LIMIT 2"));
        if (q.getArrangeCode() != null) require(rows.size() <= 1, "派工编码不唯一");
        Set<String> seen = new HashSet<>();
        for (var row : rows) {
            require(
                    Objects.equals(row.getTenantId(), tenant)
                            && Objects.equals(row.getFactoryCode(), factory)
                            && Objects.equals(row.getTaskCode(), q.getTaskCode())
                            && (q.getArrangeCode() == null
                                    || Objects.equals(row.getCode(), q.getArrangeCode()))
                            && ACTIVE.contains(String.valueOf(row.getStatus())),
                    "派工候选范围不一致");
            exact(row.getCode());
            exact(row.getActualWorksiteCode());
            exact(row.getRouteCode());
            exact(row.getSourceRouteNodeCode());
            require(
                    seen.add(row.getCode())
                            && Arrays.stream(String.valueOf(row.getWorksiteCodes()).split(","))
                                    .map(String::trim)
                                    .anyMatch(row.getActualWorksiteCode()::equals),
                    "派工候选的实际工位或编码不可核验");
        }
        boolean truncated = rows.size() > 100;
        if (truncated) rows = rows.subList(0, 100);
        var choices =
                rows.stream()
                        .map(
                                a ->
                                        new MesParameterDispatchContextDTO.ArrangementChoice(
                                                a.getCode(),
                                                a.getRouteCode(),
                                                a.getRouteName(),
                                                a.getSourceRouteNodeCode(),
                                                a.getActualWorksiteCode(),
                                                a.getStatus()))
                        .toList();
        if (q.getArrangeCode() == null || rows.isEmpty())
            return result(q, choices, List.of(), truncated, rows.isEmpty() ? "暂无已接收并确认工位的派工" : "");
        var selected = rows.get(0);
        var devices =
                bindings.selectList(
                        new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                                .select(
                                        MesWorksiteMoldEntity::getTenantId,
                                        MesWorksiteMoldEntity::getFactoryCode,
                                        MesWorksiteMoldEntity::getWorksiteCode,
                                        MesWorksiteMoldEntity::getDeviceCode)
                                .eq(MesWorksiteMoldEntity::getTenantId, tenant)
                                .eq(MesWorksiteMoldEntity::getFactoryCode, factory)
                                .eq(
                                        MesWorksiteMoldEntity::getWorksiteCode,
                                        selected.getActualWorksiteCode())
                                .eq(
                                        q.getDeviceCode() != null,
                                        MesWorksiteMoldEntity::getDeviceCode,
                                        q.getDeviceCode())
                                .eq(
                                        selected.getPlannedDeviceCode() != null
                                                && !selected.getPlannedDeviceCode().isBlank(),
                                        MesWorksiteMoldEntity::getDeviceCode,
                                        selected.getPlannedDeviceCode())
                                .isNotNull(MesWorksiteMoldEntity::getDeviceCode)
                                .ne(MesWorksiteMoldEntity::getDeviceCode, "")
                                .orderByAsc(MesWorksiteMoldEntity::getDeviceCode)
                                .last("LIMIT 101"));
        for (var d : devices) {
            exact(d.getDeviceCode());
            require(
                    Objects.equals(d.getTenantId(), tenant)
                            && Objects.equals(d.getFactoryCode(), factory)
                            && Objects.equals(d.getWorksiteCode(), selected.getActualWorksiteCode())
                            && (q.getDeviceCode() == null
                                    || Objects.equals(d.getDeviceCode(), q.getDeviceCode()))
                            && (selected.getPlannedDeviceCode() == null
                                    || selected.getPlannedDeviceCode().isBlank()
                                    || Objects.equals(
                                            d.getDeviceCode(), selected.getPlannedDeviceCode())),
                    "实际工位设备绑定范围不一致");
        }
        truncated |= devices.size() > 100;
        var codes =
                devices.stream()
                        .limit(100)
                        .map(MesWorksiteMoldEntity::getDeviceCode)
                        .distinct()
                        .toList();
        return result(q, choices, codes, truncated, codes.isEmpty() ? "实际工位尚无符合派工要求的设备绑定" : "");
    }

    private static MesParameterDispatchContextDTO.Options result(
            MesParameterDispatchContextDTO.OptionsQuery q,
            List<MesParameterDispatchContextDTO.ArrangementChoice> a,
            List<String> d,
            boolean t,
            String b) {
        return new MesParameterDispatchContextDTO.Options(
                "MES_PARAMETER_PREPARATION@1",
                q.getFactoryCode(),
                q.getTaskCode(),
                q.getArrangeCode(),
                q.getDeviceCode(),
                a,
                d,
                t,
                b);
    }

    private static void optional(String s) {
        if (s != null) exact(s);
    }

    private static void exact(String s) {
        require(
                s != null
                        && !s.isBlank()
                        && s.equals(s.trim())
                        && s.length() <= 100
                        && s.chars().noneMatch(Character::isISOControl),
                "候选编码缺失或格式不正确");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
