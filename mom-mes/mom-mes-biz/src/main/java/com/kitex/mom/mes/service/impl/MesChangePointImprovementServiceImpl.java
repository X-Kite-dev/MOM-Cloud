package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesChangePointImprovementCreateCommand;
import com.kitex.mom.mes.dto.MesChangePointLossGroupDTO;
import com.kitex.mom.mes.entity.MesChangePointImprovementEntity;
import com.kitex.mom.mes.entity.MesChangePointImprovementOperationEntity;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.mapper.MesChangePointImprovementMapper;
import com.kitex.mom.mes.mapper.MesChangePointImprovementOperationMapper;
import com.kitex.mom.mes.service.MesChangePointImprovementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 冻结变化点Pareto权威基线，并形成可可靠链接到QMS CAPA/8D的来源单。
 */
@Service
@RequiredArgsConstructor
public class MesChangePointImprovementServiceImpl
        extends ServiceImpl<MesChangePointImprovementMapper, MesChangePointImprovementEntity>
        implements MesChangePointImprovementService {

    private static final Set<String> DIMENSIONS = Set.of("CATEGORY", "CONFIG", "LOSS");
    private final MesChangePointHandleMapper handleMapper;
    private final MesChangePointImprovementOperationMapper operationMapper;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesChangePointEffectEvidenceReader effectEvidenceReader;

    @Override
    public IPage<MesChangePointImprovementEntity> queryPage(Page<MesChangePointImprovementEntity> page,
                                                            MesChangePointImprovementEntity query) {
        String factory = factoryAccess.require("read");
        LambdaQueryWrapper<MesChangePointImprovementEntity> wrapper =
                new LambdaQueryWrapper<MesChangePointImprovementEntity>()
                        .eq(MesChangePointImprovementEntity::getFactoryCode, factory)
                        .eq(MesChangePointImprovementEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(StrUtil.isNotBlank(query.getDimension()), MesChangePointImprovementEntity::getDimension,
                                query.getDimension())
                        .eq(StrUtil.isNotBlank(query.getGroupCode()), MesChangePointImprovementEntity::getGroupCode,
                                query.getGroupCode())
                        .eq(StrUtil.isNotBlank(query.getLinkStatus()), MesChangePointImprovementEntity::getLinkStatus,
                                query.getLinkStatus())
                        .orderByDesc(MesChangePointImprovementEntity::getCreateTime)
                        .orderByDesc(MesChangePointImprovementEntity::getId);
        return page(page, wrapper);
    }

    @Override
    public MesChangePointImprovementEntity getDetail(Long id) {
        require(id != null, "变化点改善来源单不能为空");
        String factory = factoryAccess.require("read");
        MesChangePointImprovementEntity record = getOne(
                new LambdaQueryWrapper<MesChangePointImprovementEntity>()
                        .eq(MesChangePointImprovementEntity::getId, id)
                        .eq(MesChangePointImprovementEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MesChangePointImprovementEntity::getFactoryCode, factory).last("LIMIT 1"), false);
        require(record != null, "当前实际工厂未找到变化点改善来源单");
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public MesChangePointImprovementEntity create(MesChangePointImprovementCreateCommand command) {
        validate(command);
        String factory = factoryAccess.require("write");
        MesChangePointImprovementEntity duplicate = getOne(
                new LambdaQueryWrapper<MesChangePointImprovementEntity>()
                        .eq(MesChangePointImprovementEntity::getRequestId, command.getRequestId())
                        .eq(MesChangePointImprovementEntity::getFactoryCode, factory).last("LIMIT 1"), false);
        if (duplicate != null) return duplicate;

        List<MesChangePointLossGroupDTO> groups = handleMapper.lossGroups(factory,
                command.getBaselineStartTime(), command.getBaselineEndTime(), command.getDimension());
        MesChangePointLossGroupDTO baseline = groups.stream()
                .filter(item -> Objects.equals(item.getGroupCode(), command.getGroupCode())).findFirst()
                .orElseThrow(() -> new CheckedException("当前工厂和时间窗内未找到该Pareto分组，禁止按陈旧统计发起CAPA"));
        require(value(baseline.getEventCount()) > 0, "基线事件数必须大于0");
        boolean actionable = value(baseline.getEventCount()) >= 2 || value(baseline.getPauseFailureCount()) > 0
                || positive(baseline.getLossAmount()) || positive(baseline.getReworkWipQty())
                || positive(baseline.getScrapWipQty());
        require(actionable, "该分组没有重复事件、暂停失败、返工、报废或确认损失，不应发起CAPA");

        String fingerprint = fingerprint(factory, command, baseline);
        MesChangePointImprovementEntity sameBaseline = getOne(
                new LambdaQueryWrapper<MesChangePointImprovementEntity>()
                        .eq(MesChangePointImprovementEntity::getFactoryCode, factory)
                        .eq(MesChangePointImprovementEntity::getBaselineFingerprint, fingerprint)
                        .notIn(MesChangePointImprovementEntity::getLinkStatus, "CANCELLED").last("LIMIT 1"), false);
        if (sameBaseline != null) return sameBaseline;

        String improvementNo = "CPIMP-" + IdUtil.getSnowflakeNextIdStr();
        MesChangePointImprovementEntity record = new MesChangePointImprovementEntity()
                .setImprovementNo(improvementNo).setRequestId(command.getRequestId())
                .setDimension(command.getDimension()).setGroupCode(command.getGroupCode())
                .setGroupName(StrUtil.blankToDefault(baseline.getGroupName(), baseline.getGroupCode()))
                .setBaselineStartTime(command.getBaselineStartTime()).setBaselineEndTime(command.getBaselineEndTime())
                .setBaselineEventCount(baseline.getEventCount())
                .setBaselineAutoPauseCount(baseline.getAutoPauseRequiredCount())
                .setBaselinePauseFailureCount(baseline.getPauseFailureCount())
                .setBaselineAffectedWipQty(baseline.getAffectedWipQty())
                .setBaselineReleasedWipQty(baseline.getReleasedWipQty())
                .setBaselineReworkWipQty(baseline.getReworkWipQty())
                .setBaselineScrapWipQty(baseline.getScrapWipQty())
                .setBaselineLossAmount(baseline.getLossAmount())
                .setBaselineAverageCloseMinutes(baseline.getAverageCloseMinutes())
                .setBaselineFingerprint(fingerprint)
                .setBaselineEventSnapshot(effectEvidenceReader.captureBaseline(factory, command.getDimension(),
                        command.getGroupCode(), command.getBaselineStartTime(), command.getBaselineEndTime()))
                .setTitle(command.getTitle()).setProblemStatement(command.getProblemStatement())
                .setImpactScope(command.getImpactScope()).setTriggerReason(command.getTriggerReason())
                .setSeverity(command.getSeverity()).setOccurrence(command.getOccurrence())
                .setDetection(command.getDetection())
                .setRiskPriorityNo(command.getSeverity() * command.getOccurrence() * command.getDetection())
                .setTeamMembers(command.getTeamMembers()).setCoordinatorCode(command.getCoordinatorCode())
                .setCoordinatorName(command.getCoordinatorName()).setDueTime(command.getDueTime())
                .setQmsRequestId("MES-CP-CAPA-" + improvementNo).setLinkStatus("PENDING")
                .setLinkResult("MES改善来源和基线已冻结，等待创建QMS CAPA")
                .setFactoryCode(factory);
        save(record);
        saveOperation(record, command.getRequestId(), "CREATE", null, "PENDING",
                "冻结Pareto基线，指纹=" + fingerprint);
        return record;
    }

    @Override
    public List<MesChangePointImprovementOperationEntity> operations(Long id) {
        MesChangePointImprovementEntity record = getDetail(id);
        return operationMapper.selectList(new LambdaQueryWrapper<MesChangePointImprovementOperationEntity>()
                .eq(MesChangePointImprovementOperationEntity::getImprovementId, record.getId())
                .orderByAsc(MesChangePointImprovementOperationEntity::getOperationTime)
                .orderByAsc(MesChangePointImprovementOperationEntity::getId));
    }

    void saveOperation(MesChangePointImprovementEntity record, String requestId, String type,
                       String before, String after, String content) {
        MomUser user = SecurityUtils.getUser();
        MesChangePointImprovementOperationEntity operation = new MesChangePointImprovementOperationEntity()
                .setRequestId(requestId).setImprovementId(record.getId())
                .setImprovementNo(record.getImprovementNo()).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after).setContent(StrUtil.subPre(content, 2000))
                .setOperatorCode(user == null ? "SYSTEM" : user.getUsername())
                .setOperatorName(user == null ? "系统" : user.getName())
                .setOperationTime(LocalDateTime.now()).setFactoryCode(record.getFactoryCode());
        operation.setTenantId(record.getTenantId());
        if (operationMapper.insert(operation) != 1) throw new CheckedException("变化点改善操作履历保存失败");
    }

    private void validate(MesChangePointImprovementCreateCommand command) {
        require(command != null, "变化点改善参数不能为空");
        require(StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length() <= 100,
                "请求号不能为空且不能超过100字符");
        command.setDimension(StrUtil.blankToDefault(command.getDimension(), "").trim().toUpperCase());
        command.setGroupCode(StrUtil.trim(command.getGroupCode()));
        require(DIMENSIONS.contains(command.getDimension()), "Pareto聚合维度无效");
        require(StrUtil.isNotBlank(command.getGroupCode()) && command.getGroupCode().length() <= 100,
                "Pareto聚合项编码不能为空且不能超过100字符");
        require(command.getBaselineStartTime() != null && command.getBaselineEndTime() != null
                        && command.getBaselineStartTime().isBefore(command.getBaselineEndTime()),
                "Pareto基线时间窗无效");
        require(Duration.between(command.getBaselineStartTime(), command.getBaselineEndTime()).toDays() <= 366,
                "Pareto基线时间窗不能超过366天");
        require(StrUtil.isNotBlank(command.getTitle()) && command.getTitle().length() <= 300,
                "CAPA标题不能为空且不能超过300字符");
        require(StrUtil.isNotBlank(command.getProblemStatement()) && command.getProblemStatement().length() <= 3000,
                "问题陈述不能为空且不能超过3000字符");
        require(StrUtil.isNotBlank(command.getImpactScope()) && command.getImpactScope().length() <= 2000,
                "影响范围不能为空且不能超过2000字符");
        require(StrUtil.isNotBlank(command.getTriggerReason()) && command.getTriggerReason().length() <= 1000,
                "触发原因不能为空且不能超过1000字符");
        require(inRiskRange(command.getSeverity()) && inRiskRange(command.getOccurrence())
                        && inRiskRange(command.getDetection()), "严重度、发生度和探测度必须在1到10之间");
        require(StrUtil.isNotBlank(command.getCoordinatorCode()) && command.getCoordinatorCode().length() <= 100
                        && StrUtil.isNotBlank(command.getCoordinatorName()) && command.getCoordinatorName().length() <= 200,
                "CAPA协调人不能为空或长度超限");
        require(command.getTeamMembers() == null || command.getTeamMembers().length() <= 3000,
                "跨职能团队成员不能超过3000字符");
        require(command.getDueTime() != null && command.getDueTime().isAfter(LocalDateTime.now()),
                "CAPA计划关闭时间必须晚于当前时间");
    }

    private String fingerprint(String factory, MesChangePointImprovementCreateCommand command,
                               MesChangePointLossGroupDTO baseline) {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("version", "MES_CHANGE_POINT_PARETO_V1");
        evidence.put("tenantId", TenantContextHolder.getTenantId());
        evidence.put("factoryCode", factory);
        evidence.put("dimension", command.getDimension());
        evidence.put("groupCode", command.getGroupCode());
        evidence.put("baselineStartTime", command.getBaselineStartTime());
        evidence.put("baselineEndTime", command.getBaselineEndTime());
        evidence.put("eventCount", baseline.getEventCount());
        evidence.put("autoPauseCount", baseline.getAutoPauseRequiredCount());
        evidence.put("pauseFailureCount", baseline.getPauseFailureCount());
        evidence.put("affectedWipQty", baseline.getAffectedWipQty());
        evidence.put("releasedWipQty", baseline.getReleasedWipQty());
        evidence.put("reworkWipQty", baseline.getReworkWipQty());
        evidence.put("scrapWipQty", baseline.getScrapWipQty());
        evidence.put("lossAmount", baseline.getLossAmount());
        evidence.put("averageCloseMinutes", baseline.getAverageCloseMinutes());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(evidence).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("变化点Pareto基线指纹计算失败");
        }
    }

    private boolean inRiskRange(Integer value) {
        return value != null && value >= 1 && value <= 10;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
