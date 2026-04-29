package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.constant.ModuleEnum;
import com.kitex.mom.plan.constant.UpdateTypeEnum;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnIssueFeedbackEntity;
import com.kitex.mom.plan.entity.PlnUpdateLogEntity;
import com.kitex.mom.plan.mapper.PlnIssueFeedbackMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnIssueService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.PlnUpdateLogService;
import com.kitex.mom.plan.vo.PlnIssueMonthVO;
import com.kitex.mom.plan.vo.PlnIssueVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * 新增或修改计划下达任务
     *
     * @param plnIssueVO 计划下达VO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveOrUpdatePlnIssue(PlnIssueVO plnIssueVO) {
        if (plnIssueVO.getIssueTime().isBefore(LocalDateTime.now())) {
            throw new CheckedException("计划下达时间不能小于当前时间");
        }
        if (plnIssueVO.getIssueId() != null) {
            PlnIssueEntity plnIssueEntity = this.getById(plnIssueVO.getIssueId());
            return checkUpdateIssue(plnIssueEntity, plnIssueVO.getNum());
        }
        PlnIssueEntity plnIssue = new PlnIssueEntity();
        BeanUtils.copyProperties(plnIssueVO, plnIssue);
        plnIssue.setIssueNo(remoteSeqConfigService.create("plan_mes").getData());
        // 新增日志保存
        PlnUpdateLogEntity initialLog = createUpdateLog(plnIssue, UpdateTypeEnum.NUM_ADD, null, plnIssueVO.getNum().toString());
        plnUpdateLogService.saveUpdateLog(Collections.singletonList(initialLog));
        return this.save(plnIssue);
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
        List<PlnIssueEntity> plnIssueEntityList = this.listByIds(ids);
        for (PlnIssueEntity plnIssueEntity : plnIssueEntityList) {
            if ("1".equals(plnIssueEntity.getStatus())) {
                throw new CheckedException("该计划已下发，不能删除");
            }
        }
        this.removeByIds(ids);
        return Boolean.TRUE;
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
    public Boolean updateIssue(PlnIssueEntity plnIssue) {
        PlnIssueEntity plnIssueEntity = this.getById(plnIssue.getId());

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
     * 修改生产生产排产结果表
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean edit(List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList) {
        Map<String, PlnIssueEntity> plnIssueEntityMap = this.list(new LambdaQueryWrapper<PlnIssueEntity>().in(PlnIssueEntity::getIssueNo,
                        omsOrderLifeCycleDtoList.stream().map(OmsOrderLifeCycleDTO::getNo).collect(Collectors.toList())))
                .stream().collect(Collectors.toMap(entity -> entity.getIssueNo() + "_" + entity.getProductCode(), Function.identity(), (v1, v2) -> v1));
        // 生产排产结果
        List<PlnIssueEntity> updateList = new ArrayList<>();
        // mrp 完成数量修改
        List<OmsOrderLifeCycleDTO> matMrpList = new ArrayList<>();
        for (OmsOrderLifeCycleDTO omsOrderLifeCycleDTO : omsOrderLifeCycleDtoList) {
            PlnIssueEntity issue = plnIssueEntityMap.get(omsOrderLifeCycleDTO.getNo() + "_" + omsOrderLifeCycleDTO.getProductCode());
            if (omsOrderLifeCycleDTO.getFinishNum() != null) {
                issue.setFinishNum(Optional.ofNullable(issue.getFinishNum()).orElse(BigDecimal.ZERO)
                        .add(omsOrderLifeCycleDTO.getFinishNum()));
            }
            if ("mrp".equals(issue.getType())) {
                matMrpList.add(omsOrderLifeCycleDTO);
            }
            issue.setStatus(omsOrderLifeCycleDTO.getIssueStatus());
            issue.setFinishTime(omsOrderLifeCycleDTO.getFinishTime());
            updateList.add(issue);
        }

        if (matMrpList.size() > 0) {
            planMatMrpService.completeNumHandle(matMrpList);
        }
        return this.updateBatchById(updateList);
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