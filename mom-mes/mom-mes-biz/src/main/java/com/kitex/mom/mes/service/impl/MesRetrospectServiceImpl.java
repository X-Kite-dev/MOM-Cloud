package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.mom.common.barcode.api.feign.RemoteBarcodeService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.enums.WrapTypeEnum;
import com.kitex.mom.mes.service.*;
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
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 生产追溯
 *
 * @author kitex
 * @since 2025-04-24
 */
@Service
@AllArgsConstructor
public class MesRetrospectServiceImpl implements MesRetrospectService {

    private final RemoteMaterialService mdmMatService;
    private final MesTaskService mesTaskService;
    private final MesProduceTaskService mesProduceTaskService;
    private final MesTaskArrangeService mesTaskArrangeService;
    private final MesTaskReportService mesTaskReportService;
    private final MesWarehouseFeedService feedService;
    private final RemoteParamService remoteParamService;
    private final MesWarehouseInoutService mesWarehouseInoutService;
    private final RemotePurchaseService remotePurchaseService;
    private final RemoteProcedureService remoteProcedureService;
    private final RemoteLiveStockService remoteLiveStockService;
    private final RemoteBarcodeService remoteBarcodeService;
    private final RemoteContainerBarcodeService remoteContainerBarcodeService;
    private final MesWrapService mesWrapService;
    private final MesWarehouseLiveStockService mesWarehouseLiveStockService;
    private final MesInspectionGenRecordService genRecordService;

