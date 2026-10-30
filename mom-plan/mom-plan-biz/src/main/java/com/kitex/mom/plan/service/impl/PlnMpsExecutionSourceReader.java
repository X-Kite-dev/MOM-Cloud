package com.kitex.mom.plan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesPlanExecutionService;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMpsLineEntity;
import com.kitex.mom.plan.entity.PlnMpsVersionEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnMpsLineMapper;
import com.kitex.mom.plan.mapper.PlnMpsVersionMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MPS确定性来源批量读取器；仅Mapper和MES证据依赖，不调用MpsService或RCCP。
 */
@Component
@RequiredArgsConstructor
public class PlnMpsExecutionSourceReader {
    private final PlnMpsVersionMapper versionMapper;
    private final PlnMpsLineMapper lineMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnProductDemandMapper demandMapper;
    private final RemoteMesPlanExecutionService mesService;

    /**
     * PLAN调用方使用只读一致性事务；MES在其自己的同一快照内采集，非跨库分布式快照。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public PlnMpsExecutionDTO.Evidence read(Long versionId, int tolerance) {
        String factory = actualFactory();
        require(versionId != null && tolerance >= 0 && tolerance <= 1440, "MPS版本及0至1440分钟容差必填");
        Long tenant = TenantContextHolder.getTenantId();
        PlnMpsVersionEntity version = versionMapper.selectOne(new LambdaQueryWrapper<PlnMpsVersionEntity>()
                .eq(PlnMpsVersionEntity::getId, versionId).eq(PlnMpsVersionEntity::getTenantId, tenant)
                .eq(PlnMpsVersionEntity::getFactoryCode, factory));
        require(version != null && Set.of("PUBLISHED", "SUPERSEDED").contains(StrUtil.blankToDefault(version.getStatus(), "UNKNOWN")),
                "当前租户、工厂未找到已发布MPS版本，未批准候选不能当承诺兑现回评");
        List<PlnMpsLineEntity> lines = lineMapper.selectList(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getVersionId, versionId).eq(PlnMpsLineEntity::getTenantId, tenant)
                .eq(PlnMpsLineEntity::getFactoryCode, factory)
                .orderByAsc(PlnMpsLineEntity::getDemandId).orderByAsc(PlnMpsLineEntity::getBucketNo)
                .orderByAsc(PlnMpsLineEntity::getId).last("LIMIT 501"));
        require(!lines.isEmpty() && lines.size() <= 500, "MPS时间桶缺失或超过500，禁止截断后回评");
        List<PlnMpsExecutionDTO.Bucket> buckets = collect(lines, Map.of(versionId, version), tolerance);
        return new PlnMpsExecutionDTO.Evidence().setVersionId(String.valueOf(versionId)).setVersionNo(version.getVersionNo())
                .setVersionStatus(version.getStatus()).setFactoryCode(factory).setObservedAt(LocalDateTime.now().toString())
                .setToleranceMinutes(tolerance).setResult(result(buckets)).setBuckets(buckets);
    }

    /**
     * RCCP精确来源批量取证，共用原兑现算法且不依赖MpsService，防止读侧构造循环。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public List<PlnMpsExecutionDTO.Bucket> readBuckets(List<Long> lineIds, int tolerance) {
        String factory = actualFactory();
        Long tenant = TenantContextHolder.getTenantId();
        require(lineIds != null && lineIds.size() <= 500 && lineIds.stream().allMatch(Objects::nonNull)
                        && lineIds.stream().distinct().count() == lineIds.size() && tolerance >= 0 && tolerance <= 1440,
                "精确来源批量须完整唯一，最多500原桶；禁止截断");
        if (lineIds.isEmpty()) return List.of();
        var lines = lineMapper.selectList(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getTenantId, tenant).eq(PlnMpsLineEntity::getFactoryCode, factory)
                .in(PlnMpsLineEntity::getId, lineIds).orderByAsc(PlnMpsLineEntity::getId));
        require(lines.size() == lineIds.size(), "精确来源必须全属于实际组织");
        var versionIds = lines.stream().map(PlnMpsLineEntity::getVersionId).distinct().toList();
        var versions = versionMapper.selectList(new LambdaQueryWrapper<PlnMpsVersionEntity>()
                .eq(PlnMpsVersionEntity::getTenantId, tenant).eq(PlnMpsVersionEntity::getFactoryCode, factory)
                .in(PlnMpsVersionEntity::getId, versionIds));
        require(versions.size() == versionIds.size() && versions.stream().allMatch(row ->
                        Set.of("PUBLISHED", "SUPERSEDED").contains(StrUtil.blankToDefault(row.getStatus(), "UNKNOWN"))),
                "RCCP来源须原已发布版本，预留不得当成实际产出");
        return collect(lines, versions.stream().collect(Collectors.toMap(PlnMpsVersionEntity::getId, row -> row)), tolerance);
    }

    private List<PlnMpsExecutionDTO.Bucket> collect(List<PlnMpsLineEntity> lines,
                                                    Map<Long, PlnMpsVersionEntity> versions, int tolerance) {
        String factory = actualFactory();
        Long tenant = TenantContextHolder.getTenantId();
        List<PlnIssueEntity> roots = issueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getTenantId, tenant).eq(PlnIssueEntity::getFactoryCode, factory)
                .eq(PlnIssueEntity::getType, "issue").in(PlnIssueEntity::getMpsLineId,
                        lines.stream().map(PlnMpsLineEntity::getId).toList())
                .orderByAsc(PlnIssueEntity::getId).last("LIMIT 1001"));
        require(roots.size() <= 1000, "MPS根生产计划来源超过安全边界，请先治理重复来源");
        Map<Long, List<PlnIssueEntity>> rootsByLine = roots.stream().collect(Collectors.groupingBy(PlnIssueEntity::getMpsLineId));
        var demandIds = lines.stream().map(PlnMpsLineEntity::getDemandId).filter(Objects::nonNull).distinct().toList();
        List<PlnProductDemandEntity> demands = demandIds.isEmpty() ? List.of()
                : demandMapper.selectList(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getTenantId, tenant).eq(PlnProductDemandEntity::getFactoryCode, factory)
                .in(PlnProductDemandEntity::getId, demandIds));
        var demandsById = demands.stream().collect(Collectors.toMap(PlnProductDemandEntity::getId, row -> row));
        List<String> planNos = roots.stream().map(PlnIssueEntity::getIssueNo).filter(StrUtil::isNotBlank).distinct().sorted().toList();
        require(planNos.size() <= 500, "独立根计划超过500，来源数量不可截断");
        Map<String, MesPlanExecutionEvidenceDTO.Plan> mes = new HashMap<>();
        String remoteGap = null;
        if (!planNos.isEmpty()) {
            try {
                var response = mesService.query(new MesPlanExecutionEvidenceDTO.Query().setFactoryCode(factory)
                        .setPlanNos(planNos).setIncludeProductionEvents(true));
                require(response != null && response.isOk() && response.getData() != null, "MES执行证据查询失败或无回执");
                var evidence = response.getData();
                require(Objects.equals(evidence.getTenantId(), tenant) && Objects.equals(evidence.getFactoryCode(), factory)
                        && evidence.getPlans() != null && evidence.getPlans().size() == planNos.size(), "MES返回组织或来源覆盖不完整");
                for (var plan : evidence.getPlans()) {
                    require(plan != null && planNos.contains(plan.getPlanNo()) && plan.getLinks() != null && plan.getGaps() != null
                            && mes.put(plan.getPlanNo(), plan) == null, "MES返回重复、范围外或不完整的来源证据");
                }
            } catch (Exception exception) {
                mes.clear();
                remoteGap = exception instanceof CheckedException ? StrUtil.subPre(exception.getMessage(), 250)
                        : "MES证据不可用或无查询权限，不采用旧成功数据补数";
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<PlnMpsExecutionDTO.Bucket> buckets = new ArrayList<>();
        for (PlnMpsLineEntity line : lines) {
            List<PlnIssueEntity> matched = rootsByLine.getOrDefault(line.getId(), List.of());
            var row = bucket(versions.get(line.getVersionId()), line, matched, mes, remoteGap, now, tolerance);
            var demand = demandsById.get(line.getDemandId());
            if (demand != null && Objects.equals(demand.getProductCode(), line.getProductCode())
                    && Objects.equals(demand.getDemandNo(), line.getDemandNo())) {
                row.setProductUnit(StrUtil.emptyToNull(StrUtil.trim(demand.getProductUnit())));
            }
            buckets.add(row);
        }
        return buckets;
    }

    private String result(List<PlnMpsExecutionDTO.Bucket> buckets) {
        return buckets.stream().anyMatch(row -> !row.getGaps().isEmpty() || !row.getQuantityGaps().isEmpty())
                ? "DATA_GAP" : buckets.stream().anyMatch(row -> Boolean.TRUE.equals(row.getOverdue())
                || Boolean.TRUE.equals(row.getCompletedAfterPlannedDate()) || Boolean.TRUE.equals(row.getCompletedAfterCustomerDue())
                || "EXECUTION_BLOCKED".equals(row.getRoute())) ? "DEVIATION" : "CLEAR";
    }

    private PlnMpsExecutionDTO.Bucket bucket(PlnMpsVersionEntity version, PlnMpsLineEntity line,
                                             List<PlnIssueEntity> roots, Map<String, MesPlanExecutionEvidenceDTO.Plan> mes, String remoteGap,
                                             LocalDateTime now, int tolerance) {
        List<String> gaps = new ArrayList<>();
        List<String> quantityGaps = new ArrayList<>();
        var row = new PlnMpsExecutionDTO.Bucket().setLineId(String.valueOf(line.getId())).setBucketKey(line.getBucketKey())
                .setDemandNo(line.getDemandNo()).setBucketNo(line.getBucketNo()).setProductCode(line.getProductCode())
                .setPlanQuantity(decimal(line.getPlanQuantity())).setPlannedDate(text(line.getPlannedDate()))
                .setSourceDueDate(text(line.getSourceDueDate())).setActive(line.getActive())
                .setCapacityResourceCode(line.getCapacityResourceCode()).setCapacityDemandMinutes(decimal(line.getCapacityDemandMinutes()))
                .setGaps(gaps).setQuantityGaps(quantityGaps).setLinks(List.of());
        row.setPlanSnapshot(JSONUtil.toJsonStr(Arrays.asList(String.valueOf(version.getId()), version.getStatus(),
                String.valueOf(version.getPolicyId()), version.getPolicyVersion(), version.getEvidenceFingerprint(),
                line.getBucketKey(), line.getActive(), decimal(line.getPlanQuantity()), line.getPlannedDate(), line.getSourceDueDate(),
                line.getCapacityEvidenceFingerprint(), line.getCapacityEvidenceSnapshot(), line.getLotPolicyEvidenceFingerprint(),
                roots.stream().map(root -> Arrays.asList(String.valueOf(root.getId()), root.getIssueNo(), root.getMpsLineId() == null
                                ? null : root.getMpsLineId().toString(), root.getPlanningSourceKey(), root.getProductCode(), decimal(root.getNum()),
                        root.getStatus(), decimal(root.getFinishNum()), root.getFinishTime(), root.getScheduledDate(), root.getScheduledEndDate(),
                        decimal(root.getUnqualifiedNum()), decimal(root.getQualityIsolatedNum()), decimal(root.getQualityReleasedNum()),
                        decimal(root.getQualityRejectedNum()), decimal(root.getScrapNum()), decimal(root.getReworkNum()),
                        decimal(root.getReworkCompletedNum()), decimal(root.getReworkQualifiedNum()), decimal(root.getReworkRejectedNum()))).toList())));
        if (line.getPlanQuantity() == null || line.getPlanQuantity().signum() <= 0 || line.getPlannedDate() == null
                || line.getSourceDueDate() == null || line.getActive() == null || StrUtil.isBlank(line.getBucketKey())) {
            gaps.add("MPS原始承诺数量、日期、活动状态或桶来源键缺失");
        }
        if (Boolean.TRUE.equals(line.getActive()) && !Boolean.FALSE.equals(line.getPending())) {
            gaps.add("活动原桶仍待审或缺待审状态，不能作为已生效来源");
        }
        if (roots.size() != 1) {
            gaps.add("独立根生产计划匹配" + roots.size() + "条，禁止按下层自制计划或重复根计划合计");
            return route(row);
        }
        PlnIssueEntity root = roots.get(0);
        row.setRootIssueId(String.valueOf(root.getId())).setRootIssueNo(root.getIssueNo()).setRootStatus(root.getStatus())
                .setPlanReceivedQuantity(decimal(root.getFinishNum()));
        if (!Objects.equals(root.getPlanningSourceKey(), line.getBucketKey())
                || !Objects.equals(root.getProductCode(), line.getProductCode()) || root.getNum() == null
                || line.getPlanQuantity() == null || root.getNum().compareTo(line.getPlanQuantity()) != 0
                || root.getStatus() == null || !Set.of("0", "1", "2", "3", "4").contains(root.getStatus())) {
            gaps.add("根生产计划来源键、产品、数量或状态与原MPS承诺不一致");
        }
        if (root.getFinishNum() != null && (root.getFinishNum().signum() < 0
                || root.getNum() != null && root.getFinishNum().compareTo(root.getNum()) > 0)) {
            gaps.add("PLAN累计回传数量越界，禁止作为有效兑现数");
        }
        if (remoteGap != null) {
            gaps.add(remoteGap);
            return route(row);
        }
        MesPlanExecutionEvidenceDTO.Plan plan = mes.get(root.getIssueNo());
        if (plan == null) {
            gaps.add("MES未完整返回精确根计划的执行范围");
            return route(row);
        }
        gaps.addAll(plan.getGaps());
        row.setLinks(plan.getLinks().stream().filter(Objects::nonNull)
                .map(this::sourceLink).toList());
        if (plan.getLinks().isEmpty()) {
            if (!"0".equals(root.getStatus()) || root.getFinishNum() != null && root.getFinishNum().signum() > 0) {
                gaps.add("PLAN已下达/执行，但MES缺少当前有效来源工单");
            } else {
                row.setStarted(false).setCompleted(false).setCompletedQuantity("0").setInStockQuantity("0")
                        .setRemainingQuantity(row.getPlanQuantity());
            }
        } else {
            List<MesPlanExecutionEvidenceDTO.Link> links = plan.getLinks();
            if (links.stream().anyMatch(link -> link == null || link.getTaskId() == null || StrUtil.isBlank(link.getTaskCode())
                    || link.getStarted() == null || link.getAllOperationsMovable() == null
                    || !Objects.equals(link.getProductCode(), line.getProductCode())
                    || "APS_BATCH_ALLOCATION".equals(link.getRelationType())
                    && (link.getAllocationId() == null || !Objects.equals(link.getSourceIssueId(), root.getId()))
                    || !Set.of("DIRECT_PLAN", "APS_BATCH_ALLOCATION").contains(StrUtil.blankToDefault(link.getRelationType(), "UNKNOWN")))) {
                gaps.add("MES来源工单、产品或APS根计划引用不一致，或缺少完整工单证据");
                return route(row);
            }
            Set<String> identities = links.stream().map(link -> link.getTaskId() + ":" + link.getAllocationId()).collect(Collectors.toSet());
            if (identities.size() != links.size()) {
                gaps.add("MES同来源重复映射，不能重复计量");
            }
            BigDecimal allocated = sum(links, "SOURCE");
            if (allocated == null || line.getPlanQuantity() == null || allocated.compareTo(line.getPlanQuantity()) != 0) {
                gaps.add("MES来源分配数量不能完整守恒到MPS桶");
            }
            BigDecimal completed = sum(links, "COMPLETE");
            BigDecimal stock = sum(links, "STOCK");
            if (completed == null) {
                quantityGaps.add("合批末序有效报工尚缺完整当前来源核定，或纠正后旧核定失效；请到MES报工来源核定核清，不能比例分摊或填零");
            } else if (completed.signum() < 0 || line.getPlanQuantity() != null && completed.compareTo(line.getPlanQuantity()) > 0) {
                gaps.add("MES来源完成量越界");
            } else {
                row.setCompletedQuantity(decimal(completed));
                if (line.getPlanQuantity() != null) {
                    row.setRemainingQuantity(decimal(line.getPlanQuantity().subtract(completed)));
                }
            }
            if (stock == null) {
                quantityGaps.add("MES来源入库量不完整，不能认定PLAN回传一致");
            } else {
                row.setInStockQuantity(decimal(stock));
                if (stock.signum() < 0 || line.getPlanQuantity() != null && stock.compareTo(line.getPlanQuantity()) > 0) {
                    gaps.add("MES来源入库量越界");
                }
                if (root.getFinishNum() != null && root.getFinishNum().compareTo(stock) != 0
                        || root.getFinishNum() == null && stock.signum() > 0) {
                    quantityGaps.add("PLAN已接收累计与MES来源入库量不一致，先核查正负回传，不与报工量混比");
                }
            }
            boolean started = links.stream().anyMatch(link -> Boolean.TRUE.equals(link.getStarted()));
            row.setStarted(started);
            boolean complete = completed != null && line.getPlanQuantity() != null
                    && completed.compareTo(line.getPlanQuantity()) == 0 && links.stream().allMatch(link ->
                    ("FINISH".equals(link.getStatus()) || "CLOSE".equals(link.getStatus())) && link.getActualFinishTime() != null);
            row.setCompleted(complete);
            row.setActualStartTime(links.stream().map(MesPlanExecutionEvidenceDTO.Link::getActualStartTime)
                    .filter(Objects::nonNull).min(Comparator.comparing(LocalDateTime::parse)).orElse(null));
            if (complete) {
                row.setActualFinishTime(links.stream().map(MesPlanExecutionEvidenceDTO.Link::getActualFinishTime)
                        .max(Comparator.comparing(LocalDateTime::parse)).orElse(null));
            }
        }
        if (gaps.isEmpty() && line.getPlannedDate() != null && row.getCompleted() != null) {
            LocalDateTime deadline = line.getPlannedDate().plusDays(1).atStartOfDay().plusMinutes(tolerance);
            if (Boolean.TRUE.equals(line.getActive())) {
                row.setOverdue(!row.getCompleted() && !now.isBefore(deadline));
            }
            if (row.getCompleted() && row.getActualFinishTime() != null && line.getSourceDueDate() != null) {
                row.setCompletedAfterCustomerDue(!LocalDateTime.parse(row.getActualFinishTime())
                        .isBefore(line.getSourceDueDate().plusDays(1).atStartOfDay()));
                row.setCompletedAfterPlannedDate(!LocalDateTime.parse(row.getActualFinishTime()).isBefore(deadline));
            }
        }
        return route(row);
    }

    private PlnMpsExecutionDTO.Bucket route(PlnMpsExecutionDTO.Bucket row) {
        if (Boolean.FALSE.equals(row.getActive())) {
            return row.setRoute("RETIRED_HISTORY").setNextStep("历史承诺仅复盘，继续处理当前活动版本，不恢复旧桶；历史缺口保留说明");
        }
        if (!row.getGaps().isEmpty()) {
            return row.setRoute("DATA_REPAIR_REQUIRED").setNextStep("先核查来源/组织/工单/数量事实，重新采集；禁止用未知数据滚动发布");
        }
        if (!row.getQuantityGaps().isEmpty()) {
            return row.setRoute("QUANTITY_RECONCILE").setNextStep("核查合批完成归属或PLAN入库正负回传；保留已开工事实，不自动改写累计");
        }
        if (Boolean.TRUE.equals(row.getCompleted())) {
            return row.setRoute("COMPLETED_REVIEW").setNextStep("复核报工兑现与原客户交期；后续质量放行、入库和交付仍各自核验");
        }
        if (row.getLinks().stream().anyMatch(link -> link.getPausedOperationCount() != null && link.getPausedOperationCount() > 0)) {
            return row.setRoute("EXECUTION_BLOCKED").setNextStep("先在MES处理暂停、设备/质量/物料门，恢复生产后重采，不用改计划掩盖现场阻断");
        }
        if (Boolean.TRUE.equals(row.getOverdue())) {
            boolean movable = !row.getLinks().isEmpty() && row.getLinks().stream()
                    .allMatch(link -> Boolean.TRUE.equals(link.getAllOperationsMovable()));
            return row.setRoute(Boolean.TRUE.equals(row.getStarted()) ? "STARTED_REPLAN_REVIEW"
                            : movable ? "PRESTART_REPLAN_REVIEW" : "DISPATCH_REVIEW")
                    .setNextStep(Boolean.TRUE.equals(row.getStarted())
                            ? "已执行工序保持锁定；从APS正式排程/效果回评核查剩余未开工偏差，另走既有局部案件。本观察不生成候选，不支持已开工MPS全量替换"
                            : movable ? "从APS评估可移动工序，或在MPS原受控入口复核冻结区和下游撤回，仍须异岗发布"
                            : "核查尚未下达/已接收派工及齐套，不能仅改MPS计划日消除逾期");
        }
        return row.setRoute(Boolean.TRUE.equals(row.getStarted()) ? "IN_PROGRESS" : "WAITING_EXECUTION")
                .setNextStep("继续按当前正式生产计划执行，后续报工/冲销/入库后重新采集兑现观察");
    }

    /**
     * 泛型集合显式投影，避免Bean复制把MES事件对象直接塞入PLAN边界。
     */
    private PlnMpsExecutionDTO.SourceLink sourceLink(MesPlanExecutionEvidenceDTO.Link source) {
        var result = BeanUtil.toBean(source, PlnMpsExecutionDTO.SourceLink.class);
        result.setProductionEvents(source.getProductionEvents() == null ? null : source.getProductionEvents().stream()
                .map(event -> BeanUtil.toBean(event, PlnMpsExecutionDTO.ProductionEvent.class)).toList());
        return result;
    }

    private BigDecimal sum(List<MesPlanExecutionEvidenceDTO.Link> links, String kind) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var link : links) {
            String value = "SOURCE".equals(kind) ? link.getSourceQuantity()
                    : "COMPLETE".equals(kind) ? link.getCompletedQuantity() : link.getInStockQuantity();
            if (value == null) {
                return null;
            }
            try {
                sum = sum.add(new BigDecimal(value));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return sum;
    }

    String actualFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
                "MPS执行回评须选择实际租户、工厂，禁止跳过隔离");
        require(SecurityUtils.getUser() != null && StrUtil.isNotBlank(SecurityUtils.getUser().getUsername()),
                "执行来源取证须实际实名账号");
        return factory;
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
