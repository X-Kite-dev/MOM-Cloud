package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import com.kitex.mom.plan.feign.RemotePlnIssueMonthService;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.enums.PmsPlanStatusEnum;
import com.kitex.mom.pms.mapper.PmsPlanMapper;
import com.kitex.mom.pms.service.PmsPlanService;
import com.kitex.mom.pms.vo.PmsPlanExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 采购计划表
 *
 * @author kitex
 * @date 2025-01-07 09:42:33
 */
@Service
@AllArgsConstructor
public class PmsPlanServiceImpl extends ServiceImpl<PmsPlanMapper, PmsPlanEntity> implements PmsPlanService {

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteSeqConfigService remoteSeqConfigService;

    private final RemotePlnIssueMonthService remotePlnIssueMonthService;

    /**
     * 导入excel 表格
     *
     * @param excelVOList   采购计划列表
     * @param bindingResult 错误信息列表
     * @param factoryCode   所属工厂编码
     * @return R
     */
    @Override
    public R importPmsPlan(List<PmsPlanExcelVO> excelVOList, BindingResult bindingResult, String factoryCode) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (a, b) -> a));
        List<PmsPlanEntity> insertList = new ArrayList<>();
        for (PmsPlanExcelVO pmsPlanExcel : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            MdmMaterialEntity material = materialMap.get(pmsPlanExcel.getMatCode());
            if (material == null) {
                errorMsg.add(pmsPlanExcel.getMatCode() + "物料编码不存在");
            }
            String pmsPlanNo = remoteSeqConfigService.create("pms_plan").getData();
            if (StrUtil.isBlank(pmsPlanNo)) {
                errorMsg.add("采购计划号生成失败");
            }
            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                insertList.add(insertExcel(pmsPlanExcel, material, pmsPlanNo, factoryCode));
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(pmsPlanExcel.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "采购计划导入成功");
    }

    /**
     * 导出excel 表格
     *
     * @param pmsPlan 查询条件
     * @param ids     导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<PmsPlanExcelVO> export(PmsPlanEntity pmsPlan, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(pmsPlan).in(ArrayUtil.isNotEmpty(ids), PmsPlanEntity::getId, ids))
                .stream()
                .map(pmsPlanEntity -> {
                    PmsPlanExcelVO pmsPlanExcelVO = new PmsPlanExcelVO();
                    BeanUtils.copyProperties(pmsPlanEntity, pmsPlanExcelVO);
                    pmsPlanExcelVO.setStatus(PmsPlanStatusEnum.getDescByCode(pmsPlanEntity.getStatus()));
                    return pmsPlanExcelVO;
                }).collect(Collectors.toList());
    }

    /**
     * 插入excel
     */
    private PmsPlanEntity insertExcel(PmsPlanExcelVO excelVO, MdmMaterialEntity material, String pmsPlanNo, String factoryCode) {
        PmsPlanEntity pmsPlan = new PmsPlanEntity();
        BeanUtils.copyProperties(excelVO, pmsPlan);
        pmsPlan.setPmsPlanNo(pmsPlanNo);
        pmsPlan.setMatName(material.getMaterialName());
        pmsPlan.setMatUnit(material.getBasicUnit());
        pmsPlan.setMatSpec(material.getSpecificationModel());
        pmsPlan.setStatus(PmsPlanStatusEnum.NO_CREATEK.getCode());
        pmsPlan.setFactoryCode(factoryCode);
        return pmsPlan;
    }

    /**
     * 通过时间和物料编码查询采购计划表
     *
     * @param templateQueryDTO 查询条件
     * @return R
     */
    @Override
    public List<Map<String, String>> getPlnDemand(TemporaryPlanQueryDTO templateQueryDTO) {
        QueryWrapper<PmsPlanEntity> wrapper = Wrappers.query();
        wrapper.select("sum( plan_order_num - IFNULL(plan_arrival_num,0) ) as planArrivalNum", "mat_code", "mat_type");
        wrapper.lambda().in(ObjUtil.isNotEmpty(templateQueryDTO.getMatCodes()), PmsPlanEntity::getMatCode, templateQueryDTO.getMatCodes());
        wrapper.lambda().like(ObjUtil.isNotEmpty(templateQueryDTO.getMatName()), PmsPlanEntity::getMatName, templateQueryDTO.getMatName());
        wrapper.lambda().le(PmsPlanEntity::getPlanDeliveryTime, templateQueryDTO.getTime());
        wrapper.lambda().isNotNull(PmsPlanEntity::getOrderNo);
        wrapper.lambda().groupBy(PmsPlanEntity::getMatCode, PmsPlanEntity::getMatType);
        List<PmsPlanEntity> list = baseMapper.getPmsPlanList(wrapper);

        Map<String, Map<String, String>> result = list.stream().collect(Collectors.groupingBy(
                PmsPlanEntity::getMatCode,
                Collectors.toMap(
                        PmsPlanEntity::getMatType,
                        record -> Objects.toString(record.getPlanArrivalNum(), ""),
                        (existing, replacement) -> existing + ", " + replacement
                )));
        List<Map<String, String>> resultList = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : result.entrySet()) {
            Map<String, String> map = new HashMap<>(entry.getValue());
            map.put("productCode", entry.getKey());
            resultList.add(map);
        }
        return resultList;
    }

    /**
     * 修改采购计划下单数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePlanOrderNum(List<PmsPlanEntity> pmsPlanEntityList) {
        if (pmsPlanEntityList.isEmpty()) {
            return;
        }
        // 1. 获取计划编号列表
        List<String> planNos = pmsPlanEntityList.stream()
                .map(PmsPlanEntity::getPmsPlanNo)
                .collect(Collectors.toList());

        // 2. 批量查询现有计划
        Map<String, PmsPlanEntity> planMap = this.list(new LambdaUpdateWrapper<PmsPlanEntity>()
                        .in(PmsPlanEntity::getPmsPlanNo, planNos))
                .stream()
                .collect(Collectors.toMap(PmsPlanEntity::getPmsPlanNo, Function.identity()));

        // 3. 构建批量更新
        List<PmsPlanEntity> updateList = pmsPlanEntityList.stream()
                .map(plan -> {
                    PmsPlanEntity planEntity = planMap.get(plan.getPmsPlanNo());
                    double planOrderNum = Optional.ofNullable(planEntity.getPlanOrderNum()).orElse(0.0) + plan.getPlanOrderNum();
                    PmsPlanEntity entity = new PmsPlanEntity();
                    entity.setId(planEntity.getId());
                    entity.setPlanOrderNum(planOrderNum);
                    if (planOrderNum >= planEntity.getPmsNum()) {
                        entity.setStatus(PmsPlanStatusEnum.CREATE.getCode());
                    } else {
                        entity.setStatus(PmsPlanStatusEnum.CREATEING.getCode());
                    }
                    return entity;
                })
                .collect(Collectors.toList());

        // 4. 批量更新计划订单编号
        this.updateBatchById(updateList);

        // 5. 构建月度下发实体列表
        List<PlnIssueMonthEntity> issueMonthList = pmsPlanEntityList.stream()
                .filter(plan -> StrUtil.isNotBlank(plan.getOrderNo()))
                .map(plan -> {
                    PlnIssueMonthEntity issueMonth = new PlnIssueMonthEntity();
                    issueMonth.setIssueMonthNo(plan.getOrderNo());
                    issueMonth.setProductCode(plan.getMatCode());
                    issueMonth.setStatus("2");
                    return issueMonth;
                })
                .collect(Collectors.toList());

        if (issueMonthList.isEmpty()) {
            return;
        }
        // 6. 调用远程服务更新月度下发数据
        Boolean updateResult = remotePlnIssueMonthService.edit(issueMonthList).getData();
        if (updateResult == null || !updateResult) {
            throw new CheckedException("更新月度下发数据失败，请检查远程服务状态");
        }
    }

    /**
     * 处理采购计划到货数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateInNoticeSave(PmsOrderDetailEntity pmsOrderDetail, Double inNum) {
        PmsPlanEntity pmsPlanEntity = this.getOne(new LambdaQueryWrapper<PmsPlanEntity>().eq(PmsPlanEntity::getPmsPlanNo, pmsOrderDetail.getPmsPlanNo()));

        if (pmsPlanEntity == null) {
            return;
        }
        Double planArrivalNum = Optional.ofNullable(pmsPlanEntity.getPlanArrivalNum()).orElse(0.0);
        double num = planArrivalNum + inNum;
        pmsPlanEntity.setPlanArrivalNum(num);
        if (num >= pmsPlanEntity.getPmsNum()) {
            pmsPlanEntity.setStatus(PmsPlanStatusEnum.COMPLETE.getCode());
        }
        this.updateById(pmsPlanEntity);

        // 生产计划模块-采购计划排产推送
        if (StrUtil.isNotBlank(pmsPlanEntity.getOrderNo())) {
            PlnIssueMonthEntity issueMonth = new PlnIssueMonthEntity();
            issueMonth.setIssueMonthNo(pmsPlanEntity.getOrderNo());
            issueMonth.setProductCode(pmsPlanEntity.getMatCode());
            if (PmsPlanStatusEnum.COMPLETE.getCode().equals(pmsPlanEntity.getStatus())) {
                issueMonth.setStatus("3");
                issueMonth.setFinishTime(LocalDate.now());
            }
            // 更新计划模块采购排产计划对应数据的完成数量
            BigDecimal inNumDecimal = new BigDecimal(inNum);
            issueMonth.setFinishNum(inNumDecimal);
            // 调用远程服务更新月度下发数据
            Boolean updateResult = remotePlnIssueMonthService.edit(Collections.singletonList(issueMonth)).getData();
            if (!updateResult) {
                throw new CheckedException("更新月度下发数据失败，请检查远程服务状态");
            }
        }
    }

    /**
     * 根据采购计划下达号查询采购计划
     *
     * @param orderNo 采购计划下达号
     * @return R
     */
    @Override
    public List<PmsPlanEntity> omsLife(String orderNo) {
        return this.list(new LambdaQueryWrapper<PmsPlanEntity>().eq(PmsPlanEntity::getOrderNo, orderNo));
    }

    /**
     * 新增
     *
     * @param pmsPlan 采购计划
     */
    @Override
    @Transactional
    public Boolean savePlan(PmsPlanEntity pmsPlan) {
        return save(pmsPlan);
    }
}