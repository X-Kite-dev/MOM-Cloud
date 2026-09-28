package com.kitex.mom.qms.service.impl;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.entity.TemplateExportParams;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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


    /**
     * 创建质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void create(InspectionProductCreateParam createParam) {
        if (createParam == null || StrUtil.isBlank(createParam.getMatCode())
                || createParam.getNum() == null || createParam.getNum() <= 0) {
            throw new RuntimeException("成品质检必须提供物料和大于0的数量");
        }
        if (StrUtil.isNotBlank(createParam.getSourceRequestId())) {
            String sourceRequestId = createParam.getSourceRequestId().trim();
            if (sourceRequestId.length() > 200) {
                throw new RuntimeException("质检来源请求号长度不能超过200位");
            }
            QmsInspectionProductTaskEntity existing = this.getOne(
                    new LambdaQueryWrapper<QmsInspectionProductTaskEntity>()
                            .eq(QmsInspectionProductTaskEntity::getSourceRequestId, sourceRequestId), false);
            if (existing != null) {
                if (Objects.equals(existing.getSourceTaskCode(), createParam.getSourceTaskCode())
                        && Objects.equals(existing.getMatCode(), createParam.getMatCode())) {
                    return;
                }
                throw new RuntimeException("质检来源请求号已被其他业务使用");
            }
            createParam.setSourceRequestId(sourceRequestId);
        } else if (this.existTask(createParam.getMatCode(), createParam.getBatchNo(), createParam.getProcessCode())) {
            throw new RuntimeException("已经有同类型的任务,无法继续创建");
        }

        // 获取关联质检标准
        QmsQualityStandardEntity qualityStandard = qualityStandardService.getById(createParam.getStandardId());
        if (qualityStandard == null || !InspectionTaskTypeEnum.PRODUCT.name().equals(qualityStandard.getInspectionType())
                || !Objects.equals(qualityStandard.getPublished(), 1)
                || !Objects.equals(qualityStandard.getMatCode(), createParam.getMatCode())) {
            throw new RuntimeException("指定质量标准未发布、不是成品检验类型或与当前物料不匹配");
        }


        // 创建质检任务
        val task = new QmsInspectionProductTaskEntity().setBatchNo(createParam.getBatchNo()).setName(createParam.getName())
                .setSourceRequestId(createParam.getSourceRequestId()).setSourceTaskCode(createParam.getSourceTaskCode())
                .setMatCode(createParam.getMatCode()).setMatName(createParam.getMatName()).setMatSpec(createParam.getMatSpec())
                .setProcessCode(createParam.getProcessCode()).setProcessName(createParam.getProcessName())
                .setStandardId(qualityStandard.getId()).setStandardName(qualityStandard.getStandardName())
                .setNum(createParam.getNum()).setStatus(InspectionTaskStatusEnum.WAIT.name())
                .setHandleNum(0)
                .setInspectionNum(createParam.getInspectionNum())
                .setDeptCode(createParam.getDeptCode())
                .setOrderId(createParam.getOrderId())
                .setOrderNo(createParam.getOrderNo());

        if (InspectionMethodEnum.FULL.name().equals(qualityStandard.getInspectionMethod())) {
            task.setInspectionNum(createParam.getNum());
        }
        if (InspectionMethodEnum.FIXED.name().equals(qualityStandard.getInspectionMethod())) {
            task.setInspectionNum(qualityStandard.getNum());
        }

        this.save(task);

        // 生成任务质检项
        val details = this.generateDetail(task, qualityStandard);
        if (InspectionMethodEnum.RANDOM.name().equals(qualityStandard.getInspectionMethod())) {
            Optional<Integer> maxNum = details.stream().map(QmsInspectionProductTaskDetailEntity::getTotalNum).max(Integer::compareTo);
            if (maxNum.isPresent()) {
                task.setInspectionNum(maxNum.get());
                this.updateById(task);
            }
        }
        productTaskDetailService.saveBatch(details);
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
        QmsInspectionProductTaskEntity task = Optional.ofNullable(this.getById(updateParam.getId()))
                .orElseThrow(() -> new RuntimeException("获取质检任务信息失败！"));
        // 获取关联质检标准
        val qualityStandard = qualityStandardService.getById(updateParam.getStandardId());
        task.setName(updateParam.getName())
                .setStandardId(qualityStandard.getId())
                .setStandardName(qualityStandard.getStandardName());
        this.updateById(task);
        // 生成任务质检项
        val details = this.generateDetail(task, qualityStandard);
        //过滤出所有的任务taskId
        List<Long> collect = details.stream().map(QmsInspectionProductTaskDetailEntity::getTaskId).collect(Collectors.toList());
        //查询明细表是否有改任务，如果有则修改，没有则新增
        List<QmsInspectionProductTaskDetailEntity> listed = productTaskDetailService.list(Wrappers.<QmsInspectionProductTaskDetailEntity>lambdaQuery()
                .in(QmsInspectionProductTaskDetailEntity::getTaskId, collect));

        for (QmsInspectionProductTaskDetailEntity item : listed) {
            for (Long taskId : collect) {
                if (item.getTaskId().equals(taskId)) {
                    details.stream().filter(i -> i.getTaskId().equals(taskId)).forEach(i -> i.setId(item.getId()));
                }
            }
        }
        productTaskDetailService.saveOrUpdateBatch(details);
    }

    /**
     * 追加质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void append(InspectionAppendParam appendParam) {
        // 查询出来质检任务和任务项明细
        val task = this.getById(appendParam.getId());
        if (Objects.isNull(task)) {
            throw new RuntimeException("质检任务未找到");
        }
        val taskDetails = productTaskDetailService.lambdaQuery()
                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, task.getId()).list();
        // 查询关联的质检标准
        val qualityStandard = qualityStandardService.getById(task.getStandardId());

        // 更新任务基础信息
        task.setNum(task.getNum() + appendParam.getNum());

        // 计算质检项是否需要更新, 过滤出需要更新的任务详情进行更新
        val details = taskDetails.stream().map(o -> this.updateTaskDetails(o, task, qualityStandard))
                .filter(Objects::nonNull).collect(Collectors.toList());

        // 保存
        productTaskDetailService.updateBatchById(details);
        task.setLatestAppendTime(new Date()).setStatus(InspectionTaskStatusEnum.RUNNING.name());
        this.updateById(task);
        // 保存历史
        historyService.append(task, appendParam.getNum());
    }

    /**
     * 作废质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void canceled(Long taskId, String reason) {
        val task = this.getById(taskId);
        if (Objects.isNull(task)) {
            throw new RuntimeException("质检任务未找到");
        }
        if (!Objects.equals(task.getStatus(), InspectionTaskStatusEnum.WAIT.name())) {
            throw new RuntimeException("只有未开始质检的任务才可以作废");
        }
        task.setStatus(InspectionTaskStatusEnum.FINISHED.name());
        task.setResult(InspectionTaskResultEnum.CANCELED.name());
        this.updateById(task);
        // 保存历史
        historyService.canceled(task, reason);
    }

    /**
     * 免检质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void exemption(Long taskId) {
        val task = this.getById(taskId);
        if (Objects.isNull(task)) {
            throw new RuntimeException("质检任务未找到");
        }
        if (Objects.equals(task.getResult(), InspectionTaskResultEnum.CANCELED.name())) {
            throw new RuntimeException("已作废的任务无法免检");
        }
        if (Objects.equals(task.getResult(), InspectionTaskResultEnum.EXEMPTION.name())) {
            throw new RuntimeException("任务已经免检");
        }
        task.setStatus(InspectionTaskStatusEnum.FINISHED.name());
        task.setResult(InspectionTaskResultEnum.EXEMPTION.name());
        task.setLestStatus("1");
        this.updateById(task);
        // 保存历史
        historyService.exemption(task);
    }

    /**
     * 检查并更新质检项
     *
     * @param detail          质检项
     * @param task            质检任务
     * @param qualityStandard 质检标准
     * @return 发生更新情况下进行返回对象
     */
    private QmsInspectionProductTaskDetailEntity updateTaskDetails(QmsInspectionProductTaskDetailEntity detail,
                                                                   QmsInspectionProductTaskEntity task, QmsQualityStandardEntity qualityStandard) {
        // 查询关联质检项配置
        List<QmsQualityStandardItemEntity> items = qualityStandardItemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, qualityStandard.getId()));
        val standardItem = this.findQualityStandardItemByTaskDetail(items, detail);
        Integer n = null;
        Integer ac = null;
        Integer re = null;
        // 全检
        if (InspectionMethodEnum.FULL.name().equals(qualityStandard.getInspectionMethod())) {
            n = task.getNum();
        }
        if (InspectionMethodEnum.FIXED.name().equals(qualityStandard.getInspectionMethod())) {
            n = qualityStandard.getNum();
        }
        if (InspectionMethodEnum.PENDING.name().equals(qualityStandard.getInspectionMethod())) {
            n = task.getInspectionNum();
        }
        if (InspectionMethodEnum.RANDOM.name().equals(qualityStandard.getInspectionMethod())) {
            // 获取当前抽样标准
            Frequency frequency = this.getInspectionFrequency(task.getNum(), standardItem);
            // 判断是否需要全检
            n = frequency.getN();
            ac = frequency.getAc();
            re = frequency.getRe();
        }

        int difference;
        if (n == 0) {
            difference = task.getNum() - detail.getTotalNum();
        } else {
            difference = n - detail.getTotalNum();
        }

        // 判断是否需要更新任务项的检测数量
        if (difference > 0) {
            val records = detail.getRecords();
            val collect = IntStream.range(0, difference).mapToObj(i -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            records.addAll(collect);

            return detail.setTotalNum(n).setWaitNum(detail.getWaitNum() + difference)
                    .setAc(ac).setRe(re).setRecords(records);
        } else {
            return null;
        }
    }

    /**
     * 生成质检任务项目
     */
    private List<QmsInspectionProductTaskDetailEntity> generateDetail(QmsInspectionProductTaskEntity task,
                                                                      QmsQualityStandardEntity qualityStandard) {
        // 从质检标准中获取质检项列表
        List<QmsQualityStandardItemEntity> items = qualityStandardItemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, qualityStandard.getId()));
        return items.stream().map(standardItem -> this.generateDetail(task, qualityStandard, standardItem))
                .collect(Collectors.toList());
    }

    /**
     * 生成质检任务项目
     */
    private QmsInspectionProductTaskDetailEntity generateDetail(QmsInspectionProductTaskEntity task, QmsQualityStandardEntity standard,
                                                                QmsQualityStandardItemEntity standardItem) {
//        if (task.getNum() < 2) {
//            throw new RuntimeException("抽检数量错误");
//        }

        Integer totalNum = 0;
        Integer waitNum = 0;
        Integer ac = null;
        Integer re = null;
        // 全检
        List<InspectionItemResultRecord> records = new ArrayList<>();
        if (InspectionMethodEnum.FULL.name().equals(standard.getInspectionMethod())) {
            records = IntStream.range(0, task.getNum())
                    .mapToObj(o -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            totalNum = task.getNum();
            waitNum = task.getNum();
        }
        // 固定数量
        if (InspectionMethodEnum.FIXED.name().equals(standard.getInspectionMethod())) {
            // 若设置的固定数量大于质检数量, 则说明全检 设置检验数量为质检数量
            int checkNum = Math.min(standard.getNum(), task.getNum());
            records = IntStream.range(0, checkNum)
                    .mapToObj(o -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            totalNum = checkNum;
            waitNum = checkNum;
        }
        // 发起质检时确定
        if (InspectionMethodEnum.PENDING.name().equals(standard.getInspectionMethod())) {
            // 若设置的固定数量大于质检数量, 则说明全检 设置检验数量为质检数量
            int checkNum = Math.min(task.getInspectionNum(), task.getNum());
            records = IntStream.range(0, checkNum)
                    .mapToObj(o -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            totalNum = checkNum;
            waitNum = checkNum;
        }
        // 抽检
        if (InspectionMethodEnum.RANDOM.name().equals(standard.getInspectionMethod())) {
            // 抽样数量
            Frequency frequency = this.getInspectionFrequency(task.getNum(), standardItem);
            // 是否是全检
            Integer n = frequency.getN();
            records = IntStream.range(0, n > 0 ? n : task.getNum()).mapToObj(o -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            totalNum = frequency.getN();
            waitNum = frequency.getN();
            ac = frequency.getAc();
            re = frequency.getRe();
        }
        return new QmsInspectionProductTaskDetailEntity().setTaskId(task.getId()).setStandardItemId(standardItem.getId())
                .setCheckType(standardItem.getCheckType()).setUpperLimit(standardItem.getUpperLimit())
                .setLowerLimit(standardItem.getLowerLimit()).setName(standardItem.getName())
                .setDemand(standardItem.getDemand()).setMethod(standardItem.getMethod())
                .setCheckClassify(standardItem.getCheckClassify())
                .setPosition(standardItem.getPosition()).setTotalNum(totalNum).setWaitNum(waitNum)
                .setAc(ac).setRe(re).setRecords(records);
    }

    /**
     * 根据任务数量或抽样配置
     */
    private Frequency getInspectionFrequency(int num, QmsQualityStandardItemEntity standardItem) {
        // 获取ID并转为数字类型
        Long samplingId = Long.parseLong(standardItem.getSamplingStrategiesId().toString());
        // 获取获取关联的抽样配置 质检标准 -> AQL配置 -> 关联抽样策略
        QmsQualitySamplingStrategiesEntity samplingStrategies = samplingStrategiesService.getById(samplingId);
        List<Frequency> frequencies = samplingStrategies.getFrequency();
        if (CollUtil.isEmpty(frequencies)) {
            throw new RuntimeException("抽样策略中没有抽样配置");
        }
        Frequency frequency = frequencies.stream().filter(o -> Objects.isNull(o.getE())).findFirst().orElse(null);
        if (Objects.nonNull(frequency) && num >= frequency.getS()) {
            return frequency;
        }
        // 计算出检测数量
        return frequencies.stream().filter(o -> o.getS() <= num && num <= o.getE()).findFirst()
                .orElseThrow(() -> new RuntimeException(standardItem.getName() + "抽样策略中没有此数量范围的抽样配置"));
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
        val taskDetail = productTaskDetailService.getById(param.getDetailId());
        if (Objects.isNull(taskDetail)) {
            throw new RuntimeException("质检详情不存在");
        }

        // 获取关联任务
        QmsInspectionProductTaskEntity task = this.getById(taskDetail.getTaskId());
        if (Objects.isNull(task)) {
            throw new RuntimeException("质检任务不存在");
        }
        // 获取关联质检标准
        val qualityStandard = qualityStandardService.getById(task.getStandardId());
        // 查询关联质检项配置
        List<QmsQualityStandardItemEntity> items = qualityStandardItemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, qualityStandard.getId()));
        val standardItem = this.findQualityStandardItemByTaskDetail(items, taskDetail);

        InspectionItemResultRecord submitRecord = param.getRecord();
        submitRecord.setQualified(qualityStandardItemService.judgementCheckResult(param.getRecord(), standardItem));
        submitRecord.setTaskId(task.getId());
        submitRecord.setDetailId(taskDetail.getId());
        submitRecord.setDetailName(taskDetail.getName());
        submitRecord.setTime(new Date());

        QmsInspectionProductItemResultRecordEntity submit = BeanUtil.copyProperties(submitRecord, QmsInspectionProductItemResultRecordEntity.class);
        recordService.save(submit);

        // 不合格数量
        int unqualifiedNum = submitRecord.getQualified() ? 0 : 1;

        // 根据RE值, 计算是否通过AQL校验, 将填写的记录写到质检任务中, RE与AC的差值, 一般情况下只差 1 , 只用其中一个就可以判断是否合格
        int ac = Objects.isNull(taskDetail.getAc()) ? 0 : taskDetail.getAc();

        // 存储结果
        taskDetail.setFinishNum(taskDetail.getFinishNum() + 1).setWaitNum(taskDetail.getWaitNum() - 1)
                .setUnqualifiedNum(taskDetail.getUnqualifiedNum() + unqualifiedNum);

        taskDetail.setQualifiedStatus(taskDetail.getUnqualifiedNum() <= ac);
        productTaskDetailService.updateById(taskDetail);

        if (Objects.equals(InspectionTaskStatusEnum.WAIT.name(), task.getStatus())) {
            task.setStatus(InspectionTaskStatusEnum.RUNNING.name());
            this.updateById(task);
        }
    }

    /**
     * 批量暂存
     *
     * @param param
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchTemporarilySave(InspectionCheckResultBatchParam param) {
        QmsInspectionProductTaskEntity task = this.getById(param.getTaskId());
        val qualityStandard = qualityStandardService.getById(task.getStandardId());
        List<QmsQualityStandardItemEntity> items = qualityStandardItemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, qualityStandard.getId()));
        List<InspectionCheckResultParam> results = param.getResults();
        List<QmsInspectionProductItemResultRecordEntity> saveRecords = new ArrayList<>();
        List<QmsInspectionProductTaskDetailEntity> updateDetails = new ArrayList<>();
        results.forEach(result -> {
            val taskDetail = productTaskDetailService.getById(result.getDetailId());
            InspectionItemResultRecord submitRecord = result.getRecord();
            val standardItem = this.findQualityStandardItemByTaskDetail(items, taskDetail);
            submitRecord.setQualified(qualityStandardItemService.judgementCheckResult(submitRecord, standardItem));
            submitRecord.setTaskId(task.getId());
            submitRecord.setDetailId(taskDetail.getId());
            submitRecord.setDetailName(taskDetail.getName());
            submitRecord.setTime(new Date());
            submitRecord.setId(null);

            QmsInspectionProductItemResultRecordEntity submit = BeanUtil.copyProperties(submitRecord, QmsInspectionProductItemResultRecordEntity.class);
            saveRecords.add(submit);

            // 不合格数量
            int unqualifiedNum = submitRecord.getQualified() ? 0 : 1;

            // 根据RE值, 计算是否通过AQL校验, 将填写的记录写到质检任务中, RE与AC的差值, 一般情况下只差 1 , 只用其中一个就可以判断是否合格
            int ac = Objects.isNull(taskDetail.getAc()) ? 0 : taskDetail.getAc();

            // 存储结果
            taskDetail.setFinishNum(taskDetail.getFinishNum() + 1).setWaitNum(taskDetail.getWaitNum() - 1)
                    .setUnqualifiedNum(taskDetail.getUnqualifiedNum() + unqualifiedNum);

            taskDetail.setQualifiedStatus(taskDetail.getUnqualifiedNum() <= ac);

            updateDetails.add(taskDetail);
        });
        recordService.saveBatch(saveRecords);
        productTaskDetailService.updateBatchById(updateDetails);

        if (Objects.equals(InspectionTaskStatusEnum.WAIT.name(), task.getStatus())) {
            task.setStatus(InspectionTaskStatusEnum.RUNNING.name());
            this.updateById(task);
        }

        // 保存附件
        if (StrUtil.isNotBlank(param.getAnnex())) {
            task.setAnnex(param.getAnnex());
            this.updateById(task);
        }

        val taskDetails = productTaskDetailService.lambdaQuery()
                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, task.getId()).list();
        // 是否全部检测完毕
        boolean canEnd = taskDetails.stream().anyMatch(o -> o.getWaitNum() > 0);
        if (!canEnd) {
            // TODO 获取配置 如果不需要自动判断结果 则不更新不合格状态  由人工判断 调用别的接口
            boolean qualified = taskDetails.stream().allMatch(o -> Objects.equals(o.getQualifiedStatus(), Boolean.TRUE));
            if (qualified) {
                task.setStatus(InspectionTaskStatusEnum.FINISHED.name()).setLatestInspectionTime(new Date());
                task.setResult(InspectionTaskResultEnum.QUALIFIED.name())
                        .setUsableNum(task.getNum()).setHandleNum(task.getNum());
            } else {
                task.setStatus(InspectionTaskStatusEnum.UNHANDLED.name()).setLatestInspectionTime(new Date());
                task.setResult(InspectionTaskResultEnum.UN_QUALIFIED.name())
                        .setUsableNum(0).setHandleNum(0);
            }
            this.updateById(task);

//            // 如果不合格 入不合格品管理
//            if (InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult())) {
//                badInfoService.add(new QmsBadInfo().setResourceTaskId(task.getId()).setResourceType(InspectionTaskTypeEnum.PRODUCT.name())
//                        .setNum(task.getNum()).setMatCode(task.getMatCode()).setMatName(task.getMatName())
//                        .setBatchNo(task.getBatchNo()));
//            }

            // 保存历史
            historyService.check(task);
            if (InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult())
                    && StrUtil.isNotBlank(task.getOrderNo())) {
                qmsPlanEventOutboxService.enqueueIsolation("PRODUCT", task.getId());
            }
        }

    }

    /**
     * 提交质检记录结果
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitInspectionResult(Long taskId) {
        val task = this.getById(taskId);
        if (Objects.isNull(task)) {
            throw new RuntimeException("质检任务未找到");
        }
        if (StrUtil.isNotBlank(task.getResult())) {
            return;
        }
        // 查询到质检项
        val taskDetails = productTaskDetailService.lambdaQuery()
                .eq(QmsInspectionProductTaskDetailEntity::getTaskId, task.getId()).list();
        // 是否全部检测完毕
        boolean canEnd = taskDetails.stream().anyMatch(o -> o.getWaitNum() > 0);
        if (canEnd) {
            throw new RuntimeException("质检任务未填写完毕, 请检查");
        }

        // 判断是否都都已经及格
        // TODO 获取配置 如果不需要自动判断结果 则不更新不合格状态  由人工判断 调用别的接口
        boolean qualified = taskDetails.stream().allMatch(o -> Objects.equals(o.getQualifiedStatus(), Boolean.TRUE));
        task.setResult(qualified ? InspectionTaskResultEnum.QUALIFIED.name() : InspectionTaskResultEnum.UN_QUALIFIED.name())
                .setStatus(qualified ? InspectionTaskStatusEnum.FINISHED.name() : InspectionTaskStatusEnum.UNHANDLED.name())
                .setLatestInspectionTime(new Date());
        this.updateById(task);

        // 如果不合格 入不合格品管理
        if (InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult())) {
            badInfoService.add(new QmsBadInfoEntity().setResourceTaskId(taskId).setResourceType(InspectionTaskTypeEnum.PRODUCT.name())
                    .setNum(task.getNum() == null ? null : java.math.BigDecimal.valueOf(task.getNum())).setMatCode(task.getMatCode()).setMatName(task.getMatName())
                    .setBatchNo(task.getBatchNo()));
        }

        // 保存历史
        historyService.check(task);
        if (InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult())
                && StrUtil.isNotBlank(task.getOrderNo())) {
            qmsPlanEventOutboxService.enqueueIsolation("PRODUCT", task.getId());
        }
        reworkResultPublisher.publish(InspectionTaskTypeEnum.PRODUCT.name(), task.getId());
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
        QmsInspectionProductTaskEntity task = this.getOne(
                new LambdaQueryWrapper<QmsInspectionProductTaskEntity>()
                        .eq(QmsInspectionProductTaskEntity::getId, dto.getId()).last("FOR UPDATE"), false);
        if (task == null) {
            throw new RuntimeException("成品质检任务不存在");
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
        QmsInspectionProductHandleRecordEntity existing = handleRecordService.getOne(
                new LambdaQueryWrapper<QmsInspectionProductHandleRecordEntity>()
                        .eq(QmsInspectionProductHandleRecordEntity::getRequestId, requestId), false);
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
            task.setUsableNum(Objects.requireNonNullElse(task.getUsableNum(), 0) + dto.getHandleNum());
        }
        if (HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult())) {
            // 入不合格品库
            badInfoService.add(new QmsBadInfoEntity().setResourceTaskId(task.getId()).setResourceType(InspectionTaskTypeEnum.PRODUCT.name())
                    .setRequestId("DISPOSITION:" + requestId)
                    .setNum(dto.getHandleNum() == null ? null : java.math.BigDecimal.valueOf(dto.getHandleNum())).setMatCode(task.getMatCode()).setMatName(task.getMatName())
                    .setBatchNo(task.getBatchNo()));
        }
        this.updateById(task);
        // 保存处理明细
        QmsInspectionProductHandleRecordEntity record = new QmsInspectionProductHandleRecordEntity();
        MomUser user = SecurityUtils.getUser();
        record.setRequestId(requestId).setHandleNum(dto.getHandleNum()).setHandleRemark(dto.getHandleRemark().trim()).setHandlePersonId(user.getId())
                .setHandlePersonName(user.getName()).setHandleTime(new Date()).setHandleResult(dto.getHandleResult())
                .setTaskId(task.getId()).setStatus(HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult())
                        || HandleResultEnum.SMASH.name().equals(dto.getHandleResult()));
        handleRecordService.save(record);
        if (Set.of(HandleResultEnum.RELEASE.name(), HandleResultEnum.CONCESSION.name(),
                HandleResultEnum.REWORK.name(), HandleResultEnum.SMASH.name()).contains(dto.getHandleResult())
                && StrUtil.isNotBlank(task.getOrderNo())) {
            qmsPlanEventOutboxService.enqueueDisposition("PRODUCT", task.getId(), record.getId());
        }

        List<QmsInspectionProductHandleRecordEntity> dispositions = handleRecordService.list(
                new LambdaQueryWrapper<QmsInspectionProductHandleRecordEntity>()
                        .eq(QmsInspectionProductHandleRecordEntity::getTaskId, task.getId()));
        task.setHandleResult(dispositions.stream().map(QmsInspectionProductHandleRecordEntity::getHandleResult)
                .filter(StrUtil::isNotBlank).distinct().sorted().collect(Collectors.joining(",")));
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
}
