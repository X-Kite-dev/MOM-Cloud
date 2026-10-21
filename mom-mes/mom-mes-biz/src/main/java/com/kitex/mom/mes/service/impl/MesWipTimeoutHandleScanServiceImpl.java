package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mes.dto.WipTimeoutNotifyResult;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesWarehouseMatDetailTypeEnum;
import com.kitex.mom.mes.mapper.MesWipTimeoutHandleMapper;
import com.kitex.mom.mes.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MesWipTimeoutHandleScanServiceImpl implements MesWipTimeoutHandleScanService {

    private static final int DEFAULT_COOLDOWN_MIN = 30;
    private static final String OPEN = "0";
    private static final String WAIT_VERIFY = "1";
    private static final String CLOSED = "2";
    private static final String AUTO_CLOSED = "3";
    private static final String CANCELLED = "9";

    private final MesWipTimeoutConfigService configService;
    private final MesWarehouseLiveStockService liveStockService;
    private final MesTaskArrangeService taskArrangeService;
    private final MesWipTimeoutHandleMapper handleMapper;
    private final MesWipTimeoutNotifyService notifyService;
    private final MesWipTimeoutOperationService operationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void scanAndEscalate() {
        LocalDateTime now = LocalDateTime.now();
        List<MesWipTimeoutConfigEntity> configs = configService.list(
                new LambdaQueryWrapper<MesWipTimeoutConfigEntity>()
                        .eq(MesWipTimeoutConfigEntity::getEnabled, Boolean.TRUE));
        List<MesWarehouseLiveStockEntity> wipList = liveStockService.list(
                new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                        .eq(MesWarehouseLiveStockEntity::getMatDetailType, MesWarehouseMatDetailTypeEnum.WIP.name())
                        .isNotNull(MesWarehouseLiveStockEntity::getInTime)
                        .isNotNull(MesWarehouseLiveStockEntity::getMatBarcode)
                        .gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO));
        Map<String, MesTaskArrangeEntity> arrangeMap = loadArranges(wipList);
        Set<String> seenIncidentKeys = new HashSet<>();
        int created = 0;
        int escalated = 0;
        int reopened = 0;
        int notified = 0;

        for (MesWarehouseLiveStockEntity stock : wipList) {
            MesTaskArrangeEntity arrange = arrangeMap.get(stock.getBillNo());
            Candidate candidate = buildCandidate(stock, arrange);
            MesWipTimeoutConfigEntity config = resolveConfig(configs, candidate, stock, seenIncidentKeys);
            if (config == null) {
                continue;
            }
            long elapsedSeconds = Duration.between(stock.getInTime(), now).getSeconds();
            if (elapsedSeconds < 0) {
                log.warn("忽略进站时间晚于当前时间的WIP, stockId={}, inTime={}", stock.getId(), stock.getInTime());
                continue;
            }
            int overtimeSec = elapsedSeconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsedSeconds;
            Integer targetLevel = calcLevel(config, overtimeSec);
            if (targetLevel == null) {
                continue;
            }

            String incidentKey = buildIncidentKey(config.getCode(), stock);
            seenIncidentKeys.add(incidentKey);
            MesWipTimeoutHandleEntity record = lockByIncidentKey(incidentKey);
            boolean forceNotify = false;
            if (record == null) {
                record = createRecord(config, stock, arrange, candidate, incidentKey, targetLevel, overtimeSec, now);
                handleMapper.insert(record);
                saveSystemOperation(record, "CREATE", null, OPEN,
                        "首次超过L" + targetLevel + "阈值，超时" + overtimeSec + "秒");
                created++;
                forceNotify = true;
            } else if (CANCELLED.equals(record.getStatus())) {
                continue;
            } else {
                refreshSnapshot(record, stock, arrange, candidate, targetLevel, overtimeSec, now);
                if (shouldReopen(record, now)) {
                    String before = record.getStatus();
                    reopen(record, targetLevel, now);
                    saveSystemOperation(record, "REOPEN", before, OPEN,
                            "承诺解除时间已到但来源WIP仍存在，自动重新打开");
                    reopened++;
                    forceNotify = true;
                } else if (CLOSED.equals(record.getStatus()) || AUTO_CLOSED.equals(record.getStatus())) {
                    handleMapper.updateById(record);
                    continue;
                } else if (targetLevel > defaultLevel(record.getAlertLevel())) {
                    int oldLevel = defaultLevel(record.getAlertLevel());
                    record.setAlertLevel(targetLevel);
                    record.setAlertTime(now);
                    saveSystemOperation(record, "ESCALATE", record.getStatus(), record.getStatus(),
                            "报警等级由L" + oldLevel + "升级为L" + targetLevel);
                    escalated++;
                    forceNotify = OPEN.equals(record.getStatus());
                }
                handleMapper.updateById(record);
            }

            int notifyLevel = defaultLevel(record.getAlertLevel());
            if (OPEN.equals(record.getStatus()) && (forceNotify || needNotify(config, record, notifyLevel, now))) {
                WipTimeoutNotifyResult result = notifyService.notifyLevel(config, record, notifyLevel);
                markNotify(record, notifyLevel, now, result);
                handleMapper.updateById(record);
                saveSystemOperation(record, "NOTIFY", record.getStatus(), record.getStatus(),
                        "L" + notifyLevel + "通知；通道=" + StrUtil.nullToEmpty(result.getChannels())
                                + "；结果=" + StrUtil.nullToEmpty(result.getResult())
                                + "；说明=" + StrUtil.nullToEmpty(result.getDetail()));
                notified++;
            }
        }

        int autoClosed = closeMissingActiveEvents(seenIncidentKeys, now);
        log.info("WIP超时扫描完成, 配置数={}, 在制品数={}, 新建={}, 升级={}, 重开={}, 通知={}, 自动解除={}",
                configs.size(), wipList.size(), created, escalated, reopened, notified, autoClosed);
    }

    private Map<String, MesTaskArrangeEntity> loadArranges(List<MesWarehouseLiveStockEntity> stocks) {
        List<String> codes = stocks.stream().map(MesWarehouseLiveStockEntity::getBillNo)
                .filter(StrUtil::isNotBlank).distinct().toList();
        if (codes.isEmpty()) {
            return new HashMap<>();
        }
        return taskArrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .in(MesTaskArrangeEntity::getCode, codes)).stream()
                .collect(Collectors.toMap(MesTaskArrangeEntity::getCode, Function.identity(), (left, right) -> left));
    }

    private Candidate buildCandidate(MesWarehouseLiveStockEntity stock, MesTaskArrangeEntity arrange) {
        String routeCode = firstNonBlank(stock.getRouteCode(), stock.getSiteCode(),
                arrange == null ? null : arrange.getRouteCode());
        String lineCode = firstNonBlank(stock.getWarehouseCode(),
                arrange == null ? null : arrange.getRouteWorkCenter());
        return new Candidate(lineCode, routeCode, stock.getMatCode());
    }

    private MesWipTimeoutConfigEntity resolveConfig(List<MesWipTimeoutConfigEntity> configs, Candidate candidate,
                                                    MesWarehouseLiveStockEntity stock,
                                                    Set<String> seenIncidentKeys) {
        List<MesWipTimeoutConfigEntity> matched = configs.stream()
                .filter(config -> scopeMatches(config.getLineCode(), candidate.lineCode()))
                .filter(config -> scopeMatches(config.getRouteCode(), candidate.routeCode()))
                .filter(config -> scopeMatches(config.getProductCode(), candidate.productCode()))
                .toList();
        if (matched.isEmpty()) {
            return null;
        }
        int highest = matched.stream().mapToInt(this::specificity).max().orElse(0);
        List<MesWipTimeoutConfigEntity> preferred = matched.stream()
                .filter(config -> specificity(config) == highest)
                .sorted(Comparator.comparing(MesWipTimeoutConfigEntity::getCode,
                        Comparator.nullsLast(String::compareTo))).toList();
        if (preferred.size() > 1) {
            // 配置歧义属于配置数据异常，不能因此把仍在现场的活动事件误判为来源消失。
            preferred.forEach(config -> seenIncidentKeys.add(buildIncidentKey(config.getCode(), stock)));
            log.error("WIP超时配置歧义，范围同优先级匹配{}条，line={}, route={}, product={}, configs={}",
                    preferred.size(), candidate.lineCode(), candidate.routeCode(), candidate.productCode(),
                    preferred.stream().map(MesWipTimeoutConfigEntity::getCode).toList());
            return null;
        }
        return preferred.get(0);
    }

    private boolean scopeMatches(String configured, String actual) {
        return StrUtil.isBlank(configured) || Objects.equals(configured, actual);
    }

    private int specificity(MesWipTimeoutConfigEntity config) {
        int score = 0;
        if (StrUtil.isNotBlank(config.getLineCode())) {
            score += 1;
        }
        if (StrUtil.isNotBlank(config.getRouteCode())) {
            score += 2;
        }
        if (StrUtil.isNotBlank(config.getProductCode())) {
            score += 4;
        }
        return score;
    }

    private MesWipTimeoutHandleEntity createRecord(MesWipTimeoutConfigEntity config,
                                                   MesWarehouseLiveStockEntity stock,
                                                   MesTaskArrangeEntity arrange,
                                                   Candidate candidate, String incidentKey,
                                                   int targetLevel, int overtimeSec, LocalDateTime now) {
        MesWipTimeoutHandleEntity record = new MesWipTimeoutHandleEntity();
        record.setIncidentKey(incidentKey);
        record.setSourceStockId(stock.getId());
        record.setSourceReportId(stock.getSourceReportId());
        record.setConfigCode(config.getCode());
        record.setLineCode(candidate.lineCode());
        record.setRouteCode(candidate.routeCode());
        record.setProductCode(candidate.productCode());
        record.setTransferCode(stock.getMatBarcode());
        record.setEnterTime(stock.getInTime());
        record.setStatus(OPEN);
        record.setAlertLevel(targetLevel);
        record.setAlertTime(now);
        record.setOvertimeSec(overtimeSec);
        record.setSourcePresent(true);
        record.setLastSeenAt(now);
        record.setReopenCount(0);
        record.setFactoryCode(stock.getFactoryCode());
        applyBusinessLink(record, stock, arrange);
        return record;
    }

    private void refreshSnapshot(MesWipTimeoutHandleEntity record, MesWarehouseLiveStockEntity stock,
                                 MesTaskArrangeEntity arrange,
                                 Candidate candidate, int targetLevel, int overtimeSec, LocalDateTime now) {
        record.setSourcePresent(true);
        record.setLastSeenAt(now);
        record.setOvertimeSec(overtimeSec);
        record.setLineCode(candidate.lineCode());
        record.setRouteCode(candidate.routeCode());
        record.setProductCode(candidate.productCode());
        applyBusinessLink(record, stock, arrange);
        if (record.getAlertLevel() == null) {
            record.setAlertLevel(targetLevel);
        }
    }

    private void applyBusinessLink(MesWipTimeoutHandleEntity record, MesWarehouseLiveStockEntity stock,
                                   MesTaskArrangeEntity arrange) {
        record.setArrangeCode(stock.getBillNo());
        record.setSourceStockId(stock.getId());
        record.setSourceReportId(stock.getSourceReportId());
        record.setProduceTaskCode(stock.getProduceTaskCode());
        if (arrange != null) {
            record.setTaskCode(arrange.getTaskCode());
            record.setWorkshopCode(arrange.getWorkshopCode());
            record.setWorkshopName(arrange.getWorkshopName());
            record.setWorksiteCode(arrange.getActualWorksiteCode());
            record.setWorksiteName(resolveActualWorksiteName(arrange));
        }
    }

    private String resolveActualWorksiteName(MesTaskArrangeEntity arrange) {
        List<String> codes = splitCsv(arrange.getWorksiteCodes());
        List<String> names = splitCsv(arrange.getWorksiteNames());
        int index = codes.indexOf(arrange.getActualWorksiteCode());
        return index >= 0 && index < names.size() ? names.get(index) : null;
    }

    private MesWipTimeoutHandleEntity lockByIncidentKey(String incidentKey) {
        return handleMapper.selectOne(new LambdaQueryWrapper<MesWipTimeoutHandleEntity>()
                .eq(MesWipTimeoutHandleEntity::getIncidentKey, incidentKey)
                .orderByDesc(MesWipTimeoutHandleEntity::getId)
                .last("limit 1 FOR UPDATE"));
    }

    private boolean shouldReopen(MesWipTimeoutHandleEntity record, LocalDateTime now) {
        if (AUTO_CLOSED.equals(record.getStatus())) {
            return true;
        }
        return CLOSED.equals(record.getStatus())
                && (record.getExpectedResolveTime() == null || !now.isBefore(record.getExpectedResolveTime()));
    }

    private void reopen(MesWipTimeoutHandleEntity record, int level, LocalDateTime now) {
        record.setStatus(OPEN);
        record.setAlertLevel(level);
        record.setAlertTime(now);
        record.setReopenCount((record.getReopenCount() == null ? 0 : record.getReopenCount()) + 1);
        record.setAssignedUserCodes(null);
        record.setAssignedUserNames(null);
        record.setHandlePersonCode(null);
        record.setHandlePersonName(null);
        record.setHandleTime(null);
        record.setHandleDesc(null);
        record.setRootCause(null);
        record.setCorrectiveAction(null);
        record.setDisposition(null);
        record.setExpectedResolveTime(null);
        record.setVerificationResult(null);
        record.setVerificationRemark(null);
        record.setVerifierCode(null);
        record.setVerifierName(null);
        record.setVerificationTime(null);
        record.setCloseTime(null);
        record.setCloseReason(null);
        record.setLastNotifyAt(null);
        record.setLastNotifyChannels(null);
        record.setLastNotifyResult(null);
        record.setLastNotifiedLevel(null);
    }

    private Integer calcLevel(MesWipTimeoutConfigEntity config, int overtimeSec) {
        if (config.getTimeoutSec() == null || overtimeSec < config.getTimeoutSec()) {
            return null;
        }
        if (config.getLevel3Sec() != null && overtimeSec >= config.getLevel3Sec()) {
            return 3;
        }
        if (config.getLevel2Sec() != null && overtimeSec >= config.getLevel2Sec()) {
            return 2;
        }
        return 1;
    }

    private boolean needNotify(MesWipTimeoutConfigEntity config, MesWipTimeoutHandleEntity record,
                               int level, LocalDateTime now) {
        if (!Objects.equals(record.getLastNotifiedLevel(), level) || record.getLastNotifyAt() == null) {
            return true;
        }
        int cooldownMin = config.getNotifyCooldownMin() == null
                ? DEFAULT_COOLDOWN_MIN : config.getNotifyCooldownMin();
        return !now.isBefore(record.getLastNotifyAt().plusMinutes(cooldownMin));
    }

    private void markNotify(MesWipTimeoutHandleEntity record, int level, LocalDateTime now,
                            WipTimeoutNotifyResult result) {
        if (level == 1 && record.getLevel1NotifiedAt() == null) {
            record.setLevel1NotifiedAt(now);
        } else if (level == 2 && record.getLevel2NotifiedAt() == null) {
            record.setLevel2NotifiedAt(now);
        } else if (level == 3 && record.getLevel3NotifiedAt() == null) {
            record.setLevel3NotifiedAt(now);
        }
        record.setLastNotifyAt(now);
        record.setLastNotifyChannels(result.getChannels());
        record.setLastNotifyResult(result.getResult());
        record.setLastNotifiedLevel(level);
        record.setAssignedUserCodes(mergeCsv(record.getAssignedUserCodes(), result.getRecipientCodes()));
        record.setAssignedUserNames(mergeCsv(record.getAssignedUserNames(), result.getRecipientNames()));
    }

    private int closeMissingActiveEvents(Set<String> seenIncidentKeys, LocalDateTime now) {
        List<MesWipTimeoutHandleEntity> active = handleMapper.selectList(
                new LambdaQueryWrapper<MesWipTimeoutHandleEntity>()
                        .in(MesWipTimeoutHandleEntity::getStatus, OPEN, WAIT_VERIFY));
        int count = 0;
        for (MesWipTimeoutHandleEntity candidate : active) {
            if (seenIncidentKeys.contains(candidate.getIncidentKey())) {
                continue;
            }
            MesWipTimeoutHandleEntity record = handleMapper.selectOne(
                    new LambdaQueryWrapper<MesWipTimeoutHandleEntity>()
                            .eq(MesWipTimeoutHandleEntity::getId, candidate.getId()).last("FOR UPDATE"));
            if (record == null || (!OPEN.equals(record.getStatus()) && !WAIT_VERIFY.equals(record.getStatus()))) {
                continue;
            }
            String before = record.getStatus();
            record.setStatus(AUTO_CLOSED);
            record.setSourcePresent(false);
            record.setCloseTime(now);
            record.setCloseReason("来源WIP已离站、数量归零、低于阈值或不再匹配启用规则");
            handleMapper.updateById(record);
            saveSystemOperation(record, "AUTO_CLOSE", before, AUTO_CLOSED, record.getCloseReason());
            count++;
        }
        return count;
    }

    private void saveSystemOperation(MesWipTimeoutHandleEntity record, String type,
                                     String before, String after, String content) {
        operationService.save(new MesWipTimeoutOperationEntity()
                .setRequestId(IdUtil.fastSimpleUUID())
                .setHandleId(record.getId())
                .setIncidentKey(record.getIncidentKey())
                .setOperationType(type)
                .setBeforeStatus(before)
                .setAfterStatus(after)
                .setContent(StrUtil.subPre(StrUtil.nullToEmpty(content), 2000))
                .setOperatorCode("SYSTEM")
                .setOperatorName("WIP超时扫描任务")
                .setOperationTime(LocalDateTime.now())
                .setFactoryCode(record.getFactoryCode()));
    }

    private String buildIncidentKey(String configCode, MesWarehouseLiveStockEntity stock) {
        return configCode + ":" + stock.getId() + ":" + stock.getInTime();
    }

    private int defaultLevel(Integer level) {
        return level == null ? 0 : level;
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(StrUtil::isNotBlank).findFirst().orElse(null);
    }

    private List<String> splitCsv(String value) {
        if (StrUtil.isBlank(value)) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).toList();
    }

    private String mergeCsv(String left, String right) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        splitCsv(left).forEach(value -> values.put(value, true));
        splitCsv(right).forEach(value -> values.put(value, true));
        return String.join(",", values.keySet());
    }

    private record Candidate(String lineCode, String routeCode, String productCode) {
    }
}
