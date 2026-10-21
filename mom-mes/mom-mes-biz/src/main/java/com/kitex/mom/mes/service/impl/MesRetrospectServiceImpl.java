package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.api.feign.RemoteBarcodeService;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.enums.WrapTypeEnum;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.api.feign.RemotePurchaseService;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.wms.api.feign.RemoteContainerBarcodeService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsLiveStockQueryDTO;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 生产追溯
 *
 * @author kitex
 * @since 2025-04-24
 */
@Service
@AllArgsConstructor
@PreAuthorize("@pms.hasPermission('mes_traceEvidence_view')")
public class MesRetrospectServiceImpl implements MesRetrospectService {

    private final RemoteMdmFactoryAccessService factoryAccess;
    private final ThreadLocal<ReadBudget> readBudget = new ThreadLocal<>();
    private final MesTaskService mesTaskService;
    private final MesProduceTaskService mesProduceTaskService;
    private final MesTaskArrangeService mesTaskArrangeService;
    private final MesTaskReportService mesTaskReportService;
    private final MesWarehouseFeedService feedService;
    private final MesWarehouseInoutService mesWarehouseInoutService;
    private final RemotePurchaseService remotePurchaseService;
    private final RemoteProcedureService remoteProcedureService;
    private final RemoteLiveStockService remoteLiveStockService;
    private final RemoteBarcodeService remoteBarcodeService;
    private final RemoteContainerBarcodeService remoteContainerBarcodeService;
    private final MesWrapService mesWrapService;
    private final MesWarehouseLiveStockService mesWarehouseLiveStockService;
    private final MesInspectionGenRecordService genRecordService;

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    /**
     * 正向追溯
     * 根据产品编码追溯生产过程中的相关信息
     *
     * @param retrospectReqVo 追溯请求参数
     * @return 追溯结果
     */
    @Override
    public RetrospectVo forward(RetrospectReqVo retrospectReqVo) {
        return guarded(retrospectReqVo, () -> forwardScoped(retrospectReqVo));
    }

    private RetrospectVo forwardScoped(RetrospectReqVo retrospectReqVo) {
        // 1. 验证产品编码并获取物料信息
        MesWrapEntity wrap = validateAndGetWarp(retrospectReqVo.getCode(), WrapTypeEnum.PRODUCT.name());

        WmsContainerBarcodeEntity barcodeEntity = remote(() -> remoteContainerBarcodeService.queryBarcode(retrospectReqVo.getCode()), "WMS箱码");
        if (barcodeEntity != null) scope(barcodeEntity, barcodeEntity.getFactoryCode());

        if (Objects.isNull(wrap) && Objects.isNull(barcodeEntity)) {
            throw new RuntimeException("箱码不存在");
        }
        List<MesTaskEntity> taskList = new ArrayList<>();
        if (Objects.nonNull(wrap)) {
            List<MesTaskEntity> wrapTaskList = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                    .eq(MesTaskEntity::getCode, wrap.getTaskCode()));
            bounded(wrapTaskList);
            wrapTaskList.forEach(row -> scope(row, row.getFactoryCode()));
            taskList.addAll(wrapTaskList);
        }