    /**
     * 正向追溯
     * 根据产品编码追溯生产过程中的相关信息
     *
     * @param retrospectReqVo 追溯请求参数
     * @return 追溯结果
     */
    @Override
    public RetrospectVo forward(RetrospectReqVo retrospectReqVo) {
        // 1. 验证产品编码并获取物料信息
        MesWrapEntity wrap = validateAndGetWarp(retrospectReqVo.getCode(), WrapTypeEnum.PRODUCT.name());

        WmsContainerBarcodeEntity barcodeEntity = remoteContainerBarcodeService.queryBarcode(retrospectReqVo.getCode()).getData();

        if (Objects.isNull(wrap) && Objects.isNull(barcodeEntity)) {
            throw new RuntimeException("箱码不存在");
        }
        List<MesTaskEntity> taskList = new ArrayList<>();
        if (Objects.nonNull(wrap)) {
            List<MesTaskEntity> wrapTaskList = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getCode, wrap.getTaskCode()));
            taskList.addAll(wrapTaskList);
        }

        if (Objects.nonNull(barcodeEntity)) {
            List<MesTaskArrangeEntity> arranges = mesTaskArrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getWrapCode, retrospectReqVo.getCode()));
            List<String> taskCodes = arranges.stream().map(MesTaskArrangeEntity::getTaskCode).toList();
            if (CollUtil.isNotEmpty(taskCodes)) {
                List<MesTaskEntity> barcodeTaskList = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                        .in(MesTaskEntity::getCode, taskCodes));
                taskList.addAll(barcodeTaskList);
            }
        }
        taskList = taskList.stream().distinct().toList();

        // 3. 并行处理每个工单
        List<RetrospectVo> results = processTasksInParallel(taskList);

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

        // 1. 解析条码信息
        BarcodeInfoDTO barcodeInfo = parseBarcodeInfo(retrospectVo);

        // 2. 查询相关工单
        List<MesTaskEntity> taskList = queryRelatedTasks(barcodeInfo);

        // 3. 并行处理每个工单
        List<RetrospectVo> results = processTasksInParallel(taskList);

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
        // 1. 验证产品编码并获取物料信息
        MesWrapEntity wrap = validateAndGetWarp(retrospectReqVo.getCode(), WrapTypeEnum.HALF.name());

        // 找到半成品零件计划生产工单
        MesTaskEntity halfFinishTask = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, wrap.getTaskCode()));

        List<String> queryTaskCodes = new ArrayList<>();
        queryTaskCodes.add(halfFinishTask.getCode());

        // 获取所有派工任务
        List<MesTaskArrangeEntity> arranges = mesTaskArrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTaskCode, halfFinishTask.getCode()));

        if (CollUtil.isEmpty(arranges)) {
            throw new RuntimeException("未查询到数据");
        }

        List<String> arrangeTaskCodes = arranges.stream().map(MesTaskArrangeEntity::getCode).distinct().toList();

        // 找到半成品库存
        List<MesWarehouseLiveStockEntity> liveStockEntityList = mesWarehouseLiveStockService.list(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .in(MesWarehouseLiveStockEntity::getBillNo, arrangeTaskCodes));

        if (CollUtil.isEmpty(liveStockEntityList)) {
            throw new RuntimeException("未查找到数据");
        }

        // 根据库存拿到物料条码
        List<String> materialCodes = liveStockEntityList.stream()
                .map(entity -> String.join("/",
                        entity.getMatBatch(),
                        entity.getMatCode(),
                        entity.getSupplierCode(),
                        entity.getNum().toString()))
                .distinct()
                .toList();

        // 根据物料条码找到他投到那些成品计划生产工单中
        List<MesWarehouseFeedEntity> feedList = feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .in(MesWarehouseFeedEntity::getMatBarcode, materialCodes));

        List<String> feedTaskCodes = feedList.stream().map(MesWarehouseFeedEntity::getTaskCode).distinct().toList();
        queryTaskCodes.addAll(feedTaskCodes);

        // 2. 查询相关工单
        List<MesTaskEntity> taskList = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .in(MesTaskEntity::getCode, queryTaskCodes));

        // 3. 并行处理每个工单
        List<RetrospectVo> results = processTasksInParallel(taskList);

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
        // 1. 解析条码信息
        BarcodeInfoDTO barcodeInfo = parseBarcodeInfo(retrospectReqVo);

        // 2. 查询相关工单
        List<MesTaskEntity> taskList = queryRelatedTasks(barcodeInfo);

        // 3. 并行处理每个工单
        List<RetrospectVo> results = processTasksInParallel(taskList);

        // 4. 合并所有工单的结果
        return mergeResults(results);
    }

    /**
     * 验证产品编码并获取箱码
     *
     * @param code 箱码
     * @return 箱码
     */
    private MesWrapEntity validateAndGetWarp(String code, String wrapType) {
        return mesWrapService.getOne(new LambdaQueryWrapper<MesWrapEntity>()
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
                .eq(MesTaskEntity::getMatCode, materialCode));
    }

    /**
     * 处理单个工单
     *
     * @param task 工单信息
     * @return 工单处理结果
     */
    private CompletableFuture<RetrospectVo> processSingleTask(MesTaskEntity task) {
        ServletRequestAttributes servletRequestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return CompletableFuture.supplyAsync(() -> {
            RequestContextHolder.setRequestAttributes(servletRequestAttributes, true);
            List<MatRetrospectDTO> mat = new ArrayList<>();
            List<MesRetrospectDTO> mes = new ArrayList<>();
            List<QmsRetrospectDTO> qms = new ArrayList<>();
            List<ProductRetrospectDTO> product = new ArrayList<>();
            List<RetrospectVo.FlowDataDTO> flowDataList = new ArrayList<>();
            List<LogisticsDTO> logistics = new ArrayList<>();

            // 查询工序任务
            List<MesProduceTaskEntity> produceTaskList = mesProduceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                    .eq(MesProduceTaskEntity::getTaskCode, task.getCode()));

            // 查询派工任务
            List<MesTaskArrangeEntity> arrangeList = mesTaskArrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTaskCode, task.getCode()));

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
        });
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
                .eq(MesWarehouseFeedEntity::getTaskCode, task.getCode())
                .eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode()));

        if (CollUtil.isEmpty(feedList)) {
            processBackFlushMaterial(task, arrange, mat, qms);
        } else {
            processFeedMaterial(task, feedList, mat, qms);
        }
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
        String backFlushFlag = remoteParamService.getByKey("BACK_FLUSH").getData();
        if ("1".equals(backFlushFlag)) {
            List<MesWarehouseInoutEntity> backFlushList = mesWarehouseInoutService.list(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                    .eq(MesWarehouseInoutEntity::getBillNo, arrange.getCode())
                    .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode()));

            Map<String, List<MesWarehouseInoutEntity>> backFlushGroupByBatch = backFlushList.stream()
                    .collect(Collectors.groupingBy(MesWarehouseInoutEntity::getMatBatch));

            backFlushGroupByBatch.forEach((batchNo, inOuts) -> {
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
        QmsInspectionPurchaseTaskEntity inspectionTask = remotePurchaseService
                .getTaskByMatCodeAndSupplierCodeAndBatchNo(inout.getMatCode(), inout.getSupplierCode(), batchNo).getData();
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
        Map<String, List<MesWarehouseFeedEntity>> feedGroupByBatch = feedList.stream()
                .collect(Collectors.groupingBy(MesWarehouseFeedEntity::getMatBatch));

        feedGroupByBatch.forEach((batchNo, feeds) -> {
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
        QmsInspectionPurchaseTaskEntity inspectionTask = remotePurchaseService
                .getTaskByMatCodeAndSupplierCodeAndBatchNo(feed.getMatCode(), feed.getSupplierCode(), batchNo).getData();
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
        List<QmsInspectionPurchaseTaskDetailEntity> inspectionDetails = remotePurchaseService.findDetailsByTaskId(inspectionTaskId).getData();
        if (CollUtil.isNotEmpty(inspectionDetails)) {
            inspectionDetails.forEach(detail -> {
                QmsRetrospectDTO.InspectionItems item = new QmsRetrospectDTO.InspectionItems();
                item.setItemName(detail.getName());
                item.setItemDemand(detail.getDemand());
                item.setItemPosition(detail.getPosition());
                item.setItemResult(detail.getQualifiedStatus() ? "合格" : "不合格");
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
        List<QmsInspectionProcedureTaskDetailEntity> inspectionDetails = remoteProcedureService.findDetailsByTaskId(inspectionTaskId).getData();
        if (CollUtil.isNotEmpty(inspectionDetails)) {
            inspectionDetails.forEach(detail -> {
                QmsRetrospectDTO.InspectionItems item = new QmsRetrospectDTO.InspectionItems();
                item.setItemName(detail.getName());
                item.setItemDemand(detail.getDemand());
                item.setItemPosition(detail.getPosition());
                item.setItemResult(detail.getQualifiedStatus() ? "合格" : "不合格");
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
        mesRetrospectDTO.setWorksiteName(arrange.getWorksiteNames());
        List<MesTaskReportEntity> reportList = mesTaskReportService.list(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getArrangeCode, arrange.getCode()));
        String deviceNames = reportList.stream()
                .map(MesTaskReportEntity::getDeviceName)
                .distinct()
                .collect(Collectors.joining(","));
        String deviceCodes = reportList.stream()
                .map(MesTaskReportEntity::getDeviceCode)
                .distinct()
                .collect(Collectors.joining(","));
        mesRetrospectDTO.setDeviceCode(deviceCodes);
        mesRetrospectDTO.setDeviceName(deviceNames);
        mesRetrospectDTO.setWorkerName(arrange.getWorkerName());
        mesRetrospectDTO.setFinishTime(Objects.nonNull(arrange.getFinishHour()) ? new BigDecimal(arrange.getFinishHour()) : BigDecimal.ZERO);
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

            List<WmsLiveStockEntity> liveStock = remoteLiveStockService.getLiveStockList(new WmsLiveStockQueryDTO().setMatCode(arrange.getMatCode())).getData();
            if (CollUtil.isNotEmpty(liveStock)) {
                Optional<WmsLiveStockEntity> stock = liveStock.stream()
                        .filter(item -> arrange.getBatchNo().equals(item.getMatBatch()))
                        .findFirst();
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
                .eq(MesInspectionGenRecordEntity::getProduceTaskCode, arrange.getProduceTaskCode()));
        List<Long> taskIds = records.stream().map(MesInspectionGenRecordEntity::getInspectionId).toList();
        if (CollUtil.isNotEmpty(taskIds)) {
            List<QmsInspectionProcedureTaskEntity> procedureTasks = remoteProcedureService.getTaskByIds(taskIds).getData();
            if (CollUtil.isNotEmpty(procedureTasks)) {
                procedureTasks.forEach(task -> {
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
        storagePackage.setWrapWorkerName(arrange.getWrapCode());
        return storagePackage;
    }

    /**
     * 处理工序流程图数据
     *
     * @param produceTaskList 工序任务列表
     * @param flowDataList    流程图数据列表
     */
    private void processFlowData(List<MesProduceTaskEntity> produceTaskList, List<RetrospectVo.FlowDataDTO> flowDataList) {
        List<MesProduceTaskEntity> sortedProduceTasks = produceTaskList.stream()
                .sorted(Comparator.comparing(MesProduceTaskEntity::getRouteOrder))
                .toList();

        for (int i = 0; i < sortedProduceTasks.size(); i++) {
            MesProduceTaskEntity currentTask = sortedProduceTasks.get(i);
            RetrospectVo.FlowDataDTO flowDataDTO = new RetrospectVo.FlowDataDTO();
            flowDataDTO.setRouteCode(currentTask.getRouteCode());
            flowDataDTO.setRouteName(currentTask.getRouteName());

            if (i < sortedProduceTasks.size() - 1 && Objects.isNull(currentTask.getLastRoute())) {
                MesProduceTaskEntity nextTask = sortedProduceTasks.get(i + 1);
                RetrospectVo.FlowDataDTO nextFlowDataDTO = new RetrospectVo.FlowDataDTO();
                nextFlowDataDTO.setRouteCode(nextTask.getRouteCode());
                nextFlowDataDTO.setRouteName(nextTask.getRouteName());

                List<RetrospectVo.FlowDataDTO> children = new ArrayList<>();
                children.add(nextFlowDataDTO);
                flowDataDTO.setChildren(children);
            }
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
        return result;
    }


    /**
     * 解析条码信息
     *
     * @param retrospectVo 追溯请求参数
     * @return 条码信息DTO
     */
    private BarcodeInfoDTO parseBarcodeInfo(RetrospectReqVo retrospectVo) {
        return Optional.ofNullable(remoteBarcodeService.rander(retrospectVo.getCode()).getData())
                .map(data -> {
                    try {
                        JSONObject jsonObject = JSON.parseObject(JSON.toJSONString(data));
                        return JSON.parseObject(JSON.toJSONString(jsonObject.get("data")), BarcodeInfoDTO.class);
                    } catch (Exception e) {
                        throw new RuntimeException("物料条码解析失败!");
                    }
                })
                .orElseThrow(() -> new RuntimeException("物料条码解析失败!"));
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
                .eq(MesWarehouseFeedEntity::getMatCode, barcodeInfo.getMatCode())
                .eq(MesWarehouseFeedEntity::getMatBatch, barcodeInfo.getMatBatch())
                .eq(MesWarehouseFeedEntity::getSupplierCode, barcodeInfo.getSupplierCode()));

        // 获取工单编码列表
        List<String> taskCodes = barcodeFeedList.stream()
                .map(MesWarehouseFeedEntity::getTaskCode)
                .toList();

        if (CollUtil.isEmpty(taskCodes)) {
            throw new RuntimeException("暂未找到任何记录");
        }

        // 查询工单信息
        return mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .in(MesTaskEntity::getCode, taskCodes));
    }

    /**
     * 并行处理工单
     *
     * @param taskList 工单列表
     * @return 处理结果列表
     */
    private List<RetrospectVo> processTasksInParallel(List<MesTaskEntity> taskList) {
        if (CollUtil.isEmpty(taskList)) {
            throw new RuntimeException("暂未找到任何记录");
        }
        // 创建并行任务
        List<CompletableFuture<RetrospectVo>> futures = taskList.stream()
                .map(this::processSingleTask)
                .toList();

        // 等待所有任务完成
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        CompletableFuture<List<RetrospectVo>> allResults = allFutures.thenApply(v ->
                futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList())
        );

        return allResults.join();
    }

    /**
     * 处理物流信息
     *
     * @param taskCode 工单编码
     * @return 物流追溯信息列表
     */
    private List<LogisticsDTO> processLogisticsData(String taskCode) {
        // 模拟数据
        List<LogisticsDTO> logisticsList = new ArrayList<>();

        // 模拟第一条物流记录
        LogisticsDTO logistics1 = new LogisticsDTO();
        logistics1.setSupplierName("顺丰速运");
        logistics1.setCarNo("粤B12345");
        logistics1.setStartTime(LocalDateTime.now().minusDays(2));
        logistics1.setEndTime(LocalDateTime.now().minusDays(1));
        logistics1.setTemperature(new BigDecimal("25.5"));
        logistics1.setHumidity(new BigDecimal("60.0"));
        logistics1.setGps("116.404,39.915");
        logistics1.setCustomerName("北京中关村电子商城");
        logistics1.setAddress("北京市海淀区中关村大街1号");
        logistics1.setConcatPerson("张三");
        logistics1.setOrderNo("ORD202403150001");

        logisticsList.add(logistics1);

        return logisticsList;
    }
}
