package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.constant.ModuleEnum;
import com.kitex.mom.plan.constant.UpdateTypeEnum;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.plan.vo.PlnIssueMonthVO;
import com.kitex.mom.plan.vo.PlnIssueVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计划下达表
 *
 * @author kitex
 * @date 2025-02-06 11:49:55
 */
@Service
@AllArgsConstructor
public class PlnIssueServiceImpl extends ServiceImpl<PlnIssueMapper, PlnIssueEntity> implements PlnIssueService {

    private final RemoteSeqConfigService remoteSeqConfigService;

    private final PlnUpdateLogService plnUpdateLogService;

    private final PlnIssueFeedbackMapper plnIssueFeedbackMapper;

    private final PlanMatMrpService planMatMrpService;

    private final PlnSchedulingEngineService plnSchedulingEngineService;

    private final PlnIssueCallbackReceiptMapper plnIssueCallbackReceiptMapper;

    private final PlnAutomationEventService plnAutomationEventService;

    private final PlanMatMrpMapper planMatMrpMapper;
    private final PlnProductDemandMapper plnProductDemandMapper;
    private final PlnSchedulingBatchAllocationMapper batchAllocationMapper;
    private final PlnSchedulingTaskDetailMapper schedulingTaskDetailMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final RemoteMesTaskService remoteMesTaskService;

    /**
     * 新增或修改计划下达任务
     *
     * @param plnIssueVO 计划下达VO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveOrUpdatePlnIssue(PlnIssueVO plnIssueVO) {
        if (plnIssueVO == null || StrUtil.isBlank(plnIssueVO.getProductCode())
                || plnIssueVO.getNum() == null || plnIssueVO.getNum().signum() <= 0
                || plnIssueVO.getIssueTime() == null) {
            throw new CheckedException("生产计划必须提供产品、正数数量和下达时间");
        }
        if (plnIssueVO.getIssueTime().isBefore(LocalDateTime.now())) {
            throw new CheckedException("计划下达时间不能小于当前时间");
        }
        if (plnIssueVO.getIssueId() != null) {
            PlnIssueEntity plnIssueEntity = requireStandaloneEditableIssue(plnIssueVO.getIssueId());
            return checkUpdateIssue(plnIssueEntity, plnIssueVO.getNum());
        }
        PlnIssueEntity plnIssue = new PlnIssueEntity();
        BeanUtils.copyProperties(plnIssueVO, plnIssue);
        plnIssue.setIssueNo(remoteSeqConfigService.create("plan_mes").getData());
        plnIssue.setStatus(IssueStatusEnum.UNISSUED.getCode());
        // 新增日志保存
        PlnUpdateLogEntity initialLog = createUpdateLog(plnIssue, UpdateTypeEnum.NUM_ADD, null, plnIssueVO.getNum().toString());
        plnUpdateLogService.saveUpdateLog(Collections.singletonList(initialLog));
        return this.save(plnIssue);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createStandaloneIssue(PlnIssueEntity input) {
        if (input == null || input.getId() != null || StrUtil.isBlank(input.getProductCode())
                || input.getNum() == null || input.getNum().signum() <= 0 || input.getIssueTime() == null) {
            throw new CheckedException("独立计划创建必须提供产品、正数数量和下达时间，且不能携带已有计划ID");
        }
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setIssueNo(remoteSeqConfigService.create("plan_mes").getData());
        issue.setCustomerCode(input.getCustomerCode());
        issue.setCustomerName(input.getCustomerName());
        issue.setProductCode(input.getProductCode());
        issue.setProductName(input.getProductName());
        issue.setSpecificationModel(input.getSpecificationModel());
        issue.setMaterialAttribute(input.getMaterialAttribute());
        issue.setNum(input.getNum());
        issue.setIssueTime(input.getIssueTime());
        issue.setDemandTime(input.getDemandTime());
        issue.setPlanType(input.getPlanType());
        issue.setRemark(input.getRemark());
        // 通知来源若原本是正式生产计划，保持既有前端口径；MRP等来源不得伪装为新供应来源。
        issue.setType("issue".equals(input.getType()) ? "issue" : "manual");
        issue.setStatus(IssueStatusEnum.UNISSUED.getCode());
        return this.save(issue);
    }

    private Boolean checkUpdateIssue(PlnIssueEntity plnIssueEntity, BigDecimal num) {
        if (IssueStatusEnum.UNISSUED.getCode().equals(plnIssueEntity.getStatus())) {
            // 修改日志保存
            PlnUpdateLogEntity initialLog = createUpdateLog(plnIssueEntity, UpdateTypeEnum.NUM_UPDATE, plnIssueEntity.getNum().toString(), num.toString());
            plnUpdateLogService.saveUpdateLog(Collections.singletonList(initialLog));

            plnIssueEntity.setNum(num);
            return this.updateById(plnIssueEntity);
        }

        throw new CheckedException(String.format("生产计划状态为%s，不可修改", IssueStatusEnum.getDescByCode(plnIssueEntity.getStatus())));
    }

    private PlnUpdateLogEntity createUpdateLog(PlnIssueEntity issueLog, UpdateTypeEnum updateType, String oldValue, String newValue) {
        PlnUpdateLogEntity log = new PlnUpdateLogEntity();
        BeanUtils.copyProperties(issueLog, log);
        log.setOperateTime(issueLog.getIssueTime());
        log.setModule(ModuleEnum.MES.getCode());
        log.setUpdateType(updateType.getCode());
        log.setNewValue(newValue);
        if (oldValue != null) {
            log.setOldValue(oldValue);
        }
        return log;
    }

    /**
     * 通过条件查询计划下达
     *
     * @param templateQueryDTO 查询条件
     */
    @Override
    public Map<String, List<PlnIssueVO>> getIssueDate(TemporaryPlanQueryDTO templateQueryDTO) {
        Map<String, List<PlnIssueVO>> map = new HashMap<>();
        List<PlnIssueEntity> mesList = this.list(new LambdaQueryWrapper<PlnIssueEntity>()
                .like(ObjUtil.isNotEmpty(templateQueryDTO.getCustomerCode()), PlnIssueEntity::getCustomerCode, templateQueryDTO.getCustomerCode())
                .in(ObjUtil.isNotEmpty(templateQueryDTO.getMatCodes()), PlnIssueEntity::getProductCode, templateQueryDTO.getMatCodes())
                .like(ObjUtil.isNotEmpty(templateQueryDTO.getMatName()), PlnIssueEntity::getProductName, templateQueryDTO.getMatName())
                .between(PlnIssueEntity::getIssueTime,
                        templateQueryDTO.getTime().plusDays(templateQueryDTO.getPlanNum() - 1).atStartOfDay(),
                        templateQueryDTO.getTime().plusDays(templateQueryDTO.getPlanNum() - 1).atTime(LocalTime.MAX))
                .eq(PlnIssueEntity::getType, "issue")
                .isNotNull(PlnIssueEntity::getNum));
        map.put(ModuleEnum.MES.getCode(), convertToPlnIssueVO(mesList));
        return map;
    }