        if (Objects.nonNull(barcodeEntity)) {
            List<MesTaskArrangeEntity> arranges = mesTaskArrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                    .eq(MesTaskArrangeEntity::getWrapCode, retrospectReqVo.getCode()));
            bounded(arranges);
            arranges.forEach(row -> scope(row, row.getFactoryCode()));
            List<String> taskCodes = arranges.stream().map(MesTaskArrangeEntity::getTaskCode).toList();
            if (CollUtil.isNotEmpty(taskCodes)) {
                List<MesTaskEntity> barcodeTaskList = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                        .in(MesTaskEntity::getCode, taskCodes));
                bounded(barcodeTaskList);
                barcodeTaskList.forEach(row -> scope(row, row.getFactoryCode()));
                taskList.addAll(barcodeTaskList);
            }
        }
        taskList = taskList.stream().distinct().toList();

        // 3. 在当前授权线程内处理工单
        List<RetrospectVo> results = processTasksInScope(taskList);

        // 4. 合并所有工单的结果
        return mergeResults(results);
    }

    /**
     * 反向追溯
     * 根据条码信息追溯生产过程中的相关信息
     *
     * @param retrospectVo 追溯请求参数
     * @return 追溯结果
     */
    @Override
    public RetrospectVo reverse(RetrospectReqVo retrospectVo) {
        return guarded(retrospectVo, () -> reverseScoped(retrospectVo));
    }

    private RetrospectVo reverseScoped(RetrospectReqVo retrospectVo) {

        // 1. 解析条码信息
        BarcodeInfoDTO barcodeInfo = parseBarcodeInfo(retrospectVo);

        // 2. 查询相关工单
        List<MesTaskEntity> taskList = queryRelatedTasks(barcodeInfo);

        // 3. 在当前授权线程内处理工单
        List<RetrospectVo> results = processTasksInScope(taskList);

        // 4. 合并所有工单的结果
        return mergeResults(results);
    }

    /**
     * 半成品正向追溯
     * 根据产品编码追溯生产过程中的相关信息
     *
     * @param retrospectReqVo 追溯请求参数
     * @return 追溯结果
     */
    @Override
    public RetrospectVo halfForward(RetrospectReqVo retrospectReqVo) {
        return guarded(retrospectReqVo, () -> halfForwardScoped(retrospectReqVo));
    }

    private RetrospectVo halfForwardScoped(RetrospectReqVo retrospectReqVo) {
        // 1. 验证产品编码并获取物料信息
        MesWrapEntity wrap = validateAndGetWarp(retrospectReqVo.getCode(), WrapTypeEnum.HALF.name());
        require(wrap != null, "半成品箱码不存在或无授权事实");

        // 找到半成品零件计划生产工单
        MesTaskEntity halfFinishTask = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesTaskEntity::getCode, wrap.getTaskCode()));
        require(halfFinishTask != null, "半成品箱码缺少原工单事实");

        List<String> queryTaskCodes = new ArrayList<>();
        queryTaskCodes.add(halfFinishTask.getCode());

        // 获取所有派工任务
        List<MesTaskArrangeEntity> arranges = mesTaskArrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesTaskArrangeEntity::getTaskCode, halfFinishTask.getCode()));
        bounded(arranges);
        arranges.forEach(row -> scope(row, row.getFactoryCode()));

        if (CollUtil.isEmpty(arranges)) {
            throw new RuntimeException("未查询到数据");
        }

        List<String> arrangeTaskCodes = arranges.stream().map(MesTaskArrangeEntity::getCode).distinct().toList();

        // 找到半成品库存
        List<MesWarehouseLiveStockEntity> liveStockEntityList = mesWarehouseLiveStockService.list(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .in(MesWarehouseLiveStockEntity::getBillNo, arrangeTaskCodes));
        bounded(liveStockEntityList);
        liveStockEntityList.forEach(row -> scope(row, row.getFactoryCode()));

        if (CollUtil.isEmpty(liveStockEntityList)) {
            throw new RuntimeException("未查找到数据");
        }

        // 根据库存拿到物料条码
        require(liveStockEntityList.stream().allMatch(s -> nonblank(s.getMatBarcode())), "历史半成品库存缺少原条码，不能用当前余量拼造来源");
        List<String> materialCodes = liveStockEntityList.stream()
                .map(MesWarehouseLiveStockEntity::getMatBarcode)
                .distinct()
                .toList();

        // 根据物料条码找到他投到那些成品计划生产工单中
        List<MesWarehouseFeedEntity> feedList = feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .in(MesWarehouseFeedEntity::getMatBarcode, materialCodes));
        bounded(feedList);
        feedList.forEach(row -> scope(row, row.getFactoryCode()));

        List<String> feedTaskCodes = feedList.stream().map(MesWarehouseFeedEntity::getTaskCode).distinct().toList();
        queryTaskCodes.addAll(feedTaskCodes);

        // 2. 查询相关工单
        List<MesTaskEntity> taskList = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .in(MesTaskEntity::getCode, queryTaskCodes));
        bounded(taskList);
        taskList.forEach(row -> scope(row, row.getFactoryCode()));

        // 3. 在当前授权线程内处理工单
        List<RetrospectVo> results = processTasksInScope(taskList);

        // 4. 合并所有工单的结果
        return mergeResults(results);
    }

    /**
     * 半成品反向追溯
     * 根据产品编码追溯生产过程中的相关信息
     *
     * @param retrospectReqVo 追溯请求参数
     * @return 追溯结果
     */
    @Override
    public RetrospectVo halfReverse(RetrospectReqVo retrospectReqVo) {
        return guarded(retrospectReqVo, () -> reverseScoped(retrospectReqVo));
    }

    /**
     * 验证产品编码并获取箱码
     *
     * @param code 箱码
     * @return 箱码
     */
    private MesWrapEntity validateAndGetWarp(String code, String wrapType) {
        return mesWrapService.getOne(new LambdaQueryWrapper<MesWrapEntity>()
                .eq(MesWrapEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesWrapEntity::getWrapCode, code)
                .eq(MesWrapEntity::getWrapType, wrapType));
    }

    /**
     * 根据物料编码查询相关工单
     *
     * @param materialCode 物料编码
     * @return 工单列表
     */
    private List<MesTaskEntity> queryTasksByMaterial(String materialCode) {
        return mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesTaskEntity::getMatCode, materialCode));
    }

    /**
     * 处理单个工单
     *
     * @param task 工单信息
     * @return 工单处理结果
     */
    private RetrospectVo processSingleTask(MesTaskEntity task) {
        scope(task, task.getFactoryCode());
        checkBudget();
        List<MatRetrospectDTO> mat = new ArrayList<>();
        List<MesRetrospectDTO> mes = new ArrayList<>();
        List<QmsRetrospectDTO> qms = new ArrayList<>();
        List<ProductRetrospectDTO> product = new ArrayList<>();
        List<RetrospectVo.FlowDataDTO> flowDataList = new ArrayList<>();
        List<LogisticsDTO> logistics = new ArrayList<>();

        // 查询工序任务
        List<MesProduceTaskEntity> produceTaskList = mesProduceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesProduceTaskEntity::getTaskCode, task.getCode()));
        bounded(produceTaskList);
        produceTaskList.forEach(row -> scope(row, row.getFactoryCode()));

        // 查询派工任务
        List<MesTaskArrangeEntity> arrangeList = mesTaskArrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesTaskArrangeEntity::getTaskCode, task.getCode()));
        bounded(arrangeList);
        arrangeList.forEach(row -> scope(row, row.getFactoryCode()));
        Set<String> processCodes = new HashSet<>();
        produceTaskList.forEach(p -> {
            require(nonblank(p.getCode()) && processCodes.add(p.getCode()), "工序任务编码为空或重复");
        });
        require(arrangeList.stream().allMatch(a -> processCodes.contains(a.getProduceTaskCode())), "派工指向缺失或错误工序，不能返回完整生产汇总");

        // 处理每个派工任务
        arrangeList.forEach(arrange -> {
            processMaterialData(task, arrange, mat, qms);
            processProcessData(task, arrange, mes);
            processProductData(task, arrange, product);
            processProcedureTaskData(arrange, qms);
        });

        // 处理工序流程图
        processFlowData(produceTaskList, flowDataList);

        // 处理物流信息
        logistics = processLogisticsData(task.getCode());

        // 构建返回结果
        return buildRetrospectVo(mat, mes, qms, product, flowDataList, logistics);
    }

    /**
     * 构建追溯结果对象
     *
     * @param mat          物料追溯列表
     * @param mes          工序追溯列表
     * @param qms          质检追溯列表
     * @param product      产品追溯列表
     * @param flowDataList 流程图数据列表
     * @return 追溯结果对象
     */
    private RetrospectVo buildRetrospectVo(List<MatRetrospectDTO> mat, List<MesRetrospectDTO> mes,
                                           List<QmsRetrospectDTO> qms, List<ProductRetrospectDTO> product,
                                           List<RetrospectVo.FlowDataDTO> flowDataList, List<LogisticsDTO> logistics) {
        RetrospectVo taskResult = new RetrospectVo();
        taskResult.setMat(mat.stream().distinct().toList());
        taskResult.setMes(mes);
        taskResult.setQms(qms);
        taskResult.setProduct(product);
        taskResult.setFlowData(flowDataList);
        taskResult.setLogistics(logistics);
        return taskResult;
    }

    /**
     * 处理物料数据
     *
     * @param task    工单信息
     * @param arrange 派工任务
     * @param mat     物料追溯列表
     * @param qms     质检追溯列表
     */
    private void processMaterialData(MesTaskEntity task, MesTaskArrangeEntity arrange, List<MatRetrospectDTO> mat, List<QmsRetrospectDTO> qms) {
        List<MesWarehouseFeedEntity> feedList = feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesWarehouseFeedEntity::getTaskCode, task.getCode())
                .eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode()));
        bounded(feedList);
        feedList.forEach(row -> scope(row, row.getFactoryCode()));

        require(feedList.stream().allMatch(f -> Objects.equals(f.getProduceTaskCode(), arrange.getProduceTaskCode())), "投料与派工工序归属不一致");

        processBackFlushMaterial(task, arrange, mat, qms);
        processFeedMaterial(task, feedList, mat, qms);
    }

    /**
     * 处理倒冲物料
     *
     * @param task    工单信息
     * @param arrange 派工任务
     * @param mat     物料追溯列表
     * @param qms     质检追溯列表
     */
    private void processBackFlushMaterial(MesTaskEntity task, MesTaskArrangeEntity arrange, List<MatRetrospectDTO> mat, List<QmsRetrospectDTO> qms) {
        {
            List<MesWarehouseInoutEntity> backFlushList = mesWarehouseInoutService.list(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                    .eq(MesWarehouseInoutEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                    .eq(MesWarehouseInoutEntity::getBillNo, arrange.getCode())
                    .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode()));
            bounded(backFlushList);
            backFlushList.forEach(row -> scope(row, row.getFactoryCode()));

            Map<List<String>, List<MesWarehouseInoutEntity>> backFlushGroupByBatch = backFlushList.stream()
                    .collect(Collectors.groupingBy(i -> Arrays.asList(i.getMatCode(), i.getMatBatch(), i.getSupplierCode())));

            backFlushGroupByBatch.forEach((key, inOuts) -> {
                String batchNo = key.get(1);
                if (!inOuts.isEmpty()) {
                    MesWarehouseInoutEntity inout = inOuts.get(0);
                    MatRetrospectDTO matDTO = createMatRetrospectDTOFromInout(task, inout, batchNo);
                    mat.add(matDTO);
                    processInspectionDataFromInout(inout, batchNo, qms);
                }
            });
        }
    }

    /**
     * 从入库出库记录创建物料追溯DTO
     *
     * @param task    工单信息
     * @param inout   入库出库记录
     * @param batchNo 批次号
     * @return 物料追溯DTO
     */
    private MatRetrospectDTO createMatRetrospectDTOFromInout(MesTaskEntity task, MesWarehouseInoutEntity inout, String batchNo) {
        MatRetrospectDTO matDTO = new MatRetrospectDTO();
        matDTO.setBatchNo(batchNo);
        matDTO.setTaskCode(task.getCode());
        matDTO.setMatName(inout.getMatName());
        matDTO.setMatCode(inout.getMatCode());
        matDTO.setSourceType("BACKFLUSH_HISTORY");
        matDTO.setSupplierCode(inout.getSupplierCode());
        matDTO.setSupplierName(inout.getSupplierName());
        matDTO.setMatProduceDate(inout.getMatProduceDate());
        matDTO.setMatValidEndDate(inout.getMatValidEndDate());
        return matDTO;
    }

    /**
     * 处理入库出库记录的质检数据
     *
     * @param inout   入库出库记录
     * @param batchNo 批次号
     * @param qms     质检追溯列表
     */
    private void processInspectionDataFromInout(MesWarehouseInoutEntity inout, String batchNo, List<QmsRetrospectDTO> qms) {
        QmsInspectionPurchaseTaskEntity inspectionTask = purchase(inout.getMatCode(), inout.getSupplierCode(), batchNo);
        if (Objects.nonNull(inspectionTask)) {
            QmsRetrospectDTO qmsDTO = new QmsRetrospectDTO();
            qmsDTO.setInspectionId(inspectionTask.getId());
            qmsDTO.setInspectionType(InspectionTaskTypeEnum.PURCHASE.name());
            qmsDTO.setInspectionResult(inspectionTask.getResult());
            qmsDTO.setItems(createInspectionItems(inspectionTask.getId()));
            qms.add(qmsDTO);
        }
    }

    /**
     * 处理投料物料
     *
     * @param task     工单信息
     * @param feedList 投料记录列表
     * @param mat      物料追溯列表
     * @param qms      质检追溯列表
     */
    private void processFeedMaterial(MesTaskEntity task, List<MesWarehouseFeedEntity> feedList, List<MatRetrospectDTO> mat, List<QmsRetrospectDTO> qms) {
        bounded(feedList);
        Map<List<String>, List<MesWarehouseFeedEntity>> feedGroupByBatch = feedList.stream()
                .collect(Collectors.groupingBy(f -> Arrays.asList(f.getMatCode(), f.getMatBatch(), f.getSupplierCode())));

        feedGroupByBatch.forEach((key, feeds) -> {
            String batchNo = key.get(1);
            if (!feeds.isEmpty()) {
                MesWarehouseFeedEntity firstFeed = feeds.get(0);
                MatRetrospectDTO matDTO = createMatRetrospectDTO(task, firstFeed, batchNo);
                mat.add(matDTO);
                processInspectionData(firstFeed, batchNo, qms);
            }
        });
    }

    /**
     * 创建物料追溯DTO
     *
     * @param task    工单信息
     * @param feed    投料记录
     * @param batchNo 批次号
     * @return 物料追溯DTO
     */
    private MatRetrospectDTO createMatRetrospectDTO(MesTaskEntity task, MesWarehouseFeedEntity feed, String batchNo) {
        MatRetrospectDTO matDTO = new MatRetrospectDTO();
        matDTO.setBatchNo(batchNo);
        matDTO.setTaskCode(task.getCode());
        matDTO.setMatName(feed.getMatName());
        matDTO.setMatCode(feed.getMatCode());
        matDTO.setSourceType("FEED_HISTORY");
        matDTO.setSupplierCode(feed.getSupplierCode());
        matDTO.setSupplierName(feed.getSupplierName());
        matDTO.setMatProduceDate(feed.getMatProduceDate());
        matDTO.setMatValidEndDate(feed.getMatValidEndDate());
        return matDTO;
    }

    /**
     * 处理质检数据
     *
     * @param feed    投料记录
     * @param batchNo 批次号
     * @param qms     质检追溯列表
     */
    private void processInspectionData(MesWarehouseFeedEntity feed, String batchNo, List<QmsRetrospectDTO> qms) {
        QmsInspectionPurchaseTaskEntity inspectionTask = purchase(feed.getMatCode(), feed.getSupplierCode(), batchNo);
        if (Objects.nonNull(inspectionTask)) {
            QmsRetrospectDTO qmsDTO = new QmsRetrospectDTO();
            qmsDTO.setInspectionId(inspectionTask.getId());
            qmsDTO.setInspectionType(InspectionTaskTypeEnum.PURCHASE.name());
            qmsDTO.setInspectionResult(inspectionTask.getResult());
            qmsDTO.setItems(createInspectionItems(inspectionTask.getId()));
            qms.add(qmsDTO);
        }
    }

    /**
     * 创建质检项列表
     *
     * @param inspectionTaskId 质检任务ID
     * @return 质检项列表
     */
    private List<QmsRetrospectDTO.InspectionItems> createInspectionItems(Long inspectionTaskId) {
        List<QmsRetrospectDTO.InspectionItems> items = new ArrayList<>();
        List<QmsInspectionPurchaseTaskDetailEntity> inspectionDetails = remote(() -> remotePurchaseService.findDetailsByTaskId(inspectionTaskId), "QMS来料检验明细");
        require(inspectionDetails != null, "QMS来料检验明细未返回明确列表");
        bounded(inspectionDetails);
        if (CollUtil.isNotEmpty(inspectionDetails)) {
            inspectionDetails.forEach(detail -> {
                scope(detail, detail.getFactoryCode());
                require(Objects.equals(detail.getTaskId(), inspectionTaskId), "QMS来料检验明细串单");
                QmsRetrospectDTO.InspectionItems item = new QmsRetrospectDTO.InspectionItems();
                item.setItemName(detail.getName());
                item.setItemDemand(detail.getDemand());
                item.setItemPosition(detail.getPosition());
                item.setItemResult(detail.getQualifiedStatus() == null ? "未判定" : detail.getQualifiedStatus() ? "合格" : "不合格");
                items.add(item);
            });
        }
        return items;
    }

    /**
     * 创建质检项列表
     *
     * @param inspectionTaskId 质检任务ID
     * @return 质检项列表
     */
    private List<QmsRetrospectDTO.InspectionItems> createProcedureInspectionItems(Long inspectionTaskId) {
        List<QmsRetrospectDTO.InspectionItems> items = new ArrayList<>();
        List<QmsInspectionProcedureTaskDetailEntity> inspectionDetails = remote(() -> remoteProcedureService.findDetailsByTaskId(inspectionTaskId), "QMS工序检验明细");
        require(inspectionDetails != null, "QMS工序检验明细未返回明确列表");
        bounded(inspectionDetails);
        if (CollUtil.isNotEmpty(inspectionDetails)) {
            inspectionDetails.forEach(detail -> {
                scope(detail, detail.getFactoryCode());
                require(Objects.equals(detail.getTaskId(), inspectionTaskId), "QMS工序检验明细串单");
                QmsRetrospectDTO.InspectionItems item = new QmsRetrospectDTO.InspectionItems();
                item.setItemName(detail.getName());
                item.setItemDemand(detail.getDemand());
                item.setItemPosition(detail.getPosition());
                item.setItemResult(detail.getQualifiedStatus() == null ? "未判定" : detail.getQualifiedStatus() ? "合格" : "不合格");
                items.add(item);
            });
        }
        return items;
    }

    /**
     * 处理工序数据
     *
     * @param task    工单信息
     * @param arrange 派工任务
     * @param mes     工序追溯列表
     */
    private void processProcessData(MesTaskEntity task, MesTaskArrangeEntity arrange, List<MesRetrospectDTO> mes) {
        MesRetrospectDTO mesRetrospectDTO = new MesRetrospectDTO();
        mesRetrospectDTO.setWorkshopName(task.getWorkshopName());
        mesRetrospectDTO.setRouteCode(arrange.getRouteCode());
        mesRetrospectDTO.setRouteName(arrange.getRouteName());
        mesRetrospectDTO.setWorksiteName(arrange.getActualWorksiteCode());
        List<MesTaskReportEntity> reportList = mesTaskReportService.list(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesTaskReportEntity::getArrangeCode, arrange.getCode()));
        bounded(reportList);
        reportList.forEach(row -> scope(row, row.getFactoryCode()));
        require(reportList.stream().allMatch(r -> Objects.equals(r.getTaskCode(), task.getCode()) && Objects.equals(r.getProduceTaskCode(), arrange.getProduceTaskCode())), "报工与工单/派工的工序归属不一致");
        String deviceNames = reportList.stream()
                .map(MesTaskReportEntity::getDeviceName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(","));
        String deviceCodes = reportList.stream()
                .map(MesTaskReportEntity::getDeviceCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(","));
        mesRetrospectDTO.setDeviceCode(deviceCodes);
        mesRetrospectDTO.setDeviceName(deviceNames);
        mesRetrospectDTO.setWorkerName(reportList.stream().map(MesTaskReportEntity::getWorkerName).filter(Objects::nonNull).distinct().collect(Collectors.joining(",")));
        mesRetrospectDTO.setFinishTime(Objects.nonNull(arrange.getFinishHour()) ? new BigDecimal(arrange.getFinishHour()) : null);
        mesRetrospectDTO.setBatchNo(arrange.getBatchNo());
        mes.add(mesRetrospectDTO);
    }

    /**
     * 处理产品数据
     *
     * @param task    工单信息
     * @param arrange 派工任务
     * @param product 产品追溯列表
     */
    private void processProductData(MesTaskEntity task, MesTaskArrangeEntity arrange, List<ProductRetrospectDTO> product) {
        if (Objects.nonNull(arrange.getLastRoute()) && arrange.getLastRoute()) {
            ProductRetrospectDTO productRetrospectDTO = new ProductRetrospectDTO();
            productRetrospectDTO.setBatchNo(arrange.getBatchNo());
            productRetrospectDTO.setMatSpec(task.getMatSpec());
            productRetrospectDTO.setMatName(task.getMatName());
            productRetrospectDTO.setNum(arrange.getCompleteNum());

            if (!nonblank(arrange.getBatchNo())) {
                readBudget.get().warnings.add("存在成品批次缺失，未查询或推测该派工的WMS库存。");
                product.add(productRetrospectDTO);
                return;
            }
            List<WmsLiveStockEntity> liveStock = remote(() -> remoteLiveStockService.getLiveStockList(new WmsLiveStockQueryDTO().setMatCode(arrange.getMatCode())), "WMS现存库存");
            require(liveStock != null, "WMS库存未返回明确列表");
            bounded(liveStock);
            liveStock.forEach(s -> scope(s, s.getFactoryCode()));
            if (CollUtil.isNotEmpty(liveStock)) {
                var matched = liveStock.stream().filter(item -> Objects.equals(arrange.getMatCode(), item.getMatCode()) && arrange.getBatchNo().equals(item.getMatBatch())).toList();
                // 旧DTO只能容纳一处库存位置；多处时保持未知，不能任选第一行冒充全部。
                Optional<WmsLiveStockEntity> stock = matched.size() == 1 ? Optional.of(matched.get(0)) : Optional.empty();
                stock.ifPresent(s -> {
                    productRetrospectDTO.setStorageLocation(createStorageLocation(s));
                    productRetrospectDTO.setStorageStatus(createStorageStatus(s));
                    productRetrospectDTO.setStoragePackage(createStoragePackage(arrange));
                });
            }
            product.add(productRetrospectDTO);
        }
    }

    /**
     * 获取中检任务列表
     *
     * @param arrange
     * @param qms
     */
    private void processProcedureTaskData(MesTaskArrangeEntity arrange, List<QmsRetrospectDTO> qms) {
        List<MesInspectionGenRecordEntity> records = genRecordService.list(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                .eq(MesInspectionGenRecordEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesInspectionGenRecordEntity::getProduceTaskCode, arrange.getProduceTaskCode()));
        bounded(records);
        records.forEach(row -> scope(row, row.getFactoryCode()));
        require(records.stream().allMatch(r -> Objects.equals(r.getTaskCode(), arrange.getTaskCode()) && r.getInspectionId() != null), "质检生成账缺少任务主键或工单归属不一致");
        List<Long> taskIds = records.stream().map(MesInspectionGenRecordEntity::getInspectionId).filter(Objects::nonNull).distinct().toList();
        if (CollUtil.isNotEmpty(taskIds)) {
            List<QmsInspectionProcedureTaskEntity> procedureTasks = remote(() -> remoteProcedureService.getTaskByIds(taskIds), "QMS工序检验任务");
            require(procedureTasks != null && procedureTasks.size() == taskIds.size() && procedureTasks.stream().map(QmsInspectionProcedureTaskEntity::getId).distinct().count() == taskIds.size(), "QMS工序检验任务返回不完整或重复");
            if (CollUtil.isNotEmpty(procedureTasks)) {
                procedureTasks.forEach(task -> {
                    scope(task, task.getFactoryCode());
                    require(taskIds.contains(task.getId()), "QMS工序检验返回未请求的任务");
                    QmsRetrospectDTO qmsDTO = new QmsRetrospectDTO();
                    qmsDTO.setInspectionId(task.getId());
                    qmsDTO.setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name());
                    qmsDTO.setInspectionResult(task.getResult());
                    qmsDTO.setItems(createProcedureInspectionItems(task.getId()));
                    qms.add(qmsDTO);
                });
            }
        }
    }

    /**
     * 创建存储位置信息
     *
     * @param stock 库存信息
     * @return 存储位置信息
     */
    private ProductRetrospectDTO.StorageLocation createStorageLocation(WmsLiveStockEntity stock) {
        ProductRetrospectDTO.StorageLocation location = new ProductRetrospectDTO.StorageLocation();
        location.setWarehouseName(stock.getWarehouseName());
        location.setWarehouseCode(stock.getWarehouseCode());
        location.setLocationCode(stock.getSiteCode());
        location.setLocationName(stock.getSiteName());
        location.setContainerCode(stock.getContainerCode());
        return location;
    }

    /**
     * 创建存储状态信息
     *
     * @param stock 库存信息
     * @return 存储状态信息
     */
    private ProductRetrospectDTO.StorageStatus createStorageStatus(WmsLiveStockEntity stock) {
        ProductRetrospectDTO.StorageStatus status = new ProductRetrospectDTO.StorageStatus();
        status.setWarehouseNum(stock.getMatNum());
        status.setWarehouseInTime(stock.getInTime());
        status.setStatus(stock.getStatus());
        status.setLapseTime(stock.getMatValidEndDate());
        return status;
    }

    /**
     * 创建存储包装信息
     *
     * @param arrange 派工任务
     * @return 存储包装信息
     */
    private ProductRetrospectDTO.StoragePackage createStoragePackage(MesTaskArrangeEntity arrange) {
        ProductRetrospectDTO.StoragePackage storagePackage = new ProductRetrospectDTO.StoragePackage();
        // 箱码不是包装人员身份；没有精确人员证据时保留空值。
        return storagePackage;
    }

    /**
     * 读取原实际任务图；只有完整节点合同才能展示真实依赖。
     * 历史列表及损坏图保留可观察的任务，不按主工序或排序猜测单件经过路径。
     *
     * @param produceTaskList 工序任务列表
     * @param flowDataList    流程图数据列表
     */
    private void processFlowData(List<MesProduceTaskEntity> produceTaskList, List<RetrospectVo.FlowDataDTO> flowDataList) {
        if (produceTaskList.isEmpty()) {
            return;
        }
        MesFrozenExecutionGraph graph = null;
        String graphState = "LEGACY";
        String graphIssue = "历史执行协议仅列实际任务，未据排序推断依赖关系";
        if (produceTaskList.stream().anyMatch(task -> task.getRouteExecutionSchemaVersion() != null)) {
            try {
                graph = MesFrozenExecutionGraph.of(produceTaskList);
                graphState = "NODE";
                graphIssue = null;
            } catch (CheckedException ex) {
                // 不能把坏图画成另一条合法路线；保留任务及明确缺口供人工追查。
                graphState = "INVALID";
                graphIssue = ex.getMessage();
            }
        }
        List<MesProduceTaskEntity> sortedProduceTasks = produceTaskList.stream()
                .sorted(Comparator.comparing(MesProduceTaskEntity::getRouteOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MesProduceTaskEntity::getCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (MesProduceTaskEntity currentTask : sortedProduceTasks) {
            RetrospectVo.FlowDataDTO flowDataDTO = new RetrospectVo.FlowDataDTO();
            flowDataDTO.setProduceTaskId(currentTask.getId() == null ? null : currentTask.getId().toString());
            flowDataDTO.setProduceTaskCode(currentTask.getCode());
            flowDataDTO.setTaskCode(currentTask.getTaskCode());
            flowDataDTO.setProcessRouteCode(currentTask.getProcessRouteCode());
            flowDataDTO.setSourceRouteNodeCode(currentTask.getSourceRouteNodeCode());
            flowDataDTO.setRouteExecutionSchemaVersion(currentTask.getRouteExecutionSchemaVersion());
            flowDataDTO.setGraphState(graphState);
            flowDataDTO.setGraphIssue(graphIssue);
            flowDataDTO.setPredecessorTaskCodes(graph == null ? List.of()
                    : graph.predecessors(currentTask).stream().map(MesProduceTaskEntity::getCode).sorted().toList());
            flowDataDTO.setRouteCode(currentTask.getRouteCode());
            flowDataDTO.setRouteName(currentTask.getRouteName());

            // 保留旧响应字段，避免将分叉汇合展开成重复的树节点。
            flowDataDTO.setChildren(List.of());
            flowDataList.add(flowDataDTO);
        }
    }

    /**
     * 合并所有任务的结果
     *
     * @param results 所有任务的结果列表
     * @return 合并后的结果
     */
    private RetrospectVo mergeResults(List<RetrospectVo> results) {
        RetrospectVo result = new RetrospectVo();
        result.setMat(results.stream()
                .flatMap(r -> r.getMat().stream())
                .distinct()
                .collect(Collectors.toList()));
        result.setMes(results.stream()
                .flatMap(r -> r.getMes().stream())
                .collect(Collectors.toList()));
        result.setQms(results.stream()
                .flatMap(r -> r.getQms().stream())
                .collect(Collectors.toList()));
        result.setProduct(results.stream()
                .flatMap(r -> r.getProduct().stream())
                .collect(Collectors.toList()));
        result.setFlowData(results.stream()
                .flatMap(r -> r.getFlowData().stream())
                .collect(Collectors.toList()));
        result.setLogistics(results.stream()
                .flatMap(r -> r.getLogistics().stream())
                .collect(Collectors.toList()));
        result.setEvidenceMode("LEGACY_SUMMARY");
        result.setWarnings(List.of("旧汇总包含投料、倒冲及报工历史，不代表逐报工净消耗或召回范围；请使用实际消耗工作台核验主键证据。",
                "物流未接入权威发运/签收，返回空列表；库存仅是同物料批次的当前参考，不证明原箱/工单来源，多处时不显示单一位置。",
                "来料检验复用现用任务查询，排除作废任务，不代表全部历史检验。",
                "工序图仅NODE状态展示原完整实际任务依赖，旧协议或证据缺口只列任务；这不是被查单件已执行路径，当前库存也不是历史库存快照。"));
        return result;
    }

    /**
     * 解析条码信息
     *
     * @param retrospectVo 追溯请求参数
     * @return 条码信息DTO
     */
    private BarcodeInfoDTO parseBarcodeInfo(RetrospectReqVo retrospectVo) {
        BarcodeInfoDTO parsed = Optional.ofNullable(remote(() -> remoteBarcodeService.rander(retrospectVo.getCode()), "物料条码解析"))
                .map(data -> {
                    try {
                        JSONObject jsonObject = JSON.parseObject(JSON.toJSONString(data));
                        return JSON.parseObject(JSON.toJSONString(jsonObject.get("data")), BarcodeInfoDTO.class);
                    } catch (Exception e) {
                        throw new RuntimeException("物料条码解析失败!");
                    }
                })
                .orElseThrow(() -> new RuntimeException("物料条码解析失败!"));
        require(parsed != null && nonblank(parsed.getMatCode()) && nonblank(parsed.getMatBatch()) && nonblank(parsed.getSupplierCode()), "条码缺少物料/批次/供应商，不扩大追溯范围");
        return parsed;
    }

    /**
     * 查询相关工单
     *
     * @param barcodeInfo 条码信息
     * @return 相关工单列表
     */
    private List<MesTaskEntity> queryRelatedTasks(BarcodeInfoDTO barcodeInfo) {
        // 查询投料记录
        List<MesWarehouseFeedEntity> barcodeFeedList = feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .eq(MesWarehouseFeedEntity::getMatCode, barcodeInfo.getMatCode())
                .eq(MesWarehouseFeedEntity::getMatBatch, barcodeInfo.getMatBatch())
                .eq(MesWarehouseFeedEntity::getSupplierCode, barcodeInfo.getSupplierCode()));
        bounded(barcodeFeedList);
        barcodeFeedList.forEach(row -> scope(row, row.getFactoryCode()));

        // 获取工单编码列表
        List<String> taskCodes = new ArrayList<>(barcodeFeedList.stream()
                .map(MesWarehouseFeedEntity::getTaskCode)
                .toList());
        // 倒冲-only的报工也必须进入使用范围，不受当前系统开关影响。
        var backflush = mesWarehouseInoutService.list(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                .eq(MesWarehouseInoutEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode())
                .eq(MesWarehouseInoutEntity::getMatCode, barcodeInfo.getMatCode()).eq(MesWarehouseInoutEntity::getMatBatch, barcodeInfo.getMatBatch())
                .eq(MesWarehouseInoutEntity::getSupplierCode, barcodeInfo.getSupplierCode()).last("LIMIT 1001"));
        bounded(backflush);
        backflush.forEach(i -> scope(i, i.getFactoryCode()));
        if (!backflush.isEmpty()) {
            require(backflush.stream().allMatch(i -> i.getBillId() != null), "倒冲历史缺少报工主键，不能猜工单");
            var ids = backflush.stream().map(MesWarehouseInoutEntity::getBillId).distinct().toList();
            var reports = mesTaskReportService.list(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).in(MesTaskReportEntity::getId, ids));
            bounded(reports);
            Map<Long, MesTaskReportEntity> byId = new HashMap<>();
            reports.forEach(r -> {
                scope(r, r.getFactoryCode());
                byId.put(r.getId(), r);
            });
            for (var i : backflush) {
                var r = byId.get(i.getBillId());
                require(r != null && nonblank(r.getRequestId()) && Objects.equals(r.getRequestId(), i.getResourceNo())
                        && Objects.equals(r.getArrangeCode(), i.getBillNo()), "倒冲来源报工/请求/派工不可核验");
                taskCodes.add(r.getTaskCode());
            }
        }
        require(taskCodes.stream().allMatch(this::nonblank), "投料/倒冲缺少原工单编码");

        if (CollUtil.isEmpty(taskCodes)) {
            throw new RuntimeException("暂未找到任何记录");
        }

        // 查询工单信息
        var resolved = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1001")
                .in(MesTaskEntity::getCode, taskCodes));
        require(resolved.size() == taskCodes.stream().distinct().count() && resolved.stream().map(MesTaskEntity::getCode).distinct().count() == resolved.size(), "来源工单缺失或编码歧义，禁止返回不完整汇总");
        return resolved;
    }

    /**
     * 在当前授权线程内处理工单
     *
     * @param taskList 工单列表
     * @return 处理结果列表
     */
    private List<RetrospectVo> processTasksInScope(List<MesTaskEntity> taskList) {
        if (CollUtil.isEmpty(taskList)) {
            throw new RuntimeException("暂未找到任何记录");
        }
        require(taskList.size() <= 100, "旧追溯涉及超过100个工单，请使用分页实际消耗工作台，不截断结果");
        return taskList.stream().map(this::processSingleTask).toList();
    }

    /**
     * 处理物流信息
     *
     * @param taskCode 工单编码
     * @return 物流追溯信息列表
     */
    private List<LogisticsDTO> processLogisticsData(String taskCode) {
        return List.of();
    }

    private RetrospectVo guarded(RetrospectReqVo request, Supplier<RetrospectVo> work) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null && nonblank(FactoryContextHolder.getFactoryCode()), "旧追溯须登录并选择实际工厂，不能跳过隔离");
        require(request != null && nonblank(request.getCode()) && request.getCode().length() <= 500, "请输入1至500字完整条码");
        require(!nonblank(request.getStartDate()) && !nonblank(request.getEndDate()), "旧条码追溯尚不支持日期过滤，不能静默忽略日期条件");
        var access = factoryAccess.check(new MdmFactoryAccessCheck().setUserId(SecurityUtils.getUser().getId()).setFactoryCode(FactoryContextHolder.getFactoryCode()).setRequiredPermission("read"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()), "缺少实际工厂读取权限或MDM不可核验");
        require(readBudget.get() == null, "不支持嵌套旧追溯调用");
        readBudget.set(new ReadBudget());
        try {
            var result = work.get();
            checkBudget();
            var warnings = new ArrayList<>(result.getWarnings() == null ? List.<String>of() : result.getWarnings());
            warnings.addAll(readBudget.get().warnings);
            result.setWarnings(warnings.stream().distinct().toList());
            return result;
        } finally {
            readBudget.remove();
        }
    }

    private void checkBudget() {
        var b = readBudget.get();
        require(b != null && System.nanoTime() < b.deadline, "旧追溯读取超过30秒，请改用分页证据工作台；不返回部分成功");
    }

    private <T> T remote(Supplier<R<T>> call, String name) {
        checkBudget();
        require(++readBudget.get().calls <= 200, "旧追溯远端调用超过200次，请使用分页证据工作台");
        var r = call.get();
        checkBudget();
        require(r != null && r.isOk(), name + "不可核验，不以失败冒充空结果");
        return r.getData();
    }

    private void scope(BaseEntity<?> row, String factory) {
        require(row != null && Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId()) && Objects.equals(factory, FactoryContextHolder.getFactoryCode()), "追溯事实不在授权租户/实际工厂");
    }

    private void bounded(List<?> rows) {
        require(rows != null && rows.size() <= 1000, "旧汇总证据超过1000条或未返回明确列表，请使用分页工作台；不截断事实");
        checkBudget();
    }

    private boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private QmsInspectionPurchaseTaskEntity purchase(String mat, String supplier, String batch) {
        if (!nonblank(mat) || !nonblank(supplier) || !nonblank(batch)) {
            readBudget.get().warnings.add("部分物料历史缺少物料/供应商/批次，未查询其来料检验；不代表无需检验或已合格。");
            return null;
        }
        var result = remote(() -> remotePurchaseService.getTaskByMatCodeAndSupplierCodeAndBatchNo(mat, supplier, batch), "QMS来料检验任务");
        if (result != null) {
            scope(result, result.getFactoryCode());
            require(Objects.equals(mat, result.getMatCode()) && Objects.equals(supplier, result.getSupplierCode()) && Objects.equals(batch, result.getBatchNo()), "QMS来料检验与请求来源不一致");
        }
        return result;
    }

    private static class ReadBudget {
        long deadline = System.nanoTime() + 30_000_000_000L;
        int calls;
        List<String> warnings = new ArrayList<>();
    }
}
