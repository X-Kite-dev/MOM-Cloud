package com.kitex.mom.mes.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteBomService;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.oms.enums.OrderLongStateEnum;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import com.kitex.mom.wms.api.feign.RemoteProductInNoticeService;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工单获取
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskServiceImpl extends ServiceImpl<MesTaskMapper, MesTaskEntity> implements MesTaskService {

    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteBomService bomService;
    private final MesProduceTaskService produceTaskService;
    private final RemotePlnIssueService remotePlnIssueService;
    private final RemoteMaterialService materialService;
    private final MesInspectionRuleService inspectionRuleService;
    private final RemoteProcedureService remoteProcedureService;
    private final RemoteClassesService remoteClassesService;
    private final MesInspectionGenRecordService genRecordService;
    private final MesTaskReportMapper mesTaskReportMapper;
    private final RemoteProductInNoticeService inNoticeService;
    private final RemoteMaterialService matService;
    private final MesTaskArrangeMapper mesTaskArrangeMapper;

    /**
     * 查询分页
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesTaskEntity> queryPage(Page<MesTaskEntity> pageParam, MesTaskEntity query) {
        QueryWrapper<MesTaskEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesTaskEntity::getCreateTime);
        IPage<MesTaskEntity> page = this.page(pageParam, autoQueryWrapper);
        // 工单的任务id
        List<String> collect = page.getRecords().stream().map(MesTaskEntity::getCode).collect(Collectors.toList());
        if (CollUtil.isEmpty(collect)) {
            return page;
        }
        List<MesProduceTaskEntity> list = produceTaskService.list(Wrappers.<MesProduceTaskEntity>lambdaQuery().in(MesProduceTaskEntity::getTaskCode, collect));
        page.getRecords().forEach(item -> {
            List<RouteProcessDTO> processDTOS = new ArrayList<>();
            //获取对应的工序任务进度
            List<MesProduceTaskEntity> mesProduceTask = list.stream().filter(task -> task.getTaskCode().equals(item.getCode())).toList();
            if (CollUtil.isNotEmpty(mesProduceTask)) {
                mesProduceTask.forEach(task -> {
                    RouteProcessDTO process = new RouteProcessDTO();
                    process.setProcessName(task.getRouteName());
                    process.setFinishNum(task.getCompleteNum() != null ? task.getCompleteNum() : BigDecimal.ZERO);
                    if (task.getNoticeNum() != null && task.getNoticeNum().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal percent = task.getCompleteNum() != null ?
                                task.getCompleteNum().multiply(new BigDecimal("100")).divide(task.getNoticeNum(), 2, RoundingMode.HALF_UP) :
                                BigDecimal.ZERO;
                        process.setPercent(percent);
                    } else {
                        process.setPercent(BigDecimal.ZERO);
                    }
                    processDTOS.add(process);
                });
                item.setProcessList(processDTOS);
            }
        });

        return page;
    }

    /**
     * 根据生产计划生成生产工单
     *
     * @param receive
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(MesTaskReceiveDTO receive) {
//        if (Objects.isNull(receive.getPlanNo())) {
//            throw new CheckedException("计划单号不允许为空，请检查参数！");
//        }
        // todo mrp运算产生的计划号相同，产品不同
//        long count = this.count(new LambdaQueryWrapper<MesTask>().eq(MesTaskEntity::getPlanNo, receive.getPlanNo()));
//        if (count > 0) {
//            throw new CheckedException(String.format("计划已下发到生产工单，请勿重复下发！计划单号:%s", receive.getPlanNo()));
//        }
        MesTaskEntity saveTask = new MesTaskEntity();
        BeanUtil.copyProperties(receive, saveTask);
        // 物料规格型号回填
        if (StrUtil.isNotEmpty(saveTask.getMatCode()) && StrUtil.isEmpty(saveTask.getMatSpec())) {
            MdmMaterialEntity mat = materialService.getDetails(saveTask.getMatCode()).getData();
            if (Objects.nonNull(mat)) {
                saveTask.setMatSpec(mat.getSpecificationModel());
            }
        }
        saveTask.setCode(remoteSeqConfigService.create("mes_task").getData());
        saveTask.setStatus(MesTaskStatusEnum.CREATE.name());
        this.save(saveTask);
    }

    /**
     * 生产工单编辑
     *
     * @param mesTask
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void edit(MesTaskEntity mesTask) {
        if (!MesTaskStatusEnum.CREATE.name().equals(mesTask.getStatus())) {
            throw new CheckedException("生产工单任务已下达或开始，无法进行编辑操作");
        }
        mesTask.setUnInStockNum(BigDecimal.ZERO);
//        if (MesTaskTypeEnum.PRODUCT.name().equals(mesTask.getType())) {
//            mesTask.setUnInStockNum(mesTask.getNoticeNum());
//        }
        this.updateById(mesTask);
    }


    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    @Override
    public MesTaskDTO findBydId(Long id) {
        MesTaskEntity mesTask = this.getById(id);
        if (Objects.isNull(mesTask)) {
            throw new CheckedException("查询失败");
        }
        MesTaskDTO result = new MesTaskDTO();
        BeanUtil.copyProperties(mesTask, result);
        return result;
    }

    /**
     * 下达任务
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void issue(Long id) {
        List<MesProduceTaskEntity> saveTaskList = new ArrayList<>();
        MesTaskEntity mesTask = this.getById(id);
        if (Objects.isNull(mesTask)) {
            throw new CheckedException("未找到生产工单");
        }
        if (Objects.isNull(mesTask.getBomId())) {
            throw new CheckedException("请先维护bom信息");
        }
        if (Objects.isNull(mesTask.getProcessRouteId()) || StrUtil.isEmpty(mesTask.getProcessRouteSnapshot())) {
            throw new CheckedException("请先维护工艺路线信息");
        }
        // 根据工艺路线保存生产工序任务表
        List<MdmProcessRouteWorkingDTO> routeList = JSONUtil.toList(mesTask.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);

        if (CollUtil.isNotEmpty(routeList)) {
            routeList.forEach(route -> {
                MesProduceTaskEntity saveTask = new MesProduceTaskEntity();
                saveTask.setCode(remoteSeqConfigService.create("mes_produce_task").getData());
                saveTask.setTaskCode(mesTask.getCode());
                saveTask.setMatCode(mesTask.getMatCode());
                saveTask.setMatName(mesTask.getMatName());
                saveTask.setMatSpec(mesTask.getMatSpec());
                saveTask.setRouteCode(route.getRouteCode());
                saveTask.setRouteName(route.getRouteName());
                saveTask.setRouteType(route.getType());
                saveTask.setRouteOrder(route.getRouteOrder());
                saveTask.setRouteWorkCenter(JSONUtil.toJsonStr(route.getMdmProcessProcedureWorkList()));

                List<String> workCodes = route.getMdmProcessProcedureWorkList().stream()
                        .map(MdmProcessRouteWorkCenterDTO::getWorkCode)
                        .collect(Collectors.toList());
                List<String> workNames = route.getMdmProcessProcedureWorkList().stream()
                        .map(MdmProcessRouteWorkCenterDTO::getWorkName)
                        .collect(Collectors.toList());

                saveTask.setWorksiteCodes(String.join(",", workCodes));
                saveTask.setWorksiteNames(String.join(",", workNames));

                saveTask.setLastRoute(route.getLastRoute());
                saveTask.setProcessRouteId(mesTask.getProcessRouteId());
                saveTask.setProcessRouteSnapshot(mesTask.getProcessRouteSnapshot());
                saveTask.setProcessRouteCode(mesTask.getProcessRouteCode());
                saveTask.setNoticeNum(mesTask.getNoticeNum());
                saveTask.setNoticeDate(LocalDateTime.now());
                saveTask.setStatus(MesProduceTaskStatusEnum.WAIT.name());
                saveTask.setWorkshopCode(mesTask.getWorkshopCode());
                saveTask.setWorkshopName(mesTask.getWorkshopName());
                saveTask.setWrapCode(mesTask.getWrapCode());
                saveTask.setReportType(mesTask.getType());
                saveTask.setIsInspection(route.getIsInspection());
                saveTask.setFactoryCode(mesTask.getFactoryCode());
                saveTaskList.add(saveTask);
            });
        }
        // 保存工序任务
        produceTaskService.saveBatch(saveTaskList);

        // 更新生产工单状态
        mesTask.setStatus(MesTaskStatusEnum.ISSUE.name());
        this.updateById(mesTask);

        if (StrUtil.isNotEmpty(mesTask.getPlanNo())) {
            planMesStatusUpdate(mesTask);
        }

        // 生成质检任务
        genInspectionTask(saveTaskList, mesTask.getMatCode());
    }

    /**
     * 生成质检任务
     *
     * @param saveTaskList 生产任务列表
     * @param matCode      物料编码
     */
    private void genInspectionTask(List<MesProduceTaskEntity> saveTaskList, String matCode) {
        // 根据物料查询质检任务生成规则
        MesInspectionRuleEntity inspectionRule = inspectionRuleService.getRuleByMatCode(matCode);
        List<MesInspectionGenRecordEntity> recordList = new ArrayList<>();
        // 生成质检任务
        saveTaskList.forEach(task -> {
            // 需要质检
            if ("1".equals(task.getIsInspection())) {
                // 设置了规则才创建
                if (Objects.nonNull(inspectionRule)) {
                    InspectionProcedureCreateParam createParam = buildCreateParam(task);
                    R<Long> procedureResult = remoteProcedureService.create(createParam);
                    // fixme 优化代码
                    String genFlag = "1";
                    String msg = "生成质检任务成功";
                    if (Objects.isNull(procedureResult.getData())) {
                        msg = procedureResult.getMsg();
                        // 解析报错信息，提取报错原因
                        if (msg != null && msg.contains("\"msg\":\"")) {
                            int start = msg.indexOf("\"msg\":\"");
                            int end = msg.indexOf("\"", start + 7);
                            msg = msg.substring(start + 7, end);
                        }
                        genFlag = "0";
                    }
                    MesInspectionGenRecordEntity record = new MesInspectionGenRecordEntity();
                    record.setTaskCode(task.getTaskCode());
                    record.setProduceTaskCode(task.getCode());
                    record.setInspectionId(procedureResult.getData());
                    record.setRuleCode(inspectionRule.getCode());
                    record.setRuleName(inspectionRule.getName());
                    record.setInspectionType(createParam.getLabelType());
                    record.setGenFlag(genFlag);
                    record.setGenRemark(msg);
                    record.setGenTime(LocalDateTime.now());
                    // 获取当前班次
                    record.setGenShiftCode(remoteClassesService.getRuleBanci(LocalDateTime.now()).getData());
                    List<String> inspectionTypes = Arrays.stream(inspectionRule.getInspectionTypes().split(",")).toList();
                    // fixme  若规则中存在中检 则需要设置下次生成时间或下次生成班次 不允许使用固定值 使用枚举进行替换
                    if (inspectionTypes.contains(ProcedureLabelTypeEnum.MIDDLE.getName())) {
                        if ("HOUR".equals(inspectionRule.getMethod())) {
                            record.setGenTime(LocalDateTime.now());
                            record.setNextTime(calculateNextTime(LocalDateTime.now(), inspectionRule.getNum()));
                        } else {
                            // 获取下一班次
                            List<MdmClassesEntity> banciList = remoteClassesService.getClassesBydate(DateUtil.formatDateTime(new Date()), 2).getData();
                            record.setNextShift(banciList.get(1).getShiftCode());
                        }
                    }
                    record.setFactoryCode(task.getFactoryCode());
                    recordList.add(record);
                }
            }
        });
        genRecordService.saveBatch(recordList);
    }

    /**
     * 生产计划模块修改对应的生产排产状态
     */
    private void planMesStatusUpdate(MesTaskEntity mesTask) {
        OmsOrderLifeCycleDTO omsOrderLifeCycleDTO = new OmsOrderLifeCycleDTO();
        omsOrderLifeCycleDTO.setNo(mesTask.getPlanNo());
        omsOrderLifeCycleDTO.setProductCode(mesTask.getMatCode());
        omsOrderLifeCycleDTO.setIssueStatus("2");
        omsOrderLifeCycleDTO.setType("mes");
        omsOrderLifeCycleDTO.setStatus(OrderLongStateEnum.SCHEDULE.getCode());
        omsOrderLifeCycleDTO.setContent(String.format("工单编号【%s】已排产,操作时间【%s】",
                mesTask.getCode(), LocalDate.now()));
        Boolean statusUpdate = remotePlnIssueService.edit(Collections.singletonList(omsOrderLifeCycleDTO)).getData();
        if (statusUpdate == null || !statusUpdate) {
            throw new CheckedException(String.format("%s的工单推送生产计划模块失败", mesTask.getCode()));
        }
    }

    /**
     * 计算下次生成时间
     *
     * @param currentTime   当前时间
     * @param intervalHours 间隔小时数
     * @return 下次生成时间
     */
    private LocalDateTime calculateNextTime(LocalDateTime currentTime, BigDecimal intervalHours) {
        if (currentTime == null || intervalHours == null) {
            return currentTime;
        }
        return currentTime.plusHours(intervalHours.longValue());
    }

    /**
     * 关闭任务
     *
     * @param id
     */
    @Override
    public void close(Long id) {
        this.update(new LambdaUpdateWrapper<MesTaskEntity>().eq(MesTaskEntity::getId, id)
                .set(MesTaskEntity::getStatus, MesTaskStatusEnum.CLOSE.name()));
    }

    /**
     * 生产工单对接生产计划接口
     *
     * @param planMesQueryDTO 查询条件
     * @return R
     */
    @Override
    public List<MesTaskEntity> planMesList(PlanMesQueryDTO planMesQueryDTO) {
        QueryWrapper<MesTaskEntity> wrapper = Wrappers.query();
        wrapper.select("notice_num - IFNULL(complete_num,0) as completeNum", "plan_no");
        wrapper.lambda().in(ObjUtil.isNotEmpty(planMesQueryDTO.getMatCodes()), MesTaskEntity::getMatCode, planMesQueryDTO.getMatCodes())
                .le(MesTaskEntity::getStartTime, planMesQueryDTO.getTime())
                .eq(MesTaskEntity::getStatus, MesTaskStatusEnum.PRODUCT.name())
                .isNotNull(MesTaskEntity::getPlanNo);
        return this.list(wrapper);
    }

    /**
     * 根据生产订单号查询生产工单最后一道工序的报工记录
     *
     * @param orders 生产订单列表
     * @return R
     */
    @Override
    public List<MesTaskReportOmsVO> getByOrders(List<String> orders) {
        List<MesTaskReportOmsVO> reportOmsVOList = new ArrayList<>();
        Map<String, MesTaskEntity> taskMap = this.list(new LambdaQueryWrapper<MesTaskEntity>().
                in(MesTaskEntity::getPlanNo, orders)).stream().collect(Collectors.toMap(MesTaskEntity::getCode, Function.identity()));
        if (CollectionUtil.isNotEmpty(taskMap)) {
            MPJLambdaWrapper<MesTaskReportEntity> wrapper = new MPJLambdaWrapper<MesTaskReportEntity>()
                    .selectAll(MesTaskReportEntity.class)
                    .select(MesTaskArrangeEntity::getRouteName, MesTaskArrangeEntity::getRouteCode, MesTaskArrangeEntity::getWorksiteCodes,
                            MesTaskArrangeEntity::getWorksiteNames, MesTaskArrangeEntity::getWorkshopCode, MesTaskArrangeEntity::getWorkshopName)
                    .innerJoin(MesTaskArrangeEntity.class, MesTaskArrangeEntity::getCode, MesTaskReportEntity::getArrangeCode)
                    .eq(MesTaskArrangeEntity::getLastRoute, "1")
                    .in(MesTaskArrangeEntity::getTaskCode, taskMap.keySet());
            List<MesTaskReportOmsVO> reportList = mesTaskReportMapper.selectJoinList(MesTaskReportOmsVO.class, wrapper);
            for (MesTaskReportOmsVO report : reportList) {
                MesTaskEntity mesTask = taskMap.get(report.getTaskCode());
                report.setNoticeNum(mesTask.getNoticeNum());
                report.setStatus(mesTask.getStatus());
                reportOmsVOList.add(report);
            }
        }
        return reportOmsVOList;
    }

    /**
     * 生产工单入库
     *
     * @param mesTaskInWmsDTO 生产工单入库DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean inWms(MesTaskInWmsDTO mesTaskInWmsDTO) {
        MesTaskEntity mesTask = Optional.ofNullable(this.getById(mesTaskInWmsDTO.getId())).orElseThrow(() -> new CheckedException("工单查询失败"));
        // 处理待入库数量
        BigDecimal unInStockNum = Optional.ofNullable(mesTask.getUnInStockNum()).orElseThrow(() -> new CheckedException("待入库数量不存在"));
        // 检查入库数量是否超过待入库数量
        if (mesTaskInWmsDTO.getInWmsNum().compareTo(unInStockNum) > 0) {
            throw new CheckedException("入库数量大于工单待入库数量");
        }
        // 更新待入库数量和已入库数量
        mesTask.setUnInStockNum(unInStockNum.subtract(mesTaskInWmsDTO.getInWmsNum()));
        mesTask.setInStockNum(Optional.ofNullable(mesTask.getInStockNum()).orElse(BigDecimal.ZERO).add(mesTaskInWmsDTO.getInWmsNum()));

        // 推送仓库
        this.wmsProductInNotice(mesTask, mesTaskInWmsDTO);
        // 推送生产计划模块
        if (StrUtil.isNotBlank(mesTask.getPlanNo())) {
            planMesFinishUpdate(mesTask, mesTaskInWmsDTO);
        }
        // 更新工单信息
        return this.updateById(mesTask);
    }

    /**
     * 根据生产计划单号查询生产工单
     *
     * @param planNo 生产计划单号
     * @return R
     */
    @Override
    public List<MesTaskEntity> omsLife(String planNo) {
        return this.list(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getPlanNo, planNo));
    }

    /**
     * 推送仓库
     *
     * @param task            工单信息
     * @param mesTaskInWmsDTO 入库信息
     */
    private void wmsProductInNotice(MesTaskEntity task, MesTaskInWmsDTO mesTaskInWmsDTO) {
        // 查询主数据物料信息
        MdmMaterialEntity mat = Optional.ofNullable(matService.getDetails(task.getMatCode()).getData())
                .orElseThrow(() -> new CheckedException("物料信息查询失败"));
        // 主表处理逻辑
        double inNum = mesTaskInWmsDTO.getInWmsNum().doubleValue();
        WmsInNoticeEntity inNotice = new WmsInNoticeEntity();
        inNotice.setNoticeInNum(inNum);
        inNotice.setAllInNum(0d);
        inNotice.setBillType(InBillTypeEnum.PRODUCT.getCode());
        inNotice.setDocumentTable(DocumentEnum.MES_TASK.getCode());
        inNotice.setDocumentId(task.getId());
        inNotice.setWarehouseCode(mesTaskInWmsDTO.getWarehouseCode());
        inNotice.setWarehouseName(mesTaskInWmsDTO.getWarehouseName());
        inNotice.setRemark(mesTaskInWmsDTO.getRemark());
        inNotice.setFactoryCode(task.getFactoryCode());
        // 明细表处理逻辑
        WmsInNoticeDetailEntity inNoticeDetail = new WmsInNoticeDetailEntity();
        inNoticeDetail.setMatCode(task.getMatCode());
        inNoticeDetail.setMatName(task.getMatName());
        inNoticeDetail.setMatSpec(task.getMatSpec());
        inNoticeDetail.setMatUnit(mat.getBasicUnit());
        inNoticeDetail.setWarehouseCode(mesTaskInWmsDTO.getWarehouseCode());
        inNoticeDetail.setWarehouseName(mesTaskInWmsDTO.getWarehouseName());
        inNoticeDetail.setNoticeInNum(inNum);
        inNoticeDetail.setInNum(0d);
        inNoticeDetail.setNotInNum(inNum);
        inNoticeDetail.setOperateNum(inNum);
        inNoticeDetail.setFactoryCode(task.getFactoryCode());
        inNotice.setDetailList(Collections.singletonList(inNoticeDetail));
        inNoticeService.save(inNotice);
    }

    /**
     * 推送生产计划模块
     *
     * @param task            生产工单
     * @param mesTaskInWmsDTO 入库信息
     */
    private void planMesFinishUpdate(MesTaskEntity task, MesTaskInWmsDTO mesTaskInWmsDTO) {
        OmsOrderLifeCycleDTO omsOrderLifeCycleDTO = new OmsOrderLifeCycleDTO();
        omsOrderLifeCycleDTO.setNo(task.getPlanNo());
        omsOrderLifeCycleDTO.setProductCode(task.getMatCode());
        omsOrderLifeCycleDTO.setContent(String.format("工单编号【%s】,入库数量【%s】,仓库编码【%s】,仓库名称【%s】,操作时间【%s】",
                task.getCode(), mesTaskInWmsDTO.getInWmsNum(), mesTaskInWmsDTO.getWarehouseCode(),
                mesTaskInWmsDTO.getWarehouseName(), LocalDate.now()));
        omsOrderLifeCycleDTO.setStatus(OrderLongStateEnum.WAREHOUSE.getCode());
        omsOrderLifeCycleDTO.setType("mes");
        omsOrderLifeCycleDTO.setFinishNum(mesTaskInWmsDTO.getInWmsNum());
        Boolean statusUpdate = remotePlnIssueService.edit(Collections.singletonList(omsOrderLifeCycleDTO)).getData();
        if (statusUpdate == null || !statusUpdate) {
            throw new CheckedException(String.format("%s的工单推送生产计划模块失败", task.getCode()));
        }
    }

    private InspectionProcedureCreateParam buildCreateParam(MesProduceTaskEntity task) {
        InspectionProcedureCreateParam result = new InspectionProcedureCreateParam();
        result.setTaskCode(task.getCode());
        result.setMatCode(task.getMatCode());
        result.setMatName(task.getMatName());
        result.setProcessCode(task.getRouteCode());
        result.setProcessName(task.getRouteName());
        result.setLabelType(ProcedureLabelTypeEnum.FIRST.getName());
//        result.setBatchNo(task.get)
        result.setNum(task.getNoticeNum().intValue());
        return result;
    }

    /**
     * 根据产品编码和工位编码查询当前用户需要做的工单信息
     * 如果有已经开始的则查询开始的，没有开始的则查询最近需要开始的工单信息
     *
     * @param queryDTO 查询参数
     * @return 当前用户需要做的工单信息
     */
    @Override
    public MesTaskCurrentWorkVO getCurrentWorkByProductAndWorksite(MesTaskQueryByProductWorksiteDTO queryDTO) {
        // 获取当前用户信息
        MomUser currentUser = SecurityUtils.getUser();
        if (currentUser == null) {
            throw new CheckedException("用户未登录");
        }

        // 构建查询条件
        LambdaQueryWrapper<MesTaskArrangeEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 根据产品编码查询
        queryWrapper.eq(MesTaskArrangeEntity::getMatCode, queryDTO.getProductCode());

        // 根据工位编码查询（工位编码是逗号分隔的字符串，需要模糊匹配）
        queryWrapper.apply("FIND_IN_SET({0}, worksite_codes) > 0", queryDTO.getWorksiteCode());

        // 根据车间编码查询（如果提供）
        if (StrUtil.isNotBlank(queryDTO.getWorkshopCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getWorkshopCode, queryDTO.getWorkshopCode());
        }

        // 根据班次编码查询（如果提供）
        if (StrUtil.isNotBlank(queryDTO.getShiftCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getShiftCode, queryDTO.getShiftCode());
        }

        // 查询当前用户的派工任务
        queryWrapper.apply("FIND_IN_SET({0}, worker_code) > 0", currentUser.getUsername());

        // 查询状态为已接收、生产中、暂停中的任务（已开始的任务）
        List<String> startedStatusList = Arrays.asList(
                MesProduceTaskStatusEnum.RECEIVE.name(),
                MesProduceTaskStatusEnum.PRODUCT.name(),
                MesProduceTaskStatusEnum.PAUSE.name()
        );

        // 查询状态为待接收的任务（未开始的任务）
        List<String> notStartedStatusList = Arrays.asList(
                MesProduceTaskStatusEnum.UN_RECEIVE.name()
        );

        // 先查询已开始的任务
        queryWrapper.in(MesTaskArrangeEntity::getStatus, startedStatusList);
        queryWrapper.orderByDesc(MesTaskArrangeEntity::getStartTime);
        queryWrapper.last("LIMIT 1");

        MesTaskArrangeEntity startedTask = mesTaskArrangeMapper.selectOne(queryWrapper);

        if (startedTask != null) {
            // 如果找到已开始的任务，返回该任务
            return convertToCurrentWorkVO(startedTask, true);
        }

        // 如果没有已开始的任务，查询最近需要开始的任务
        queryWrapper.clear();
        queryWrapper.eq(MesTaskArrangeEntity::getMatCode, queryDTO.getProductCode());
        queryWrapper.apply("FIND_IN_SET({0}, worksite_codes) > 0", queryDTO.getWorksiteCode());

        if (StrUtil.isNotBlank(queryDTO.getWorkshopCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getWorkshopCode, queryDTO.getWorkshopCode());
        }

        if (StrUtil.isNotBlank(queryDTO.getShiftCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getShiftCode, queryDTO.getShiftCode());
        }

        queryWrapper.apply("FIND_IN_SET({0}, worker_code) > 0", currentUser.getUsername());
        queryWrapper.in(MesTaskArrangeEntity::getStatus, notStartedStatusList);
        queryWrapper.orderByAsc(MesTaskArrangeEntity::getCreateTime);
        queryWrapper.last("LIMIT 1");

        MesTaskArrangeEntity notStartedTask = mesTaskArrangeMapper.selectOne(queryWrapper);

        if (notStartedTask != null) {
            // 返回未开始的任务
            return convertToCurrentWorkVO(notStartedTask, false);
        }

        // 如果没有找到任何任务，返回null
        return null;
    }

    @Override
    public MesTaskDTO getByTaskCode(String taskCode) {
        return baseMapper.getByTaskCode(taskCode);
    }

    /**
     * 将MesTaskArrange转换为MesTaskCurrentWorkVO
     *
     * @param arrange   派工任务
     * @param isStarted 是否已开始
     * @return 当前工作VO
     */
    private MesTaskCurrentWorkVO convertToCurrentWorkVO(MesTaskArrangeEntity arrange, boolean isStarted) {
        MesTaskCurrentWorkVO vo = new MesTaskCurrentWorkVO();

        vo.setArrangeId(arrange.getId());
        vo.setArrangeCode(arrange.getCode());
        vo.setTaskCode(arrange.getTaskCode());
        vo.setProduceTaskCode(arrange.getProduceTaskCode());
        vo.setProductCode(arrange.getMatCode());
        vo.setProductName(arrange.getMatName());
        vo.setProductSpec(arrange.getMatSpec());
        vo.setRouteCode(arrange.getRouteCode());
        vo.setRouteName(arrange.getRouteName());
        vo.setRouteType(arrange.getRouteType());

        // 从工位编码中提取当前工位
        String[] worksiteCodes = arrange.getWorksiteCodes().split(",");
        String[] worksiteNames = arrange.getWorksiteNames().split(",");
        // 这里简化处理，取第一个工位，实际可能需要根据具体业务逻辑调整
        vo.setWorksiteCode(worksiteCodes.length > 0 ? worksiteCodes[0] : arrange.getWorksiteCodes());
        vo.setWorksiteName(worksiteNames.length > 0 ? worksiteNames[0] : arrange.getWorksiteNames());

        vo.setNoticeNum(arrange.getNoticeNum());
        vo.setCompleteNum(arrange.getCompleteNum());
        vo.setUnPassNum(arrange.getUnPassNum());
        vo.setStatus(arrange.getStatus());
        vo.setStartTime(arrange.getStartTime());
        vo.setWorkshopCode(arrange.getWorkshopCode());
        vo.setWorkshopName(arrange.getWorkshopName());
        vo.setShiftCode(arrange.getShiftCode());
        vo.setShiftName(arrange.getShiftName());
        vo.setBatchNo(arrange.getBatchNo());
        vo.setIsStarted(isStarted);

        // 设置优先级：已开始的任务优先级更高（数字更小）
        vo.setPriority(isStarted ? 1 : 2);

        return vo;
    }
}
