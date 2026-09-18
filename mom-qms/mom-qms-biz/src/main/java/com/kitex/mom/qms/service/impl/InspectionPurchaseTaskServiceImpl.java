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
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialAuthorizationService;
import com.kitex.mom.pms.api.feign.RemotePurchaseReceiptService;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptSetQualifiedNumDTO;
import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.dto.req.InspectionPurchaseTaskReq;
import com.kitex.mom.qms.dto.resp.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.enums.*;
import com.kitex.mom.qms.mapper.QmsInspectionPurchaseTaskMapper;
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
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import cn.hutool.json.JSONUtil;

/**
 * 来料质检
 *
 * @author kitex
 * @date 2022/12/1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionPurchaseTaskServiceImpl extends ServiceImpl<QmsInspectionPurchaseTaskMapper, QmsInspectionPurchaseTaskEntity> implements InspectionPurchaseTaskService {

    private final QmsInspectionPurchaseTaskDetailService purchaseTaskDetailService;

    private final QualityStandardService qualityStandardService;

    private final QualityStandardItemService qualityStandardItemService;

    private final InspectionPurchaseHistoryService historyService;

    private final QualitySamplingStrategiesService samplingStrategiesService;

    private final InspectionPurchaseItemResultRecordService recordService;

    private final QmsBadInfoService badInfoService;

    private final RemotePurchaseReceiptService receiptService;

    private final InspectionPurchaseHandleRecordService handleRecordService;

    private final RemoteMesTaskService remoteMesTaskService;

    private final RemoteMaterialAuthorizationService remoteMaterialAuthorizationService;


    /**
     * 创建质检任务
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(InspectionPurchaseCreateParam createParam) {
        // 判断是否已经有同类型任务
        if (this.existTask(createParam.getMatCode(), createParam.getSupplierCode(), createParam.getBatchNo())) {
            throw new RuntimeException("已经有同类型的任务,无法继续创建");
        }
        MdmMaterialAuthorizationEntity authorization = resolveSupplierAuthorization(createParam);
        if (authorization != null && authorization.getQualityStandardId() != null) {
            createParam.setStandardId(authorization.getQualityStandardId());
        }
        // 获取关联质检标准
        QmsQualityStandardEntity qualityStandard = null;
        // 若没传质检标准id  则去匹配一个
        if (createParam.getStandardId() == null) {
            qualityStandard = qualityStandardService.getOne(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                    .eq(QmsQualityStandardEntity::getMatCode, createParam.getMatCode())
                    .eq(QmsQualityStandardEntity::getInspectionType, InspectionTaskTypeEnum.PURCHASE.name())
                    .eq(QmsQualityStandardEntity::getPublished, 1));
        } else {
            qualityStandard = qualityStandardService.getById(createParam.getStandardId());
        }
        if (Objects.isNull(qualityStandard)) {
            throw new RuntimeException("未找到质检标准，请先创建质检标准");
        }
        if (!InspectionTaskTypeEnum.PURCHASE.name().equals(qualityStandard.getInspectionType())
                || !Objects.equals(qualityStandard.getPublished(), 1)
                || !Objects.equals(qualityStandard.getMatCode(), createParam.getMatCode())) {
            throw new RuntimeException("指定质检标准未发布、不是来料检验类型或与当前物料不匹配");
        }

        // 创建质检任务
        val task = new QmsInspectionPurchaseTaskEntity().setBatchNo(createParam.getBatchNo())
                .setTaskCode(createParam.getNoticeId())
                .setMatCode(createParam.getMatCode()).setMatName(createParam.getMatName()).setMatSpec(createParam.getMatSpec())
                .setMatProduceDate(createParam.getMatProduceDate()).setSupplierCode(createParam.getSupplierCode())
                .setSupplierName(createParam.getSupplierName()).setStandardId(qualityStandard.getId())
                .setStandardName(qualityStandard.getStandardName()).setNum(createParam.getNum())
                .setHandleNum(0)
                .setInspectionNum(createParam.getInspectionNum())
                .setStatus(InspectionTaskStatusEnum.WAIT.name()).setDeptCode(createParam.getDeptCode());
        task.setInspectionLevel(authorization == null ? "NORMAL" : authorization.getInspectionLevel());
        task.setOriginalInspectionMethod(qualityStandard.getInspectionMethod());
        if (authorization != null) {
            task.setMaterialAuthorizationId(authorization.getId());
            task.setMaterialAuthorizationNo(authorization.getAuthorizationNo());
            task.setMaterialAuthorizationValidEnd(authorization.getValidEnd());
            task.setComplianceSnapshot(JSONUtil.toJsonStr(authorization));
        }
        if (isTightened(task)) {
            task.setInspectionNum(createParam.getNum());
        }
        if (InspectionMethodEnum.FULL.name().equals(qualityStandard.getInspectionMethod())) {
            task.setInspectionNum(createParam.getNum());
        }
        if (InspectionMethodEnum.FIXED.name().equals(qualityStandard.getInspectionMethod())) {
            task.setInspectionNum(qualityStandard.getNum());
        }
        this.save(task);

        // 生成任务质检项
        val details = this.generateDetail(task, qualityStandard);
        if (!isTightened(task) && InspectionMethodEnum.RANDOM.name().equals(qualityStandard.getInspectionMethod())) {
            Optional<Integer> maxNum = details.stream().map(QmsInspectionPurchaseTaskDetailEntity::getTotalNum).max(Integer::compareTo);
            if (maxNum.isPresent()) {
                task.setInspectionNum(maxNum.get());
                this.updateById(task);
            }
        }

        purchaseTaskDetailService.saveBatch(details);
        // 保存历史
        historyService.create(task);

        return task.getId();
    }

    private MdmMaterialAuthorizationEntity resolveSupplierAuthorization(InspectionPurchaseCreateParam createParam) {
        if (StrUtil.hasBlank(createParam.getMatCode(), createParam.getSupplierCode())) {
            return null;
        }
        try {
            List<MdmMaterialAuthorizationEntity> records = remoteMaterialAuthorizationService.active(
                    "SUPPLIER_CERTIFICATION", createParam.getMatCode(), createParam.getSupplierCode(), LocalDate.now()).getData();
            if (CollUtil.isEmpty(records)) {
                return null;
            }
            return records.stream().filter(item -> "TIGHTENED".equals(item.getInspectionLevel()))
                    .findFirst().orElse(records.get(0));
        } catch (Exception ex) {
            throw new RuntimeException("读取供应商物料认证失败，禁止在合规状态未知时创建来料检验任务", ex);
        }
    }

    private boolean isTightened(QmsInspectionPurchaseTaskEntity task) {
        return task != null && "TIGHTENED".equals(task.getInspectionLevel());
    }

    /**
     * 批量创建
     *
     * @param createParams
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> batchCreate(List<InspectionPurchaseCreateParam> createParams) {
        Map<String, Long> resultMap = new HashMap<>();
        createParams.forEach(param -> {
            Long taskId = this.create(param);
            resultMap.put(param.getNoticeId(), taskId);
        });
        return resultMap;
    }


    /**
     * 修改质检任务
     *
     * @param updateParam
     */
    public void update(InspectionPurchaseCreateParam updateParam) {
        QmsInspectionPurchaseTaskEntity task = Optional.ofNullable(this.getById(updateParam.getId()))
                .orElseThrow(() -> new RuntimeException("获取质检任务信息失败！"));
        // 获取关联质检标准
        val qualityStandard = qualityStandardService.getById(updateParam.getStandardId());
        task.setName(updateParam.getName()).setStandardId(qualityStandard.getId())
                .setStandardName(qualityStandard.getStandardName());
        this.updateById(task);
        // 生成任务质检项
        val details = this.generateDetail(task, qualityStandard);
        // 过滤出所有的任务taskId
        List<Long> collect = details.stream().map(QmsInspectionPurchaseTaskDetailEntity::getTaskId)
                .collect(Collectors.toList());
        // 查询明细表是否有改任务，如果有则修改，没有则新增
        List<QmsInspectionPurchaseTaskDetailEntity> listed = purchaseTaskDetailService
                .list(Wrappers.<QmsInspectionPurchaseTaskDetailEntity>lambdaQuery()
                        .in(QmsInspectionPurchaseTaskDetailEntity::getTaskId, collect));

        for (QmsInspectionPurchaseTaskDetailEntity item : listed) {
            for (Long taskId : collect) {
                if (item.getTaskId().equals(taskId)) {
                    details.stream().filter(i -> i.getTaskId().equals(taskId)).forEach(i -> i.setId(item.getId()));
                }
            }
        }
        purchaseTaskDetailService.saveOrUpdateBatch(details);
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
        val taskDetails = purchaseTaskDetailService.lambdaQuery()
                .eq(QmsInspectionPurchaseTaskDetailEntity::getTaskId, task.getId()).list();
        // 查询关联的质检标准
        val qualityStandard = qualityStandardService.getById(task.getStandardId());

        // 更新任务基础信息
        task.setNum(task.getNum() + appendParam.getNum());
        if (isTightened(task)) {
            task.setInspectionNum(task.getNum());
        }

        // 计算质检项是否需要更新, 过滤出需要更新的任务详情进行更新
        val details = taskDetails.stream().map(o -> this.updateTaskDetails(o, task, qualityStandard))
                .filter(Objects::nonNull).collect(Collectors.toList());

        // 保存
        purchaseTaskDetailService.updateBatchById(details);
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
        if (isTightened(task)) {
            throw new RuntimeException("加严检验任务不允许免检，必须完成全部检验项");
        }
        task.setStatus(InspectionTaskStatusEnum.FINISHED.name());
        task.setResult(InspectionTaskResultEnum.EXEMPTION.name());
        this.updateById(task);
        // 保存历史
        historyService.exemption(task);

        if (StrUtil.isNotBlank(task.getTaskCode())) {
            // 调用采购收料合格品回馈接口
            this.pmsReceiptHandler(task, task.getNum());
        }
    }

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    public QmsInspectionPurchaseTaskDTO findById(Long id) {
        QmsInspectionPurchaseTaskDTO result = new QmsInspectionPurchaseTaskDTO();
        QmsInspectionPurchaseTaskEntity task = this.getById(id);
        result.setTask(task);
        List<QmsInspectionPurchaseTaskDetailEntity> details = purchaseTaskDetailService.list(new LambdaQueryWrapper<QmsInspectionPurchaseTaskDetailEntity>()
                .eq(QmsInspectionPurchaseTaskDetailEntity::getTaskId, task.getId()));
        Integer waitNum = details.stream().mapToInt(QmsInspectionPurchaseTaskDetailEntity::getWaitNum).sum();
        if (!InspectionTaskStatusEnum.FINISHED.name().equals(task.getStatus()) && !Objects.equals(waitNum, 0)) {
            details = details.stream().filter(item -> !Objects.equals(item.getWaitNum(), 0)).collect(Collectors.toList());
        }
        result.setDetails(details);
        result.setClassify(new ArrayList<>());
        if (!Objects.equals(waitNum, 0)) {
            Map<String, Integer> classifyMaxWaitNumMap = details.stream()
                    .collect(Collectors.groupingBy(
                            QmsInspectionPurchaseTaskDetailEntity::getCheckClassify,
                            Collectors.collectingAndThen(
                                    Collectors.mapping(QmsInspectionPurchaseTaskDetailEntity::getWaitNum,
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
     * @param detail          质检项
     * @param task            质检任务
     * @param qualityStandard 质检标准
     * @return 发生更新情况下进行返回对象
     */
    private QmsInspectionPurchaseTaskDetailEntity updateTaskDetails(QmsInspectionPurchaseTaskDetailEntity detail,
                                                                    QmsInspectionPurchaseTaskEntity task, QmsQualityStandardEntity qualityStandard) {
        // 查询关联质检项配置
        List<QmsQualityStandardItemEntity> items = qualityStandardItemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, qualityStandard.getId()));
        val standardItem = this.findQualityStandardItemByTaskDetail(items, detail);

        Integer n = null;
        Integer totalNum = 0;
        Integer ac = null;
        Integer re = null;
        // 全检
        if (isTightened(task) || InspectionMethodEnum.FULL.name().equals(qualityStandard.getInspectionMethod())) {
            n = task.getNum();
        }
        if (!isTightened(task) && InspectionMethodEnum.FIXED.name().equals(qualityStandard.getInspectionMethod())) {
            n = qualityStandard.getNum();
        }
        if (!isTightened(task) && InspectionMethodEnum.PENDING.name().equals(qualityStandard.getInspectionMethod())) {
            n = task.getInspectionNum();
        }
        if (!isTightened(task) && InspectionMethodEnum.RANDOM.name().equals(qualityStandard.getInspectionMethod())) {
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
    private List<QmsInspectionPurchaseTaskDetailEntity> generateDetail(QmsInspectionPurchaseTaskEntity task,
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
    private QmsInspectionPurchaseTaskDetailEntity generateDetail(QmsInspectionPurchaseTaskEntity task, QmsQualityStandardEntity standard,
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
        if (isTightened(task) || InspectionMethodEnum.FULL.name().equals(standard.getInspectionMethod())) {
            records = IntStream.range(0, task.getNum())
                    .mapToObj(o -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            totalNum = task.getNum();
            waitNum = task.getNum();
        }
        // 固定数量
        if (!isTightened(task) && InspectionMethodEnum.FIXED.name().equals(standard.getInspectionMethod())) {
            // 若设置的固定数量大于质检数量, 则说明全检 设置检验数量为质检数量
            int checkNum = Math.min(standard.getNum(), task.getNum());
            records = IntStream.range(0, checkNum)
                    .mapToObj(o -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            totalNum = checkNum;
            waitNum = checkNum;
        }
        // 发起质检时确定
        if (!isTightened(task) && InspectionMethodEnum.PENDING.name().equals(standard.getInspectionMethod())) {
            // 若设置的固定数量大于质检数量, 则说明全检 设置检验数量为质检数量
            int checkNum = Math.min(task.getInspectionNum(), task.getNum());
            records = IntStream.range(0, checkNum)
                    .mapToObj(o -> new InspectionItemResultRecord())
                    .collect(Collectors.toList());
            totalNum = checkNum;
            waitNum = checkNum;
        }
        // 抽检
        if (!isTightened(task) && InspectionMethodEnum.RANDOM.name().equals(standard.getInspectionMethod())) {
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

        return new QmsInspectionPurchaseTaskDetailEntity().setTaskId(task.getId()).setStandardItemId(standardItem.getId())
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
        Long samplingId = Long.valueOf(standardItem.getSamplingStrategiesId().toString());
        // 获取获取关联的抽样配置 质检标准 -> AQL配置 -> 关联抽样策略
        QmsQualitySamplingStrategiesEntity samplingStrategies = samplingStrategiesService.getById(samplingId);
        List<Frequency> frequencies = samplingStrategies.getFrequency();
        if (CollUtil.isEmpty(frequencies)) {
            throw new RuntimeException("抽样策略中没有抽样配置");
        }

        Frequency maxFrequency = frequencies.stream().filter(frequency -> Objects.isNull(frequency.getE())).findFirst()
                .orElseThrow(() -> new RuntimeException(standardItem.getName() + "质检标准项的抽样策略配置错误"));
        // 1个默认全检
        if (num == 1) {
            return new Frequency().setAc(0).setS(1).setE(1).setRe(1).setN(1);
        }
        // 最大及无限数量抽样
        if (num >= maxFrequency.getS()) {
            return maxFrequency;
        }

        // 计算出检测数量
        return frequencies.stream().filter(o -> o.getS() <= num && num <= o.getE()).findFirst()
                .orElseThrow(() -> new RuntimeException(standardItem.getName() + "抽样策略中没有此数量范围的抽样配置"));
    }

    /**
     * 判断是否已经有同类型任务
     *
     * @param matCode      物料号
     * @param supplierCode 供应商号
     * @param batchNo      批次号
     * @return 是否已经存在
     */
    public boolean existTask(String matCode, String supplierCode, String batchNo) {
        // 已作废和已处理（已生产入库通知单）的不能追加
        List<String> notHandle = new ArrayList<>();
        notHandle.add(InspectionTaskResultEnum.CANCELED.name());
        return this.lambdaQuery().eq(QmsInspectionPurchaseTaskEntity::getMatCode, matCode)
                .eq(QmsInspectionPurchaseTaskEntity::getSupplierCode, supplierCode)
                .eq(QmsInspectionPurchaseTaskEntity::getBatchNo, batchNo)
                .ge(QmsInspectionPurchaseTaskEntity::getLatestAppendTime, LocalDateTime.of(LocalDate.now(), LocalTime.MIN))
                .le(QmsInspectionPurchaseTaskEntity::getLatestAppendTime, LocalDateTime.of(LocalDate.now(), LocalTime.MAX))
                .notIn(QmsInspectionPurchaseTaskEntity::getResult, notHandle).exists();
    }

    /**
     * 根据物料/供应商/批次号 查询质检任务, 创建质检时使用
     */
    public QmsInspectionPurchaseTaskEntity getTaskByMatCodeAndSupplierCodeAndBatchNo(String matCode, String supplierCode,
                                                                                     String batchNo) {
        // 已作废和已处理（已生产入库通知单）的不能追加
        List<String> notHandle = new ArrayList<>();
        notHandle.add(InspectionTaskResultEnum.CANCELED.name());
        return this.lambdaQuery().eq(QmsInspectionPurchaseTaskEntity::getMatCode, matCode)
                .eq(QmsInspectionPurchaseTaskEntity::getSupplierCode, supplierCode)
                .eq(QmsInspectionPurchaseTaskEntity::getBatchNo, batchNo)
                .notIn(QmsInspectionPurchaseTaskEntity::getResult, notHandle).oneOpt()
                .orElse(null);
    }

    /**
     * 获取质检项列表
     */
    public List<QmsInspectionPurchaseTaskDetailEntity> findDetailsByTaskId(Long taskId) {
        return purchaseTaskDetailService.lambdaQuery().eq(QmsInspectionPurchaseTaskDetailEntity::getTaskId, taskId).list();
    }

    /**
     * 获取质检项详情
     */
    public QmsInspectionPurchaseTaskDetailEntity findDetailById(Long detailId) {
        QmsInspectionPurchaseTaskDetailEntity result = purchaseTaskDetailService.getById(detailId);
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
        val taskDetail = purchaseTaskDetailService.getById(param.getDetailId());
        if (Objects.isNull(taskDetail)) {
            throw new RuntimeException("质检详情不存在");
        }
        // 获取关联任务
        QmsInspectionPurchaseTaskEntity task = this.getById(taskDetail.getTaskId());
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

        QmsInspectionPurchaseItemResultRecordEntity submit = BeanUtil.copyProperties(submitRecord, QmsInspectionPurchaseItemResultRecordEntity.class);
        recordService.save(submit);

        // 不合格数量
        int unqualifiedNum = submitRecord.getQualified() ? 0 : 1;

        // 根据RE值, 计算是否通过AQL校验, 将填写的记录写到质检任务中, RE与AC的差值, 一般情况下只差 1 , 只用其中一个就可以判断是否合格
        int ac = Objects.isNull(taskDetail.getAc()) ? 0 : taskDetail.getAc();

        // 存储结果
        taskDetail.setFinishNum(taskDetail.getFinishNum() + 1).setWaitNum(taskDetail.getWaitNum() - 1)
                .setUnqualifiedNum(taskDetail.getUnqualifiedNum() + unqualifiedNum);

        taskDetail.setQualifiedStatus(taskDetail.getUnqualifiedNum() <= ac);
        purchaseTaskDetailService.updateById(taskDetail);

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
        QmsInspectionPurchaseTaskEntity task = this.getById(param.getTaskId());
        val qualityStandard = qualityStandardService.getById(task.getStandardId());
        List<QmsQualityStandardItemEntity> items = qualityStandardItemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, qualityStandard.getId()));
        List<InspectionCheckResultParam> results = param.getResults();
        List<QmsInspectionPurchaseItemResultRecordEntity> saveRecords = new ArrayList<>();
        List<QmsInspectionPurchaseTaskDetailEntity> updateDetails = new ArrayList<>();
        results.forEach(result -> {
            val taskDetail = purchaseTaskDetailService.getById(result.getDetailId());
            InspectionItemResultRecord submitRecord = result.getRecord();
            val standardItem = this.findQualityStandardItemByTaskDetail(items, taskDetail);
            submitRecord.setQualified(qualityStandardItemService.judgementCheckResult(submitRecord, standardItem));
            submitRecord.setTaskId(task.getId());
            submitRecord.setDetailId(taskDetail.getId());
            submitRecord.setDetailName(taskDetail.getName());
            submitRecord.setTime(new Date());
            submitRecord.setId(null);

            QmsInspectionPurchaseItemResultRecordEntity submit = BeanUtil.copyProperties(submitRecord, QmsInspectionPurchaseItemResultRecordEntity.class);
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
        purchaseTaskDetailService.updateBatchById(updateDetails);

        if (Objects.equals(InspectionTaskStatusEnum.WAIT.name(), task.getStatus())) {
            task.setStatus(InspectionTaskStatusEnum.RUNNING.name());
            this.updateById(task);
        }

        // 保存附件
        if (StrUtil.isNotBlank(param.getAnnex())) {
            task.setAnnex(param.getAnnex());
            this.updateById(task);
        }

        // 所有检验完毕自动提交结果
        val taskDetails = purchaseTaskDetailService.lambdaQuery()
                .eq(QmsInspectionPurchaseTaskDetailEntity::getTaskId, task.getId()).list();
        // 是否全部检测完毕
        boolean canEnd = taskDetails.stream().anyMatch(o -> o.getWaitNum() > 0);
        if (!canEnd) {
            boolean qualified = taskDetails.stream().allMatch(o -> Objects.equals(o.getQualifiedStatus(), Boolean.TRUE));
            if (qualified) {
                task.setResult(InspectionTaskResultEnum.QUALIFIED.name())
                        .setUsableNum(task.getNum()).setHandleNum(task.getNum());
                task.setStatus(InspectionTaskStatusEnum.FINISHED.name()).setLatestInspectionTime(new Date());
                if (StrUtil.isNotBlank(task.getTaskCode())) {
                    // 调用采购收料合格品回馈接口
                    this.pmsReceiptHandler(task, task.getNum());
                }
            } else {
                task.setStatus(InspectionTaskStatusEnum.UNHANDLED.name()).setLatestInspectionTime(new Date());
                task.setResult(InspectionTaskResultEnum.UN_QUALIFIED.name())
                        .setUsableNum(0).setHandleNum(0);
            }
            this.updateById(task);

            // 保存历史
            historyService.check(task);
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

        // 查询到质检项
        val taskDetails = purchaseTaskDetailService.lambdaQuery()
                .eq(QmsInspectionPurchaseTaskDetailEntity::getTaskId, task.getId()).list();
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

        if (InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult())) {
            badInfoService.add(new QmsBadInfoEntity().setResourceTaskId(taskId).setResourceType(InspectionTaskTypeEnum.PURCHASE.name())
                    .setNum(task.getNum() == null ? null : java.math.BigDecimal.valueOf(task.getNum())).setMatCode(task.getMatCode()).setMatName(task.getMatName())
                    .setSupplierCode(task.getSupplierCode()).setSupplierName(task.getSupplierName())
                    .setBatchNo(task.getBatchNo()));
        } else {
            if (StrUtil.isNotBlank(task.getTaskCode())) {
                // 调用采购收料合格品回馈接口
                this.pmsReceiptHandler(task, task.getNum());
            }
        }

        // 保存历史
        historyService.check(task);
    }

    /**
     * 采购收料回馈
     *
     * @param task       采购质检任务
     * @param receiptNum 数量
     */
    private void pmsReceiptHandler(QmsInspectionPurchaseTaskEntity task, Integer receiptNum) {
        // 如果不合格 入不合格品管理
        WmsPurchaseReceiptSetQualifiedNumDTO receipt = new WmsPurchaseReceiptSetQualifiedNumDTO();
        receipt.setMatBatch(task.getBatchNo());
        receipt.setMatCode(task.getMatCode());
        receipt.setMatName(task.getMatName());
        receipt.setBillNo(task.getTaskCode());
        receipt.setQualityId(task.getId());
        Double num = Double.valueOf(receiptNum);
        receipt.setMatNum(num);
        receipt.setQualityNum(num);
        receipt.setResult(task.getResult());
        // 调用采购收料合格品回馈接口
        receiptService.setQualifiedNum(receipt);
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
    public QmsInspectionPurchaseTaskAppVO queryAppPage(Page<QmsInspectionPurchaseTaskEntity> page,
                                                       InspectionPurchaseTaskReq param, CommonQuery commonQuery) {
        QmsInspectionPurchaseTaskAppVO result = new QmsInspectionPurchaseTaskAppVO();
        List<QmsInspectionPurchaseTaskEntity> allList = this.list();
        Map<String, Long> statusCountMap = allList.stream()
                .collect(Collectors.groupingBy(QmsInspectionPurchaseTaskEntity::getStatus, Collectors.counting()));
        List<QmsStatisticsDTO> classify = statusCountMap.entrySet().stream()
                .map(entry -> new QmsStatisticsDTO(entry.getKey(), entry.getValue().intValue()))
                .toList();
        result.setClassify(classify);
        QueryWrapper<QmsInspectionPurchaseTaskEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(param);
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
        List<QmsInspectionPurchaseTaskEntity> allList = this.list();
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

    /**
     * 获取各状态数量统计
     *
     * @param param 查询参数
     * @return 状态统计
     */
    public Map<String, Long> getStatusCount(InspectionPurchaseTaskReq param) {
        QueryWrapper<QmsInspectionPurchaseTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .like(StrUtil.isNotBlank(param.getMatCode()), QmsInspectionPurchaseTaskEntity::getMatCode, param.getMatCode())
                .like(StrUtil.isNotBlank(param.getMatName()), QmsInspectionPurchaseTaskEntity::getMatName, param.getMatName())
                .like(StrUtil.isNotBlank(param.getSupplierCode()), QmsInspectionPurchaseTaskEntity::getSupplierCode,
                        param.getSupplierCode())
                .eq(StrUtil.isNotBlank(param.getResult()), QmsInspectionPurchaseTaskEntity::getResult, param.getResult())
                .like(StrUtil.isNotBlank(param.getSupplierName()), QmsInspectionPurchaseTaskEntity::getSupplierName,
                        param.getSupplierName())
                .like(StrUtil.isNotBlank(param.getBatchNo()), QmsInspectionPurchaseTaskEntity::getBatchNo,
                        param.getBatchNo());
        autoQueryWrapper.orderByDesc("create_time");

        List<QmsInspectionPurchaseTaskEntity> tasks = this.list(autoQueryWrapper);

        // 统计常规状态
        return tasks.stream()
                .collect(Collectors.groupingBy(QmsInspectionPurchaseTaskEntity::getStatus, Collectors.counting()));
    }

    /**
     * 分页
     */
    public Page<QmsInspectionPurchaseTaskEntity> page(Page<QmsInspectionPurchaseTaskEntity> page, InspectionPurchaseTaskReq param,
                                                      CommonQuery commonQuery) {
        QueryWrapper<QmsInspectionPurchaseTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .like(StrUtil.isNotBlank(param.getMatCode()), QmsInspectionPurchaseTaskEntity::getMatCode, param.getMatCode())
                .like(StrUtil.isNotBlank(param.getMatName()), QmsInspectionPurchaseTaskEntity::getMatName, param.getMatName())
                .like(StrUtil.isNotBlank(param.getMatSpec()), QmsInspectionPurchaseTaskEntity::getMatSpec, param.getMatSpec())
                .like(StrUtil.isNotBlank(param.getSupplierCode()), QmsInspectionPurchaseTaskEntity::getSupplierCode,
                        param.getSupplierCode())
                .eq(StrUtil.isNotBlank(param.getResult()), QmsInspectionPurchaseTaskEntity::getResult, param.getResult())
                .like(StrUtil.isNotBlank(param.getSupplierName()), QmsInspectionPurchaseTaskEntity::getSupplierName,
                        param.getSupplierName())
                .like(StrUtil.isNotBlank(param.getBatchNo()), QmsInspectionPurchaseTaskEntity::getBatchNo,
                        param.getBatchNo())
                .eq(StrUtil.isNotBlank(param.getStatus()), QmsInspectionPurchaseTaskEntity::getStatus, param.getStatus());
        autoQueryWrapper.orderByDesc("create_time");
        return this.page(page, autoQueryWrapper);
    }


    /**
     * 从质检项列表中获取质检任务详情关联的质检项
     */
    private QmsQualityStandardItemEntity findQualityStandardItemByTaskDetail(List<QmsQualityStandardItemEntity> list,
                                                                             QmsInspectionPurchaseTaskDetailEntity detail) {
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
        List<QmsInspectionPurchaseTaskDetailEntity> list = purchaseTaskDetailService.lambdaQuery()
                .eq(QmsInspectionPurchaseTaskDetailEntity::getTaskId, taskId).list();
        // 判断是否有符合要求的数据
        if (CollUtil.isEmpty(list)) {
            return reportListRespDTO;
        }
        List<Map<String, Object>> records = new ArrayList<>();
        List<Integer> tableColumns = new ArrayList<>();
        Integer key = 0;
        for (QmsInspectionPurchaseTaskDetailEntity detail : list) {
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
            record.put("qualifiedStatus",
                    Objects.nonNull(detail.getQualifiedStatus()) && detail.getQualifiedStatus() ? "合格" : "不合格");
            List<QmsInspectionPurchaseItemResultRecordEntity> itemList = recordService.list(new LambdaQueryWrapper<QmsInspectionPurchaseItemResultRecordEntity>()
                    .eq(QmsInspectionPurchaseItemResultRecordEntity::getDetailId, detail.getId()));
            if (CollUtil.isEmpty(itemList)) {
                continue;
            }
            for (int i = 1; i <= itemList.size(); i++) {
                tableColumns.add(i);
                // 获取对应对象
                QmsInspectionPurchaseItemResultRecordEntity item = itemList.get(i - 1);
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

    private String getCheckTypeName(QmsInspectionPurchaseTaskDetailEntity detail) {
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
        QmsInspectionPurchaseTaskEntity task = this.getOne(
                new LambdaQueryWrapper<QmsInspectionPurchaseTaskEntity>()
                        .eq(QmsInspectionPurchaseTaskEntity::getId, dto.getId()).last("FOR UPDATE"), false);
        if (task == null) {
            throw new RuntimeException("来料质检任务不存在");
        }
        if (!InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult())
                || !InspectionTaskStatusEnum.UNHANDLED.name().equals(task.getStatus())) {
            throw new RuntimeException("只有待处置的不合格来料质检任务可以处置");
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
        QmsInspectionPurchaseHandleRecordEntity existing = handleRecordService.getOne(
                new LambdaQueryWrapper<QmsInspectionPurchaseHandleRecordEntity>()
                        .eq(QmsInspectionPurchaseHandleRecordEntity::getRequestId, requestId), false);
        if (existing != null) {
            if (Objects.equals(existing.getTaskId(), dto.getId())
                    && Objects.equals(existing.getHandleNum(), dto.getHandleNum())
                    && Objects.equals(existing.getHandleResult(), dto.getHandleResult())) {
                return;
            }
            throw new RuntimeException("处置请求号已被其他业务使用");
        }
        task.setHandleNum(Objects.requireNonNullElse(task.getHandleNum(), 0) + dto.getHandleNum());
        if (task.getHandleNum() > task.getNum()) {
            throw new RuntimeException("处置数量不能大于未处置数量");
        }
        if (HandleResultEnum.CONCESSION.name().equals(dto.getHandleResult()) || HandleResultEnum.RELEASE.name().equals(dto.getHandleResult())) {
            task.setUsableNum(Objects.requireNonNullElse(task.getUsableNum(), 0) + dto.getHandleNum());
        }
        if (HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult())) {
            // 入不合格品库
            badInfoService.add(new QmsBadInfoEntity().setResourceTaskId(task.getId()).setResourceType(InspectionTaskTypeEnum.PURCHASE.name())
                    .setRequestId("DISPOSITION:" + requestId)
                    .setNum(dto.getHandleNum() == null ? null : java.math.BigDecimal.valueOf(dto.getHandleNum())).setMatCode(task.getMatCode()).setMatName(task.getMatName())
                    .setSupplierCode(task.getSupplierCode()).setSupplierName(task.getSupplierName())
                    .setBatchNo(task.getBatchNo()));
        }
        this.updateById(task);

        if (StrUtil.isNotBlank(task.getTaskCode())) {
            // 调用采购收料合格品回馈接口
            this.pmsReceiptHandler(task, dto.getHandleNum());
        }

        // 保存处理明细
        QmsInspectionPurchaseHandleRecordEntity record = new QmsInspectionPurchaseHandleRecordEntity();
        MomUser user = SecurityUtils.getUser();
        record.setRequestId(requestId).setHandleNum(dto.getHandleNum()).setHandleRemark(dto.getHandleRemark().trim()).setHandlePersonId(user.getId())
                .setHandlePersonName(user.getName()).setHandleTime(new Date()).setHandleResult(dto.getHandleResult())
                .setTaskId(task.getId()).setStatus(HandleResultEnum.UNQUALIFIED.name().equals(dto.getHandleResult())
                        || HandleResultEnum.SMASH.name().equals(dto.getHandleResult()));
        if (HandleResultEnum.CONCESSION.name().equals(dto.getHandleResult()) || HandleResultEnum.RELEASE.name().equals(dto.getHandleResult())) {
            // TODO 确定是否自动调用仓库接口 如果需要则加逻辑 如果不需要直接去掉
            // 如果是手动创建的，则不需要处理
            if (StrUtil.isEmpty(task.getTaskCode())) {
                record.setStatus(Boolean.TRUE);
            }
        }
        handleRecordService.save(record);

        List<QmsInspectionPurchaseHandleRecordEntity> dispositions = handleRecordService.list(
                new LambdaQueryWrapper<QmsInspectionPurchaseHandleRecordEntity>()
                        .eq(QmsInspectionPurchaseHandleRecordEntity::getTaskId, task.getId()));
        task.setHandleResult(dispositions.stream().map(QmsInspectionPurchaseHandleRecordEntity::getHandleResult)
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
    }

    /**
     * 胜场返工返修工单
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleRework(Long id) {
        QmsInspectionPurchaseHandleRecordEntity record = handleRecordService.getById(id);
        QmsInspectionPurchaseTaskEntity task = this.getById(record.getTaskId());
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
        try {
            remoteMesTaskService.add(dto);

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
    public QmsInspectionPurchaseResultDTO queryById(Long id) {
        QmsInspectionPurchaseResultDTO dto = new QmsInspectionPurchaseResultDTO();
        QmsInspectionPurchaseTaskEntity task = this.getById(id);
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
        TemplateExportParams params = new TemplateExportParams(StrUtil.format("templates/purchaseReport1.xlsx"));
        // 开启横向遍历 开启横向遍历 开启横向遍历
        params.setColForEach(true);
        Map<String, Object> map = new HashMap<String, Object>();
        QmsInspectionPurchaseTaskEntity qmsInspectionPurchaseTaskEntity = this.getById(taskId);
        if (Objects.isNull(qmsInspectionPurchaseTaskEntity)) {
            throw new RuntimeException("信息有误，导出失败！");
        }
        // 构造打印主体数据
        map.put("matCode", qmsInspectionPurchaseTaskEntity.getMatCode());
        map.put("matName", qmsInspectionPurchaseTaskEntity.getMatName());
        map.put("supplierCode", qmsInspectionPurchaseTaskEntity.getSupplierCode());
        map.put("supplierName", qmsInspectionPurchaseTaskEntity.getSupplierName());
        map.put("batchNo", qmsInspectionPurchaseTaskEntity.getBatchNo());
        map.put("standardName", qmsInspectionPurchaseTaskEntity.getStandardName());
        map.put("num", qmsInspectionPurchaseTaskEntity.getNum());
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        map.put("latestInspectionTime", simpleDateFormat.format(qmsInspectionPurchaseTaskEntity.getLatestInspectionTime()));
        map.put("status", "QUALIFIED".equals(qmsInspectionPurchaseTaskEntity.getResult()) ? "合格" : "不合格");
        // 构造 竖向打印数据
        List<QmsInspectionPurchaseTaskDetailEntity> list = purchaseTaskDetailService.lambdaQuery()
                .eq(QmsInspectionPurchaseTaskDetailEntity::getTaskId, taskId).list();
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
                new String("采购质检报表.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

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
                                       List<QmsInspectionPurchaseTaskDetailEntity> list, Integer num) {
        Integer key = 0;
        List<Integer> tableColumns = new ArrayList<>();
        for (QmsInspectionPurchaseTaskDetailEntity detail : list) {
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
            List<QmsInspectionPurchaseItemResultRecordEntity> itemList = recordService.list(new LambdaQueryWrapper<QmsInspectionPurchaseItemResultRecordEntity>()
                    .eq(QmsInspectionPurchaseItemResultRecordEntity::getDetailId, detail.getId()));
            for (int i = 1; i <= 10; i++) {
                tableColumns.add((num - 1) * 10 + i);
                // 获取对应对象
                Integer currentInfo = (num - 1) * 10 + i - 1;
                if (currentInfo < itemList.size()) {
                    QmsInspectionPurchaseItemResultRecordEntity item = itemList.get((num - 1) * 10 + i - 1);
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

//	public MdmMat matInfo(String matCode) {
//		return mdmMatMapper.matInfo(matCode);
//	}
//
//	public void addInNotice(AddInBillNoticeReq inspectionProductDTO, HttpServletRequest request) {
//		QmsInspectionPurchaseTask purchaseTask = Optional
//				.ofNullable(purchaseTaskService.getById(inspectionProductDTO.getId()))
//				.orElseThrow(() -> new JeecgBootException("查询成品质检任务失败！"));
//		// 对入库通知单数量进行校验，入库数量不能大于来料数量
//		BigDecimal inBillNum = inspectionProductDTO.getDetails().get(0).getNoticeInNum();
//		BigDecimal allNum = BigDecimal.valueOf(purchaseTask.getNum());
//		if (inBillNum.compareTo(allNum) > 0) {
//			throw new JeecgBootException("通知入库数量不能大于来料数量！");
//		}
//		// 修改任务状态改为已处理
//		purchaseTask.setStatus(InspectionTaskStatusEnum.PROCESSED.name());
//		// 记录通知入库数量
//		purchaseTask.setNoticeInBillNum(inBillNum);
//		purchaseTaskService.updateById(purchaseTask);
//		// 向wms模块推送可入库数量
//		serviceToWms.buildPurchaseParam(inspectionProductDTO, inBillNum, purchaseTask.getSupplierCode(),
//				purchaseTask.getSupplierName(), purchaseTask.getId(), request);
//	}

}
