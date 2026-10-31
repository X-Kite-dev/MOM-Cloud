package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsProcedureInspectionChecks.require;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.entity.TemplateExportParams;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner;
import com.kitex.mom.qms.dto.QmsProcedureSourceWithdrawalDTO;
import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.dto.req.InspectionProcedureTaskReq;
import com.kitex.mom.qms.dto.resp.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.enums.*;
import com.kitex.mom.qms.mapper.QmsInspectionProcedureTaskMapper;
import com.kitex.mom.qms.mapper.QmsProcedureSourceWithdrawalReceiptMapper;
import com.kitex.mom.qms.service.*;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 工序质检
 *
 * @author kitex
 * @date 2022/12/1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionProcedureTaskServiceImpl extends ServiceImpl<QmsInspectionProcedureTaskMapper, QmsInspectionProcedureTaskEntity> implements InspectionProcedureTaskService {

    private final QmsInspectionProcedureTaskDetailService procedureTaskDetailService;

    private final QualityStandardService qualityStandardService;

    private final QualityStandardItemService qualityStandardItemService;

    private final InspectionProcedureHistoryService historyService;

    private final QualitySamplingStrategiesService samplingStrategiesService;

    private final InspectionProcedureItemResultRecordService recordService;

    private final QmsBadInfoService badInfoService;
    private final QmsProcedureDispositionService procedureDispositions;

    private final InspectionProcedureHandleRecordService handleRecordService;

    private final RemoteMesTaskService remoteMesTaskService;

    private final QmsReworkResultPublisher reworkResultPublisher;

    private final QmsPlanEventOutboxService qmsPlanEventOutboxService;

    private final QmsProcedureSourceWithdrawalReceiptMapper sourceWithdrawalReceiptMapper;
    private final QmsInspectionHistorySupport inspectionAccess;
    private final QmsInspectionBasisService inspectionBasis;
    private final QmsInspectionPieceService inspectionPieces;

    /**
     * 创建质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(InspectionProcedureCreateParam createParam) {
        inspectionAccess.requireScope();
        if (createParam == null
                || StrUtil.isBlank(createParam.getMatCode())
                || StrUtil.isBlank(createParam.getProcessCode())
                || StrUtil.isBlank(createParam.getLabelType())) {
            throw new RuntimeException("工序质检必须提供物料、工序和首中末标签类型");
        }
        if (createParam.getNum() == null || createParam.getNum() <= 0) {
            throw new RuntimeException("工序质检数量必须大于0");
        }
        String originalCommand = QmsInspectionBasisJson.creation(createParam);
        if (StrUtil.isBlank(createParam.getSourceRequestId())) createParam.setSourceRequestId(null);
        if (StrUtil.isNotBlank(createParam.getSourceRequestId())) {
            String sourceRequestId = createParam.getSourceRequestId().trim();
            if (sourceRequestId.length() > 200) {
                throw new RuntimeException("质检来源请求号长度不能超过200位");
            }
            boolean v2Source =
                    sourceRequestId.startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX);
            if (v2Source) {
                if (StrUtil.isBlank(createParam.getSourceTaskCode())) {
                    throw new RuntimeException("V2工序质检来源必须指定MES工序任务编码");
                }
                String expectedFingerprint =
                        InspectionProcedureSourceContract.fingerprint(createParam);
                if (!Objects.equals(
                        expectedFingerprint, createParam.getSourceRequestFingerprint())) {
                    throw new RuntimeException("V2工序质检来源完整参数摘要不一致");
                }
            } else if (StrUtil.isNotBlank(createParam.getSourceRequestFingerprint())) {
                throw new RuntimeException("旧质检来源请求不得夹带V2参数摘要");
            }
            QmsInspectionProcedureTaskEntity existing =
                    this.lambdaQuery()
                            .eq(
                                    QmsInspectionProcedureTaskEntity::getSourceRequestId,
                                    sourceRequestId)
                            .eq(
                                    QmsInspectionProcedureTaskEntity::getTenantId,
                                    TenantContextHolder.getTenantId())
                            .eq(
                                    QmsInspectionProcedureTaskEntity::getFactoryCode,
                                    FactoryContextHolder.getFactoryCode())
                            .one();
            if (existing != null) {
                if (inspectionBasis.sameCreation("PROCEDURE", existing.getId(), originalCommand))
                    return existing.getId();
                // 既有V2摘要已经包含原始参数，缺新增依据时仅保留这条已验证兼容路径。
                if (v2Source
                        && Objects.equals(
                                existing.getSourceRequestFingerprint(),
                                createParam.getSourceRequestFingerprint())
                        && Objects.equals(
                                existing.getSourceTaskCode(), createParam.getSourceTaskCode())
                        && Objects.equals(existing.getMatCode(), createParam.getMatCode())
                        && Objects.equals(existing.getProcessCode(), createParam.getProcessCode())
                        && Objects.equals(existing.getLabelType(), createParam.getLabelType()))
                    return existing.getId();
                throw new CheckedException("原任务缺少可验证的原始报检命令，请按原任务核对");
            }
            createParam.setSourceRequestId(sourceRequestId);
        } else if (this.existTask(
                createParam.getMatCode(), createParam.getProcessCode(), createParam.getBatchNo())) {
            // 兼容没有来源请求号的旧调用。
            throw new RuntimeException("已经有同类型的任务,无法继续创建");
        }
        var productionContext = inspectionBasis.freezeSource(createParam);
        var qualityStandard =
                publishedStandard(createParam.getStandardId(), createParam.getMatCode());
        // 创建质检任务
        val task =
                new QmsInspectionProcedureTaskEntity()
                        .setBatchNo(createParam.getBatchNo())
                        .setSourceTaskCode(
                                StrUtil.blankToDefault(
                                        createParam.getSourceTaskCode(), createParam.getTaskCode()))
                        .setSourceRequestId(createParam.getSourceRequestId())
                        .setSourceRequestFingerprint(createParam.getSourceRequestFingerprint())
                        .setMatCode(createParam.getMatCode())
                        .setMatName(createParam.getMatName())
                        .setMatSpec(createParam.getMatSpec())
                        .setProcessCode(createParam.getProcessCode())
                        .setProcessName(createParam.getProcessName())
                        .setStandardId(qualityStandard.getId())
                        .setStandardName(qualityStandard.getStandardName())
                        .setNum(createParam.getNum())
                        .setStatus(InspectionTaskStatusEnum.WAIT.name())
                        .setHandleNum(0)
                        .setInspectionNum(createParam.getInspectionNum())
                        .setLabelType(createParam.getLabelType())
                        .setMatBarcode(createParam.getMatBarcode())
                        .setOrderNo(createParam.getOrderNo())
                        .setOrderId(createParam.getOrderId())
                        .setDeptCode(createParam.getDeptCode())
                        .setLatestAppendTime(new Date());
        task.setTenantId(TenantContextHolder.getTenantId());
        task.setFactoryCode(FactoryContextHolder.getFactoryCode());
        require(this.save(task) && task.getId() != null && task.getId() > 0, "工序质检任务保存失败");
        var frozenPlan =
                inspectionBasis.prepare(
                        "PROCEDURE", qualityStandard, task.getNum(), task.getInspectionNum());
        var details = generateDetail(task, frozenPlan);
        int sampleCount =
                details.stream()
                        .mapToInt(QmsInspectionProcedureTaskDetailEntity::getTotalNum)
                        .max()
                        .orElseThrow();
        task.setInspectionNum(sampleCount);
        require(this.updateById(task), "工序质检实际抽样数量保存失败");
        for (var detail : details) require(procedureTaskDetailService.save(detail), "工序检验项目保存失败");
        inspectionBasis.record(task, details, frozenPlan, "CREATE", originalCommand, productionContext);
        // 保存历史
        historyService.create(task);
        return task.getId();
    }

    /**
     * 批量保存
     *
     * @param createParams
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> batchCreate(List<InspectionProcedureCreateParam> createParams) {
        Map<String, Long> resultMap = new HashMap<>();
        createParams.forEach(param -> {
            Long taskId = this.create(param);
            resultMap.put(param.getTaskCode(), taskId);
        });
        return resultMap;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createManual(InspectionProcedureCreateParam createParam) {
        inspectionAccess.requireWrite();
        requireManualSource(createParam);
        return create(createParam);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> batchCreateManual(List<InspectionProcedureCreateParam> createParams) {
        inspectionAccess.requireWrite();
        require(
                createParams != null && !createParams.isEmpty() && createParams.size() <= 5000,
                "请提交有界的人工报检任务");
        createParams.forEach(this::requireManualSource);
        return batchCreate(createParams);
    }

    private void requireManualSource(InspectionProcedureCreateParam param) {
        require(
                param != null
                        && StrUtil.isBlank(param.getSourceRequestFingerprint())
                        && (param.getSourceRequestId() == null
                                || !param.getSourceRequestId()
                                        .trim()
                                        .startsWith(
                                                InspectionProcedureSourceContract.REQUEST_PREFIX)),
                "人工报检不能冒用MES冻结来源，请从原生产任务发起");
    }

    /**
     * 修改质检任务
     *
     * @param updateParam
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(InspectionProcedureCreateParam updateParam) {
        inspectionAccess.requireWrite();
        require(updateParam != null, "请指定需要修改的检验任务");
        var task = lockInspection(updateParam.getId());
        assertInspectionWritable(task);
        requireMutableSource(task);
        inspectionPieces.anonymous(task);
        require("WAIT".equals(task.getStatus()), "已经开始检验的任务不能更换标准");
        var oldDetails = inspectionDetails(task.getId());
        QmsProcedureInspectionChecks.completion(
                task,
                oldDetails,
                baseMapper.inspectionSamples(
                        task.getId(), task.getTenantId(), task.getFactoryCode()));
        require(oldDetails.stream().allMatch(d -> d.getFinishNum() == 0), "已有检验样本的任务不能更换标准");
        var qualityStandard = publishedStandard(updateParam.getStandardId(), task.getMatCode());
        task.setName(updateParam.getName())
                .setStandardId(qualityStandard.getId())
                .setStandardName(qualityStandard.getStandardName());
        var frozenPlan =
                inspectionBasis.prepare(
                        "PROCEDURE",
                        qualityStandard,
                        task.getNum(),
                        updateParam.getInspectionNum() == null
                                ? task.getInspectionNum()
                                : updateParam.getInspectionNum());
        var details = generateDetail(task, frozenPlan);
        require(
                procedureTaskDetailService.remove(
                        detailScope()
                                .eq(
                                        QmsInspectionProcedureTaskDetailEntity::getTaskId,
                                        task.getId())),
                "旧检验项目替换失败");
        for (var detail : details) require(procedureTaskDetailService.save(detail), "新检验项目保存失败");
        task.setInspectionNum(
                details.stream()
                        .mapToInt(QmsInspectionProcedureTaskDetailEntity::getTotalNum)
                        .max()
                        .orElseThrow());
        require(updateById(task), "检验任务标准与数量保存失败");
        inspectionBasis.record(task, details, frozenPlan, "RECONFIGURE", null);
    }

    /**
     * 追加质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void append(InspectionAppendParam appendParam) {
        inspectionAccess.requireWrite();
        require(
                appendParam != null && appendParam.getNum() != null && appendParam.getNum() > 0,
                "追加受检数量必须大于0");
        var task = lockInspection(appendParam.getId());
        assertInspectionWritable(task);
        requireMutableSource(task);
        inspectionPieces.anonymous(task);
        var details = inspectionDetails(task.getId());
        QmsProcedureInspectionChecks.completion(
                task,
                details,
                baseMapper.inspectionSamples(
                        task.getId(), task.getTenantId(), task.getFactoryCode()));
        var frozenPlan = inspectionBasis.validateCurrent(task, details);
        if (frozenPlan != null) {
            appendFrozen(task, details, frozenPlan, appendParam.getNum());
            return;
        }
        var qualityStandard = publishedStandard(task.getStandardId(), task.getMatCode());
        int previousBatch = task.getNum();
        try {
            task.setNum(Math.addExact(task.getNum(), appendParam.getNum()));
        } catch (ArithmeticException e) {
            throw new CheckedException("追加后的受检数量超出范围", e);
        }
        var items = standardItems(task, qualityStandard);
        QmsInspectionEvidenceChecks.sameItems(
                details.stream()
                        .map(QmsInspectionProcedureTaskDetailEntity::getStandardItemId)
                        .toList(),
                items.stream().map(QmsQualityStandardItemEntity::getId).toList());
        for (var detail : details) {
            updateTaskDetails(
                    detail,
                    task,
                    previousBatch,
                    qualityStandard,
                    findQualityStandardItemByTaskDetail(items, detail));
            require(procedureTaskDetailService.updateById(detail), "追加检验项目保存失败");
        }
        task.setInspectionNum(
                details.stream()
                        .mapToInt(QmsInspectionProcedureTaskDetailEntity::getTotalNum)
                        .max()
                        .orElseThrow());
        task.setLatestAppendTime(new Date());
        require(updateById(task), "追加检验任务数量保存失败");
        // 保存历史
        historyService.append(task, appendParam.getNum());
    }

    /** 追加沿原方案求值，保留实际样本和原始创建依据。 */
    private void appendFrozen(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> details,
            QmsFrozenInspectionPlan plan,
            int increment) {
        int batch;
        try {
            batch = Math.addExact(task.getNum(), increment);
        } catch (ArithmeticException e) {
            throw new CheckedException("追加后的受检数量超出范围", e);
        }
        var targets = new HashMap<Long, QmsFrozenInspectionPlan.Target>();
        for (var t : plan.targets(batch)) targets.put(t.item().standardItemId(), t);
        for (var detail : details) {
            var target = targets.get(detail.getStandardItemId());
            require(
                    target != null
                            && target.count() >= detail.getTotalNum()
                            && detail.getRecords() != null,
                    "冻结方案追加不能缩减原应检量或缺少样本占位依据");
            int difference = target.count() - detail.getTotalNum();
            detail.getRecords()
                    .addAll(
                            IntStream.range(0, difference)
                                    .mapToObj(i -> new InspectionItemResultRecord())
                                    .toList());
            detail.setTotalNum(target.count())
                    .setWaitNum(target.count() - detail.getFinishNum())
                    .setAc(target.ac())
                    .setRe(target.re());
            if (detail.getFinishNum() > 0)
                detail.setQualifiedStatus(
                        detail.getUnqualifiedNum() <= (target.ac() == null ? 0 : target.ac()));
            QmsProcedureInspectionChecks.counters(detail);
            require(procedureTaskDetailService.updateById(detail), "冻结追加检验项目保存失败");
        }
        task.setNum(batch).setInspectionNum(plan.maximum(batch)).setLatestAppendTime(new Date());
        require(updateById(task), "冻结追加检验任务保存失败");
        inspectionBasis.record(task, details, plan, "APPEND", null);
        historyService.append(task, increment);
    }

    /**
     * 作废质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void canceled(Long taskId, String reason) {
        inspectionAccess.requireWrite();
        var task = lockInspection(taskId);
        if (!Objects.equals(task.getStatus(), InspectionTaskStatusEnum.WAIT.name())
                || StrUtil.isNotBlank(task.getResult())) {
            throw new RuntimeException("只有未开始质检的任务才可以作废");
        }
        task.setStatus(InspectionTaskStatusEnum.FINISHED.name());
        task.setResult(InspectionTaskResultEnum.CANCELED.name());
        require(updateById(task), "检验任务作废保存失败");
        // 保存历史
        historyService.canceled(task, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsProcedureSourceWithdrawalDTO.Receipt withdrawByMesSource(
            QmsProcedureSourceWithdrawalDTO.Command command) {
        requireWithdrawal(command != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getFactoryCode())
                        && CollUtil.isNotEmpty(command.getSourceTaskCodes())
                        && command.getExpectedTaskIds() != null && StrUtil.isNotBlank(command.getReason()),
                "QMS来源质检撤回必须提供请求号、工厂、MES工序任务、精确QMS任务ID和原因");
        String requestId = command.getRequestId().trim();
        String factoryCode = command.getFactoryCode().trim();
        String reason = command.getReason().trim();
        requireWithdrawal(requestId.length() <= 64 && factoryCode.length() <= 100 && reason.length() <= 500,
                "QMS来源质检撤回请求号、工厂或原因长度不合法");
        List<String> sourceTaskCodes = command.getSourceTaskCodes().stream()
                .filter(StrUtil::isNotBlank).map(String::trim).distinct().sorted().toList();
        List<Long> taskIds = command.getExpectedTaskIds().stream()
                .filter(Objects::nonNull).distinct().sorted().toList();
        requireWithdrawal(sourceTaskCodes.size() == command.getSourceTaskCodes().size()
                        && taskIds.size() == command.getExpectedTaskIds().size(),
                "MES工序任务编码或QMS任务ID不能为空、重复");
        requireWithdrawal(sourceTaskCodes.size() <= 200 && taskIds.size() <= 200,
                "单次来源质检撤回最多支持200个工序和200张质检任务");
        String taskSetFingerprint =
                sha256Withdrawal(
                        factoryCode
                                + '|'
                                + String.join(",", sourceTaskCodes)
                                + '|'
                                + taskIds.stream()
                                        .map(String::valueOf)
                                        .collect(Collectors.joining(",")));
        String expectedEvidence =
                StrUtil.blankToDefault(command.getExpectedEvidenceFingerprint(), "").trim();
        requireWithdrawal(Boolean.TRUE.equals(command.getPreview()) || expectedEvidence.length() == 64,
                "执行QMS来源质检撤回必须提供64位冻结证据指纹");
        String requestFingerprint = sha256Withdrawal(taskSetFingerprint + '|' + expectedEvidence + '|' + reason);

        if (!Boolean.TRUE.equals(command.getPreview())) {
            sourceWithdrawalReceiptMapper.lockKey("REQUEST:" + requestId);
            QmsProcedureSourceWithdrawalReceiptEntity replay = sourceWithdrawalReceiptMapper.selectOne(
                    new LambdaQueryWrapper<QmsProcedureSourceWithdrawalReceiptEntity>()
                            .eq(QmsProcedureSourceWithdrawalReceiptEntity::getRequestId, requestId));
            if (replay != null) {
                requireWithdrawal(Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                        "QMS来源质检撤回幂等号已被不同命令使用");
                return sourceWithdrawalReceipt(replay, "ALREADY_WITHDRAWN");
            }
        }
        sourceWithdrawalReceiptMapper.lockKey("TASK-SET:" + taskSetFingerprint);

        List<QmsInspectionProcedureTaskEntity> tasks = taskIds.isEmpty() ? List.of() : list(
                new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                        .in(QmsInspectionProcedureTaskEntity::getId, taskIds)
                        .eq(QmsInspectionProcedureTaskEntity::getFactoryCode, factoryCode)
                        .orderByAsc(QmsInspectionProcedureTaskEntity::getId).last("FOR UPDATE"));
        requireWithdrawal(tasks.size() == taskIds.size(), "MES质检生成账关联的QMS任务缺失或跨工厂");
        List<QmsInspectionProcedureTaskEntity> allForSources = list(
                new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                        .in(QmsInspectionProcedureTaskEntity::getSourceTaskCode, sourceTaskCodes)
                        .eq(QmsInspectionProcedureTaskEntity::getFactoryCode, factoryCode)
                        .orderByAsc(QmsInspectionProcedureTaskEntity::getId));
        List<Long> actualTaskIds = allForSources.stream().map(QmsInspectionProcedureTaskEntity::getId)
                .distinct().sorted().toList();
        requireWithdrawal(actualTaskIds.equals(taskIds),
                "QMS按MES工序来源查询到的任务与MES质检生成账不一致，禁止遗漏或猜测作废");
        requireWithdrawal(tasks.stream().allMatch(task -> sourceTaskCodes.contains(task.getSourceTaskCode())),
                "QMS任务与MES工序来源不一致");

        QmsProcedureSourceWithdrawalReceiptEntity completed =
                sourceWithdrawalReceiptMapper.selectOne(
                        new LambdaQueryWrapper<QmsProcedureSourceWithdrawalReceiptEntity>()
                                .eq(
                                        QmsProcedureSourceWithdrawalReceiptEntity
                                                ::getTaskSetFingerprint,
                                        taskSetFingerprint)
                                .eq(
                                        QmsProcedureSourceWithdrawalReceiptEntity::getStatus,
                                        "SUCCEEDED")
                                .orderByDesc(
                                        QmsProcedureSourceWithdrawalReceiptEntity::getHandledTime)
                                .orderByDesc(QmsProcedureSourceWithdrawalReceiptEntity::getId)
                                .last("LIMIT 1"));
        if (completed != null) {
            requireWithdrawal(tasks.stream().allMatch(task -> InspectionTaskStatusEnum.FINISHED.name().equals(task.getStatus())
                            && InspectionTaskResultEnum.CANCELED.name().equals(task.getResult())),
                    "QMS来源撤回回执存在但任务状态不一致，须先完成账实核查");
            return sourceWithdrawalReceipt(completed, "ALREADY_WITHDRAWN");
        }

        List<Map<String, Object>> taskFacts = new ArrayList<>();
        for (QmsInspectionProcedureTaskEntity task : tasks) {
            requireWithdrawal(InspectionTaskStatusEnum.WAIT.name().equals(task.getStatus())
                            && StrUtil.isBlank(task.getResult()) && task.getLatestInspectionTime() == null
                            && Optional.ofNullable(task.getUsableNum()).orElse(0) == 0
                            && Optional.ofNullable(task.getHandleNum()).orElse(0) == 0,
                    "QMS工序质检任务[" + task.getId() + "]已开始、追加、判定或处置，不能随MES计划直接作废");
            List<QmsInspectionProcedureHistoryEntity> histories = historyService.list(
                    new LambdaQueryWrapper<QmsInspectionProcedureHistoryEntity>()
                            .eq(QmsInspectionProcedureHistoryEntity::getTaskId, task.getId())
                            .orderByAsc(QmsInspectionProcedureHistoryEntity::getId));
            requireWithdrawal(histories.size() == 1
                            && InspectionHistoryTypeEnum.CREATE.name().equals(histories.get(0).getType())
                            && Objects.equals(histories.get(0).getNum(), task.getNum()),
                    "QMS工序质检任务[" + task.getId() + "]历史账并非仅首次创建，不能直接作废");
            long resultCount = recordService.count(new LambdaQueryWrapper<QmsInspectionProcedureItemResultRecordEntity>()
                    .eq(QmsInspectionProcedureItemResultRecordEntity::getTaskId, task.getId()));
            long handleCount = handleRecordService.count(new LambdaQueryWrapper<QmsInspectionProcedureHandleRecordEntity>()
                    .eq(QmsInspectionProcedureHandleRecordEntity::getTaskId, task.getId()));
            requireWithdrawal(resultCount == 0 && handleCount == 0,
                    "QMS工序质检任务[" + task.getId() + "]已有检验或处置记录，不能直接作废");
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put(
                    "task",
                    Arrays.asList(
                            task.getId(),
                            task.getSourceTaskCode(),
                            task.getSourceRequestId(),
                            task.getOrderNo(),
                            task.getMatCode(),
                            task.getProcessCode(),
                            task.getLabelType(),
                            task.getNum(),
                            task.getStatus(),
                            task.getResult(),
                            task.getUsableNum(),
                            task.getHandleNum(),
                            task.getLatestAppendTime(),
                            task.getLatestInspectionTime(),
                            task.getFactoryCode()));
            fact.put(
                    "history",
                    histories.stream()
                            .map(
                                    history ->
                                            Arrays.asList(
                                                    history.getId(),
                                                    history.getType(),
                                                    history.getNum(),
                                                    history.getTime()))
                            .toList());
            fact.put("itemResultCount", resultCount);
            fact.put("handleCount", handleCount);
            taskFacts.add(fact);
        }
        Map<String, Object> evidenceFacts = new LinkedHashMap<>();
        evidenceFacts.put("factoryCode", factoryCode);
        evidenceFacts.put("sourceTaskCodes", sourceTaskCodes);
        evidenceFacts.put("tasks", taskFacts);
        String evidenceSnapshot = JSONUtil.toJsonStr(evidenceFacts);
        String actualEvidence = sha256Withdrawal(evidenceSnapshot);
        if (Boolean.TRUE.equals(command.getPreview())) {
            return new QmsProcedureSourceWithdrawalDTO.Receipt()
                    .setRequestId(requestId)
                    .setStatus("ELIGIBLE")
                    .setCancelledTaskCount(0)
                    .setTaskIds(taskIds)
                    .setEvidenceFingerprint(actualEvidence)
                    .setEvidenceSnapshot(evidenceSnapshot)
                    .setHandledTime(LocalDateTime.now())
                    .setMessage(
                            tasks.isEmpty()
                                    ? "MES工序没有关联QMS质检任务，无需作废"
                                    : "关联QMS工序质检任务均未开始，可随MES计划受控作废");
        }
        requireWithdrawal(Objects.equals(expectedEvidence, actualEvidence),
                "QMS工序质检事实已变化，请重新申请计划撤回");
        for (QmsInspectionProcedureTaskEntity task : tasks) {
            task.setStatus(InspectionTaskStatusEnum.FINISHED.name()).setResult(InspectionTaskResultEnum.CANCELED.name());
            requireWithdrawal(updateById(task), "QMS工序质检任务[" + task.getId() + "]作废失败");
            historyService.canceled(task, reason);
        }
        QmsProcedureSourceWithdrawalReceiptEntity receipt = new QmsProcedureSourceWithdrawalReceiptEntity()
                .setRequestId(requestId).setRequestFingerprint(requestFingerprint)
                .setTaskSetFingerprint(taskSetFingerprint).setSourceTaskCodes(JSONUtil.toJsonStr(sourceTaskCodes))
                .setTaskIds(taskIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .setCancelledTaskCount(tasks.size()).setExpectedEvidenceFingerprint(expectedEvidence)
                .setActualEvidenceFingerprint(actualEvidence).setEvidenceSnapshot(evidenceSnapshot)
                .setStatus("SUCCEEDED").setReason(reason)
                .setMessage("已受控作废" + tasks.size() + "张未开始的QMS工序质检任务")
                .setHandledTime(LocalDateTime.now()).setFactoryCode(factoryCode);
        requireWithdrawal(sourceWithdrawalReceiptMapper.insert(receipt) == 1, "QMS来源质检撤回回执保存失败");
        return sourceWithdrawalReceipt(receipt, "SUCCEEDED");
    }

    private QmsProcedureSourceWithdrawalDTO.Receipt sourceWithdrawalReceipt(
            QmsProcedureSourceWithdrawalReceiptEntity receipt, String status) {
        List<Long> taskIds = StrUtil.isBlank(receipt.getTaskIds()) ? List.of()
                : Arrays.stream(receipt.getTaskIds().split(",")).map(Long::valueOf).toList();
        return new QmsProcedureSourceWithdrawalDTO.Receipt().setRequestId(receipt.getRequestId()).setStatus(status)
                .setCancelledTaskCount(receipt.getCancelledTaskCount()).setTaskIds(taskIds)
                .setEvidenceFingerprint(receipt.getActualEvidenceFingerprint())
                .setEvidenceSnapshot(receipt.getEvidenceSnapshot()).setHandledTime(receipt.getHandledTime())
                .setMessage(receipt.getMessage());
    }

    private String sha256Withdrawal(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("QMS来源质检撤回指纹生成失败");
        }
    }

    private void requireWithdrawal(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 免检质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void exemption(Long taskId) {
        inspectionAccess.requireWrite();
        var task = lockInspection(taskId);
        if (Objects.equals(task.getResult(), InspectionTaskResultEnum.CANCELED.name())) {
            throw new RuntimeException("已作废的任务无法免检");
        }
        if (Objects.equals(task.getResult(), InspectionTaskResultEnum.EXEMPTION.name())) {
            throw new RuntimeException("任务已经免检");
        }
        if (!InspectionTaskStatusEnum.WAIT.name().equals(task.getStatus())
                || StrUtil.isNotBlank(task.getResult())) {
            throw new RuntimeException("只有尚未开始的质检任务可以免检，不合格任务必须走正式处置");
        }
        task.setStatus(InspectionTaskStatusEnum.FINISHED.name());
        task.setResult(InspectionTaskResultEnum.EXEMPTION.name());
        task.setUsableNum(task.getNum()).setHandleNum(task.getNum()).setHandleResult(InspectionTaskResultEnum.EXEMPTION.name());
        require(updateById(task), "检验任务免检保存失败");
        // 保存历史
        historyService.exemption(task);
    }

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    public QmsInspectionProcedureTaskDTO findById(Long id) {
        QmsInspectionProcedureTaskDTO result = new QmsInspectionProcedureTaskDTO();
        QmsInspectionProcedureTaskEntity task = this.getById(id);
        result.setTask(task);
        List<QmsInspectionProcedureTaskDetailEntity> details = procedureTaskDetailService.list(new LambdaQueryWrapper<QmsInspectionProcedureTaskDetailEntity>()
                .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, task.getId()));
        Integer waitNum = details.stream().map(QmsInspectionProcedureTaskDetailEntity::getWaitNum).reduce(0, Integer::sum);
        if (!InspectionTaskStatusEnum.FINISHED.name().equals(task.getStatus()) && !Objects.equals(waitNum, 0)) {
            details = details.stream().filter(item -> !Objects.equals(item.getWaitNum(), 0)).collect(Collectors.toList());
        }
        result.setDetails(details);
        result.setClassify(new ArrayList<>());
        if (!Objects.equals(waitNum, 0)) {
            Map<String, Integer> classifyMaxWaitNumMap = details.stream()
                    .collect(Collectors.groupingBy(
                            QmsInspectionProcedureTaskDetailEntity::getCheckClassify,
                            Collectors.collectingAndThen(
                                    Collectors.mapping(QmsInspectionProcedureTaskDetailEntity::getWaitNum,
                                            Collectors.maxBy(Integer::compare)),
                                    optional -> optional.orElse(0)
                            )
                    ));

            List<QmsCheckClassifyDTO> classify = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : classifyMaxWaitNumMap.entrySet()) {
                QmsCheckClassifyDTO checkClassifyDTO = new QmsCheckClassifyDTO();
                checkClassifyDTO.setName(entry.getKey());
                checkClassifyDTO.setNum(entry.getValue());
                classify.add(checkClassifyDTO);
            }
            result.setClassify(classify);
        }
        return result;
    }

    /**
     * 检查并更新质检项
     *
     * @param detail 质检项
     * @param task 质检任务
     * @param standard 质检标准
     * @param item 原项目关联的标准项
     */
    private void updateTaskDetails(
            QmsInspectionProcedureTaskDetailEntity detail,
            QmsInspectionProcedureTaskEntity task,
            int previousBatch,
            QmsQualityStandardEntity standard,
            QmsQualityStandardItemEntity item) {
        var frequencies =
                "RANDOM".equals(standard.getInspectionMethod()) ? samplingFrequencies(item) : null;
        var previousFrequency =
                frequencies == null
                        ? null
                        : QmsProcedureInspectionChecks.frequency(previousBatch, frequencies);
        var frequency =
                frequencies == null
                        ? null
                        : QmsProcedureInspectionChecks.frequency(task.getNum(), frequencies);
        int previousCount =
                QmsProcedureInspectionChecks.sampleSize(
                        previousBatch,
                        standard.getInspectionMethod(),
                        standard.getNum(),
                        task.getInspectionNum(),
                        previousFrequency);
        int count =
                QmsProcedureInspectionChecks.sampleSize(
                        task.getNum(),
                        standard.getInspectionMethod(),
                        standard.getNum(),
                        task.getInspectionNum(),
                        frequency);
        int ac = detail.getAc() == null ? 0 : detail.getAc(),
                re = detail.getRe() == null ? 1 : detail.getRe();
        require(
                previousCount == detail.getTotalNum()
                        && ac == (previousFrequency == null ? 0 : previousFrequency.getAc())
                        && re == (previousFrequency == null ? 1 : previousFrequency.getRe())
                        && ac == (frequency == null ? 0 : frequency.getAc())
                        && re == (frequency == null ? 1 : frequency.getRe())
                        && Objects.equals(detail.getCheckType(), item.getCheckType())
                        && Objects.equals(detail.getUpperLimit(), item.getUpperLimit())
                        && Objects.equals(detail.getLowerLimit(), item.getLowerLimit()),
                "当前抽样或判定配置与原检验要求不兼容，请新建受控检验任务");
        // 旧任务未保存完整抽样计划；原来等量全检的事实不能被追加解释成少量抽检。
        require(
                detail.getTotalNum() != previousBatch || count == task.getNum(),
                "原任务没有可证明的抽样上限，追加后必须保持全检；请新建检验任务");
        require(count >= detail.getTotalNum(), "追加检验不能缩减原应检数量，请核对抽样配置");
        int difference = count - detail.getTotalNum();
        require(detail.getRecords() != null, "原检验项目缺少样本占位信息，请先核对原记录");
        detail.getRecords()
                .addAll(
                        IntStream.range(0, difference)
                                .mapToObj(i -> new InspectionItemResultRecord())
                                .collect(Collectors.toList()));
        detail.setTotalNum(count).setWaitNum(count - detail.getFinishNum());
        QmsProcedureInspectionChecks.counters(detail);
    }

    private QmsQualityStandardEntity publishedStandard(Long id, String matCode) {
        var query =
                new LambdaQueryWrapper<QmsQualityStandardEntity>()
                        .eq(
                                QmsQualityStandardEntity::getTenantId,
                                TenantContextHolder.getTenantId())
                        .eq(
                                QmsQualityStandardEntity::getFactoryCode,
                                FactoryContextHolder.getFactoryCode())
                        .eq(QmsQualityStandardEntity::getMatCode, matCode)
                        .eq(
                                QmsQualityStandardEntity::getInspectionType,
                                InspectionTaskTypeEnum.PROCEDURE.name())
                        .eq(QmsQualityStandardEntity::getPublished, 1)
                        .eq(id != null, QmsQualityStandardEntity::getId, id);
        var standard = qualityStandardService.getOne(query);
        require(
                standard != null
                        && (id == null || Objects.equals(id, standard.getId()))
                        && Objects.equals(standard.getMatCode(), matCode)
                        && Objects.equals(standard.getPublished(), 1)
                        && "PROCEDURE".equals(standard.getInspectionType())
                        && Objects.equals(standard.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                standard.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "当前实际组织不存在匹配物料的已发布工序检验标准");
        return standard;
    }

    private void requireMutableSource(QmsInspectionProcedureTaskEntity task) {
        require(
                StrUtil.isBlank(task.getSourceRequestFingerprint())
                        && (task.getSourceRequestId() == null
                                || !task.getSourceRequestId()
                                        .startsWith(
                                                InspectionProcedureSourceContract.REQUEST_PREFIX)),
                "MES已冻结下发的检验任务不能直接改量或更换标准，请由原生产来源重新发起");
    }

    private List<QmsInspectionProcedureTaskDetailEntity> inspectionDetails(Long taskId) {
        return procedureTaskDetailService.list(
                detailScope()
                        .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, taskId)
                        .last("LIMIT 5001"));
    }

    private List<QmsQualityStandardItemEntity> standardItems(
            QmsInspectionProcedureTaskEntity task, QmsQualityStandardEntity standard) {
        var items =
                qualityStandardItemService.list(
                        new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                                .eq(QmsQualityStandardItemEntity::getStandardId, standard.getId())
                                .eq(QmsQualityStandardItemEntity::getTenantId, task.getTenantId())
                                .eq(
                                        QmsQualityStandardItemEntity::getFactoryCode,
                                        task.getFactoryCode())
                                .last("LIMIT 5001"));
        require(items != null && !items.isEmpty() && items.size() <= 5000, "质检标准没有完整有界的检验项目");
        var ids = new HashSet<Long>();
        for (var item : items)
            require(
                    item != null
                            && item.getId() != null
                            && ids.add(item.getId())
                            && Objects.equals(item.getStandardId(), standard.getId())
                            && Objects.equals(item.getTenantId(), task.getTenantId())
                            && Objects.equals(item.getFactoryCode(), task.getFactoryCode()),
                    "检验标准项缺失、重复或不属于当前标准/组织");
        return items;
    }

    /** 由完整冻结方案生成不同的实际项目。 */
    private List<QmsInspectionProcedureTaskDetailEntity> generateDetail(
            QmsInspectionProcedureTaskEntity task, QmsFrozenInspectionPlan plan) {
        return plan.targets(task.getNum()).stream()
                .map(
                        target -> {
                            var item = target.item();
                            int count = target.count();
                            var detail =
                                    new QmsInspectionProcedureTaskDetailEntity()
                                            .setTaskId(task.getId())
                                            .setStandardItemId(item.standardItemId())
                                            .setCheckType(item.checkType())
                                            .setUpperLimit(item.upperLimit())
                                            .setLowerLimit(item.lowerLimit())
                                            .setName(item.name())
                                            .setDemand(item.demand())
                                            .setMethod(item.testMethod())
                                            .setCheckClassify(item.checkClassify())
                                            .setPosition(item.position())
                                            .setTotalNum(count)
                                            .setWaitNum(count)
                                            .setAc(target.ac())
                                            .setRe(target.re())
                                            .setRecords(
                                                    IntStream.range(0, count)
                                                            .mapToObj(
                                                                    i ->
                                                                            new InspectionItemResultRecord())
                                                            .collect(Collectors.toList()))
                                            .setFactoryCode(task.getFactoryCode());
                            detail.setTenantId(task.getTenantId());
                            return detail;
                        })
                .toList();
    }

private List<Frequency> samplingFrequencies(QmsQualityStandardItemEntity item) {
        require(
                item.getSamplingStrategiesId() != null
                        && item.getSamplingStrategiesId().matches("[1-9][0-9]{0,18}"),
                "检验项缺少有效抽样策略");
        Long id;
        try {
            id = Long.valueOf(item.getSamplingStrategiesId());
        } catch (NumberFormatException e) {
            throw new CheckedException("抽样策略编号超出范围", e);
        }
        var strategy =
                samplingStrategiesService.getOne(
                        new LambdaQueryWrapper<QmsQualitySamplingStrategiesEntity>()
                                .eq(QmsQualitySamplingStrategiesEntity::getId, id)
                                .eq(
                                        QmsQualitySamplingStrategiesEntity::getTenantId,
                                        TenantContextHolder.getTenantId())
                                .eq(
                                        QmsQualitySamplingStrategiesEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode()));
        require(
                strategy != null
                        && Objects.equals(strategy.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                strategy.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "当前组织不存在检验抽样策略");
        return strategy.getFrequency();
    }

    /**
     * 判断是否已经有同类型任务
     *
     * @param matCode     物料号
     * @param processCode 工序
     * @param batchNo     批次号
     * @return 是否已经存在
     */
    public boolean existTask(String matCode, String processCode, String batchNo) {
        // 已作废和已处理（已生产入库通知单）的不能追加
        List<String> notHandle = new ArrayList<>();
        notHandle.add(InspectionTaskResultEnum.CANCELED.name());
        return this.lambdaQuery().eq(QmsInspectionProcedureTaskEntity::getMatCode, matCode)
                .eq(QmsInspectionProcedureTaskEntity::getProcessCode, processCode)
                .eq(QmsInspectionProcedureTaskEntity::getBatchNo, batchNo)
                .ge(QmsInspectionProcedureTaskEntity::getLatestAppendTime, LocalDateTime.of(LocalDate.now(), LocalTime.MIN))
                .le(QmsInspectionProcedureTaskEntity::getLatestAppendTime, LocalDateTime.of(LocalDate.now(), LocalTime.MAX))
                .notIn(QmsInspectionProcedureTaskEntity::getResult, notHandle).exists();
    }

    /**
     * 根据物料/供应商/批次号 查询质检任务, 创建质检时使用
     */
    public QmsInspectionProcedureTaskEntity getTaskByMatCodeAndSupplierCodeAndBatchNo(String matCode, String processCode,
                                                                                      String batchNo) {
        // 已作废和已处理（已生产入库通知单）的不能追加
        List<String> notHandle = new ArrayList<>();
        notHandle.add(InspectionTaskResultEnum.CANCELED.name());
        return this.lambdaQuery().eq(QmsInspectionProcedureTaskEntity::getMatCode, matCode)
                .eq(QmsInspectionProcedureTaskEntity::getProcessCode, processCode)
                .eq(QmsInspectionProcedureTaskEntity::getBatchNo, batchNo)
                .notIn(QmsInspectionProcedureTaskEntity::getResult, notHandle).oneOpt()
                .orElseThrow(() -> new RuntimeException("未找到相关的质检任务"));
    }

    /**
     * 获取质检项列表
     */
    public List<QmsInspectionProcedureTaskDetailEntity> findDetailsByTaskId(Long taskId) {
        return procedureTaskDetailService.lambdaQuery().eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, taskId).list();
    }

    /**
     * 获取质检项详情
     */
    public QmsInspectionProcedureTaskDetailEntity findDetailById(Long detailId) {
        QmsInspectionProcedureTaskDetailEntity result = procedureTaskDetailService.getById(detailId);
        if (Objects.isNull(result)) {
            throw new RuntimeException("质检详情不存在");
        }
        return result;
    }

    /**
     * 暂存质检内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void temporarilySave(InspectionCheckResultParam param) {
        inspectionAccess.requireWrite();
        require(
                param != null && param.getDetailId() != null && param.getRecord() != null,
                "请填写指定检验项目的结果");
        var initial =
                procedureTaskDetailService.getOne(
                        detailScope()
                                .eq(
                                        QmsInspectionProcedureTaskDetailEntity::getId,
                                        param.getDetailId()));
        require(initial != null, "当前组织不存在指定检验项目");
        var task = lockInspection(initial.getTaskId());
        assertInspectionWritable(task);
        var detail = requireDetail(param.getDetailId(), task.getId());
        inspectionPieces.anonymous(task);
        collect(task, detail, param.getRecord());
        if ("WAIT".equals(task.getStatus())) {
            task.setStatus("RUNNING");
            require(updateById(task), "检验任务开始状态保存失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchTemporarilySave(InspectionCheckResultBatchParam param) {
        inspectionAccess.requireWrite();
        require(
                param != null
                        && param.getResults() != null
                        && !param.getResults().isEmpty()
                        && param.getResults().size() <= 5000,
                "请填写有界的检验结果");
        var task = lockInspection(param.getTaskId());
        assertInspectionWritable(task);
        inspectionPieces.anonymous(task);
        Set<Long> details = new HashSet<>();
        for (var result : param.getResults()) {
            require(
                    result != null
                            && result.getDetailId() != null
                            && result.getRecord() != null
                            && details.add(result.getDetailId()),
                    "检验项目或结果缺失，或同一项目重复提交");
            collect(task, requireDetail(result.getDetailId(), task.getId()), result.getRecord());
        }
        boolean changed = "WAIT".equals(task.getStatus());
        if (changed) task.setStatus("RUNNING");
        if (StrUtil.isNotBlank(param.getAnnex())) {
            task.setAnnex(param.getAnnex());
            changed = true;
        }
        if (changed) require(updateById(task), "检验任务状态或附件保存失败");
        finishInspection(task, false);
    }

    /** 实物标识、原请求、实际样本、累计和正式判定在同一父任务事务中提交。 */
    @Transactional(rollbackFor = Exception.class)
    public com.kitex.mom.qms.dto.QmsInspectionPieceDTO.Receipt collectPiece(
            com.kitex.mom.qms.dto.QmsInspectionPieceDTO.Command command) {
        inspectionAccess.requireWrite();
        require(command != null, "请提供逐件检验原请求");
        var task = lockInspection(QmsInspectionPieceService.id(command.taskId()));
        var actual = inspectionDetails(task.getId());
        var context = inspectionPieces.begin(task, actual, command);
        if (context.replay() != null) return context.replay();
        assertInspectionWritable(task);
        for (var detail : actual) {
            var input = QmsInspectionPieceService.input(detail, context.results().get(detail.getId()));
            inspectionPieces.attach(context, task, collect(task, detail, input));
        }
        if ("WAIT".equals(task.getStatus())) {
            task.setStatus("RUNNING");
            require(updateById(task), "检验任务开始状态保存失败");
        }
        finishInspection(task, false);
        return inspectionPieces.completed(context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void submitInspectionResult(Long taskId) {
        inspectionAccess.requireWrite();
        var task = lockInspection(taskId);
        if (StrUtil.isNotBlank(task.getResult())) return;
        assertInspectionWritable(task);
        finishInspection(task, true);
    }

    private QmsInspectionProcedureTaskEntity lockInspection(Long id) {
        require(id != null && id > 0, "工序检验任务编号无效");
        require(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "工序检验写入必须在原业务事务中");
        var task =
                baseMapper.lockForInspection(
                        id,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        require(
                task != null
                        && Objects.equals(task.getId(), id)
                        && Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                task.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "当前实际组织不存在工序检验任务");
        inspectionBasis.validateCurrent(task, inspectionDetails(task.getId()));
        return task;
    }

    private LambdaQueryWrapper<QmsInspectionProcedureTaskDetailEntity> detailScope() {
        return new LambdaQueryWrapper<QmsInspectionProcedureTaskDetailEntity>()
                .eq(
                        QmsInspectionProcedureTaskDetailEntity::getTenantId,
                        TenantContextHolder.getTenantId())
                .eq(
                        QmsInspectionProcedureTaskDetailEntity::getFactoryCode,
                        FactoryContextHolder.getFactoryCode());
    }

    private QmsInspectionProcedureTaskDetailEntity requireDetail(Long id, Long taskId) {
        var detail =
                procedureTaskDetailService.getOne(
                        detailScope()
                                .eq(QmsInspectionProcedureTaskDetailEntity::getId, id)
                                .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, taskId));
        require(detail != null, "检验项目不属于当前实际任务");
        return detail;
    }

    private QmsInspectionProcedureItemResultRecordEntity collect(
            QmsInspectionProcedureTaskEntity task,
            QmsInspectionProcedureTaskDetailEntity detail,
            InspectionItemResultRecord input) {
        QmsProcedureInspectionChecks.counters(detail);
        require(detail.getWaitNum() > 0, "该检验项已达到应检数量，请勿重复提交");
        boolean qualified = QmsProcedureInspectionChecks.judge(detail, input);
        var record =
                new QmsInspectionProcedureItemResultRecordEntity()
                        .setTaskId(task.getId())
                        .setDetailId(detail.getId())
                        .setDetailName(detail.getName())
                        .setDoubleValue(input.getDoubleValue())
                        .setQualified(qualified)
                        .setTime(new Date());
        require(recordService.save(record), "实际检验样本保存失败");
        detail.setFinishNum(detail.getFinishNum() + 1)
                .setWaitNum(detail.getWaitNum() - 1)
                .setUnqualifiedNum(detail.getUnqualifiedNum() + (qualified ? 0 : 1));
        detail.setQualifiedStatus(
                detail.getUnqualifiedNum() <= (detail.getAc() == null ? 0 : detail.getAc()));
        require(procedureTaskDetailService.updateById(detail), "检验项目累计数量保存失败");
        return record;
    }

    private void finishInspection(QmsInspectionProcedureTaskEntity task, boolean strict) {
        var details =
                procedureTaskDetailService.list(
                        detailScope()
                                .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, task.getId())
                                .last("LIMIT 5001"));
        inspectionBasis.validateCurrent(task, details);
        var completion =
                QmsProcedureInspectionChecks.completion(
                        task,
                        details,
                        baseMapper.inspectionSamples(
                                task.getId(), task.getTenantId(), task.getFactoryCode()));
        if (!completion.ready()) {
            require(!strict, "检验任务尚未填写完毕，请核对待检项目");
            return;
        }
        task.setStatus(completion.qualified() ? "FINISHED" : "UNHANDLED")
                .setResult(completion.qualified() ? "QUALIFIED" : "UN_QUALIFIED")
                .setLatestInspectionTime(new Date())
                .setUsableNum(completion.qualified() ? task.getNum() : 0)
                .setHandleNum(completion.qualified() ? task.getNum() : 0);
        require(updateById(task), "检验正式判定保存失败");
        if (!completion.qualified())
            badInfoService.add(
                    new QmsBadInfoEntity()
                            .setResourceTaskId(task.getId())
                            .setResourceType("PROCEDURE")
                            .setNum(BigDecimal.valueOf(task.getNum()))
                            .setMatCode(task.getMatCode())
                            .setMatName(task.getMatName())
                            .setBatchNo(task.getBatchNo()));
        historyService.check(task);
        if (!completion.qualified() && StrUtil.isNotBlank(task.getOrderNo()))
            qmsPlanEventOutboxService.enqueueIsolation("PROCEDURE", task.getId());
        reworkResultPublisher.publish("PROCEDURE", task.getId());
    }

    private void assertInspectionWritable(QmsInspectionProcedureTaskEntity task) {
        require(
                ("WAIT".equals(task.getStatus()) || "RUNNING".equals(task.getStatus()))
                        && StrUtil.isBlank(task.getResult()),
                "当前检验任务已结束或进入处置，不能继续填写结果");
    }


    /**
     * 分页
     */
    public Page<QmsInspectionProcedureTaskEntity> page(Page<QmsInspectionProcedureTaskEntity> page, InspectionProcedureTaskReq param,
                                                       CommonQuery commonQuery) {
        QueryWrapper<QmsInspectionProcedureTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .eq(param.getTaskId() != null, QmsInspectionProcedureTaskEntity::getId, param.getTaskId())
                .like(StrUtil.isNotBlank(param.getMatCode()), QmsInspectionProcedureTaskEntity::getMatCode, param.getMatCode())
                .like(StrUtil.isNotBlank(param.getMatName()), QmsInspectionProcedureTaskEntity::getMatName, param.getMatName())
                .like(StrUtil.isNotBlank(param.getMatSpec()), QmsInspectionProcedureTaskEntity::getMatSpec, param.getMatSpec())
                .eq(StrUtil.isNotBlank(param.getResult()), QmsInspectionProcedureTaskEntity::getResult, param.getResult())
                .like(StrUtil.isNotBlank(param.getProcessCode()), QmsInspectionProcedureTaskEntity::getProcessCode,
                        param.getProcessCode())
                .like(StrUtil.isNotBlank(param.getProcessName()), QmsInspectionProcedureTaskEntity::getProcessName,
                        param.getProcessName())
                .like(StrUtil.isNotBlank(param.getBatchNo()), QmsInspectionProcedureTaskEntity::getBatchNo,
                        param.getBatchNo())
                .eq(StrUtil.isNotBlank(param.getStatus()), QmsInspectionProcedureTaskEntity::getStatus, param.getStatus());
        autoQueryWrapper.orderByDesc("create_time");
        return this.page(page, autoQueryWrapper);
    }

    /**
     * app分页
     *
     * @param page
     * @param param
     * @param commonQuery
     * @return
     */
    @Override
    public QmsInspectionProcedureTaskAppVO queryAppPage(Page<QmsInspectionProcedureTaskEntity> page,
                                                        InspectionProcedureTaskReq param, CommonQuery commonQuery) {
        QmsInspectionProcedureTaskAppVO result = new QmsInspectionProcedureTaskAppVO();
        List<QmsInspectionProcedureTaskEntity> allList = this.list();
        Map<String, Long> statusCountMap = allList.stream()
                .collect(Collectors.groupingBy(QmsInspectionProcedureTaskEntity::getStatus, Collectors.counting()));
        List<QmsStatisticsDTO> classify = statusCountMap.entrySet().stream()
                .map(entry -> new QmsStatisticsDTO(entry.getKey(), entry.getValue().intValue()))
                .toList();
        result.setClassify(classify);
        QueryWrapper<QmsInspectionProcedureTaskEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(param);
        result.setPage(this.page(page, autoQueryWrapper));
        return result;
    }

    /**
     * 查询状态栏
     *
     * @return
     */
    @Override
    public List<QmsInspectionStatusVO> queryStatusList() {
        List<QmsInspectionStatusVO> statusList = InspectionTaskStatusEnum.convertToStatusList();
        List<QmsInspectionProcedureTaskEntity> allList = this.list();
        statusList.forEach(status -> {
            // 未处理（1 完成质检但是最终的结果时不合格 2 完成质检但是需要处理最终的合格数量）
            if (InspectionTaskStatusEnum.UNHANDLED.name().equals(status.getCode())) {
                status.setNum(Math.toIntExact(allList.stream().filter(item -> item.getStatus().equals(InspectionTaskStatusEnum.FINISHED.name())
                        && item.getResult().equals(InspectionTaskResultEnum.UN_QUALIFIED.name()) && !Objects.equals(item.getNum(), item.getHandleNum())).count()));
            } else {
                status.setNum(Math.toIntExact(allList.stream().filter(item -> item.getStatus().equals(status.getCode())).count()));
            }
        });
        return statusList;
    }

    @Override
    public Map<String, Long> getStatusCount(InspectionProcedureTaskReq param) {
        QueryWrapper<QmsInspectionProcedureTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .eq(param.getTaskId() != null, QmsInspectionProcedureTaskEntity::getId, param.getTaskId())
                .like(StrUtil.isNotBlank(param.getMatCode()), QmsInspectionProcedureTaskEntity::getMatCode, param.getMatCode())
                .like(StrUtil.isNotBlank(param.getMatName()), QmsInspectionProcedureTaskEntity::getMatName, param.getMatName())
                .eq(StrUtil.isNotBlank(param.getResult()), QmsInspectionProcedureTaskEntity::getResult, param.getResult())
                .like(StrUtil.isNotBlank(param.getProcessCode()), QmsInspectionProcedureTaskEntity::getProcessCode,
                        param.getProcessCode())
                .like(StrUtil.isNotBlank(param.getProcessName()), QmsInspectionProcedureTaskEntity::getProcessName,
                        param.getProcessName())
                .like(StrUtil.isNotBlank(param.getBatchNo()), QmsInspectionProcedureTaskEntity::getBatchNo,
                        param.getBatchNo());
        autoQueryWrapper.orderByDesc("create_time");

        List<QmsInspectionProcedureTaskEntity> tasks = this.list(autoQueryWrapper);

        // 统计常规状态
        return tasks.stream()
                .collect(Collectors.groupingBy(QmsInspectionProcedureTaskEntity::getStatus, Collectors.counting()));
    }

    /**
     * 从质检项列表中获取质检任务详情关联的质检项
     */
    private QmsQualityStandardItemEntity findQualityStandardItemByTaskDetail(List<QmsQualityStandardItemEntity> list,
                                                                             QmsInspectionProcedureTaskDetailEntity detail) {
        // 查询关联质检项配置
        return list.stream().filter(o -> Objects.equals(o.getId(), detail.getStandardItemId())).findFirst()
                .orElseThrow(() -> new RuntimeException(detail.getName() + "未找到对应的质检项配置"));
    }

    /**
     * 根据任务Id获取质检项报表
     *
     * @param taskId
     * @return
     */
    public ReportListRespDTO findReportListByTaskId(Long taskId) {
        ReportListRespDTO reportListRespDTO = new ReportListRespDTO();
        List<QmsInspectionProcedureTaskDetailEntity> list = procedureTaskDetailService.lambdaQuery()
                .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, taskId).list();
        // 判断是否有符合要求的数据
        if (CollUtil.isEmpty(list)) {
            return reportListRespDTO;
        }
        List<Map<String, Object>> records = new ArrayList<>();
        List<Integer> tableColumns = new ArrayList<>();
        Integer key = 0;
        for (QmsInspectionProcedureTaskDetailEntity detail : list) {
            key++;
            Map<String, Object> record = new HashMap<>();
            // 获取点检类型
            record.put("#", key);
            record.put("name", detail.getName());
            record.put("demand", detail.getDemand());
            record.put("method", detail.getMethod());
            record.put("position", detail.getPosition());
            record.put("totalNum", detail.getTotalNum());
            record.put("checkClassify", detail.getCheckClassify());
            record.put("checkType", this.getCheckTypeName(detail));
            record.put(
                    "qualifiedStatus",
                    Objects.nonNull(detail.getQualifiedStatus()) && detail.getQualifiedStatus()
                            ? "合格"
                            : "不合格");
            List<QmsInspectionProcedureItemResultRecordEntity> itemList = recordService.list(new LambdaQueryWrapper<QmsInspectionProcedureItemResultRecordEntity>()
                    .eq(QmsInspectionProcedureItemResultRecordEntity::getDetailId, detail.getId()));
            if (CollUtil.isEmpty(itemList)) {
                continue;
            }
            for (int i = 1; i <= itemList.size(); i++) {
                tableColumns.add(i);
                // 获取对应对象
                QmsInspectionProcedureItemResultRecordEntity item = itemList.get(i - 1);
                if (Objects.nonNull(item.getDoubleValue()) && item.getDoubleValue() > 0) {
                    record.put(String.valueOf(i), item.getDoubleValue());
                } else {
                    record.put(String.valueOf(i), item.getQualified() ? "YES" : "NO");
                }

            }
            records.add(record);
        }
        List<Integer> sorted = tableColumns.stream().distinct().sorted().collect(Collectors.toList());
        reportListRespDTO.setRecords(records);
        reportListRespDTO.setTableColumns(sorted.stream().map(String::valueOf).collect(Collectors.toList()));
        return reportListRespDTO;
    }

    private String getCheckTypeName(QmsInspectionProcedureTaskDetailEntity detail) {
        String result = "";
        CheckTypeEnum checkTypeEnum = CheckTypeEnum.findByName(detail.getCheckType());
        result = checkTypeEnum.getValue();
        if (CheckTypeEnum.UPPER_LIMIT.name().equals(detail.getCheckType())) {
            result += "(" + detail.getUpperLimit() + ")";
        }
        if (CheckTypeEnum.LOWER_LIMIT.name().equals(detail.getCheckType())) {
            result += "(" + detail.getLowerLimit() + ")";
        }
        if (CheckTypeEnum.RANG.name().equals(detail.getCheckType())) {
            result += "(下限：" + detail.getLowerLimit() + " 上限：" + detail.getUpperLimit() + ")";
        }
        return result;
    }

    /**
     * 处置完成
     *
     * @param dto
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleFinish(HandleFinishDTO dto) {
        if (procedureDispositions.handleIfManaged(dto)) {
            return;
        }
        // 锁定质检任务，避免两个不同请求同时处置导致累计数量越界。
        QmsInspectionProcedureTaskEntity task =
                this.getOne(
                        new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                                .eq(QmsInspectionProcedureTaskEntity::getId, dto.getId())
                                .last("FOR UPDATE"),
                        false);
        if (task == null) {
            throw new RuntimeException("工序质检任务不存在");
        }
        if (dto.getHandleNum() == null || dto.getHandleNum() <= 0) {
            throw new RuntimeException("处置数量必须大于0");
        }
        try {
            HandleResultEnum.valueOf(dto.getHandleResult());
        } catch (Exception exception) {
            throw new RuntimeException("请选择有效的处置结果");
        }
        if (StrUtil.isBlank(dto.getHandleRemark())) {
            throw new RuntimeException("处置原因不能为空");
        }
        String requestId = StrUtil.blankToDefault(dto.getRequestId(), UUID.randomUUID().toString()).trim();
        if (requestId.length() > 100) {
            throw new RuntimeException("处置请求号长度不能超过100位");
        }
        QmsInspectionProcedureHandleRecordEntity existing = handleRecordService.getOne(
                new LambdaQueryWrapper<QmsInspectionProcedureHandleRecordEntity>()
                        .eq(QmsInspectionProcedureHandleRecordEntity::getRequestId, requestId), false);
        if (existing != null) {
            if (Objects.equals(existing.getTaskId(), dto.getId())
                    && Objects.equals(existing.getHandleNum(), dto.getHandleNum())
                    && Objects.equals(existing.getHandleResult(), dto.getHandleResult())) {
                return;
            }
            throw new RuntimeException("处置请求号已被其他业务使用");
        }
        if (!InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult())
                || !InspectionTaskStatusEnum.UNHANDLED.name().equals(task.getStatus())) {
            throw new RuntimeException("只有待处置的不合格工序质检任务可以处置");
        }
        task.setHandleNum(Objects.requireNonNullElse(task.getHandleNum(), 0) + dto.getHandleNum());
        if (task.getHandleNum() > task.getNum()) {
            throw new RuntimeException("处置数量不能大于未处置数量");
        }
        if (HandleResultEnum.RELEASE.name().equals(dto.getHandleResult())
                || HandleResultEnum.CONCESSION.name().equals(dto.getHandleResult())) {
            // 可用量只累计真正允许继续流转的数量，供MES按数量解除隔离。
            task.setUsableNum(Objects.requireNonNullElse(task.getUsableNum(), 0) + dto.getHandleNum());
        }
        if (HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult())) {
            // 入不合格品库
            badInfoService.add(new QmsBadInfoEntity().setResourceTaskId(task.getId()).setResourceType(InspectionTaskTypeEnum.PROCEDURE.name())
                    .setRequestId("DISPOSITION:" + requestId)
                    .setNum(dto.getHandleNum() == null ? null : java.math.BigDecimal.valueOf(dto.getHandleNum())).setMatCode(task.getMatCode()).setMatName(task.getMatName())
                    .setBatchNo(task.getBatchNo()));
        }
        this.updateById(task);

        // 保存处理明细
        QmsInspectionProcedureHandleRecordEntity record = new QmsInspectionProcedureHandleRecordEntity();
        MomUser user = SecurityUtils.getUser();
        record.setRequestId(requestId).setHandleNum(dto.getHandleNum()).setHandleRemark(dto.getHandleRemark().trim()).setHandlePersonId(user.getId())
                .setHandlePersonName(user.getName()).setHandleTime(new Date()).setHandleResult(dto.getHandleResult())
                .setTaskId(task.getId()).setStatus(Boolean.FALSE);
        if (HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult()) || HandleResultEnum.SMASH.name().equals(dto.getHandleResult())) {
            record.setStatus(Boolean.TRUE);
        }
        handleRecordService.save(record);
        if (Set.of(HandleResultEnum.RELEASE.name(), HandleResultEnum.CONCESSION.name(),
                HandleResultEnum.REWORK.name(), HandleResultEnum.SMASH.name()).contains(dto.getHandleResult())
                && StrUtil.isNotBlank(task.getOrderNo())) {
            qmsPlanEventOutboxService.enqueueDisposition("PROCEDURE", task.getId(), record.getId());
        }

        List<QmsInspectionProcedureHandleRecordEntity> dispositions = handleRecordService.list(
                new LambdaQueryWrapper<QmsInspectionProcedureHandleRecordEntity>()
                        .eq(QmsInspectionProcedureHandleRecordEntity::getTaskId, task.getId()));
        String handleResults = dispositions.stream()
                .map(QmsInspectionProcedureHandleRecordEntity::getHandleResult)
                .filter(StrUtil::isNotBlank).distinct().sorted().collect(Collectors.joining(","));
        task.setHandleResult(handleResults);
        if (Objects.equals(task.getHandleNum(), task.getNum())) {
            boolean onlyAccepted = dispositions.stream().allMatch(item ->
                    HandleResultEnum.RELEASE.name().equals(item.getHandleResult())
                            || HandleResultEnum.CONCESSION.name().equals(item.getHandleResult()));
            if (onlyAccepted) {
                boolean concession = dispositions.stream().anyMatch(item ->
                        HandleResultEnum.CONCESSION.name().equals(item.getHandleResult()));
                task.setResult(concession ? InspectionTaskResultEnum.RECEIVE.name()
                        : InspectionTaskResultEnum.RELEASE.name());
            }
            task.setStatus(InspectionTaskStatusEnum.FINISHED.name());
        }
        this.updateById(task);
        reworkResultPublisher.publish(InspectionTaskTypeEnum.PROCEDURE.name(), task.getId());
    }

    /**
     * 胜场返工返修工单
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleRework(Long id) {
        QmsInspectionProcedureHandleRecordEntity record = handleRecordService.getById(id);
        QmsInspectionProcedureTaskEntity task = this.getById(record.getTaskId());
        if (Objects.isNull(task)) {
            throw new RuntimeException("未找到质检任务");
        }
        MesTaskReceiveDTO dto = new MesTaskReceiveDTO();
        dto.setMatCode(task.getMatCode());
        dto.setMatName(task.getMatName());
        dto.setNoticeNum(BigDecimal.valueOf(record.getHandleNum()));
        dto.setResourceType(HandleResultEnum.REWORK.name());
        dto.setResourceId(task.getId());
        dto.setType("PART");
        dto.setFactoryCode(task.getFactoryCode());
        MesTaskReceiveRequestSigner.seal(dto, "QMS_PROCEDURE_REWORK:" + record.getId());
        try {
            var response = remoteMesTaskService.add(dto);
            if (response == null || !response.isOk() || response.getData() == null
                    || !Objects.equals(dto.getSourceRequestId(), response.getData().getRequestId())) {
                throw new RuntimeException("MES未返回可核验的返工工单接收回执");
            }

            record.setStatus(Boolean.TRUE);
            handleRecordService.updateById(record);
        } catch (Exception e) {
            log.error("调用MES服务异常, taskId: {}, recordId: {}", task.getId(), id, e);
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * 详情
     *
     * @param id
     * @return
     */
    @Override
    public QmsInspectionProcedureResultDTO queryById(Long id) {
        QmsInspectionProcedureResultDTO dto = new QmsInspectionProcedureResultDTO();
        QmsInspectionProcedureTaskEntity task = this.getById(id);
        dto.setTask(task);
        dto.setReportList(this.findReportListByTaskId(task.getId()));
        dto.setStandard(qualityStandardService.getById(task.getStandardId()));
        return dto;
    }

    /**
     * 导出报表
     *
     * @param taskId
     * @return
     */
    @SneakyThrows
    public ResponseEntity<byte[]> download(Long taskId, Integer num) {
        TemplateExportParams params =
                new TemplateExportParams(StrUtil.format("templates/procedureReport1.xlsx"));
        // 开启横向遍历 开启横向遍历 开启横向遍历
        params.setColForEach(true);
        Map<String, Object> map = new HashMap<String, Object>();
        QmsInspectionProcedureTaskEntity qmsInspectionProcedureTaskEntity = this.getById(taskId);
        if (Objects.isNull(qmsInspectionProcedureTaskEntity)) {
            throw new RuntimeException("信息有误，导出失败！");
        }
        // 构造打印主体数据
        map.put("matCode", qmsInspectionProcedureTaskEntity.getMatCode());
        map.put("matName", qmsInspectionProcedureTaskEntity.getMatName());
        map.put("processCode", qmsInspectionProcedureTaskEntity.getProcessCode());
        map.put("processName", qmsInspectionProcedureTaskEntity.getProcessName());
        map.put("batchNo", qmsInspectionProcedureTaskEntity.getBatchNo());
        map.put("standardName", qmsInspectionProcedureTaskEntity.getStandardName());
        map.put("num", qmsInspectionProcedureTaskEntity.getNum());
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        map.put(
                "latestInspectionTime",
                simpleDateFormat.format(
                        qmsInspectionProcedureTaskEntity.getLatestInspectionTime()));
        map.put(
                "status",
                "QUALIFIED".equals(qmsInspectionProcedureTaskEntity.getResult()) ? "合格" : "不合格");
        // 构造 竖向打印数据
        List<QmsInspectionProcedureTaskDetailEntity> list = procedureTaskDetailService.lambdaQuery()
                .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, taskId).list();
        List<Map<String, Object>> listMap_x = new ArrayList<>();
        List<Map<String, Object>> listMap_y = new ArrayList<>();
        createQualityStandard(listMap_x, listMap_y, list, num);
        map.put("listMap", listMap_x);
        map.put("noMap", listMap_y);

        Workbook workbook = ExcelExportUtil.exportExcel(params, map);
        // 设置header信息
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment",
                new String("工序质检报表.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        return new ResponseEntity<>(byteArrayOutputStream.toByteArray(), headers, HttpStatus.OK);
    }

    /**
     * 构造数据
     *
     * @param list
     * @return
     */
    private void createQualityStandard(List<Map<String, Object>> listMap_x, List<Map<String, Object>> listMap_y,
                                       List<QmsInspectionProcedureTaskDetailEntity> list, Integer num) {
        Integer key = 0;
        List<Integer> tableColumns = new ArrayList<>();
        for (QmsInspectionProcedureTaskDetailEntity detail : list) {
            key++;
            // 构建竖向排列数据
            Map<String, Object> map = new HashMap<>();
            // 获取点检类型
            CheckTypeEnum checkTypeEnum = CheckTypeEnum.findByName(detail.getCheckType());
            map.put("no", key.toString());
            map.put("name", detail.getName());
            map.put("demand", detail.getDemand());
            map.put("method", detail.getMethod());
            map.put("position", detail.getPosition());
            map.put("totalNum", detail.getTotalNum().toString());
            map.put("checkType", checkTypeEnum.getValue());
            map.put(
                    "qualifiedStatus",
                    Objects.nonNull(detail.getQualifiedStatus()) && detail.getQualifiedStatus()
                            ? "合格"
                            : "不合格");
            listMap_x.add(map);
            // 构建横向排列数据
            List<QmsInspectionProcedureItemResultRecordEntity> itemList = recordService.list(new LambdaQueryWrapper<QmsInspectionProcedureItemResultRecordEntity>()
                    .eq(QmsInspectionProcedureItemResultRecordEntity::getDetailId, detail.getId()));
            for (int i = 1; i <= 10; i++) {
                tableColumns.add((num - 1) * 10 + i);
                // 获取对应对象
                Integer currentInfo = (num - 1) * 10 + i - 1;
                if (currentInfo < itemList.size()) {
                    QmsInspectionProcedureItemResultRecordEntity item = itemList.get((num - 1) * 10 + i - 1);
                    if (Objects.nonNull(item.getDoubleValue()) && item.getDoubleValue() > 0) {
                        map.put(String.valueOf(i), item.getDoubleValue());
                    } else {
                        map.put(String.valueOf(i), item.getQualified() ? "YES" : "NO");
                    }
                } else {
                    continue;
                }

            }
        }
        // 横向排序数字
        List<Integer> sorted = tableColumns.stream().distinct().sorted().collect(Collectors.toList());
        for (Integer s : sorted) {
            Map<String, Object> map = new HashMap<>();
            map.put("no", s);
            listMap_y.add(map);
        }
    }




}