    private List<PlnIssueVO> convertToPlnIssueVO(List<PlnIssueEntity> entities) {
        return entities.stream().map(entity -> {
            PlnIssueVO vo = new PlnIssueVO();
            BeanUtils.copyProperties(entity, vo);
            vo.setIssueId(entity.getId());
            return vo;
        }).toList();
    }

    /**
     * 下发到各模块
     *
     * @param plnIssueModuleDTO 生产计划下发DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Boolean module(PlnIssueModuleDTO plnIssueModuleDTO) {
        if (plnIssueModuleDTO == null || plnIssueModuleDTO.getIds() == null || plnIssueModuleDTO.getIds().length == 0) {
            throw new CheckedException("下发目标不能为空");
        }

        List<PlnIssueEntity> issueEntities = this.listByIds(Arrays.asList(plnIssueModuleDTO.getIds()));
        if (issueEntities.size() != plnIssueModuleDTO.getIds().length) {
            throw new CheckedException("存在无效的下发目标");
        }

        boolean hasNotUnissued = issueEntities.stream().anyMatch(entity -> !IssueStatusEnum.UNISSUED.getCode().equals(entity.getStatus()));
        if (hasNotUnissued) {
            throw new CheckedException("仅未下发状态的生产计划允许下发");
        }

        boolean hasNotScheduled = issueEntities.stream().anyMatch(entity -> !"issue".equals(entity.getType()));
        if (hasNotScheduled) {
            throw new CheckedException("当前数据未完成排产，请先进行正式排产后再下发生产");
        }

        return plnSchedulingEngineService.issueToModule(plnIssueModuleDTO);
    }

    /**
     * 主生产计划
     *
     * @param page           分页对象
     * @param plnIssueEntity 查询对象
     * @return R
     */
    @Override
    public Page<PlnIssueEntity> getMesPlanPage(Page page, PlnIssueEntity plnIssueEntity) {
        QueryWrapper<PlnIssueEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                // 只查询需要的字段（必须包含GROUP BY的字段，其他字段需用聚合函数）
                .select(
                        PlnIssueEntity::getProductCode,
                        PlnIssueEntity::getProductName,
                        PlnIssueEntity::getSpecificationModel
                )
                .like(ObjUtil.isNotEmpty(plnIssueEntity.getProductCode()), PlnIssueEntity::getProductCode, plnIssueEntity.getProductCode())
                .like(ObjUtil.isNotEmpty(plnIssueEntity.getProductName()), PlnIssueEntity::getProductName, plnIssueEntity.getProductName())
                .like(ObjUtil.isNotEmpty(plnIssueEntity.getSpecificationModel()), PlnIssueEntity::getSpecificationModel, plnIssueEntity.getSpecificationModel())
                .between(PlnIssueEntity::getIssueTime,
                        plnIssueEntity.getIssueTime().toLocalDate().with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay(),
                        plnIssueEntity.getIssueTime().toLocalDate().with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX))
                .groupBy(PlnIssueEntity::getProductCode, PlnIssueEntity::getProductName, PlnIssueEntity::getSpecificationModel); // 只按productCode分组

