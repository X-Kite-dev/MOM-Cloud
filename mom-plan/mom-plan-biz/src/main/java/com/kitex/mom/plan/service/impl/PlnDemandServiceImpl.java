package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteCustomerService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.PlanMesQueryDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnDemandEntity;
import com.kitex.mom.plan.entity.PlnDemandMonthEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.mapper.PlnDemandMapper;
import com.kitex.mom.plan.service.PlnDemandMonthService;
import com.kitex.mom.plan.service.PlnDemandService;
import com.kitex.mom.plan.service.PlnIssueService;
import com.kitex.mom.plan.vo.PlanDemandExcelVO;
import com.kitex.mom.plan.vo.PlnDemandVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计划需求表
 *
 * @author kitex
 * @date 2025-02-05 16:46:53
 */
@Service
@AllArgsConstructor
public class PlnDemandServiceImpl extends ServiceImpl<PlnDemandMapper, PlnDemandEntity> implements PlnDemandService {

    private final PlnDemandMonthService plnDemandMonthService;

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteCustomerService remoteCustomerService;

    private final RemoteMesTaskService remoteTaskService;

    private final PlnIssueService plnIssueService;

    /**
     * 通过条件查询需求
     *
     * @param templateQueryDTO 查询条件
     */
    @Override
    public List<PlnDemandVO> getDemand(TemporaryPlanQueryDTO templateQueryDTO) {
        LambdaQueryWrapper<PlnDemandEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(ObjUtil.isNotEmpty(templateQueryDTO.getMatCode()), PlnDemandEntity::getProductCode, templateQueryDTO.getMatCode());
        wrapper.like(ObjUtil.isNotEmpty(templateQueryDTO.getMatName()), PlnDemandEntity::getProductName, templateQueryDTO.getMatName());
        wrapper.like(ObjUtil.isNotEmpty(templateQueryDTO.getCustomerCode()), PlnDemandEntity::getCustomerCode, templateQueryDTO.getCustomerCode());
        if (templateQueryDTO.getPlanNum() == null) {
            templateQueryDTO.setPlanNum(1);
        }
        LocalDate enTime = templateQueryDTO.getTime().plusDays(templateQueryDTO.getPlanNum() - 1);
        wrapper.between(PlnDemandEntity::getDemandTime, templateQueryDTO.getTime(), enTime);
        wrapper.orderByDesc(PlnDemandEntity::getCreateTime);
        List<PlnDemandEntity> list = this.list(wrapper);
        if (list.isEmpty()) {
            return null;
        }
        Map<String, PlnDemandEntity> latestMap = new LinkedHashMap<>();
        for (PlnDemandEntity entity : list) {
            latestMap.putIfAbsent(entity.getProductCode(), entity);
        }
        // 转换为去重后的列表
        List<PlnDemandEntity> plnDemandEntities = new ArrayList<>(latestMap.values());
        Set<String> productCodes = latestMap.keySet();

        // 查询月计划需求表
        Map<String, PlnDemandMonthEntity> demandMonthEntityMap = plnDemandMonthService.list(new LambdaQueryWrapper<PlnDemandMonthEntity>().in(PlnDemandMonthEntity::getProductCode, productCodes))
                .stream().collect(Collectors.toMap(PlnDemandMonthEntity::getProductCode, Function.identity(), (a, b) -> a));
        List<PlnDemandVO> plnDemandVOS = new ArrayList<>();
        for (PlnDemandEntity plnDemandEntity : plnDemandEntities) {
            PlnDemandVO plnDemandVO = new PlnDemandVO();
            BeanUtils.copyProperties(plnDemandEntity, plnDemandVO);
            PlnDemandMonthEntity plnDemandMonthEntity = demandMonthEntityMap.get(plnDemandEntity.getProductCode());
            if (plnDemandMonthEntity != null) {
                plnDemandVO.setThreeMonthNum(plnDemandMonthEntity.getThreeMonthNum());
            }
            plnDemandVOS.add(plnDemandVO);
        }
        return plnDemandVOS;
    }

    /**
     * 查询T+N页面生产情况
     *
     * @param templateQueryDTO 查询条件
     */
    @Override
    public List<Map<String, String>> getPlanMes(TemporaryPlanQueryDTO templateQueryDTO) {
        PlanMesQueryDTO queryDTO = new PlanMesQueryDTO();
        queryDTO.setMatCodes(templateQueryDTO.getMatCodes());
        queryDTO.setTime(templateQueryDTO.getTime());
        List<MesTaskEntity> data = remoteTaskService.planMesList(queryDTO).getData();
        if (data.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, String>> resultList = new ArrayList<>();

        Map<String, MesTaskEntity> taskMap = data.stream().collect(Collectors.toMap(MesTaskEntity::getPlanNo, Function.identity(), (a, b) -> a));
        List<PlnIssueEntity> list = plnIssueService.list(new LambdaQueryWrapper<PlnIssueEntity>()
                .in(PlnIssueEntity::getIssueNo, taskMap.keySet()));
        for (PlnIssueEntity issueEntity : list) {
            issueEntity.setNum(taskMap.get(issueEntity.getIssueNo()).getCompleteNum().stripTrailingZeros());
        }
        Map<String, Map<String, String>> collect = list.stream().collect(Collectors.groupingBy(
                PlnIssueEntity::getProductCode,
                Collectors.toMap(
                        PlnIssueEntity::getPlanType,
                        record -> Objects.toString(record.getNum(), ""))));
        for (Map.Entry<String, Map<String, String>> entry : collect.entrySet()) {
            Map<String, String> map = new HashMap<>(entry.getValue());
            map.put("productCode", entry.getKey());
            resultList.add(map);
        }
        return resultList;
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料需求列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    public R importPlanDemand(List<PlanDemandExcelVO> excelVOList, BindingResult bindingResult) {
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
        List<PlnDemandEntity> insertList = new ArrayList<>();
        for (PlanDemandExcelVO excelVO : excelVOList) {
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
        return R.ok(null, "物料需求导入成功");
    }

    /**
     * 插入excel
     */
    private PlnDemandEntity insertExcel(PlanDemandExcelVO excelVO, MdmCustomerEntity mdmCustomerEntity,
                                        MdmMaterialEntity mdmMaterialEntity) {
        PlnDemandEntity plnDemand = new PlnDemandEntity();
        BeanUtils.copyProperties(excelVO, plnDemand);
        plnDemand.setCustomerName(mdmCustomerEntity.getCustomerName());
        plnDemand.setProductName(mdmMaterialEntity.getMaterialName());
        plnDemand.setSpecificationModel(mdmMaterialEntity.getSpecificationModel());
        plnDemand.setMaterialAttribute(mdmMaterialEntity.getMaterialAttribute());
        return plnDemand;
    }
}