package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipAgingDTO.*;
import com.kitex.mom.mes.mapper.MesWipAgingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * 请求内同一只读可重复读观察；每次HTTP请求重新观察当前队列。
 */
@Service
public class MesWipAgingService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DEFINITION = "按实际当前未终结工序任务逐ID计数；FINISH/CLOSE排除，未知状态保留。"
            + "下达至观察为自然停留，首次开工至观察为自然历时，含等待/暂停；非净加工时间或当前状态时长。"
            + "严格超过阈值才超时；未知时间仍纳入队列分母。最大停留与最早下达仅代表可核定子集。"
            + "任务数不是唯一实物件数；缺历史平均在制量，不计算周转率。";
    private final MesActualFactoryAccessService access;
    private final MesWipAgingMapper mapper;
    private final MesWipAgingCalculator calculator;
    private final Clock clock;

    @Autowired
    public MesWipAgingService(MesActualFactoryAccessService access, MesWipAgingMapper mapper, MesWipAgingCalculator calculator) {
        this(access, mapper, calculator, Clock.system(BUSINESS_ZONE));
    }

    MesWipAgingService(MesActualFactoryAccessService access, MesWipAgingMapper mapper, MesWipAgingCalculator calculator, Clock clock) {
        this.access = access;
        this.mapper = mapper;
        this.calculator = calculator;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statistics statistics(Query input) {
        Context context = context();
        Query q = normalize(input);
        LocalDateTime observed = observed();
        LocalDateTime cutoff = observed.minusHours(Long.parseLong(q.getThresholdHours()));
        Coverage coverage = mapper.coverage(context.tenant(), context.factory(), q, observed, cutoff);
        boolean incomplete = calculator.coverageIncomplete(q, coverage);
        Summary summary = calculator.summary(mapper.summary(context.tenant(), context.factory(), q, observed, cutoff), observed, incomplete);
        List<Aggregate> aggregates = mapper.groups(context.tenant(), context.factory(), q, observed, cutoff);
        require(aggregates != null, "在制分组不可读取");
        require(aggregates.size() <= 1000, "在制分组超过1000组，请缩小筛选范围；未返回截断统计");
        List<Group> groups = aggregates.stream().map(a -> new Group(a.getGroupCode() == null ? "UNKNOWN" : a.getGroupCode(),
                a.getGroupCode() == null ? "未归属" : a.getGroupCode(), a.getGroupCode() == null,
                calculator.summary(a, observed, incomplete))).toList();
        return new Statistics().setTenantId(context.tenant().toString()).setFactoryCode(context.factory()).setActorId(context.actor())
                .setObservedAt(iso(observed)).setScope(scope(q)).setDefinition(DEFINITION).setDataGaps(calculator.gaps(q, coverage, summary))
                .setSummary(summary).setGroups(groups).setCoverage(coverage);
    }

    /**
     * 明细使用新观察时点，选择器不改变基础统计scope。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Details details(Query input) {
        Context context = context();
        Query q = normalize(input);
        LocalDateTime observed = observed();
        LocalDateTime cutoff = observed.minusHours(Long.parseLong(q.getThresholdHours()));
        Coverage coverage = mapper.coverage(context.tenant(), context.factory(), q, observed, cutoff);
        boolean incomplete = calculator.coverageIncomplete(q, coverage);
        Summary summary = calculator.summary(mapper.summary(context.tenant(), context.factory(), q, observed, cutoff), observed, incomplete);
        String total = mapper.detailTotal(context.tenant(), context.factory(), q, observed, cutoff);
        require(total != null && total.matches("0|[1-9][0-9]*"), "在制明细总数不可读取");
        List<Fact> facts = mapper.details(context.tenant(), context.factory(), q, observed, cutoff, (q.getCurrent() - 1L) * 30L);
        require(facts != null && facts.size() <= 30, "在制明细不可读取或分页无效");
        List<String> gaps = calculator.gaps(q, coverage, summary);
        gaps.add("明细为本次新观察，可能与之前统计请求不同，请比较observedAt");
        return new Details().setTenantId(context.tenant().toString()).setFactoryCode(context.factory()).setActorId(context.actor())
                .setObservedAt(iso(observed)).setScope(scope(q)).setDataGaps(gaps).setStatus(q.getStatus()).setAgingCategory(q.getAgingCategory())
                .setCurrent(q.getCurrent().toString()).setSelectedGroupCode(q.getSelectedGroupCode())
                .setSelectedGroupUnattributed(q.isSelectedGroupUnattributed()).setTotal(total)
                .setRows(facts.stream().map(f -> calculator.row(f, observed)).toList());
    }

    private Context context() {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        var actor = SecurityUtils.getUser();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
                && actor != null && actor.getId() != null, "在制统计缺少真实认证组织上下文");
        return new Context(tenant, factory, actor.getId().toString());
    }

    private Query normalize(Query input) {
        require(input != null, "在制筛选不可为空");
        Query q = new Query().setWorkshopCode(code(input.getWorkshopCode())).setTaskCode(code(input.getTaskCode()))
                .setRouteCode(code(input.getRouteCode())).setGroupBy(input.getGroupBy()).setThresholdHours(input.getThresholdHours())
                .setStatus(input.getStatus()).setAgingCategory(input.getAgingCategory()).setCurrent(input.getCurrent())
                .setSelectedGroupCode(code(input.getSelectedGroupCode())).setSelectedGroupUnattributed(input.isSelectedGroupUnattributed());
        require(Set.of("WORKSHOP", "TASK", "ROUTE").contains(safe(q.getGroupBy())), "不支持的在制分组");
        require(q.getThresholdHours() != null && q.getThresholdHours().matches("[1-9][0-9]{0,3}")
                && Integer.parseInt(q.getThresholdHours()) <= 8760, "阈值小时须为1至8760的正整数文本");
        require(Set.of("ALL", "WAIT", "UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE", "UNKNOWN").contains(safe(q.getStatus())), "不支持的明细状态");
        require(Set.of("ALL", "WITHIN", "OVER", "UNKNOWN").contains(safe(q.getAgingCategory())), "不支持的停留分类");
        require(q.getCurrent() != null && q.getCurrent() >= 1 && q.getCurrent() <= Long.MAX_VALUE / 30L + 1L,
                "页码须大于零且分页偏移不能超过数据库整数范围，每页固定30条");
        require(!q.isSelectedGroupUnattributed() || q.getSelectedGroupCode() == null, "分组编码与未归属分组不能同时选择");
        return q;
    }

    private String code(String raw) {
        require(raw == null || raw.chars().noneMatch(Character::isISOControl), "编码不能包含控制字符");
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        require(value.length() <= 100, "编码不能超过100字符");
        return value;
    }

    private Scope scope(Query q) {
        return new Scope(q.getWorkshopCode(), q.getTaskCode(), q.getRouteCode(), q.getGroupBy(), q.getThresholdHours());
    }

    private LocalDateTime observed() {
        return LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    private String iso(LocalDateTime value) {
        return value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    private record Context(Long tenant, String factory, String actor) {
    }
}