        // 如果需要分页，返回的结果可以用Map接收（因为字段可能不是完整实体）
        return this.page(page, wrapper);
    }

    /**
     * 通过id删除计划下达表
     *
     * @param ids id列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeBatchIssue(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw new CheckedException("删除生产计划必须提供有效计划ID");
        }
        List<Long> uniqueIds = ids.stream().distinct().sorted().toList();
        for (Long id : uniqueIds) {
            requireStandaloneEditableIssue(id);
        }
        return this.removeByIds(uniqueIds);
    }

    /**
     * 计划下达反馈
     *
     * @param plnFeedbackEntities 计划下达反馈实体列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean feedback(List<PlnIssueFeedbackEntity> plnFeedbackEntities) {
        for (PlnIssueFeedbackEntity plnFeedbackEntity : plnFeedbackEntities) {
            plnIssueFeedbackMapper.insert(plnFeedbackEntity);
        }
        return Boolean.TRUE;
    }

    /**
     * 通过id查询计划下达表
     *
     * @param id id
     * @return R
     */
    @Override
    public PlnIssueEntity getIssueDetail(Long id) {
        return this.getByIdDeep(id);
    }

    /**
     * 通过条件查询上个月生产计划下达
     *
     * @param monthPlanQueryDTO 查询条件
     */
    @Override
    public List<Map<String, Object>> getIssueUpMonth(MonthPlanQueryDTO monthPlanQueryDTO) {
        QueryWrapper<PlnIssueEntity> wrapper = Wrappers.query();
        wrapper.select("sum(num) as num", "product_code");
        wrapper.lambda().in(ObjUtil.isNotEmpty(monthPlanQueryDTO.getMatCodes()), PlnIssueEntity::getProductCode, monthPlanQueryDTO.getMatCodes());
        LocalDate time = monthPlanQueryDTO.getTime();
        // 获取上个月的日期
        LocalDate lastMonth = time.minusMonths(1);
        // 获取上个月的月初日期
        LocalDate firstDayOfLastMonth = lastMonth.with(TemporalAdjusters.firstDayOfMonth());
        // 获取上个月的月底日期
        LocalDate lastDayOfLastMonth = lastMonth.with(TemporalAdjusters.lastDayOfMonth());
        wrapper.lambda().between(PlnIssueEntity::getIssueTime, firstDayOfLastMonth.atStartOfDay(), lastDayOfLastMonth.atTime(LocalTime.MAX));
        wrapper.lambda().orderByAsc(PlnIssueEntity::getStatus);
        wrapper.lambda().groupBy(PlnIssueEntity::getProductCode);
        List<PlnIssueEntity> list = this.list(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlnIssueEntity plnIssueEntity : list) {
            Map<String, Object> map = new HashMap<>();
            map.put("productCode", plnIssueEntity.getProductCode());
            map.put("planUpMonth", plnIssueEntity.getNum());
            result.add(map);
        }
        return result;
    }

    /**
     * 查询生产排产列表
     *
     * @param temporaryPlanQueryDTO 查询条件
     */
    @Override
    public List<Map<Object, Object>> getMesPlan(TemporaryPlanQueryDTO temporaryPlanQueryDTO) {
        LambdaQueryWrapper<PlnIssueEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.in(ObjUtil.isNotEmpty(temporaryPlanQueryDTO.getMatCodes()), PlnIssueEntity::getProductCode, temporaryPlanQueryDTO.getMatCodes());
        wrapper.eq(ObjUtil.isNotEmpty(temporaryPlanQueryDTO.getCustomerCode()), PlnIssueEntity::getCustomerCode, temporaryPlanQueryDTO.getCustomerCode());
        wrapper.eq(PlnIssueEntity::getType, "issue");
        // 获取起始时间
        LocalDateTime startTime = temporaryPlanQueryDTO.getTime().atStartOfDay();
        // 根据 planNum 是否为 null 确定结束时间
        LocalDateTime endTime;
        if (temporaryPlanQueryDTO.getPlanNum() == null) {
            // 如果 planNum 为 null，结束时间为当月的最后一天
            endTime = temporaryPlanQueryDTO.getTime().with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX);
        } else {
            // 如果 planNum 不为 null，结束时间为起始时间加上 planNum - 1 天
            endTime = temporaryPlanQueryDTO.getTime().plusDays(temporaryPlanQueryDTO.getPlanNum() - 1).atTime(LocalTime.MAX);
        }
        // 构建查询条件
        wrapper.and(q -> q
                .between(PlnIssueEntity::getScheduledDate, startTime.toLocalDate(), endTime.toLocalDate())
                .or()
                .between(PlnIssueEntity::getIssueTime, startTime, endTime));
        wrapper.isNotNull(PlnIssueEntity::getNum);
        List<PlnIssueEntity> list = this.list(wrapper);
        if (list.isEmpty()) {
            return null;
        }
        Map<String, PlnIssueEntity> collect = list.stream().collect(Collectors.toMap(PlnIssueEntity::getPlanType, Function.identity(),
                (a, b) -> a));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        Map<String, Map<Object, Object>> result = list.stream()
                .filter(record -> record.getNum() != null)
                .collect(Collectors.groupingBy(
                        PlnIssueEntity::getPlanType,
                        Collectors.toMap(
                                record -> (record.getScheduledDate() != null ? record.getScheduledDate() : record.getIssueTime().toLocalDate()).format(formatter),
                                record -> record.getNum().toString(),
                                (existing, replacement) -> existing + ", " + replacement
                        )));
        // 如果需要将结果转换为List<Map<String, String>>
        List<Map<Object, Object>> resultList = new ArrayList<>();
        for (Map.Entry<String, Map<Object, Object>> entry : result.entrySet()) {
            Map<Object, Object> map = new HashMap<>(entry.getValue());
            map.put("novemberPlan", sumMapValues(map));
            PlnIssueEntity plnIssueEntity = collect.get(entry.getKey());
            map.put("planType", plnIssueEntity.getPlanType());
            map.put("customerCode", plnIssueEntity.getCustomerCode());
            map.put("customerName", plnIssueEntity.getCustomerName());
            map.put("productCode", plnIssueEntity.getProductCode());
            map.put("productName", plnIssueEntity.getProductName());
            resultList.add(map);
        }
        return resultList;
    }

    /**
     * 计算 Map 中所有 value 的总和
     *
     * @param map 要计算的 Map
     * @return 总和
     */
    private BigDecimal sumMapValues(Map<Object, Object> map) {
        BigDecimal sum = BigDecimal.ZERO;
        // 遍历 Map 中的每个 entry
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            // 获取当前 entry 的 value
            Object value = entry.getValue();
            // 如果是 Number 类型，将其转换为 BigDecimal 并累加到总和中
            BigDecimal numValue = new BigDecimal(value.toString());
            sum = sum.add(numValue);
        }
        return sum;
    }

    /**
     * 生产计划排产
     *
     * @param plnIssueMonthVO 月计划下达VO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean mesSaveOrUpdate(PlnIssueMonthVO plnIssueMonthVO) {
        if (plnIssueMonthVO.getIssueTime().isBefore(LocalDateTime.now())) {
            throw new CheckedException("计划下达时间不能小于当前时间");
        }
        PlnIssueEntity plnIssueEntity = this.getOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getCustomerCode, plnIssueMonthVO.getCustomerCode())
                .eq(PlnIssueEntity::getProductCode, plnIssueMonthVO.getProductCode())
                .eq(PlnIssueEntity::getPlanType, plnIssueMonthVO.getPlanType())
                .eq(PlnIssueEntity::getIssueTime, plnIssueMonthVO.getIssueTime()));
        if (plnIssueEntity != null) {
            return checkUpdateIssue(plnIssueEntity, plnIssueMonthVO.getNum());
        }
        plnIssueEntity = new PlnIssueEntity();
        BeanUtils.copyProperties(plnIssueMonthVO, plnIssueEntity);
        plnIssueEntity.setIssueNo(remoteSeqConfigService.create("plan_mes").getData());
        // 新增日志保存
        PlnUpdateLogEntity initialLog = createUpdateLog(plnIssueEntity, UpdateTypeEnum.NUM_ADD, null, plnIssueEntity.getNum().toString());
        plnUpdateLogService.saveUpdateLog(Collections.singletonList(initialLog));
        return this.save(plnIssueEntity);
    }

    /**
     * 主生产计划日期
     *
     * @param productCodes 产品编码列表
     * @return R
     */
    @Override
    public List<Map<Object, Object>> getMesPlanDetails(String[] productCodes) {

        QueryWrapper<PlnIssueEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("product_code, issue_time, scheduled_date, sum(num) as num, status");
        queryWrapper.lambda().in(PlnIssueEntity::getProductCode, Arrays.asList(productCodes))
                .gt(PlnIssueEntity::getNum, 0)
                .groupBy(PlnIssueEntity::getProductCode, PlnIssueEntity::getIssueTime, PlnIssueEntity::getScheduledDate, PlnIssueEntity::getStatus);
        List<PlnIssueEntity> issueList = this.list(queryWrapper);
        List<PlnIssueEntity> filteredList = filterByMinStatus(issueList);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        List<Map<Object, Object>> resultList = filteredList.stream().collect(Collectors.groupingBy(PlnIssueEntity::getProductCode))
                .entrySet().stream().map(entry -> {
                    Map<Object, Object> map = new HashMap<>();
                    map.put("productCode", entry.getKey());
                    entry.getValue().forEach(issue -> {
                        Map<Object, Object> dateValue = new HashMap<>();
                        dateValue.put("value", issue.getNum());
                        dateValue.put("status", issue.getStatus());
                        LocalDate displayDate = issue.getScheduledDate() != null ? issue.getScheduledDate() : issue.getIssueTime().toLocalDate();
                        map.put(displayDate.format(formatter), dateValue);
                    });
                    return map;
                }).toList();

        return resultList;
    }

    /**
     * 修改计划下达表
     *
     * @param plnIssue 计划下达表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateIssue(PlnIssueEntity plnIssue) {
        if (plnIssue == null || plnIssue.getId() == null
                || !IssueStatusEnum.NOT_ISSUED.getCode().equals(plnIssue.getStatus())) {
            throw new CheckedException("旧计划状态接口仅允许关闭独立且未下发的计划；其他变更须走受控流程");
        }
        PlnIssueEntity plnIssueEntity = requireStandaloneEditableIssue(plnIssue.getId());

        // 修改日志保存
        PlnUpdateLogEntity plnUpdateLogEntities = new PlnUpdateLogEntity();
        BeanUtils.copyProperties(plnIssueEntity, plnUpdateLogEntities);
        plnUpdateLogEntities.setOperateTime(plnIssueEntity.getIssueTime());
        plnUpdateLogEntities.setModule(ModuleEnum.MES.getCode());
        plnUpdateLogEntities.setUpdateType(UpdateTypeEnum.STATUS_UPDATE.getCode());
        plnUpdateLogEntities.setNewValue(plnIssue.getStatus());
        plnUpdateLogEntities.setOldValue(plnIssueEntity.getStatus());
        plnUpdateLogService.saveUpdateLog(Collections.singletonList(plnUpdateLogEntities));

        plnIssueEntity.setStatus(plnIssue.getStatus());
        return this.updateById(plnIssueEntity);
    }

    /**
     * 旧接口只处理无需求/MRP/APS/MES来源的独立草案，严禁跳过跨模块撤回。
     */
    private PlnIssueEntity requireStandaloneEditableIssue(Long issueId) {
        PlnIssueEntity issue = this.getOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, issueId).last("FOR UPDATE"));
        if (issue == null) throw new CheckedException("生产计划不存在：" + issueId);
        String currentFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(currentFactory) && !Objects.equals(currentFactory, issue.getFactoryCode())) {
            throw new CheckedException("当前工厂无权变更该生产计划");
        }
        if (!IssueStatusEnum.UNISSUED.getCode().equals(issue.getStatus())
                || Boolean.TRUE.equals(issue.getLockStatus()) || issue.getLockTaskId() != null
                || positive(issue.getFinishNum()) || positive(issue.getUnqualifiedNum())
                || positive(issue.getQualityIsolatedNum()) || positive(issue.getQualityReleasedNum())
                || positive(issue.getQualityRejectedNum()) || positive(issue.getScrapNum())
                || positive(issue.getReworkNum()) || positive(issue.getReworkCompletedNum())
                || positive(issue.getReworkQualifiedNum()) || positive(issue.getReworkRejectedNum())) {
            throw new CheckedException("计划已下发、锁定或存在执行/质量事实，不能通过旧接口修改；请走受控变更");
        }
        if (StrUtil.isBlank(issue.getIssueNo())
                || this.count(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getIssueNo, issue.getIssueNo()).ne(PlnIssueEntity::getId, issueId)) != 0
                || plnProductDemandMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getDemandNo, issue.getIssueNo())) != 0
                || planMatMrpMapper.selectCount(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, issue.getIssueNo())) != 0
                || batchAllocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issueId)) != 0
                || schedulingTaskDetailMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                .eq(PlnSchedulingTaskDetailEntity::getIssueId, issueId)) != 0
                || schedulingOperationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getIssueId, issueId)) != 0) {
            throw new CheckedException("计划已关联需求、MRP或APS，不能通过旧接口修改；请走受控变更");
        }
        R<List<MesTaskEntity>> mes = remoteMesTaskService.omsLife(issue.getIssueNo());
        if (mes == null || !mes.isOk() || mes.getData() == null) {
            throw new CheckedException("MES工单事实不可核验，禁止修改生产计划");
        }
        if (!mes.getData().isEmpty()) {
            throw new CheckedException("计划已关联MES工单，不能通过旧接口修改；请走受控变更");
        }
        return issue;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * 修改生产生产排产结果表
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean edit(List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList) {
        if (omsOrderLifeCycleDtoList == null || omsOrderLifeCycleDtoList.isEmpty()) {
            return true;
        }
        for (OmsOrderLifeCycleDTO callback : omsOrderLifeCycleDtoList) {
            if (callback == null || callback.getNo() == null || callback.getNo().isBlank()
                    || callback.getProductCode() == null || callback.getProductCode().isBlank()) {
                throw new CheckedException("生产执行回传必须包含计划号和产品编码");
            }
        }
        Map<String, PlnIssueEntity> plnIssueEntityMap = this.list(new LambdaQueryWrapper<PlnIssueEntity>().in(PlnIssueEntity::getIssueNo,
                        omsOrderLifeCycleDtoList.stream().map(OmsOrderLifeCycleDTO::getNo).collect(Collectors.toList())))
                .stream().collect(Collectors.toMap(entity -> entity.getIssueNo() + "_" + entity.getProductCode(), Function.identity(), (v1, v2) -> v1));
        // 生产排产结果
        Map<Long, PlnIssueEntity> updateMap = new LinkedHashMap<>();
        // mrp 完成数量修改
        List<OmsOrderLifeCycleDTO> matMrpList = new ArrayList<>();
        List<PlnIssueCallbackReceiptEntity> receipts = new ArrayList<>();
        for (OmsOrderLifeCycleDTO omsOrderLifeCycleDTO : omsOrderLifeCycleDtoList) {
            PlnIssueEntity mappedIssue = plnIssueEntityMap.get(omsOrderLifeCycleDTO.getNo() + "_" + omsOrderLifeCycleDTO.getProductCode());
            if (mappedIssue == null) {
                throw new CheckedException("未找到生产执行回传对应的计划：" + omsOrderLifeCycleDTO.getNo()
                        + "/" + omsOrderLifeCycleDTO.getProductCode());
            }
            String requestId = omsOrderLifeCycleDTO.getRequestId();
            String requestFingerprint = callbackFingerprint(omsOrderLifeCycleDTO);
            if (requestId != null && !requestId.isBlank()) {
                requestId = requestId.trim();
                if (requestId.length() > 200) {
                    throw new CheckedException("生产执行回传幂等号长度不能超过200");
                }
                plnIssueCallbackReceiptMapper.lockRequest(requestId);
                PlnIssueCallbackReceiptEntity existing = plnIssueCallbackReceiptMapper.selectOne(
                        new LambdaQueryWrapper<PlnIssueCallbackReceiptEntity>()
                                .eq(PlnIssueCallbackReceiptEntity::getRequestId, requestId), false);
                if (existing != null) {
                    if (!Objects.equals(existing.getRequestFingerprint(), requestFingerprint)) {
                        throw new CheckedException("生产执行回传幂等号已使用且载荷不一致：" + requestId);
                    }
                    continue;
                }
            }
            PlnIssueEntity issue = updateMap.get(mappedIssue.getId());
            if (issue == null) {
                issue = baseMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                        .eq(PlnIssueEntity::getId, mappedIssue.getId()).last("FOR UPDATE"));
                if (issue == null) throw new CheckedException("生产执行回传锁定计划失败：" + mappedIssue.getIssueNo());
                updateMap.put(issue.getId(), issue);
            }
            if (omsOrderLifeCycleDTO.getFinishNum() != null) {
                issue.setFinishNum(addNonNegative(issue.getFinishNum(), omsOrderLifeCycleDTO.getFinishNum(), "完成"));
            }
            issue.setUnqualifiedNum(addNullable(issue.getUnqualifiedNum(), omsOrderLifeCycleDTO.getUnqualifiedNum(), "直接不合格"));
            issue.setQualityIsolatedNum(addNullable(issue.getQualityIsolatedNum(), omsOrderLifeCycleDTO.getQualityIsolatedNum(), "质量隔离"));
            issue.setQualityReleasedNum(addNullable(issue.getQualityReleasedNum(), omsOrderLifeCycleDTO.getQualityReleasedNum(), "质量放行"));
            issue.setQualityRejectedNum(addNullable(issue.getQualityRejectedNum(), omsOrderLifeCycleDTO.getQualityRejectedNum(), "质量判退"));
            issue.setScrapNum(addNullable(issue.getScrapNum(), omsOrderLifeCycleDTO.getScrapNum(), "报废"));
            issue.setReworkNum(addNullable(issue.getReworkNum(), omsOrderLifeCycleDTO.getReworkNum(), "返工创建"));
            issue.setReworkCompletedNum(addNullable(issue.getReworkCompletedNum(), omsOrderLifeCycleDTO.getReworkCompletedNum(), "返工完工"));
            issue.setReworkQualifiedNum(addNullable(issue.getReworkQualifiedNum(), omsOrderLifeCycleDTO.getReworkQualifiedNum(), "返工复检放行"));
            issue.setReworkRejectedNum(addNullable(issue.getReworkRejectedNum(), omsOrderLifeCycleDTO.getReworkRejectedNum(), "返工复检拒绝"));
            validateQualityDispositionQuantity(issue);
            validateReworkQuantity(issue);
            if ("mrp".equals(issue.getType()) && omsOrderLifeCycleDTO.getFinishNum() != null) {
                matMrpList.add(omsOrderLifeCycleDTO);
            }
            if (omsOrderLifeCycleDTO.getIssueStatus() != null && !omsOrderLifeCycleDTO.getIssueStatus().isBlank()) {
                issue.setStatus(omsOrderLifeCycleDTO.getIssueStatus());
            }
            if (omsOrderLifeCycleDTO.getFinishTime() != null) {
                issue.setFinishTime(omsOrderLifeCycleDTO.getFinishTime());
            }
            if (requestId != null && !requestId.isBlank()) {
                receipts.add(new PlnIssueCallbackReceiptEntity()
                        .setRequestId(requestId).setRequestFingerprint(requestFingerprint)
                        .setSourceType(omsOrderLifeCycleDTO.getType()).setIssueId(issue.getId())
                        .setIssueNo(issue.getIssueNo()).setProductCode(issue.getProductCode())
                        .setIssueStatus(omsOrderLifeCycleDTO.getIssueStatus())
                        .setLifeCycleStatus(omsOrderLifeCycleDTO.getStatus())
                        .setFinishQuantity(omsOrderLifeCycleDTO.getFinishNum())
                        .setUnqualifiedQuantity(omsOrderLifeCycleDTO.getUnqualifiedNum())
                        .setQualityIsolatedQuantity(omsOrderLifeCycleDTO.getQualityIsolatedNum())
                        .setQualityReleasedQuantity(omsOrderLifeCycleDTO.getQualityReleasedNum())
                        .setQualityRejectedQuantity(omsOrderLifeCycleDTO.getQualityRejectedNum())
                        .setScrapQuantity(omsOrderLifeCycleDTO.getScrapNum())
                        .setReworkQuantity(omsOrderLifeCycleDTO.getReworkNum())
                        .setReworkCompletedQuantity(omsOrderLifeCycleDTO.getReworkCompletedNum())
                        .setReworkQualifiedQuantity(omsOrderLifeCycleDTO.getReworkQualifiedNum())
                        .setReworkRejectedQuantity(omsOrderLifeCycleDTO.getReworkRejectedNum())
                        .setReceivedTime(LocalDateTime.now()).setFactoryCode(issue.getFactoryCode()));
            }
        }

        if (matMrpList.size() > 0) {
            planMatMrpService.completeNumHandle(matMrpList);
        }
        if (!updateMap.isEmpty() && !this.updateBatchById(updateMap.values())) {
            return false;
        }
        for (PlnIssueCallbackReceiptEntity receipt : receipts) {
            plnIssueCallbackReceiptMapper.insert(receipt);
            PlnIssueEntity issue = updateMap.get(receipt.getIssueId());
            // 回执与事件收件箱同事务落库；后续由独立事件事务执行补产履约和质量阈值评估。
            plnAutomationEventService.receiveQualityCallback(receipt, issue);
        }
        return true;
    }

    private String callbackFingerprint(OmsOrderLifeCycleDTO callback) {
        try {
            String raw = String.join("|", String.valueOf(callback.getNo()), String.valueOf(callback.getProductCode()),
                    String.valueOf(callback.getType()), String.valueOf(callback.getStatus()),
                    String.valueOf(callback.getIssueStatus()), String.valueOf(callback.getFinishNum()),
                    String.valueOf(callback.getUnqualifiedNum()), String.valueOf(callback.getQualityIsolatedNum()),
                    String.valueOf(callback.getQualityReleasedNum()), String.valueOf(callback.getQualityRejectedNum()),
                    String.valueOf(callback.getScrapNum()), String.valueOf(callback.getReworkNum()),
                    String.valueOf(callback.getReworkCompletedNum()), String.valueOf(callback.getReworkQualifiedNum()),
                    String.valueOf(callback.getReworkRejectedNum()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("生产执行回传指纹生成失败");
        }
    }

    private BigDecimal addNullable(BigDecimal current, BigDecimal delta, String label) {
        return delta == null ? current : addNonNegative(current, delta, label);
    }

    private BigDecimal addNonNegative(BigDecimal current, BigDecimal delta, String label) {
        BigDecimal result = Optional.ofNullable(current).orElse(BigDecimal.ZERO).add(delta);
        if (result.signum() < 0) throw new CheckedException(label + "累计数量不能为负");
        return result;
    }

    private void validateReworkQuantity(PlnIssueEntity issue) {
        BigDecimal created = Optional.ofNullable(issue.getReworkNum()).orElse(BigDecimal.ZERO);
        BigDecimal completed = Optional.ofNullable(issue.getReworkCompletedNum()).orElse(BigDecimal.ZERO);
        BigDecimal qualified = Optional.ofNullable(issue.getReworkQualifiedNum()).orElse(BigDecimal.ZERO);
        BigDecimal rejected = Optional.ofNullable(issue.getReworkRejectedNum()).orElse(BigDecimal.ZERO);
        if (completed.compareTo(created) > 0 || qualified.add(rejected).compareTo(completed) > 0) {
            throw new CheckedException("计划返工创建、完工与复检处置数量不守恒：" + issue.getIssueNo());
        }
    }

    private void validateQualityDispositionQuantity(PlnIssueEntity issue) {
        BigDecimal unqualified = Optional.ofNullable(issue.getUnqualifiedNum()).orElse(BigDecimal.ZERO);
        BigDecimal isolated = Optional.ofNullable(issue.getQualityIsolatedNum()).orElse(BigDecimal.ZERO);
        BigDecimal released = Optional.ofNullable(issue.getQualityReleasedNum()).orElse(BigDecimal.ZERO);
        if (released.compareTo(unqualified.max(isolated)) > 0) {
            throw new CheckedException("计划质量放行累计量超过已上报不合格或已隔离数量：" + issue.getIssueNo());
        }
    }

    /**
     * 根据产品编码和下达日期查询列表
     *
     * @param productCode 产品编码
     * @param issueTime   下达时间
     * @param status      状态
     * @return R
     */
    @Override
    public List<PlnIssueEntity> getIssueListByDate(String productCode, LocalDate issueDate, String status) {
        return this.list(new LambdaQueryWrapper<PlnIssueEntity>().eq(PlnIssueEntity::getProductCode, productCode)
                .between(PlnIssueEntity::getIssueTime, issueDate.atStartOfDay(), issueDate.atTime(LocalTime.MAX))
                .eq(PlnIssueEntity::getStatus, status)
                .isNotNull(PlnIssueEntity::getNum).orderByDesc(PlnIssueEntity::getCreateTime));
    }

    /**
     * 过滤数据
     *
     * @param issueList 原始记录列表
     * @return 过滤后的记录列表
     */
    private List<PlnIssueEntity> filterByMinStatus(List<PlnIssueEntity> issueList) {
        // 使用 Map 来分组，键为 productCode 和 issueTime 的组合，值为每组中 status 最小的 PlnIssueEntity 对象
        Map<String, PlnIssueEntity> map = new HashMap<>();
        for (PlnIssueEntity entity : issueList) {
            String key = entity.getProductCode() + "-" + entity.getIssueTime();
            if (!map.containsKey(key)) {
                map.put(key, entity);
            } else {
                PlnIssueEntity existingEntity = map.get(key);
                // 将 String 类型的 status 转换为 int 类型进行比较
                int currentStatus = Integer.parseInt(entity.getStatus());
                int existingStatus = Integer.parseInt(existingEntity.getStatus());
                if (currentStatus < existingStatus) {
                    map.put(key, entity);
                }
            }
        }
        // 将 Map 中的值转换为列表
        return new ArrayList<>(map.values());
    }
}
