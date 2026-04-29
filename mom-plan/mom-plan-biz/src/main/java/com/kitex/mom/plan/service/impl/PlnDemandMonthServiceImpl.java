package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteCustomerService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthBalanceDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthDispatchDTO;
import com.kitex.mom.plan.entity.PlnDemandMonthEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.mapper.PlnDemandMonthMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnIssueMonthMapper;
import com.kitex.mom.plan.service.PlnDemandMonthService;
import com.kitex.mom.plan.vo.PlnDemandMonthDeviationVO;
import com.kitex.mom.plan.vo.PlnDemandMonthExcelVO;
import com.kitex.mom.plan.vo.PlnDemandMonthReadinessVO;
import com.kitex.mom.plan.vo.PlnDemandMonthVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计划月需求表
 *
 * @author kitex
 * @date 2025-02-10 09:08:44
 */
@Service
@AllArgsConstructor
public class PlnDemandMonthServiceImpl extends ServiceImpl<PlnDemandMonthMapper, PlnDemandMonthEntity> implements PlnDemandMonthService {

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteCustomerService remoteCustomerService;

    private final PlnIssueMapper plnIssueMapper;

    private final PlnIssueMonthMapper plnIssueMonthMapper;

    /**
     * 通过条件查询需求
     *
     * @param monthPlanQueryDTO 查询条件
     */
    @Override
    public List<PlnDemandMonthVO> getDemand(MonthPlanQueryDTO monthPlanQueryDTO) {
        LambdaQueryWrapper<PlnDemandMonthEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(ObjUtil.isNotEmpty(monthPlanQueryDTO.getMatCode()), PlnDemandMonthEntity::getProductCode, monthPlanQueryDTO.getMatCode());
        wrapper.like(ObjUtil.isNotEmpty(monthPlanQueryDTO.getMatName()), PlnDemandMonthEntity::getProductName, monthPlanQueryDTO.getMatName());
        wrapper.like(ObjUtil.isNotEmpty(monthPlanQueryDTO.getCustomerCode()), PlnDemandMonthEntity::getCustomerCode, monthPlanQueryDTO.getCustomerCode());
        wrapper.eq(ObjUtil.isNotEmpty(monthPlanQueryDTO.getSourceType()), PlnDemandMonthEntity::getSourceType, monthPlanQueryDTO.getSourceType());
        wrapper.eq(ObjUtil.isNotEmpty(monthPlanQueryDTO.getVersionNo()), PlnDemandMonthEntity::getVersionNo, monthPlanQueryDTO.getVersionNo());
        wrapper.eq(monthPlanQueryDTO.getEffectiveFlag() != null, PlnDemandMonthEntity::getEffectiveFlag, monthPlanQueryDTO.getEffectiveFlag());
        wrapper.eq(PlnDemandMonthEntity::getDemandTime, monthPlanQueryDTO.getTime());
        wrapper.orderByDesc(PlnDemandMonthEntity::getCreateTime);
        List<PlnDemandMonthEntity> list = this.list(wrapper);
        Map<String, PlnDemandMonthEntity> latestMap = new LinkedHashMap<>();
        for (PlnDemandMonthEntity entity : list) {
            latestMap.putIfAbsent(entity.getProductCode(), entity);
        }
        // 转换为去重后的列表
        List<PlnDemandMonthEntity> plnDemandMonthEntities = new ArrayList<>(latestMap.values());

        List<PlnDemandMonthVO> plnDemandMonthVOS = new ArrayList<>();
        for (PlnDemandMonthEntity plnDemandMonthEntity : plnDemandMonthEntities) {
            PlnDemandMonthVO plnDemandMonthVO = new PlnDemandMonthVO();
            BeanUtils.copyProperties(plnDemandMonthEntity, plnDemandMonthVO);
            plnDemandMonthVOS.add(plnDemandMonthVO);
        }
        return plnDemandMonthVOS;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean balance(PlnDemandMonthBalanceDTO balanceDTO) {
        if (balanceDTO == null || balanceDTO.getTime() == null) {
            throw new CheckedException("平衡参数不能为空");
        }
        LocalDate targetMonth = balanceDTO.getTime().withDayOfMonth(1);

        LambdaQueryWrapper<PlnDemandMonthEntity> query = Wrappers.lambdaQuery();
        query.eq(PlnDemandMonthEntity::getDemandTime, targetMonth);
        query.eq(ObjUtil.isNotEmpty(balanceDTO.getVersionNo()), PlnDemandMonthEntity::getVersionNo, balanceDTO.getVersionNo());
        List<PlnDemandMonthEntity> sourceList = this.list(query);
        if (sourceList == null || sourceList.isEmpty()) {
            return true;
        }

        Map<String, List<PlnDemandMonthEntity>> grouped = sourceList.stream().collect(Collectors.groupingBy(item ->
                String.join("#",
                        Optional.ofNullable(item.getFactoryCode()).orElse(""),
                        Optional.ofNullable(item.getCustomerCode()).orElse(""),
                        Optional.ofNullable(item.getProductCode()).orElse(""),
                        String.valueOf(item.getDemandTime()),
                        Optional.ofNullable(item.getVersionNo()).orElse("")
                )));

        for (List<PlnDemandMonthEntity> group : grouped.values()) {
            java.math.BigDecimal forecast = java.math.BigDecimal.ZERO;
            java.math.BigDecimal confirmed = java.math.BigDecimal.ZERO;
            for (PlnDemandMonthEntity item : group) {
                java.math.BigDecimal demand = item.getTotalDemandNum() == null ? java.math.BigDecimal.ZERO : item.getTotalDemandNum();
                if ("CONFIRMED".equalsIgnoreCase(item.getSourceType())) {
                    confirmed = confirmed.max(demand);
                } else {
                    forecast = forecast.max(demand);
                }
            }
            java.math.BigDecimal executable = forecast.max(confirmed);
            for (PlnDemandMonthEntity item : group) {
                item.setExecutableNum(executable);
                if (balanceDTO.getEffectiveFlag() != null) {
                    item.setEffectiveFlag(balanceDTO.getEffectiveFlag());
                }
                this.updateById(item);
            }
        }
        return true;
    }

    @Override
    public List<PlnDemandMonthDeviationVO> deviation(MonthPlanQueryDTO monthPlanQueryDTO) {
        if (monthPlanQueryDTO == null || monthPlanQueryDTO.getTime() == null) {
            throw new CheckedException("查询月份不能为空");
        }
        LocalDate targetMonth = monthPlanQueryDTO.getTime().withDayOfMonth(1);

        LambdaQueryWrapper<PlnDemandMonthEntity> demandQuery = Wrappers.lambdaQuery();
        demandQuery.eq(PlnDemandMonthEntity::getDemandTime, targetMonth);
        demandQuery.eq(ObjUtil.isNotEmpty(monthPlanQueryDTO.getVersionNo()), PlnDemandMonthEntity::getVersionNo, monthPlanQueryDTO.getVersionNo());
        List<PlnDemandMonthEntity> monthDemands = this.list(demandQuery);
        if (CollUtil.isEmpty(monthDemands)) {
            return Collections.emptyList();
        }

        Map<String, List<PlnDemandMonthEntity>> groupMap = monthDemands.stream().collect(Collectors.groupingBy(item ->
                String.join("#",
                        Optional.ofNullable(item.getFactoryCode()).orElse(""),
                        Optional.ofNullable(item.getCustomerCode()).orElse(""),
                        Optional.ofNullable(item.getProductCode()).orElse(""),
                        Optional.ofNullable(item.getVersionNo()).orElse("")
                )));

        LocalDate monthEnd = targetMonth.withDayOfMonth(targetMonth.lengthOfMonth());
        LambdaQueryWrapper<PlnIssueEntity> issueQuery = Wrappers.lambdaQuery();
        issueQuery.eq(PlnIssueEntity::getType, "month_dispatch");
        issueQuery.between(PlnIssueEntity::getDemandTime, targetMonth, monthEnd);
        issueQuery.eq(ObjUtil.isNotEmpty(monthPlanQueryDTO.getVersionNo()), PlnIssueEntity::getPlanType, monthPlanQueryDTO.getVersionNo());
        List<PlnIssueEntity> issueList = plnIssueMapper.selectList(issueQuery);
        Map<String, java.math.BigDecimal> executedMap = issueList.stream().collect(Collectors.groupingBy(item ->
                        String.join("#",
                                Optional.ofNullable(item.getFactoryCode()).orElse(""),
                                Optional.ofNullable(item.getCustomerCode()).orElse(""),
                                Optional.ofNullable(item.getProductCode()).orElse(""),
                                Optional.ofNullable(item.getPlanType()).orElse("")
                        ),
                Collectors.mapping(item -> Optional.ofNullable(item.getNum()).orElse(java.math.BigDecimal.ZERO),
                        Collectors.reducing(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))));

        List<PlnDemandMonthDeviationVO> result = new ArrayList<>();
        for (Map.Entry<String, List<PlnDemandMonthEntity>> entry : groupMap.entrySet()) {
            List<PlnDemandMonthEntity> group = entry.getValue();
            PlnDemandMonthEntity sample = group.get(0);
            java.math.BigDecimal forecast = java.math.BigDecimal.ZERO;
            java.math.BigDecimal confirmed = java.math.BigDecimal.ZERO;
            java.math.BigDecimal executable = java.math.BigDecimal.ZERO;
            for (PlnDemandMonthEntity item : group) {
                java.math.BigDecimal demand = Optional.ofNullable(item.getTotalDemandNum()).orElse(java.math.BigDecimal.ZERO);
                if ("CONFIRMED".equalsIgnoreCase(item.getSourceType())) {
                    confirmed = confirmed.max(demand);
                } else {
                    forecast = forecast.max(demand);
                }
                executable = executable.max(Optional.ofNullable(item.getExecutableNum()).orElse(java.math.BigDecimal.ZERO));
            }
            java.math.BigDecimal executed = executedMap.getOrDefault(entry.getKey(), java.math.BigDecimal.ZERO);

            PlnDemandMonthDeviationVO vo = new PlnDemandMonthDeviationVO();
            vo.setFactoryCode(sample.getFactoryCode());
            vo.setCustomerCode(sample.getCustomerCode());
            vo.setCustomerName(sample.getCustomerName());
            vo.setProductCode(sample.getProductCode());
            vo.setProductName(sample.getProductName());
            vo.setForecastNum(forecast);
            vo.setConfirmedNum(confirmed);
            vo.setExecutableNum(executable);
            vo.setExecutedNum(executed);
            vo.setDiffConfirmedForecast(confirmed.subtract(forecast));
            vo.setDiffExecutedConfirmed(executed.subtract(confirmed));
            result.add(vo);
        }
        return result;
    }

    @Override
    public PlnDemandMonthReadinessVO readiness(MonthPlanQueryDTO monthPlanQueryDTO) {
        if (monthPlanQueryDTO == null || monthPlanQueryDTO.getTime() == null) {
            throw new CheckedException("查询月份不能为空");
        }
        LocalDate targetMonth = monthPlanQueryDTO.getTime().withDayOfMonth(1);
        LocalDate monthEnd = targetMonth.withDayOfMonth(targetMonth.lengthOfMonth());

        LambdaQueryWrapper<PlnDemandMonthEntity> demandQuery = Wrappers.lambdaQuery();
        demandQuery.eq(PlnDemandMonthEntity::getDemandTime, targetMonth);
        demandQuery.eq(ObjUtil.isNotEmpty(monthPlanQueryDTO.getVersionNo()), PlnDemandMonthEntity::getVersionNo, monthPlanQueryDTO.getVersionNo());
        List<PlnDemandMonthEntity> demandList = this.list(demandQuery);

        boolean balanced = false;
        if (CollUtil.isNotEmpty(demandList)) {
            balanced = demandList.stream().allMatch(item -> item.getExecutableNum() != null);
        }

        LambdaQueryWrapper<PlnIssueEntity> issueQuery = Wrappers.lambdaQuery();
        issueQuery.eq(PlnIssueEntity::getType, "month_dispatch");
        issueQuery.between(PlnIssueEntity::getDemandTime, targetMonth, monthEnd);
        issueQuery.eq(ObjUtil.isNotEmpty(monthPlanQueryDTO.getVersionNo()), PlnIssueEntity::getPlanType, monthPlanQueryDTO.getVersionNo());
        long dispatchedCount = plnIssueMapper.selectCount(issueQuery);

        PlnDemandMonthReadinessVO vo = new PlnDemandMonthReadinessVO();
        vo.setBalanced(balanced);
        vo.setDispatched(dispatchedCount > 0);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean dispatchToIssue(PlnDemandMonthDispatchDTO dispatchDTO) {
        if (dispatchDTO == null || dispatchDTO.getTime() == null) {
            throw new CheckedException("下推参数不能为空");
        }
        if (ObjUtil.isEmpty(dispatchDTO.getVersionNo())) {
            throw new CheckedException("下推版本不能为空");
        }
        LocalDate targetMonth = dispatchDTO.getTime().withDayOfMonth(1);
        LocalDate monthEnd = targetMonth.withDayOfMonth(targetMonth.lengthOfMonth());

        LambdaQueryWrapper<PlnDemandMonthEntity> query = Wrappers.lambdaQuery();
        query.eq(PlnDemandMonthEntity::getDemandTime, targetMonth);
        query.eq(ObjUtil.isNotEmpty(dispatchDTO.getVersionNo()), PlnDemandMonthEntity::getVersionNo, dispatchDTO.getVersionNo());
        query.eq(PlnDemandMonthEntity::getEffectiveFlag, true);
        List<PlnDemandMonthEntity> demandList = this.list(query);
        if (CollUtil.isEmpty(demandList)) {
            return true;
        }

        for (PlnDemandMonthEntity demand : demandList) {
            java.math.BigDecimal executable = demand.getExecutableNum() == null ? java.math.BigDecimal.ZERO : demand.getExecutableNum();
            if (executable.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                continue;
            }
            int days = targetMonth.lengthOfMonth();
            java.math.BigDecimal[] divRem = executable.divideAndRemainder(java.math.BigDecimal.valueOf(days));
            java.math.BigDecimal dailyBase = divRem[0];
            int remainder = divRem[1].intValue();

            for (int i = 0; i < days; i++) {
                LocalDate day = targetMonth.plusDays(i);
                if (day.isAfter(monthEnd)) {
                    break;
                }
                java.math.BigDecimal dayNum = dailyBase.add(i < remainder ? java.math.BigDecimal.ONE : java.math.BigDecimal.ZERO);
                if (dayNum.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                    continue;
                }

                PlnIssueEntity existing = plnIssueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                        .eq(PlnIssueEntity::getFactoryCode, demand.getFactoryCode())
                        .eq(PlnIssueEntity::getCustomerCode, demand.getCustomerCode())
                        .eq(PlnIssueEntity::getProductCode, demand.getProductCode())
                        .eq(PlnIssueEntity::getDemandTime, day)
                        .eq(PlnIssueEntity::getType, "month_dispatch")
                        .eq(PlnIssueEntity::getPlanType, dispatchDTO.getVersionNo())
                        .last("limit 1"));

                if (existing == null) {
                    PlnIssueEntity issue = new PlnIssueEntity();
                    issue.setFactoryCode(demand.getFactoryCode());
                    issue.setCustomerCode(demand.getCustomerCode());
                    issue.setCustomerName(demand.getCustomerName());
                    issue.setProductCode(demand.getProductCode());
                    issue.setProductName(demand.getProductName());
                    issue.setSpecificationModel(demand.getSpecificationModel());
                    issue.setMaterialAttribute(demand.getMaterialAttribute());
                    issue.setNum(dayNum);
                    issue.setDemandTime(day);
                    issue.setIssueTime(day.atStartOfDay());
                    issue.setType("month_dispatch");
                    issue.setPlanType(dispatchDTO.getVersionNo());
                    issue.setStatus(IssueStatusEnum.UNISSUED.getCode());
                    issue.setLockStatus(Boolean.FALSE);
                    plnIssueMapper.insert(issue);
                } else {
                    existing.setNum(dayNum);
                    existing.setIssueTime(day.atStartOfDay());
                    plnIssueMapper.updateById(existing);
                }
            }
        }
        return true;
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料月需求列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    public R importPlanDemandMonth(List<PlnDemandMonthExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        // 物料列表
        Map<String, MdmCustomerEntity> customerMap = remoteCustomerService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(),
                        (a, b) -> a));
        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(),
                        (a, b) -> a));

        List<PlnDemandMonthEntity> insertList = new ArrayList<>();
        for (PlnDemandMonthExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            MdmCustomerEntity mdmCustomerEntity = customerMap.get(excelVO.getCustomerCode());
            if (mdmCustomerEntity == null) {
                errorMsg.add(excelVO.getCustomerCode() + "客户编码不存在");
            }
            MdmMaterialEntity mdmMaterialEntity = materialMap.get(excelVO.getProductCode());
            if (mdmMaterialEntity == null) {
                errorMsg.add(excelVO.getProductCode() + "物料编码不存在");
            }
            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                insertList.add(insertExcel(excelVO, mdmCustomerEntity, mdmMaterialEntity));
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "物料月需求导入成功");
    }

    private PlnDemandMonthEntity insertExcel(PlnDemandMonthExcelVO excelVO, MdmCustomerEntity mdmCustomerEntity,
                                             MdmMaterialEntity mdmMaterialEntity) {
        PlnDemandMonthEntity plnDemandMonthEntity = new PlnDemandMonthEntity();
        BeanUtils.copyProperties(excelVO, plnDemandMonthEntity);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM");
        YearMonth yearMonth = YearMonth.parse(excelVO.getDemandTime(), formatter);

        // 获取该月的第一天
        LocalDate firstDayOfMonth = yearMonth.atDay(1); // 使用 atDay 方法获取第一天
        // 获取该月的第一天
        plnDemandMonthEntity.setDemandTime(firstDayOfMonth);
        plnDemandMonthEntity.setCustomerName(mdmCustomerEntity.getCustomerName());
        plnDemandMonthEntity.setProductName(mdmMaterialEntity.getMaterialName());
        plnDemandMonthEntity.setSpecificationModel(mdmMaterialEntity.getSpecificationModel());
        plnDemandMonthEntity.setMaterialAttribute(mdmMaterialEntity.getMaterialAttribute());
        return plnDemandMonthEntity;
    }
}