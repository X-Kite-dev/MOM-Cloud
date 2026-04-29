package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.enums.MatAttributeEnum;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlanMatMrpMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnIssueMonthMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import com.kitex.mom.plan.service.PlanMatMrpService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MRP运算记录表
 *
 * @author kitex
 * @date 2025-04-15 17:37:58
 */
@Service
@AllArgsConstructor
public class PlanMatMrpServiceImpl extends ServiceImpl<PlanMatMrpMapper, PlanMatMrpEntity> implements PlanMatMrpService {

    private final PlnIssueMapper plnIssueMapper;

    private final PlnIssueMonthMapper plnIssueMonthMapper;

    private final PlnProductDemandMapper plnProductDemandMapper;

    /**
     * 下发任务
     *
     * @param ids MRP运算记录表ids
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean issueByIds(Long[] ids) {
        List<Long> mrpIds = Arrays.asList(ids);
        List<PlanMatMrpEntity> planMatMrpEntities = this.listByIds(mrpIds);

        for (PlanMatMrpEntity planMatMrpEntity : planMatMrpEntities) {
            MatAttributeEnum matAttributeEnum = MatAttributeEnum.findByCode(planMatMrpEntity.getType());
            if (matAttributeEnum == null) {
                throw new CheckedException("未知物料属性");
            }
            switch (matAttributeEnum) {
                case MES -> mesHandler(planMatMrpEntity);
                case PMS -> pmsHandler(planMatMrpEntity);
                case OUT -> outHandler(planMatMrpEntity);
            }
        }
        // 修改MRP运算数据的状态为已下发
        this.update(new LambdaUpdateWrapper<PlanMatMrpEntity>().in(PlanMatMrpEntity::getId, mrpIds)
                .set(PlanMatMrpEntity::getStatus, true));
        return Boolean.TRUE;
    }

    /**
     * 完成数量处理
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean completeNumHandle(List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList) {
        Map<String, PlanMatMrpEntity> matMrpMap = this.list(new LambdaQueryWrapper<PlanMatMrpEntity>().in(PlanMatMrpEntity::getPlanDemandNo,
                        omsOrderLifeCycleDtoList.stream().map(OmsOrderLifeCycleDTO::getNo).collect(Collectors.toList()))).stream()
                .collect(Collectors.toMap(
                        entity -> entity.getPlanDemandNo() + "_" + entity.getMatCode(),
                        Function.identity(),
                        (v1, v2) -> v1));
        // mrp 数据处理
        List<PlanMatMrpEntity> mrpList = new ArrayList<>();
        // 客户订单完成数量修改
        List<OmsOrderLifeCycleDTO> plnProductDemandList = new ArrayList<>();
        for (OmsOrderLifeCycleDTO omsOrderLifeCycleDTO : omsOrderLifeCycleDtoList) {
            PlanMatMrpEntity matMrp = matMrpMap.get(omsOrderLifeCycleDTO.getNo() + "_" + omsOrderLifeCycleDTO.getProductCode());
            if (omsOrderLifeCycleDTO.getFinishNum() != null) {
                matMrp.setCompleteNum(Optional.ofNullable(matMrp.getCompleteNum()).orElse(BigDecimal.ZERO)
                        .add(omsOrderLifeCycleDTO.getFinishNum()));
                mrpList.add(matMrp);
            }
            if (matMrp.getSubitemBomId() != null && matMrp.getSubitemBomId() == 0) {
                plnProductDemandList.add(omsOrderLifeCycleDTO);
            }
        }
        // 如果有产品数量修改则修改客户订单完成数量
        if (plnProductDemandList.size() > 0) {
            customerOrderHandle(plnProductDemandList);
        }

        if (mrpList.size() > 0) {
            this.updateBatchById(mrpList);
        }
        return Boolean.TRUE;
    }

    /*
     * 客户订单完成数量处理
     */
    private void customerOrderHandle(List<OmsOrderLifeCycleDTO> plnProductDemandList) {
        Map<String, PlnProductDemandEntity> plnProductDemandMap = plnProductDemandMapper.selectList(new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .in(PlnProductDemandEntity::getDemandNo, plnProductDemandList.stream()
                                .map(OmsOrderLifeCycleDTO::getNo).collect(Collectors.toList()))).stream()
                .collect(Collectors.toMap(PlnProductDemandEntity::getDemandNo, Function.identity()));

        for (OmsOrderLifeCycleDTO omsOrderLifeCycleDTO : plnProductDemandList) {
            PlnProductDemandEntity productDemand = plnProductDemandMap.get(omsOrderLifeCycleDTO.getNo());
            if (omsOrderLifeCycleDTO.getFinishNum() != null) {
                BigDecimal num = Optional.ofNullable(productDemand.getCompleteNum()).orElse(BigDecimal.ZERO)
                        .add(omsOrderLifeCycleDTO.getFinishNum());
                plnProductDemandMapper.update(new LambdaUpdateWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getDemandNo, omsOrderLifeCycleDTO.getNo())
                        .set(PlnProductDemandEntity::getCompleteNum, num));
            }
        }
    }

    /*
     * 创建生产工单
     */
    private void mesHandler(PlanMatMrpEntity planMatMrpEntity) {
        PlnIssueEntity plnIssueEntity = new PlnIssueEntity();
        plnIssueEntity.setIssueNo(planMatMrpEntity.getPlanDemandNo());
        plnIssueEntity.setCustomerName(planMatMrpEntity.getCustomerName());
        plnIssueEntity.setCustomerCode(planMatMrpEntity.getCustomerCode());
        plnIssueEntity.setProductCode(planMatMrpEntity.getMatCode());
        plnIssueEntity.setProductName(planMatMrpEntity.getMatName());
        plnIssueEntity.setSpecificationModel(planMatMrpEntity.getMatSpec());
        plnIssueEntity.setNum(planMatMrpEntity.getNum());
        plnIssueEntity.setIssueTime(LocalDateTime.now());
        plnIssueEntity.setDemandTime(planMatMrpEntity.getOrderDate());
        plnIssueEntity.setStatus("0");
        plnIssueEntity.setFactoryCode(planMatMrpEntity.getFactoryCode());
        plnIssueEntity.setType("mrp");
        plnIssueMapper.insert(plnIssueEntity);
    }

    /*
     * 创建采购计划
     */
    private void pmsHandler(PlanMatMrpEntity planMatMrpEntity) {
        PlnIssueMonthEntity plnIssueMonthEntity = new PlnIssueMonthEntity();
        plnIssueMonthEntity.setIssueMonthNo(planMatMrpEntity.getPlanDemandNo());
        plnIssueMonthEntity.setCustomerName(planMatMrpEntity.getCustomerName());
        plnIssueMonthEntity.setCustomerCode(planMatMrpEntity.getCustomerCode());
        plnIssueMonthEntity.setProductCode(planMatMrpEntity.getMatCode());
        plnIssueMonthEntity.setProductName(planMatMrpEntity.getMatName());
        plnIssueMonthEntity.setSpecificationModel(planMatMrpEntity.getMatSpec());
        plnIssueMonthEntity.setMaterialAttribute(planMatMrpEntity.getType());
        plnIssueMonthEntity.setNum(planMatMrpEntity.getNum());
        plnIssueMonthEntity.setIssueTime(LocalDate.from(LocalDateTime.now()));
        plnIssueMonthEntity.setStatus("0");
        plnIssueMonthEntity.setDemandTime(planMatMrpEntity.getOrderDate());
        plnIssueMonthEntity.setFactoryCode(planMatMrpEntity.getFactoryCode());
        plnIssueMonthEntity.setType("mrp");
        plnIssueMonthMapper.insert(plnIssueMonthEntity);
    }

    /*
     * 创建外协任务
     */
    private void outHandler(PlanMatMrpEntity planMatMrpEntity) {
        PlnIssueMonthEntity plnIssueMonthEntity = new PlnIssueMonthEntity();
        plnIssueMonthEntity.setIssueMonthNo(planMatMrpEntity.getPlanDemandNo());
        plnIssueMonthEntity.setCustomerName(planMatMrpEntity.getCustomerName());
        plnIssueMonthEntity.setCustomerCode(planMatMrpEntity.getCustomerCode());
        plnIssueMonthEntity.setProductCode(planMatMrpEntity.getMatCode());
        plnIssueMonthEntity.setProductName(planMatMrpEntity.getMatName());
        plnIssueMonthEntity.setSpecificationModel(planMatMrpEntity.getMatSpec());
        plnIssueMonthEntity.setMaterialAttribute(planMatMrpEntity.getType());
        plnIssueMonthEntity.setNum(planMatMrpEntity.getNum());
        plnIssueMonthEntity.setIssueTime(LocalDate.from(LocalDateTime.now()));
        plnIssueMonthEntity.setStatus("0");
        plnIssueMonthEntity.setDemandTime(planMatMrpEntity.getOrderDate());
        plnIssueMonthEntity.setFactoryCode(planMatMrpEntity.getFactoryCode());
        plnIssueMonthEntity.setType("mrp");
        plnIssueMonthMapper.insert(plnIssueMonthEntity);
    }
}