package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnApsCaseCommand;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnApsReplanDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnApsCaseService;
import com.kitex.mom.plan.service.PlnApsReplanAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 专用局部重排事实到统一 APS 案件的只读适配实现。
 */
@Service
@RequiredArgsConstructor
public class PlnApsReplanAdapterImpl implements PlnApsReplanAdapter {

    private final PlnApsCaseService caseService;
    private final PlnShortageReplanCaseMapper shortageMapper;
    private final PlnResourceReplanCaseMapper resourceMapper;
    private final PlnQualityReplanCaseMapper qualityMapper;
    private final PlnInsertionReplanCaseMapper insertionMapper;
    private final PlnSchedulingEffectReviewMapper effectReviewMapper;

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    private static boolean present(String... values) {
        return java.util.Arrays.stream(values).allMatch(StrUtil::isNotBlank);
    }

    private static String version(LocalDateTime updateTime, LocalDateTime createTime, String state) {
        LocalDateTime time = updateTime == null ? createTime : updateTime;
        require(time != null, "局部重排来源缺少可核验的更新时间");
        return time + ":" + StrUtil.blankToDefault(state, "NA");
    }

    private static Long parseId(String value, String label) {
        try {
            Long id = Long.valueOf(value);
            require(id > 0, label + "必须为正整数");
            return id;
        } catch (NumberFormatException ex) {
            throw new CheckedException(label + "格式不正确");
        }
    }

    private static <T> T required(T value, String message) {
        require(value != null, message);
        return value;
    }

