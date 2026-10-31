package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsProductInspectionChecks.require;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.entity.TemplateExportParams;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
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
import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.dto.req.InspectionProductTaskReq;
import com.kitex.mom.qms.dto.resp.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.enums.*;
import com.kitex.mom.qms.mapper.QmsInspectionProductTaskMapper;
import com.kitex.mom.qms.service.*;
import groovy.util.logging.Slf4j;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成品质检
 *
 * @author kitex
 * @date 2022/12/20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionProductTaskServiceImpl extends ServiceImpl<QmsInspectionProductTaskMapper, QmsInspectionProductTaskEntity> implements InspectionProductTaskService {


    private final QmsInspectionProductTaskDetailService productTaskDetailService;

    private final QualityStandardService qualityStandardService;

    private final QualityStandardItemService qualityStandardItemService;

    private final InspectionProductHistoryService historyService;

    private final QualitySamplingStrategiesService samplingStrategiesService;

    private final InspectionProductItemResultRecordService recordService;

    private final QmsBadInfoService badInfoService;

    private final InspectionProductHandleRecordService handleRecordService;

    private final RemoteMesTaskService remoteMesTaskService;

    private final QmsReworkResultPublisher reworkResultPublisher;

    private final QmsPlanEventOutboxService qmsPlanEventOutboxService;
    private final QmsInspectionHistorySupport inspectionAccess;
    private final QmsInspectionBasisService inspectionBasis;


    /**
     * 创建质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void create(InspectionProductCreateParam createParam) {
        inspectionAccess.requireScope();
        if (createParam == null
                || StrUtil.isBlank(createParam.getMatCode())
                || createParam.getNum() == null
                || createParam.getNum() <= 0) {
            throw new RuntimeException("成品质检必须提供物料和大于0的数量");
        }
        String originalCommand = QmsInspectionBasisJson.creation(createParam);
        if (StrUtil.isBlank(createParam.getSourceRequestId())) createParam.setSourceRequestId(null);
        if (StrUtil.isNotBlank(createParam.getSourceRequestId())) {
            String sourceRequestId = createParam.getSourceRequestId().trim();
            if (sourceRequestId.length() > 200) {
                throw new RuntimeException("质检来源请求号长度不能超过200位");
            }
            QmsInspectionProductTaskEntity existing =
                    this.getOne(
                            new LambdaQueryWrapper<QmsInspectionProductTaskEntity>()
                                    .eq(
                                            QmsInspectionProductTaskEntity::getTenantId,
                                            TenantContextHolder.getTenantId())
                                    .eq(
                                            QmsInspectionProductTaskEntity::getFactoryCode,
                                            FactoryContextHolder.getFactoryCode())
                                    .eq(
                                            QmsInspectionProductTaskEntity::getSourceRequestId,
                                            sourceRequestId),
                            false);
            if (existing != null) {
                require(
                        inspectionBasis.sameCreation("PRODUCT", existing.getId(), originalCommand),
                        "原任务缺少原始报检依据，请按原任务核对，不能从现状确认创建重试");
                return;
            }
            createParam.setSourceRequestId(sourceRequestId);
        } else if (this.existTask(
                createParam.getMatCode(), createParam.getBatchNo(), createParam.getProcessCode())) {
            throw new RuntimeException("已经有同类型的任务,无法继续创建");
        }

        // 获取关联质检标准
        QmsQualityStandardEntity qualityStandard =
                qualityStandardService.getOne(
                        new LambdaQueryWrapper<QmsQualityStandardEntity>()
                                .eq(QmsQualityStandardEntity::getId, createParam.getStandardId())
                                .eq(
                                        QmsQualityStandardEntity::getTenantId,
                                        TenantContextHolder.getTenantId())
                                .eq(
                                        QmsQualityStandardEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode()));
        if (qualityStandard == null
                || !Objects.equals(qualityStandard.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(
                        qualityStandard.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                || !Objects.equals(qualityStandard.getId(), createParam.getStandardId())
                || !InspectionTaskTypeEnum.PRODUCT
                        .name()
                        .equals(qualityStandard.getInspectionType())
                || !Objects.equals(qualityStandard.getPublished(), 1)
                || !Objects.equals(qualityStandard.getMatCode(), createParam.getMatCode())) {
            throw new RuntimeException("指定质量标准未发布、不是成品检验类型或与当前物料不匹配");
        }

        // 创建质检任务
        val task =
                new QmsInspectionProductTaskEntity()
                        .setBatchNo(createParam.getBatchNo())
                        .setName(createParam.getName())
                        .setSourceRequestId(createParam.getSourceRequestId())
                        .setSourceTaskCode(createParam.getSourceTaskCode())
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
                        .setDeptCode(createParam.getDeptCode())
                        .setOrderId(createParam.getOrderId())
                        .setOrderNo(createParam.getOrderNo());

        task.setTenantId(TenantContextHolder.getTenantId());
        task.setFactoryCode(FactoryContextHolder.getFactoryCode());
        if (!save(task) || task.getId() == null || task.getId() <= 0)
            throw new CheckedException("成品检验任务保存失败");
        var frozenPlan =
                inspectionBasis.prepare(
                        "PRODUCT", qualityStandard, task.getNum(), task.getInspectionNum());
        var details = generateDetail(task, frozenPlan);
        task.setInspectionNum(
                details.stream()
                        .mapToInt(QmsInspectionProductTaskDetailEntity::getTotalNum)
                        .max()
                        .orElseThrow());
        if (!updateById(task)) throw new CheckedException("成品检验实际应检量保存失败");
        for (var detail : details)
            if (!productTaskDetailService.save(detail)) throw new CheckedException("成品检验项目保存失败");
        inspectionBasis.record(task, details, frozenPlan, "CREATE", originalCommand);
        // 保存历史
        historyService.create(task);
    }

    /**
     * 修改质检任务
     *
     * @param updateParam
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(InspectionProductCreateParam updateParam) {
        inspectionAccess.requireWrite();
        require(updateParam != null, "请指定需要修改的检验任务");
        var task = lockInspection(updateParam.getId());
        assertInspectionWritable(task);
        requireMutableSource(task);
        require("WAIT".equals(task.getStatus()), "已经开始检验的任务不能更换标准");
        var oldDetails = inspectionDetails(task.getId());
        QmsProductInspectionChecks.completion(
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
                        "PRODUCT",
                        qualityStandard,
                        task.getNum(),
                        updateParam.getInspectionNum() == null
                                ? task.getInspectionNum()
                                : updateParam.getInspectionNum());
        var details = generateDetail(task, frozenPlan);
        require(
                productTaskDetailService.remove(
                        detailScope()
                                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, task.getId())),
                "旧检验项目替换失败");
        for (var detail : details) require(productTaskDetailService.save(detail), "新检验项目保存失败");
        task.setInspectionNum(
                details.stream()
                        .mapToInt(QmsInspectionProductTaskDetailEntity::getTotalNum)
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
        var details = inspectionDetails(task.getId());
        QmsProductInspectionChecks.completion(
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
                        .map(QmsInspectionProductTaskDetailEntity::getStandardItemId)
                        .toList(),
                items.stream().map(QmsQualityStandardItemEntity::getId).toList());
        for (var detail : details) {
            updateTaskDetails(
                    detail,
                    task,
                    previousBatch,
                    qualityStandard,
                    findQualityStandardItemByTaskDetail(items, detail));
            require(productTaskDetailService.updateById(detail), "追加检验项目保存失败");
        }
        task.setInspectionNum(
                details.stream()
                        .mapToInt(QmsInspectionProductTaskDetailEntity::getTotalNum)
                        .max()
                        .orElseThrow());
        task.setLatestAppendTime(new Date());
        require(updateById(task), "追加检验任务数量保存失败");
        // 保存历史
        historyService.append(task, appendParam.getNum());
    }

    /** 追加沿原方案求值，保留实际样本和原始创建依据。 */
    private void appendFrozen(
            QmsInspectionProductTaskEntity task,
            List<QmsInspectionProductTaskDetailEntity> details,
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
            QmsProductInspectionChecks.counters(detail);
            require(productTaskDetailService.updateById(detail), "冻结追加检验项目保存失败");
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
        requireUnstarted(task);
        task.setStatus("FINISHED").setResult("CANCELED");
        require(updateById(task), "成品检验作废保存失败");
        historyService.canceled(task, reason);
    }

    /**
     * 免检质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void exemption(Long taskId) {
        inspectionAccess.requireWrite();
        var task = lockInspection(taskId);
        requireUnstarted(task);
        task.setStatus("FINISHED").setResult("EXEMPTION").setLestStatus("1");
        require(updateById(task), "成品检验免检保存失败");
        historyService.exemption(task);
    }

    /**
     * 检查并更新质检项
     *
     * @param detail 质检项
     * @param task 质检任务
     * @param previousBatch 追加前批量
     * @param standard 已发布标准
     * @param item 原项目对应标准项
     * @return 发生更新情况下进行返回对象
     */
    private void updateTaskDetails(
            QmsInspectionProductTaskDetailEntity detail,
            QmsInspectionProductTaskEntity task,
            int previousBatch,
            QmsQualityStandardEntity standard,
            QmsQualityStandardItemEntity item) {
        var frequencies =
                "RANDOM".equals(standard.getInspectionMethod()) ? samplingFrequencies(item) : null;
        var previousFrequency =
                frequencies == null
                        ? null
                        : QmsProductInspectionChecks.frequency(previousBatch, frequencies);
        var frequency =
                frequencies == null
                        ? null
                        : QmsProductInspectionChecks.frequency(task.getNum(), frequencies);
        int previousCount =
                QmsProductInspectionChecks.sampleSize(
                        previousBatch,
                        standard.getInspectionMethod(),
                        standard.getNum(),
                        task.getInspectionNum(),
                        previousFrequency);
        int count =
                QmsProductInspectionChecks.sampleSize(
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
        QmsProductInspectionChecks.counters(detail);
    }

    /** 由完整冻结方案生成不同的实际项目。 */
    private List<QmsInspectionProductTaskDetailEntity> generateDetail(
            QmsInspectionProductTaskEntity task, QmsFrozenInspectionPlan plan) {
        return plan.targets(task.getNum()).stream()
                .map(
                        target -> {
                            var item = target.item();
                            int count = target.count();
                            var detail =
                                    new QmsInspectionProductTaskDetailEntity()
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

/**
     * 判断是否已经有同类型任务
     *
     * @param matCode 物料号
     * @param batchNo 批次号
     * @return 是否已经存在
     */
    public boolean existTask(String matCode, String batchNo, String processCode) {
        // 已作废和已处理（已生产入库通知单）的不能追加
        List<String> notHandle = new ArrayList<>();
        notHandle.add(InspectionTaskResultEnum.CANCELED.name());
        return this.lambdaQuery().eq(QmsInspectionProductTaskEntity::getMatCode, matCode)
                .eq(QmsInspectionProductTaskEntity::getBatchNo, batchNo)
                .eq(QmsInspectionProductTaskEntity::getProcessCode, processCode)
                .ge(QmsInspectionProductTaskEntity::getLatestAppendTime, LocalDateTime.of(LocalDate.now(), LocalTime.MIN))
                .le(QmsInspectionProductTaskEntity::getLatestAppendTime, LocalDateTime.of(LocalDate.now(), LocalTime.MAX))
                .notIn(QmsInspectionProductTaskEntity::getResult, notHandle).exists();
    }

    /**
     * 根据物料/供应商/批次号 查询质检任务, 创建质检时使用
     */
    public QmsInspectionProductTaskEntity getTaskByMatCodeAndBatchNo(String matCode, String batchNo) {
        // 已作废和已处理（已生产入库通知单）的不能追加
        List<String> notHandle = new ArrayList<>();
        notHandle.add(InspectionTaskResultEnum.CANCELED.name());
        return this.lambdaQuery()
                .eq(QmsInspectionProductTaskEntity::getMatCode, matCode)
                .eq(QmsInspectionProductTaskEntity::getBatchNo, batchNo)
                .notIn(QmsInspectionProductTaskEntity::getResult, notHandle)
                .orderByDesc(QmsInspectionProductTaskEntity::getCreateTime)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new RuntimeException("未找到相关的质检任务"));
    }

    /**
     * 获取质检项列表
     */
    public List<QmsInspectionProductTaskDetailEntity> findDetailsByTaskId(Long taskId) {
        return productTaskDetailService.lambdaQuery().eq(QmsInspectionProductTaskDetailEntity::getTaskId, taskId).list();
    }

    /**
     * 获取质检项详情
     */
    public QmsInspectionProductTaskDetailEntity findDetailById(Long detailId) {
        QmsInspectionProductTaskDetailEntity result = productTaskDetailService.getById(detailId);
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
                productTaskDetailService.getOne(
                        detailScope()
                                .eq(
                                        QmsInspectionProductTaskDetailEntity::getId,
                                        param.getDetailId()));
        require(initial != null, "当前组织不存在指定检验项目");
        var task = lockInspection(initial.getTaskId());
        assertInspectionWritable(task);
        var detail = requireDetail(param.getDetailId(), task.getId());
        collect(task, detail, param.getRecord());
        if ("WAIT".equals(task.getStatus())) {
            task.setStatus("RUNNING");
            require(updateById(task), "检验任务开始状态保存失败");
        }
    }

    /**
     * 批量暂存
     *
     * @param param
     */
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

    /**
     * 提交质检记录结果
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitInspectionResult(Long taskId) {
        inspectionAccess.requireWrite();
        var task = lockInspection(taskId);
        if (StrUtil.isNotBlank(task.getResult())) return;
        assertInspectionWritable(task);
        finishInspection(task, true);
    }


    /**
     * 分页
     */
    public Page<QmsInspectionProductTaskEntity> page(Page<QmsInspectionProductTaskEntity> page, InspectionProductTaskReq param,
                                                     CommonQuery commonQuery) {
        QueryWrapper<QmsInspectionProductTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .eq(param.getTaskId() != null, QmsInspectionProductTaskEntity::getId, param.getTaskId())
                .like(StrUtil.isNotBlank(param.getBatchNo()), QmsInspectionProductTaskEntity::getBatchNo, param.getBatchNo())
                .like(StrUtil.isNotBlank(param.getMatCode()), QmsInspectionProductTaskEntity::getMatCode, param.getMatCode())
                .like(StrUtil.isNotBlank(param.getMatSpec()), QmsInspectionProductTaskEntity::getMatSpec, param.getMatSpec())
                .eq(StrUtil.isNotBlank(param.getResult()), QmsInspectionProductTaskEntity::getResult, param.getResult())
                .like(StrUtil.isNotBlank(param.getProcessCode()), QmsInspectionProductTaskEntity::getProcessCode,
                        param.getProcessCode())
                .like(StrUtil.isNotBlank(param.getProcessName()), QmsInspectionProductTaskEntity::getProcessName,
                        param.getProcessName())
                .like(StrUtil.isNotBlank(param.getMatName()), QmsInspectionProductTaskEntity::getMatName, param.getMatName())
                .eq(StrUtil.isNotBlank(param.getStatus()), QmsInspectionProductTaskEntity::getStatus, param.getStatus());
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
    public QmsInspectionProductTaskAppVO queryAppPage(Page<QmsInspectionProductTaskEntity> page,
                                                      InspectionProductTaskReq param, CommonQuery commonQuery) {
        QmsInspectionProductTaskAppVO result = new QmsInspectionProductTaskAppVO();
        List<QmsInspectionProductTaskEntity> allList = this.list();
        Map<String, Long> statusCountMap = allList.stream()
                .collect(Collectors.groupingBy(QmsInspectionProductTaskEntity::getStatus, Collectors.counting()));
        List<QmsStatisticsDTO> classify = statusCountMap.entrySet().stream()
                .map(entry -> new QmsStatisticsDTO(entry.getKey(), entry.getValue().intValue()))
                .toList();
        result.setClassify(classify);
        QueryWrapper<QmsInspectionProductTaskEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(param);
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
        List<QmsInspectionProductTaskEntity> allList = this.list();
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
    public Map<String, Long> getStatusCount(InspectionProductTaskReq param) {
        QueryWrapper<QmsInspectionProductTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .eq(param.getTaskId() != null, QmsInspectionProductTaskEntity::getId, param.getTaskId())
                .like(StrUtil.isNotBlank(param.getBatchNo()), QmsInspectionProductTaskEntity::getBatchNo, param.getBatchNo())
                .like(StrUtil.isNotBlank(param.getMatCode()), QmsInspectionProductTaskEntity::getMatCode, param.getMatCode())
                .eq(StrUtil.isNotBlank(param.getResult()), QmsInspectionProductTaskEntity::getResult, param.getResult())
                .like(StrUtil.isNotBlank(param.getProcessCode()), QmsInspectionProductTaskEntity::getProcessCode,
                        param.getProcessCode())
                .like(StrUtil.isNotBlank(param.getProcessName()), QmsInspectionProductTaskEntity::getProcessName,
                        param.getProcessName())
                .like(StrUtil.isNotBlank(param.getMatName()), QmsInspectionProductTaskEntity::getMatName, param.getMatName());
        autoQueryWrapper.orderByDesc("create_time");

        List<QmsInspectionProductTaskEntity> tasks = this.list(autoQueryWrapper);

        // 统计常规状态
        return tasks.stream()
                .collect(Collectors.groupingBy(QmsInspectionProductTaskEntity::getStatus, Collectors.counting()));
    }

    /**
     * 查询单条
     */
    public QmsInspectionProductTaskDTO findById(Long id) {
        QmsInspectionProductTaskDTO result = new QmsInspectionProductTaskDTO();
        QmsInspectionProductTaskEntity task = this.getById(id);
        result.setTask(task);
        List<QmsInspectionProductTaskDetailEntity> details = productTaskDetailService.list(new LambdaQueryWrapper<QmsInspectionProductTaskDetailEntity>()
                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, task.getId()));
        Integer waitNum = details.stream().map(QmsInspectionProductTaskDetailEntity::getWaitNum).reduce(0, Integer::sum);
        if (!InspectionTaskStatusEnum.FINISHED.name().equals(task.getStatus()) && !Objects.equals(waitNum, 0)) {
            details = details.stream().filter(item -> !Objects.equals(item.getWaitNum(), 0)).collect(Collectors.toList());
        }
        result.setDetails(details);
        result.setClassify(new ArrayList<>());
        if (!Objects.equals(waitNum, 0)) {
            Map<String, Integer> classifyMaxWaitNumMap = details.stream()
                    .collect(Collectors.groupingBy(
                            QmsInspectionProductTaskDetailEntity::getCheckClassify,
                            Collectors.collectingAndThen(
                                    Collectors.mapping(QmsInspectionProductTaskDetailEntity::getWaitNum,
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
     * 从质检项列表中获取质检任务详情关联的质检项
     */
    private QmsQualityStandardItemEntity findQualityStandardItemByTaskDetail(List<QmsQualityStandardItemEntity> list,
                                                                             QmsInspectionProductTaskDetailEntity detail) {
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
        List<QmsInspectionProductTaskDetailEntity> list = productTaskDetailService.lambdaQuery()
                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, taskId).list();
        // 判断是否有符合要求的数据
        if (CollUtil.isEmpty(list)) {
            return reportListRespDTO;
        }
        List<Map<String, Object>> records = new ArrayList<>();
        List<Integer> tableColumns = new ArrayList<>();
        Integer key = 0;
        for (QmsInspectionProductTaskDetailEntity detail : list) {
            key++;
            Map<String, Object> record = new HashMap<>();
            // 获取点检类型
            record.put("#", key);
            record.put("name", detail.getName());
            record.put("demand", detail.getDemand());
            record.put("method", detail.getMethod());
            record.put("position", detail.getPosition());
            record.put("totalNum", detail.getTotalNum());
            record.put("checkType", this.getCheckTypeName(detail));
            record.put("checkClassify", detail.getCheckClassify());
            record.put("qualifiedStatus",
                    Objects.nonNull(detail.getQualifiedStatus()) && detail.getQualifiedStatus() ? "合格" : "不合格");
            List<QmsInspectionProductItemResultRecordEntity> itemList = recordService.list(new LambdaQueryWrapper<QmsInspectionProductItemResultRecordEntity>()
                    .eq(QmsInspectionProductItemResultRecordEntity::getDetailId, detail.getId()));
            if (CollUtil.isEmpty(itemList)) {
                continue;
            }
            for (int i = 1; i <= itemList.size(); i++) {
                tableColumns.add(i);
                // 获取对应对象
                QmsInspectionProductItemResultRecordEntity item = itemList.get(i - 1);
                record.put(String.valueOf(i),
                        Objects.nonNull(item.getQualified()) && item.getQualified() ? "YES" : "NO");
            }
            records.add(record);
        }
        List<Integer> sorted = tableColumns.stream().distinct().sorted().collect(Collectors.toList());
        reportListRespDTO.setRecords(records);
        reportListRespDTO.setTableColumns(sorted.stream().map(String::valueOf).collect(Collectors.toList()));
        return reportListRespDTO;
    }

    private String getCheckTypeName(QmsInspectionProductTaskDetailEntity detail) {
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
        inspectionAccess.requireWrite();
        require(dto != null, "请指定需要处置的成品检验任务");
        var task = lockInspection(dto.getId());
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
        String requestId =
                StrUtil.blankToDefault(dto.getRequestId(), UUID.randomUUID().toString()).trim();
        if (requestId.length() > 100) {
            throw new RuntimeException("处置请求号长度不能超过100位");
        }
        QmsInspectionProductHandleRecordEntity existing =
                handleRecordService.getOne(
                        new LambdaQueryWrapper<QmsInspectionProductHandleRecordEntity>()
                                .eq(
                                        QmsInspectionProductHandleRecordEntity::getRequestId,
                                        requestId),
                        false);
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
            throw new RuntimeException("只有待处置的不合格成品质检任务可以处置");
        }
        task.setHandleNum(Objects.requireNonNullElse(task.getHandleNum(), 0) + dto.getHandleNum());
        if (task.getHandleNum() > task.getNum()) {
            throw new RuntimeException("处置数量不能大于未处置数量");
        }
        if (HandleResultEnum.RELEASE.name().equals(dto.getHandleResult())
                || HandleResultEnum.CONCESSION.name().equals(dto.getHandleResult())) {
            task.setUsableNum(
                    Objects.requireNonNullElse(task.getUsableNum(), 0) + dto.getHandleNum());
        }
        if (HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult())) {
            // 入不合格品库
            badInfoService.add(
                    new QmsBadInfoEntity()
                            .setResourceTaskId(task.getId())
                            .setResourceType(InspectionTaskTypeEnum.PRODUCT.name())
                            .setRequestId("DISPOSITION:" + requestId)
                            .setNum(
                                    dto.getHandleNum() == null
                                            ? null
                                            : java.math.BigDecimal.valueOf(dto.getHandleNum()))
                            .setMatCode(task.getMatCode())
                            .setMatName(task.getMatName())
                            .setBatchNo(task.getBatchNo()));
        }
        require(updateById(task), "成品检验处置数量或状态保存失败");
        // 保存处理明细
        QmsInspectionProductHandleRecordEntity record =
                new QmsInspectionProductHandleRecordEntity();
        MomUser user = SecurityUtils.getUser();
        record.setRequestId(requestId)
                .setHandleNum(dto.getHandleNum())
                .setHandleRemark(dto.getHandleRemark().trim())
                .setHandlePersonId(user.getId())
                .setHandlePersonName(user.getName())
                .setHandleTime(new Date())
                .setHandleResult(dto.getHandleResult())
                .setTaskId(task.getId())
                .setStatus(
                        HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult())
                                || HandleResultEnum.SMASH.name().equals(dto.getHandleResult()));
        require(
                handleRecordService.save(record) && record.getId() != null && record.getId() > 0,
                "成品检验处置明细保存失败");
        if (Set.of(
                                HandleResultEnum.RELEASE.name(),
                                HandleResultEnum.CONCESSION.name(),
                                HandleResultEnum.REWORK.name(),
                                HandleResultEnum.SMASH.name())
                        .contains(dto.getHandleResult())
                && StrUtil.isNotBlank(task.getOrderNo())) {
            qmsPlanEventOutboxService.enqueueDisposition("PRODUCT", task.getId(), record.getId());
        }

        List<QmsInspectionProductHandleRecordEntity> dispositions =
                handleRecordService.list(
                        new LambdaQueryWrapper<QmsInspectionProductHandleRecordEntity>()
                                .eq(
                                        QmsInspectionProductHandleRecordEntity::getTaskId,
                                        task.getId()));
        task.setHandleResult(
                dispositions.stream()
                        .map(QmsInspectionProductHandleRecordEntity::getHandleResult)
                        .filter(StrUtil::isNotBlank)
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining(",")));
        if (Objects.equals(task.getHandleNum(), task.getNum())) {
            boolean onlyAccepted =
                    dispositions.stream()
                            .allMatch(
                                    item ->
                                            HandleResultEnum.RELEASE
                                                            .name()
                                                            .equals(item.getHandleResult())
                                                    || HandleResultEnum.CONCESSION
                                                            .name()
                                                            .equals(item.getHandleResult()));
            if (onlyAccepted) {
                boolean concession =
                        dispositions.stream()
                                .anyMatch(
                                        item ->
                                                HandleResultEnum.CONCESSION
                                                        .name()
                                                        .equals(item.getHandleResult()));
                task.setResult(
                        concession
                                ? InspectionTaskResultEnum.RECEIVE.name()
                                : InspectionTaskResultEnum.RELEASE.name());
            }
            task.setStatus(InspectionTaskStatusEnum.FINISHED.name());
        }
        require(updateById(task), "成品检验处置数量或状态保存失败");
        reworkResultPublisher.publish(InspectionTaskTypeEnum.PRODUCT.name(), task.getId());
    }

    /**
     * 胜场返工返修工单
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleRework(Long id) {
        QmsInspectionProductHandleRecordEntity record = handleRecordService.getById(id);
        QmsInspectionProductTaskEntity task = this.getById(record.getTaskId());
        if (Objects.isNull(task)) {
            throw new RuntimeException("未找到质检任务");
        }
        MesTaskReceiveDTO dto = new MesTaskReceiveDTO();
        dto.setMatCode(task.getMatCode());
        dto.setMatName(task.getMatName());
        dto.setNoticeNum(BigDecimal.valueOf(record.getHandleNum()));
        dto.setResourceType(HandleResultEnum.REWORK.name());
        dto.setResourceId(task.getId());
        dto.setType("PRODUCT");
        dto.setFactoryCode(task.getFactoryCode());
        MesTaskReceiveRequestSigner.seal(dto, "QMS_PRODUCT_REWORK:" + record.getId());
        try {
            var response = remoteMesTaskService.add(dto);
            if (response == null || !response.isOk() || response.getData() == null
                    || !Objects.equals(dto.getSourceRequestId(), response.getData().getRequestId())) {
                throw new RuntimeException("MES未返回可核验的返工工单接收回执");
            }

            record.setStatus(Boolean.TRUE);
            handleRecordService.updateById(record);
        } catch (Exception e) {
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
    public QmsInspectionProductResultDTO queryById(Long id) {
        QmsInspectionProductResultDTO dto = new QmsInspectionProductResultDTO();
        QmsInspectionProductTaskEntity task = this.getById(id);
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
        TemplateExportParams params = new TemplateExportParams(StrUtil.format("templates/productReport1.xlsx"));
        // 开启横向遍历 开启横向遍历 开启横向遍历
        params.setColForEach(true);
        Map<String, Object> map = new HashMap<String, Object>();
        QmsInspectionProductTaskEntity qmsInspectionProductTaskEntity = this.getById(taskId);
        if (Objects.isNull(qmsInspectionProductTaskEntity)) {
            throw new RuntimeException("信息有误，导出失败！");
        }
        // 构造打印主体数据
        map.put("matCode", qmsInspectionProductTaskEntity.getMatCode());
        map.put("matName", qmsInspectionProductTaskEntity.getMatName());
        map.put("batchNo", qmsInspectionProductTaskEntity.getBatchNo());
        map.put("processCode", qmsInspectionProductTaskEntity.getProcessCode());
        map.put("processName", qmsInspectionProductTaskEntity.getProcessName());
        map.put("standardName", qmsInspectionProductTaskEntity.getStandardName());
        map.put("num", qmsInspectionProductTaskEntity.getNum());
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        map.put("latestInspectionTime", simpleDateFormat.format(qmsInspectionProductTaskEntity.getLatestInspectionTime()));
        map.put("status", "QUALIFIED".equals(qmsInspectionProductTaskEntity.getResult()) ? "合格" : "不合格");
        // 构造 竖向打印数据
        List<QmsInspectionProductTaskDetailEntity> list = productTaskDetailService.lambdaQuery()
                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, taskId).list();
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
                new String("成品质检报表.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

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
                                       List<QmsInspectionProductTaskDetailEntity> list, Integer num) {
        Integer key = 0;
        List<Integer> tableColumns = new ArrayList<>();
        for (QmsInspectionProductTaskDetailEntity detail : list) {
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
            map.put("qualifiedStatus",
                    Objects.nonNull(detail.getQualifiedStatus()) && detail.getQualifiedStatus() ? "合格" : "不合格");
            listMap_x.add(map);
            // 构建横向排列数据
            List<QmsInspectionProductItemResultRecordEntity> itemList = recordService.list(new LambdaQueryWrapper<QmsInspectionProductItemResultRecordEntity>()
                    .eq(QmsInspectionProductItemResultRecordEntity::getDetailId, detail.getId()));
            for (int i = 1; i <= 10; i++) {
                tableColumns.add((num - 1) * 10 + i);
                // 获取对应对象
                Integer currentInfo = (num - 1) * 10 + i - 1;
                if (currentInfo < itemList.size()) {
                    QmsInspectionProductItemResultRecordEntity item = itemList.get((num - 1) * 10 + i - 1);
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

//    public MdmMat matInfo(String matCode) {
//        return mdmMatMapper.matInfo(matCode);
//    }
//
//
//    /**
//     * 创建入库通知单
//     *
//     * @param inspectionProductDTO
//     * @param request
//     */
//    @Transactional(rollbackFor = Exception.class)
//    public void addInNotice(AddInBillNoticeReq inspectionProductDTO, HttpServletRequest request) {
//        QmsInspectionProductTask productTask = Optional.ofNullable(productTaskService.getById(inspectionProductDTO.getId()))
//                .orElseThrow(() -> new JeecgBootException("查询成品质检任务失败！"));
//        // 对入库通知单数量进行校验，入库数量不能大于来料数量
//        BigDecimal inBillNum = inspectionProductDTO.getDetails().get(0).getNoticeInNum();
//        BigDecimal allNum = BigDecimal.valueOf(productTask.getNum());
//        if (inBillNum.compareTo(allNum) > 0) {
//            throw new JeecgBootException("通知入库数量不能大于来料数量！");
//        }
//        // 修改任务状态改为已处理
//        productTask.setStatus(InspectionTaskStatusEnum.PROCESSED.name());
//        // 记录通知入库数量
//        productTask.setNoticeInBillNum(inBillNum);
//        productTaskService.updateById(productTask);
//        // 向wms模块发送起请求
//        serviceToWms.buildProductParam(inspectionProductDTO, inBillNum, productTask.getId(), request);
//    }

    private QmsInspectionProductTaskEntity lockInspection(Long id) {
        require(id != null && id > 0, "成品检验任务编号无效");
        require(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "成品检验写入必须在原业务事务中");
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
                "当前实际组织不存在成品检验任务");
        inspectionBasis.validateCurrent(task, inspectionDetails(task.getId()));
        return task;
    }

    private LambdaQueryWrapper<QmsInspectionProductTaskDetailEntity> detailScope() {
        return new LambdaQueryWrapper<QmsInspectionProductTaskDetailEntity>()
                .eq(
                        QmsInspectionProductTaskDetailEntity::getTenantId,
                        TenantContextHolder.getTenantId())
                .eq(
                        QmsInspectionProductTaskDetailEntity::getFactoryCode,
                        FactoryContextHolder.getFactoryCode());
    }

    private QmsInspectionProductTaskDetailEntity requireDetail(Long id, Long taskId) {
        var detail =
                productTaskDetailService.getOne(
                        detailScope()
                                .eq(QmsInspectionProductTaskDetailEntity::getId, id)
                                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, taskId));
        require(detail != null, "检验项目不属于当前实际任务");
        return detail;
    }

    private void collect(
            QmsInspectionProductTaskEntity task,
            QmsInspectionProductTaskDetailEntity detail,
            InspectionItemResultRecord input) {
        QmsProductInspectionChecks.counters(detail);
        require(detail.getWaitNum() > 0, "该检验项已达到应检数量，请勿重复提交");
        boolean qualified = QmsProductInspectionChecks.judge(detail, input);
        var record =
                new QmsInspectionProductItemResultRecordEntity()
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
        require(productTaskDetailService.updateById(detail), "检验项目累计数量保存失败");
    }

    private void finishInspection(QmsInspectionProductTaskEntity task, boolean strict) {
        var details =
                productTaskDetailService.list(
                        detailScope()
                                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, task.getId())
                                .last("LIMIT 5001"));
        inspectionBasis.validateCurrent(task, details);
        var completion =
                QmsProductInspectionChecks.completion(
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
                            .setResourceType("PRODUCT")
                            .setNum(BigDecimal.valueOf(task.getNum()))
                            .setMatCode(task.getMatCode())
                            .setMatName(task.getMatName())
                            .setBatchNo(task.getBatchNo()));
        historyService.check(task);
        if (!completion.qualified() && StrUtil.isNotBlank(task.getOrderNo()))
            qmsPlanEventOutboxService.enqueueIsolation("PRODUCT", task.getId());
        reworkResultPublisher.publish("PRODUCT", task.getId());
    }

    private void assertInspectionWritable(QmsInspectionProductTaskEntity task) {
        require(
                ("WAIT".equals(task.getStatus()) || "RUNNING".equals(task.getStatus()))
                        && StrUtil.isBlank(task.getResult()),
                "当前检验任务已结束或进入处置，不能继续填写结果");
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
                                InspectionTaskTypeEnum.PRODUCT.name())
                        .eq(QmsQualityStandardEntity::getPublished, 1)
                        .eq(id != null, QmsQualityStandardEntity::getId, id);
        var standard = qualityStandardService.getOne(query);
        require(
                standard != null
                        && (id == null || Objects.equals(id, standard.getId()))
                        && Objects.equals(standard.getMatCode(), matCode)
                        && Objects.equals(standard.getPublished(), 1)
                        && "PRODUCT".equals(standard.getInspectionType())
                        && Objects.equals(standard.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                standard.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "当前实际组织不存在匹配物料的已发布成品检验标准");
        return standard;
    }

    private List<QmsInspectionProductTaskDetailEntity> inspectionDetails(Long taskId) {
        return productTaskDetailService.list(
                detailScope()
                        .eq(QmsInspectionProductTaskDetailEntity::getTaskId, taskId)
                        .last("LIMIT 5001"));
    }

    private List<QmsQualityStandardItemEntity> standardItems(
            QmsInspectionProductTaskEntity task, QmsQualityStandardEntity standard) {
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
    private void requireMutableSource(QmsInspectionProductTaskEntity task) {
        require(
                task.getSourceRequestId() == null
                        || !task.getSourceRequestId().trim().startsWith("REWORK-REINSPECTION:"),
                "已绑定返工来源的复检任务不能直接改量或更换标准，请从原返工单处理");
    }

    private void requireUnstarted(QmsInspectionProductTaskEntity task) {
        require(
                "WAIT".equals(task.getStatus()) && StrUtil.isBlank(task.getResult()),
                "只有未开始检验的任务才能作废或免检");
        require(
                baseMapper
                        .inspectionSamples(task.getId(), task.getTenantId(), task.getFactoryCode())
                        .isEmpty(),
                "已有实际检验样本的任务不能作废或免检");
        var details = inspectionDetails(task.getId());
        require(details != null && details.size() <= 5000, "检验项不可读取或超出治理上限");
        for (var d : details) {
            QmsProductInspectionChecks.counters(d);
            require(d.getFinishNum() == 0 && d.getUnqualifiedNum() == 0, "已有采集依据的任务不能作废或免检");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createManual(InspectionProductCreateParam param) {
        inspectionAccess.requireWrite();
        require(
                param != null
                        && (param.getSourceRequestId() == null
                                || !param.getSourceRequestId()
                                        .trim()
                                        .startsWith("REWORK-REINSPECTION:")),
                "人工报检不能冒用返工复检来源");
        create(param);
    }




}
