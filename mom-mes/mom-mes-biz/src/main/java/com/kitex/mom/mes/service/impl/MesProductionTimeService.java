package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.Fact;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.Query;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.Result;
import com.kitex.mom.mes.mapper.MesProductionTimeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MesProductionTimeService {

    private final MesActualFactoryAccessService access;
    private final MesProductionTimeMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result statistics(Query input) {
        String factory = access.require("read");
        Query query = normalize(input);
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip(), "统计缺少真实租户上下文");
        LocalDateTime observed = LocalDateTime.now();
        List<Fact> facts = new ArrayList<>();
        // 精确工件统计不能把整条派工的加工、暂停或人员时间当作工件实测时间。
        if (query.getIdentifier() == null) {
            add(facts, mapper.segments(tenant, factory, query, 20001));
            add(facts, mapper.labor(tenant, factory, query, 20001));
            add(facts, mapper.pauses(tenant, factory, query, 20001));
        }
        add(facts, mapper.workpieces(tenant, factory, query, 20001));
        Result result = new MesProductionTimeCalculator().calculate(query, facts, observed).setFactoryCode(factory);
        long orphaned = mapper.orphanedSources(tenant, factory, query);
        if (orphaned > 0) {
            result.getDataGaps().add("有" + orphaned + "条同组织时间来源缺少精确原派工关联，未计入汇总；无法按车间或班次核定归属");
        }
        return result;
    }

    private void add(List<Fact> target, List<Fact> source) {
        require(source != null, "工时来源不可读取，不能按没有数据处理");
        require((long) target.size() + source.size() <= 20000, "工时来源超过20000条，请缩小时间或工单范围；未返回截断统计");
        target.addAll(source);
    }

    private Query normalize(Query source) {
        require(source != null && source.getStartTime() != null && source.getEndTime() != null
                && source.getEndTime().isAfter(source.getStartTime()), "请选择正向的统计起止时间");
        Query query = new Query().setStartTime(source.getStartTime()).setEndTime(source.getEndTime())
                .setTaskCode(code(source.getTaskCode())).setProduceTaskCode(code(source.getProduceTaskCode()))
                .setArrangeCode(code(source.getArrangeCode())).setWorkshopCode(code(source.getWorkshopCode()))
                .setShiftCode(code(source.getShiftCode()))
                .setIdentifier(identifier(source.getIdentifier())).setIdentifierType(identifier(source.getIdentifierType()))
                .setGroupBy(Objects.requireNonNullElse(source.getGroupBy(), "TASK"))
                .setDetailPage(Objects.requireNonNullElse(source.getDetailPage(), 1))
                .setDetailSize(Objects.requireNonNullElse(source.getDetailSize(), 50));
        require((query.getIdentifier() == null) == (query.getIdentifierType() == null), "请同时指定生产标识类型和标识号");
        require(query.getIdentifier() == null || query.getTaskCode() != null, "按生产标识统计必须指定工单");
        require(Set.of("TASK", "PROCESS", "ARRANGE", "WORKPIECE").contains(query.getGroupBy()), "不支持的工时分组");
        require(query.getDetailPage() > 0 && query.getDetailPage() <= 100000
                && query.getDetailSize() > 0 && query.getDetailSize() <= 200, "明细页码无效，每页1至200条");
        long days = query.getTaskCode() == null ? 31 : 366;
        require(Duration.between(query.getStartTime(), query.getEndTime()).compareTo(Duration.ofDays(days)) <= 0,
                "全工厂统计最多31天，指定工单最多366天，请缩小范围");
        return query;
    }

    private String identifier(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        require(!raw.isBlank() && raw.length() <= 200 && raw.chars().noneMatch(Character::isISOControl),
                "生产标识长度或格式无效");
        return raw;
    }

    private String code(String raw) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        require(value.length() <= 200 && value.chars().noneMatch(Character::isISOControl), "统计编码长度或格式无效");
        return value;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
