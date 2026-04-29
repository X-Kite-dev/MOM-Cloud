package com.kitex.mom.mes.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWorkCenterService;
import com.kitex.mom.mes.dto.MesTaskDTO;
import com.kitex.mom.mes.dto.MesTaskReportFeedVO;
import com.kitex.mom.mes.dto.MesTaskReportUnqualifiedVO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.ReportRecordTypeEnum;
import com.kitex.mom.mes.event.FeedRecordSaveEvent;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.process.ProcessOperationFactory;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.oms.enums.OrderLongStateEnum;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskReportServiceImpl extends ServiceImpl<MesTaskReportMapper, MesTaskReportEntity> implements MesTaskReportService {

    private final MesTaskReportRecordService recordService;
    private final MesTaskReportUnqualifiedService unqualifiedService;
    private final MesTaskReportFeedService feedReportService;
    private final MesTaskArrangeService arrangeService;
    private final RemoteMaterialService matService;
    private final MesWarehouseLiveStockService liveStockService;
    private final RemoteParamService remoteParamService;
    private final MesTaskService taskService;
    private final MesProduceTaskService produceTaskService;
    private final MesWarehouseFeedService feedService;
    private final RemoteWorkCenterService workCenterService;
    private final RemoteDeviceService deviceService;
    private final RemotePlnIssueService remotePlnIssueService;
    private final ProcessOperationFactory processOperationFactory;


    /**
     * 生产报工
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void report(MesTaskReportVO params) {
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, params.getArrangeCode()));

        if (Objects.isNull(arrange)) {
            throw new RuntimeException("参数错误");
        }
        if (!MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus())) {
            throw new RuntimeException("生产任务未在进行中，请先对进行任务开工！");
        }

        MesTaskEntity task = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()));

        params.setTaskType(task.getType());
        params.setFactoryCode(task.getFactoryCode());

        // 设备状态
        // TODO: 工作中心服务已废弃，需要重新实现根据工位查询设备的功能
        // 获取工作中心绑定的设备
        // MdmWorkCenterEntity workCenterEntity = workCenterService.detailsByCode(params.getWorksiteCode()).getData();
        // if (Objects.isNull(workCenterEntity)) {
        //     throw new RuntimeException("工作中心查询失败");
        // }
        // if (StrUtil.isNotEmpty(workCenterEntity.getDeviceCode())) {
        //     MdmDeviceEntity queryParams = new MdmDeviceEntity();
        //     queryParams.setDeviceCode(workCenterEntity.getDeviceCode());
        //     MdmDeviceEntity device = deviceService.getDetails(queryParams).getData();
        //     if (Objects.isNull(device) || "0".equals(device.getDeviceState()) || "5".equals(device.getDeviceState())) {
        //         throw new RuntimeException("设备尚未开机，请先进行设备开机");
        //     }
        //     params.setDeviceCode(device.getDeviceCode());
        //     params.setDeviceName(device.getDeviceName());
        // }
        // 报工处理
        // 校验物料可用性
        MdmMaterialEntity mat = matService.getDetails(params.getMatCode()).getData();
        if (Objects.isNull(mat) || Objects.equals(mat.getStatus(), 0)) {
            throw new RuntimeException("物料不可用，报工失败");
        }

//        // 前置工序生产的数量是否足够
//        MesProduceTask lastProduceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTask>()
//                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
//                .eq(MesProduceTaskEntity::getWorkshopCode, arrange.getWorkshopCode())
//                .eq(MesProduceTaskEntity::getRouteOrder, arrange.getRouteOrder() - 1));
//        if (Objects.nonNull(lastProduceTask)) {
//            if (Objects.isNull(lastProduceTask.getCompleteNum())) {
//                throw new RuntimeException("前置工序生产的数量不足，报工失败");
//            }
//            if (params.getPassNum().add(Objects.isNull(arrange.getCompleteNum()) ? BigDecimal.ZERO : arrange.getCompleteNum())
//                    .compareTo(lastProduceTask.getCompleteNum()) > 0) {
//                throw new RuntimeException("前置工序生产的数量不足，报工失败");
//            }
//
//            // 消耗上工序入库的数量
//            MesWarehouseLiveStockInoutDTO out = new MesWarehouseLiveStockInoutDTOEntity();
//            out.setBillNo(arrange.getCode());
//            out.setProduceTaskCode(lastProduceTask.getCode());
//            out.setMatCode(mat.getMaterialCode());
//            out.setMatName(mat.getMaterialName());
//            out.setMatSpec(mat.getSpecificationModel());
//            out.setMatUnit(mat.getBasicUnit());
//            out.setNum(params.getPassNum());
//            out.setMatBatch(arrange.getBatchNo());
////            out.setSiteCode(params.getWorksiteCode());
////            out.setSiteName(workCenterEntity.getName());
//            out.setMatDetailType(MesWarehouseMatDetailTypeEnum.WIP.name());
//            out.setBizType(WarehouseInBizTypeEnum.PROCESS_PRODUCT_OUT.getCode());
//            out.setOrgId(arrange.getOrgId());
//            liveStockService.outStock(out);
//        }

        // 当前的派工任务
        params.setTaskArrange(arrange);
        // 调用不同的报工方式进行报工处理
        Map<String, Object> reportMap = processOperationFactory.execute(arrange.getRouteType(), params);
        MesTaskReportEntity report = (MesTaskReportEntity) reportMap.get("report");
//        this.save(report);
//
//        // 获取是否倒冲 如果倒冲 则直接在线边库中扣除
//        String backFlushFlag = remoteParamService.getByKey("BACK_FLUSH").getData();
//        if ("1".equals(backFlushFlag)) {
//            // 获取投料
//            List<ProcessProcedureDTO> processList = JSONUtil.toList(task.getProcessRouteSnapshot(), ProcessProcedureDTO.class);
//            ProcessProcedureDTO progress = processList.stream()
//                    .filter(process -> process.getRouteCode().equals(arrange.getRouteCode()))
//                    .findFirst()
//                    .orElseThrow(() -> new RuntimeException("未找到对应的工序信息"));
//            List<ProcessProcedureDTO.ProcessProcedureMatDTO> mdmProcessProcedureMatList = progress.getMdmProcessProcedureMatList();
//            mdmProcessProcedureMatList.forEach(item -> {
//                MdmMaterialEntity rawMat = matService.getDetails(item.getMaterialCode()).getData();
//                if (Objects.isNull(rawMat) || Objects.equals(rawMat.getStatus(), 0)) {
//                    throw new RuntimeException("物料不可用，报工失败");
//                }
//                MesWarehouseLiveStockInoutDTO out = new MesWarehouseLiveStockInoutDTOEntity();
//                out.setBillNo(arrange.getCode());
//                out.setProduceTaskCode(arrange.getProduceTaskCode());
//                out.setMatCode(rawMat.getMaterialCode());
//                out.setMatName(rawMat.getMaterialName());
//                out.setMatSpec(rawMat.getSpecificationModel());
//                out.setMatUnit(rawMat.getBasicUnit());
//                out.setNum(params.getPassNum().multiply(item.getQuantity()));
//                out.setSiteCode(params.getWorksiteCode());
//                out.setSiteName(workCenterEntity.getName());
//                out.setMatBatch(arrange.getBatchNo());
//                out.setMatDetailType(MesWarehouseMatDetailTypeEnum.RAW.name());
//                out.setBizType(WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode());
//                liveStockService.outStock(out);
//            });
//        } else {
//            // 消耗投料
//            feedService.use(new MesWarehouseUseDTOEntity().setReportNum(params.getPassNum()).setArrangeCode(arrange.getCode())
//                    .setProcessRouteCode(task.getProcessRouteCode()).setRouteCode(arrange.getRouteCode()).setSnapshot(task.getProcessRouteSnapshot()));
//        }


        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(report, record);
        record.setType(ReportRecordTypeEnum.REPORT.name());
        record.setContent(String.valueOf(reportMap.get("reportMessage")));
        record.setFactoryCode(arrange.getFactoryCode());
        recordService.save(record);

        // 包装装箱单独记录一份操作记录
        if (StrUtil.isEmpty(String.valueOf(reportMap.get("wrapMessage")))) {
            MesTaskReportRecordEntity wrapRecord = new MesTaskReportRecordEntity();
            BeanUtils.copyProperties(reportMap, wrapRecord);
            wrapRecord.setType(ReportRecordTypeEnum.REPORT.name());
            wrapRecord.setContent(String.valueOf(reportMap.get("wrapMessage")));
            wrapRecord.setFactoryCode(arrange.getFactoryCode());
            recordService.save(wrapRecord);
        }

        // 报工完更新工序任务表
        if (Objects.isNull(arrange.getCompleteNum())) {
            arrange.setCompleteNum(BigDecimal.ZERO);
        }
        arrange.setCompleteNum(arrange.getCompleteNum().add(report.getReportNum()));
        arrangeService.updateById(arrange);

        // 更新生产工单状态
        updateTask(arrange, task, report);
//
//        // 入库线边库
//        MesWarehouseLiveStockInoutDTO in = new MesWarehouseLiveStockInoutDTOEntity();
//        in.setBillNo(arrange.getCode());
//        in.setProduceTaskCode(arrange.getProduceTaskCode());
//        in.setRouteCode(arrange.getRouteCode());
//        in.setRouteName(arrange.getRouteName());
//        in.setMatCode(mat.getMaterialCode());
//        in.setMatName(mat.getMaterialName());
//        in.setMatSpec(mat.getSpecificationModel());
//        in.setMatBatch(arrange.getBatchNo());
//        in.setMatUnit(mat.getBasicUnit());
////            in.setMatBarcode();
//        in.setNum(report.getReportNum());
//        in.setSiteCode(params.getWorksiteCode());
//        in.setSiteName(workCenterEntity.getName());
//        in.setMatDetailType(MesWarehouseMatDetailTypeEnum.WIP.name());
//
//        if (Objects.nonNull(arrange.getLastRoute()) && arrange.getLastRoute()) {
//            // 末尾工序 零件计划类型设置为半成品 成品计划类型设置为成品
//            in.setMatDetailType(MesWarehouseMatDetailTypeEnum.SEMI_FINISHED.name());
//            if ("PRODUCT".equals(task.getType())) {
//                in.setMatDetailType(MesWarehouseMatDetailTypeEnum.PRODUCT.name());
//            }
//
//            // todo 推送生产计划模块
//            if (StrUtil.isNotBlank(task.getPlanNo())) {
//                planMesFinishUpdate(task, report.getReportNum());
//            }
//        }
//
//        in.setBizType(WarehouseInBizTypeEnum.PROCESS_PRODUCT_IN.getCode());
//        in.setOrgId(arrange.getOrgId());
//        liveStockService.inStock(in);
    }

    /**
     * 更新生产工单状态
     *
     * @param arrange
     */
    private void updateTask(MesTaskArrangeEntity arrange, MesTaskEntity task, MesTaskReportEntity report) {
        // 如果是最后一道工序 需要更新生产工单数量
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()));
        produceTask.setCompleteNum((Objects.isNull(produceTask.getCompleteNum()) ? BigDecimal.ZERO : produceTask.getCompleteNum()).add(Objects.isNull(report.getReportNum()) ? BigDecimal.ZERO : report.getReportNum()));
