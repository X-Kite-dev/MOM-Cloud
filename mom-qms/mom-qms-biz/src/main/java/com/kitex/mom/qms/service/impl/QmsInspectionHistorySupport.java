package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.dto.QmsFirstInspectionDecisionDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/** 生产质检历史的实际工厂授权与首次判定解释，授权事实仍由MDM提供。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionHistorySupport {
    private final RemoteMdmFactoryAccessService factories;

    String requireScope() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(
                tenant != null
                        && tenant > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(factory),
                "质检历史缺少实际租户或工厂，禁止跳过隔离");
        return factory;
    }

    String requireRead() {
        return requireAccess("read");
    }

    String requireWrite() {
        return requireAccess("write");
    }

    private String requireAccess(String permission) {
        String factory = requireScope();
        var auth = SecurityUtils.getAuthentication();
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        require(
                auth != null
                        && auth.isAuthenticated()
                        && user != null
                        && user.getId() != null
                        && user.getId() > 0
                        && tenant != null
                        && tenant > 0
                        && Objects.equals(tenant, user.getTenantId())
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(factory),
                "质检历史须实名登录并选择实际租户和工厂，禁止跳过隔离");
        R<Boolean> response;
        try {
            response =
                    factories.check(
                            new MdmFactoryAccessCheck()
                                    .setUserId(user.getId())
                                    .setFactoryCode(factory)
                                    .setRequiredPermission(permission));
        } catch (RuntimeException failure) {
            throw new CheckedException("质检历史的工厂授权暂时无法核验", failure);
        }
        require(
                response != null && response.isOk() && Boolean.TRUE.equals(response.getData()),
                "当前人员没有所需工厂权限，或工厂授权不可核验");
        return factory;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    static void bounded(int size) {
        require(size <= 5000, "单任务质检历史超过5000条，请先治理，禁止截断追溯依据");
    }

    QmsFirstInspectionDecisionDTO first(String type, Long taskId, List<Fact> facts) {
        bounded(facts.size());
        var result =
                new QmsFirstInspectionDecisionDTO()
                        .setInspectionType(type)
                        .setTaskId(taskId)
                        .setAvailability("MISSING")
                        .setEvidenceRef("QMS:" + type + ":TASK:" + taskId);
        result.getDataGaps().add("历史未绑定完整首检事件序列，最早留存记录不能证明原始首检资格");
        if (facts.isEmpty()) {
            result.getDataGaps().add("没有首次检验结果历史，不能从当前结论推算");
            return result;
        }
        Fact first = facts.get(0);
        result.setHistoryId(first.id()).setJudgedAt(first.time());
        if (incomplete(first)) return gap(result, "INCOMPLETE", "最早检验历史缺少时间、明确判定或正数检验量");
        for (Fact fact : facts) {
            if (!Objects.equals(first.time(), fact.time())) break;
            if (incomplete(fact)) return gap(result, "INCOMPLETE", "最早时刻存在依据不完整的检验历史");
            if (!Objects.equals(first.qualified(), fact.qualified())
                    || !Objects.equals(first.quantity(), fact.quantity()))
                return gap(result, "AMBIGUOUS", "最早时刻存在不同判定或数量，不能按记录ID猜测首次结果");
        }
        return result.setAvailability("AVAILABLE")
                .setQualified(first.qualified())
                .setInspectionQuantity(first.quantity())
                .setEvidenceRef("QMS:" + type + ":HISTORY:" + first.id());
    }

    private boolean incomplete(Fact fact) {
        return fact.time() == null
                || fact.qualified() == null
                || fact.quantity() == null
                || fact.quantity() <= 0;
    }

    private QmsFirstInspectionDecisionDTO gap(
            QmsFirstInspectionDecisionDTO result, String state, String message) {
        result.setAvailability(state);
        result.getDataGaps().add(message);
        return result;
    }

    record Fact(Long id, Date time, Integer quantity, Boolean qualified) {}
}
