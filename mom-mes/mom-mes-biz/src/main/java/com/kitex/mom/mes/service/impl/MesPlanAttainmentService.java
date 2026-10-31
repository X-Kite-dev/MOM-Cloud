package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.*;
import com.kitex.mom.mes.mapper.MesPlanAttainmentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * 同一请求的全量聚合、覆盖及分组在只读一致性事务中观察当前计划与当前状态。
 */
@Service
public class MesPlanAttainmentService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DEFINITION = "只读当前计划基准/当前状态，非历史快照；TASK按工单plan_end_date，PROCESS按工序任务planned_end_time，"
            + "截止时间落在[startTime,endTime)的对象逐ID计数；完成=有效FINISH，按时=finish_time<=计划截止；"
            + "两比率共用适用到期对象分母。CLOSE依显式策略计未完成或排除，CREATE草稿排除；不跨层合计产量。";
    private final MesActualFactoryAccessService access;
    private final MesPlanAttainmentMapper mapper;
    private final MesPlanAttainmentCalculator calculator;
    private final Clock clock;

    @Autowired
    public MesPlanAttainmentService(MesActualFactoryAccessService access, MesPlanAttainmentMapper mapper,
                                    MesPlanAttainmentCalculator calculator) {
        this(access, mapper, calculator, Clock.system(BUSINESS_ZONE));
    }

    MesPlanAttainmentService(MesActualFactoryAccessService access, MesPlanAttainmentMapper mapper,
                             MesPlanAttainmentCalculator calculator, Clock clock) {
        this.access = access;
        this.mapper = mapper;
        this.calculator = calculator;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statistics statistics(Query input) {
        Context context = context();
        Query query = normalize(input);
        LocalDateTime observed = observed();
        Coverage coverage = mapper.coverage(context.tenant(), context.factory(), query, observed);
        boolean incomplete = calculator.coverageIncomplete(query, coverage);
        Summary summary = calculator.summary(mapper.summary(context.tenant(), context.factory(), query, observed), query, incomplete);
        List<Aggregate> aggregates = mapper.groups(context.tenant(), context.factory(), query, observed);
        require(aggregates != null, "计划分组不可读取");
        require(aggregates.size() <= 1000, "计划分组超过1000组，请缩小筛选范围；未返回截断统计");
        List<Group> groups = aggregates.stream().map(group -> new Group(group.getGroupCode() == null ? "UNKNOWN" : group.getGroupCode(),
                group.getGroupCode() == null ? "未归属" : group.getGroupCode(), group.getGroupCode() == null,
                calculator.summary(group, query, incomplete))).toList();
        return new Statistics().setTenantId(context.tenant().toString()).setFactoryCode(context.factory()).setActorId(context.actor())
                .setObservedAt(iso(observed)).setScope(scope(query)).setProvisional(query.getEndTime().isAfter(observed))
                .setDefinition(DEFINITION).setDataGaps(calculator.gaps(query, coverage, summary))
                .setSummary(summary).setGroups(groups).setCoverage(coverage);
    }

    /**
     * 跨HTTP下钻重新观察并返回新observedAt；不承诺跨请求历史快照。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Details details(Query input) {
        Context context = context();
        Query query = normalize(input);
        LocalDateTime observed = observed();
        Coverage coverage = mapper.coverage(context.tenant(), context.factory(), query, observed);
        boolean incomplete = calculator.coverageIncomplete(query, coverage);
        Summary summary = calculator.summary(mapper.summary(context.tenant(), context.factory(), query, observed), query, incomplete);
        String total = mapper.detailTotal(context.tenant(), context.factory(), query, observed);
        require(total != null && total.matches("[0-9]+"), "计划明细总数不可读取");
        List<Fact> facts = mapper.details(context.tenant(), context.factory(), query, observed, (query.getCurrent() - 1L) * 30L);
        require(facts != null && facts.size() <= 30, "计划明细不可读取或分页无效");
        List<String> gaps = calculator.gaps(query, coverage, summary);
        gaps.add("明细为本次新观察，可能与之前统计请求的计划或状态不同，请比较observedAt");
        return new Details().setTenantId(context.tenant().toString()).setFactoryCode(context.factory()).setActorId(context.actor())
                .setObservedAt(iso(observed)).setScope(scope(query)).setDataGaps(gaps).setCategory(query.getCategory())
                .setCurrent(query.getCurrent().toString()).setSelectedGroupCode(query.getSelectedGroupCode())
                .setSelectedGroupUnattributed(query.isSelectedGroupUnattributed()).setTotal(total)
                .setRows(facts.stream().map(calculator::row).toList());
    }

    private Context context() {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        var actor = SecurityUtils.getUser();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
                && actor != null && actor.getId() != null, "计划统计缺少真实认证组织上下文");
        return new Context(tenant, factory, actor.getId().toString());
    }

    private Query normalize(Query input) {
        require(input != null && input.getStartTime() != null && input.getEndTime() != null
                && input.getEndTime().isAfter(input.getStartTime()), "请选择正向起止时间");
        require(Duration.between(input.getStartTime(), input.getEndTime()).compareTo(Duration.ofDays(366)) <= 0,
                "计划统计最多366天");
        require(input.getStartTime().getNano() % 1000 == 0 && input.getEndTime().getNano() % 1000 == 0,
                "时间精度最多六位小数");
        Query query = new Query().setObjectLevel(input.getObjectLevel()).setStartTime(input.getStartTime()).setEndTime(input.getEndTime())
                .setWorkshopCode(code(input.getWorkshopCode())).setTaskCode(code(input.getTaskCode())).setRouteCode(code(input.getRouteCode()))
                .setGroupBy(input.getGroupBy()).setClosedPolicy(input.getClosedPolicy()).setCategory(input.getCategory())
                .setCurrent(input.getCurrent()).setSelectedGroupCode(code(input.getSelectedGroupCode()))
                .setSelectedGroupUnattributed(input.isSelectedGroupUnattributed());
        require(Set.of("TASK", "PROCESS").contains(nullSafe(query.getObjectLevel())), "不支持的计划对象层级");
        require(Set.of("WORKSHOP", "TASK", "ROUTE").contains(nullSafe(query.getGroupBy())), "不支持的计划分组");
        require(Set.of("INCLUDE_UNFINISHED", "EXCLUDE_CLOSED").contains(nullSafe(query.getClosedPolicy())), "不支持的关闭策略");
        require(Set.of("ALL", "ON_TIME", "LATE", "OPEN", "CLOSED", "DRAFT", "UNKNOWN").contains(nullSafe(query.getCategory())), "不支持的明细分类");
        require("PROCESS".equals(query.getObjectLevel()) || (query.getRouteCode() == null && !"ROUTE".equals(query.getGroupBy())),
                "工序筛选和工序分组仅适用于PROCESS层级");
        require(query.getCurrent() != null && query.getCurrent() >= 1
                && query.getCurrent() <= Long.MAX_VALUE / 30L + 1L, "页码必须大于零且分页偏移不能超过数据库整数范围，每页固定30条");
        require(!query.isSelectedGroupUnattributed() || query.getSelectedGroupCode() == null, "分组编码与未归属分组不能同时选择");
        return query;
    }

    private String code(String raw) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        require(value.length() <= 100 && value.chars().noneMatch(Character::isISOControl), "编码格式无效或超过100字符");
        return value;
    }

    private Scope scope(Query query) {
        return new Scope(query.getObjectLevel(), iso(query.getStartTime()), iso(query.getEndTime()), query.getWorkshopCode(),
                query.getTaskCode(), query.getRouteCode(), query.getGroupBy(), query.getClosedPolicy());
    }

    private LocalDateTime observed() {
        return LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    private String iso(LocalDateTime time) {
        return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String nullSafe(String value) {
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