//        produceTask.setUnPassNum((Objects.isNull(produceTask.getUnPassNum()) ? BigDecimal.ZERO : produceTask.getUnPassNum()).add(Objects.isNull(report.getQualifiedNum()) ? BigDecimal.ZERO : report.getReportNum()));
        produceTaskService.updateById(produceTask);
        // 生产工单下的所有工序任务
        List<MesProduceTaskEntity> allTasks = produceTaskService.list(new LambdaUpdateWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode()));
        // 计算完成数量和不合格数量
//        BigDecimal allCompleteNum = allTasks.stream()
//                .map(item -> Objects.isNull(item.getCompleteNum()) ? BigDecimal.ZERO : item.getCompleteNum())
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal allUnPassNum = allTasks.stream()
//                .map(item -> Objects.isNull(item.getUnPassNum()) ? BigDecimal.ZERO : item.getUnPassNum())
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 更新生产工单数量
//        task.setCompleteNum(allCompleteNum);
//        task.setUnPassNum(allUnPassNum);
        // 末尾工序更新未入库数量
        if (Objects.nonNull(arrange.getLastRoute()) && arrange.getLastRoute()) {
            task.setUnInStockNum(task.getUnInStockNum().add(arrange.getCompleteNum()));
        }
        taskService.updateById(task);
    }

    /**
     * 推送生产计划模块
     *
     * @param task      生产工单
     * @param reportNum 操作数量
     */
    private void planMesFinishUpdate(MesTaskEntity task, BigDecimal reportNum) {
        OmsOrderLifeCycleDTO omsOrderLifeCycleDTO = new OmsOrderLifeCycleDTO();
        omsOrderLifeCycleDTO.setNo(task.getPlanNo());
        omsOrderLifeCycleDTO.setProductCode(task.getMatCode());
        omsOrderLifeCycleDTO.setContent(String.format("工单编号【%s】，已生产数量【%s】,操作时间【%s】",
                task.getCode(), reportNum, LocalDate.now()));
        omsOrderLifeCycleDTO.setStatus(OrderLongStateEnum.SCHEDULE.getCode());
        omsOrderLifeCycleDTO.setType("mes");
        omsOrderLifeCycleDTO.setFinishNum(reportNum);
        Boolean statusUpdate = remotePlnIssueService.edit(Collections.singletonList(omsOrderLifeCycleDTO)).getData();
        if (statusUpdate == null || !statusUpdate) {
            throw new CheckedException(String.format("%s的工单推送生产计划模块失败", task.getCode()));
        }
    }

    /**
     * 生产报工-不合格品上报
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unqualified(MesTaskReportUnqualifiedVO params) {
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, params.getArrangeCode()));

        if (Objects.isNull(arrange)) {
            throw new RuntimeException("参数错误");
        }
        if (!MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus())) {
            throw new RuntimeException("生产任务未在进行中，请先对进行任务开工！");
        }
        MomUser currentUser = SecurityUtils.getUser();
        // 保存不合格品上报信息
        MesTaskReportUnqualifiedEntity unqualified = new MesTaskReportUnqualifiedEntity();
        unqualified.setTaskCode(params.getTaskCode());
        unqualified.setArrangeCode(params.getArrangeCode());
        unqualified.setProduceTaskCode(params.getProduceTaskCode());
        unqualified.setMatCode(params.getMatCode());
        unqualified.setMatName(params.getMatName());
        unqualified.setMatSpec(params.getMatSpec());
        unqualified.setReportTime(LocalDateTime.now());
        unqualified.setUnqualifiedNum(params.getUnPassNum());
        unqualified.setType(params.getUnqualifiedType());
        unqualified.setWorkerCode(currentUser.getUsername());
        unqualified.setWorkerName(currentUser.getName());
        unqualified.setFactoryCode(arrange.getFactoryCode());
        unqualifiedService.save(unqualified);

        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(unqualified, record);
        record.setType(ReportRecordTypeEnum.UNQUALIFIED.name());
        record.setContent(currentUser.getName() + "于" + DateUtil.format(unqualified.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行报工-不合格品上报，不合格数量为：" + unqualified.getUnqualifiedNum());
        recordService.save(record);

        // 报工完更新工序任务表
        if (Objects.nonNull(arrange)) {
            if (Objects.isNull(arrange.getUnPassNum())) {
                arrange.setUnPassNum(BigDecimal.ZERO);
            }
            arrange.setUnPassNum(arrange.getUnPassNum().add(unqualified.getUnqualifiedNum()));
            arrangeService.updateById(arrange);
        }
        //更新工单不合格数量
        MesTaskDTO mesTaskDTO = taskService.getByTaskCode(params.getTaskCode());
        // 计算 unPassNum 的值，防止空指针
        BigDecimal unPassNum1 = arrange.getUnPassNum() != null ? arrange.getUnPassNum() : BigDecimal.ZERO;
        BigDecimal unPassNum2 = mesTaskDTO.getUnPassNum() != null ? mesTaskDTO.getUnPassNum() : BigDecimal.ZERO;
        BigDecimal totalUnPassNum = unPassNum1.add(unPassNum2);

        // 构建更新条件
        LambdaUpdateWrapper<MesTaskEntity> updateWrapper = Wrappers.<MesTaskEntity>lambdaUpdate()
                .eq(MesTaskEntity::getCode, params.getTaskCode());

        // 执行更新
        taskService.update(updateWrapper.set(MesTaskEntity::getUnPassNum, totalUnPassNum));


    }

    /**
     * 生产报工-投料
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feed(MesTaskReportFeedVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 保存投料信息
        MesTaskReportFeedEntity feed = new MesTaskReportFeedEntity();
        feed.setArrangeCode(params.getArrangeCode());
        feed.setTaskCode(params.getTaskCode());
        feed.setProduceTaskCode(params.getProduceTaskCode());
        feed.setMatCode(params.getMatCode());
        feed.setMatName(params.getMatName());
        feed.setMatSpec(params.getMatSpec());
        feed.setReportNum(params.getNoticeNum());
        feed.setReportTime(LocalDateTime.now());
        feed.setFeedNum(params.getFeedNum());
        feed.setWorkerCode(currentUser.getUsername());
        feed.setWorkerName(currentUser.getName());
        feed.setInspectionTaskId(params.getInspectionTaskId());
        feed.setInspectionResult(params.getInspectionResult());
        feed.setFactoryCode(params.getFactoryCode());
        feedReportService.save(feed);

        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(feed, record);
        record.setType(ReportRecordTypeEnum.FEED.name());
        record.setContent(currentUser.getName() + "于" + DateUtil.format(feed.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行报工-投料，投料数量为：" + feed.getFeedNum());
        recordService.save(record);

    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleFeedEvent(FeedRecordSaveEvent event) {
        log.debug("投料记录保存");
        MesTaskReportFeedVO params = event.getVo();
        this.feed(params);
    }

}