    private static String text(Long value) {
        return value == null ? null : value.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public PlnApsCaseDTO launchFromReplanSource(PlnApsReplanDTO.LaunchCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getSourceType())
                        && StrUtil.isNotBlank(command.getSourceId()) && StrUtil.isNotBlank(command.getRequestId()),
                "局部重排进入智能APS的来源类型、主键和请求号不能为空");
        String type = command.getSourceType().trim().toUpperCase(Locale.ROOT);
        Long sourceId = parseId(command.getSourceId(), "局部重排来源主键");
        SourceIdentity source = sourceIdentity(type, sourceId);
        return launch(source, command.getRequestId().trim());
    }

    @Override
    public PlnApsCaseDTO launchFromEffectReview(Long reviewId, String requestId) {
        require(reviewId != null && reviewId > 0 && StrUtil.isNotBlank(requestId),
                "效果回评进入智能APS的主键和请求号不能为空");
        PlnSchedulingEffectReviewEntity row = effectReviewMapper.selectById(reviewId);
        require(row != null, "排程效果回评不存在");
        return launch(new SourceIdentity("EFFECT_REVIEW", "EXECUTION_DEVIATION_REPLAN", row.getId(),
                row.getReviewNo(), version(row.getUpdateTime(), row.getCreateTime(),
                row.getLatestExecutionFingerprint())), requestId.trim());
    }

    @Override
    public PlnApsReplanDTO.Projection projection(Long apsCaseId) {
        PlnApsCaseDTO apsCase = caseService.detail(apsCaseId);
        PlnApsCaseDTO.Source source = Objects.requireNonNullElse(apsCase.getSources(), List.<PlnApsCaseDTO.Source>of())
                .stream().filter(item -> "PRIMARY".equals(item.getSourceRole())).findFirst()
                .orElseThrow(() -> new CheckedException("APS案件缺少主来源引用"));
        Long sourceId = parseId(source.getSourceId(), "APS案件来源主键");
        return switch (source.getSourceType()) {
            case "SHORTAGE_REPLAN_CASE" -> shortageProjection(apsCase, source,
                    required(shortageMapper.selectById(sourceId), "缺料重排来源已不存在"));
            case "RESOURCE_REPLAN_CASE", "EXECUTION_DEVIATION_CASE" -> resourceProjection(apsCase, source,
                    required(resourceMapper.selectById(sourceId), "资源/执行偏差重排来源已不存在"));
            case "QUALITY_REPLAN_CASE" -> qualityProjection(apsCase, source,
                    required(qualityMapper.selectById(sourceId), "质量重排来源已不存在"));
            case "INSERTION_REPLAN_CASE" -> insertionProjection(apsCase, source,
                    required(insertionMapper.selectById(sourceId), "插单重排来源已不存在"));
            case "EFFECT_REVIEW" -> effectProjection(apsCase, source,
                    required(effectReviewMapper.selectById(sourceId), "效果回评来源已不存在"));
            default -> throw new CheckedException("当前APS案件不是专用重排或效果回评来源");
        };
    }

    private PlnApsCaseDTO launch(SourceIdentity source, String requestId) {
        PlnApsCaseCommand.Launch command = new PlnApsCaseCommand.Launch();
        command.setCaseType(source.caseType());
        command.setSourceType(source.sourceType());
        command.setSourceId(source.id().toString());
        command.setSourceVersion(source.version());
        command.setRequestId(requestId);
        return caseService.launch(command);
    }

    private SourceIdentity sourceIdentity(String type, Long id) {
        return switch (type) {
            case "SHORTAGE" -> {
                PlnShortageReplanCaseEntity row = required(shortageMapper.selectById(id), "缺料重排案件不存在");
                yield new SourceIdentity("SHORTAGE_REPLAN_CASE", "SHORTAGE_REPLAN", id, row.getCaseNo(),
                        version(row.getUpdateTime(), row.getCreateTime(), row.getStatus()));
            }
            case "RESOURCE" -> {
                PlnResourceReplanCaseEntity row = required(resourceMapper.selectById(id), "资源重排案件不存在");
                yield new SourceIdentity("RESOURCE_REPLAN_CASE", "RESOURCE_EXCEPTION_REPLAN", id, row.getCaseNo(),
                        version(row.getUpdateTime(), row.getCreateTime(), row.getStatus()));
            }
            case "EXECUTION_DEVIATION" -> {
                PlnResourceReplanCaseEntity row = required(resourceMapper.selectById(id), "执行偏差重排案件不存在");
                require("EXECUTION_DEVIATION".equals(row.getEvidenceSourceType()),
                        "指定资源案件不是执行偏差来源");
                yield new SourceIdentity("EXECUTION_DEVIATION_CASE", "EXECUTION_DEVIATION_REPLAN", id,
                        row.getCaseNo(), version(row.getUpdateTime(), row.getCreateTime(), row.getStatus()));
            }
            case "QUALITY" -> {
                PlnQualityReplanCaseEntity row = required(qualityMapper.selectById(id), "质量重排案件不存在");
                yield new SourceIdentity("QUALITY_REPLAN_CASE", "QUALITY_EXCEPTION_REPLAN", id, row.getCaseNo(),
                        version(row.getUpdateTime(), row.getCreateTime(), row.getStatus()));
            }
            case "INSERTION" -> {
                PlnInsertionReplanCaseEntity row = required(insertionMapper.selectById(id), "插单重排案件不存在");
                yield new SourceIdentity("INSERTION_REPLAN_CASE", "INSERTION_REPLAN", id, row.getCaseNo(),
                        version(row.getUpdateTime(), row.getCreateTime(), row.getStatus()));
            }
            default -> throw new CheckedException("不支持的局部重排来源类型：" + type);
        };
    }

    private PlnApsReplanDTO.Projection shortageProjection(PlnApsCaseDTO apsCase, PlnApsCaseDTO.Source source,
                                                          PlnShortageReplanCaseEntity row) {
        Map<String, Object> metrics = map();
        metrics.put("materialCode", row.getMaterialCode());
        metrics.put("expectedSupplyTime", row.getExpectedSupplyTime());
        metrics.put("movedOperationCount", row.getMovedOperationCount());
        metrics.put("maximumShiftMinutes", row.getMaximumShiftMinutes());
        metrics.put("deliveryRisk", row.getDeliveryRisk());
        return base(apsCase, source, row.getStatus(), row.getIssueId(), row.getIssueNo(), row.getScenarioId(),
                row.getOriginalTaskId(), row.getProposalTaskId(), row.getProposalFingerprint(),
                present(row.getMesExecutionFingerprint(), row.getOriginalScheduleFingerprint()),
                "缺料供应证据、冻结区和MES可移动工序由原缺料重排领域服务核验", metrics);
    }

    private PlnApsReplanDTO.Projection resourceProjection(PlnApsCaseDTO apsCase, PlnApsCaseDTO.Source source,
                                                          PlnResourceReplanCaseEntity row) {
        Map<String, Object> metrics = map();
        metrics.put("resourceType", row.getResourceType());
        metrics.put("resourceCode", row.getResourceCode());
        metrics.put("expectedRecoveryTime", row.getExpectedRecoveryTime());
        metrics.put("movedOperationCount", row.getMovedOperationCount());
        metrics.put("reassignedOperationCount", row.getReassignedOperationCount());
        metrics.put("deliveryRisk", row.getDeliveryRisk());
        return base(apsCase, source, row.getStatus(), row.getIssueId(), row.getIssueNo(), row.getScenarioId(),
                row.getOriginalTaskId(), row.getProposalTaskId(), row.getProposalFingerprint(),
                present(row.getResourceEvidenceFingerprint(), row.getMesExecutionFingerprint(),
                        row.getOriginalScheduleFingerprint()),
                "资源资格、恢复时窗、冻结区和MES执行边界由原资源重排领域服务核验", metrics);
    }

    private PlnApsReplanDTO.Projection qualityProjection(PlnApsCaseDTO apsCase, PlnApsCaseDTO.Source source,
                                                         PlnQualityReplanCaseEntity row) {
        Map<String, Object> metrics = map();
        metrics.put("deviationRate", row.getDeviationRate());
        metrics.put("requiredSupplementQuantity", row.getRequiredSupplementQuantity());
        metrics.put("movedOperationCount", row.getMovedOperationCount());
        metrics.put("deliveryRisk", row.getDeliveryRisk());
        return base(apsCase, source, row.getStatus(), row.getIssueId(), row.getIssueNo(), row.getScenarioId(),
                row.getOriginalTaskId(), row.getProposalTaskId(), row.getProposalFingerprint(),
                present(row.getQualityEvidenceFingerprint(), row.getMesExecutionFingerprint()),
                "质量损失、补产闭环和MES可移动边界由原质量重排领域服务核验", metrics);
    }

    private PlnApsReplanDTO.Projection insertionProjection(PlnApsCaseDTO apsCase, PlnApsCaseDTO.Source source,
                                                           PlnInsertionReplanCaseEntity row) {
        Map<String, Object> metrics = map();
        metrics.put("urgencyLevel", row.getUrgencyLevel());
        metrics.put("affectedIssueCount", row.getAffectedIssueCount());
        metrics.put("movedOperationCount", row.getMovedOperationCount());
        metrics.put("newLateIssueCount", row.getNewLateIssueCount());
        metrics.put("insertionLateMinutes", row.getInsertionLateMinutes());
        return base(apsCase, source, row.getStatus(), row.getInsertionIssueId(), row.getInsertionIssueNo(),
                row.getScenarioId(), null, row.getProposalTaskId(), row.getProposalFingerprint(),
                present(row.getSourceIssueFingerprint(), row.getMesExecutionFingerprint(),
                        row.getOriginalScheduleFingerprint()),
                "插单与被挤占计划作为联合范围，由原插单重排领域服务核验和发布", metrics);
    }

    private PlnApsReplanDTO.Projection effectProjection(PlnApsCaseDTO apsCase, PlnApsCaseDTO.Source source,
                                                        PlnSchedulingEffectReviewEntity row) {
        Map<String, Object> metrics = map();
        metrics.put("completionRate", row.getCompletionRate());
        metrics.put("scheduleAdherenceRate", row.getScheduleAdherenceRate());
        metrics.put("maximumStartDeviationMinutes", row.getMaximumAbsoluteStartDeviationMinutes());
        metrics.put("maximumFinishDeviationMinutes", row.getMaximumAbsoluteFinishDeviationMinutes());
        metrics.put("lateIssueCount", row.getLateIssueCount());
        metrics.put("realizationGap", row.getRealizationGap());
        return base(apsCase, source, row.getReviewStatus(), null, null, row.getScenarioId(),
                row.getSelectedTaskId(), null, row.getLatestExecutionFingerprint(),
                present(row.getPlannedScopeFingerprint(), row.getLatestExecutionFingerprint()),
                "已发布排程的MES执行效果超过原观察容差，当前案件只待人工确认，不自动重排", metrics);
    }

    private PlnApsReplanDTO.Projection base(PlnApsCaseDTO apsCase, PlnApsCaseDTO.Source source,
                                            String status, Long issueId, String issueNo, Long scenarioId,
                                            Long originalTaskId, Long proposalTaskId, String fingerprint,
                                            boolean movableVerified, String summary, Map<String, Object> metrics) {
        return new PlnApsReplanDTO.Projection().setApsCase(apsCase).setSourceType(source.getSourceType())
                .setSourceId(source.getSourceId()).setSourceCode(source.getSourceCode()).setSourceStatus(status)
                .setIssueId(text(issueId)).setIssueNo(issueNo).setScenarioId(text(scenarioId))
                .setOriginalTaskId(text(originalTaskId)).setProposalTaskId(text(proposalTaskId))
                .setEvidenceFingerprint(fingerprint).setMovableScopeVerified(movableVerified)
                .setSummary(summary).setMetrics(metrics);
    }

    private record SourceIdentity(String sourceType, String caseType, Long id, String code, String version) {
    }
}
