package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.constant.ModuleEnum;
import com.kitex.mom.plan.constant.UpdateTypeEnum;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import com.kitex.mom.plan.entity.PlnUpdateLogEntity;
import com.kitex.mom.plan.mapper.PlnIssueMonthMapper;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnIssueMonthService;
import com.kitex.mom.plan.service.PlnIssueService;
import com.kitex.mom.plan.service.PlnUpdateLogService;
import com.kitex.mom.plan.vo.PlnIssueMonthVO;
import com.kitex.mom.pms.api.feign.RemotePmsPlanService;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.enums.PmsPlanStatusEnum;
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
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 月计划下达表
 *
 * @author kitex
 * @date 2025-02-10 16:11:29
 */
@Service
@AllArgsConstructor
public class PlnIssueMonthServiceImpl extends ServiceImpl<PlnIssueMonthMapper, PlnIssueMonthEntity> implements PlnIssueMonthService {

    private final PlnUpdateLogService plnUpdateLogService;

    private final PlnIssueService plnIssueService;

    private final RemotePmsPlanService remotePmsPlanService;

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteSeqConfigService remoteSeqConfigService;

    private final PlanMatMrpService planMatMrpService;

    /**
     * 新增或修改月计划下达表
     *
     * @param plnIssueMonthVO 月计划下达VO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveOrUpdateIssueMonth(PlnIssueMonthVO plnIssueMonthVO) {
        PlnIssueMonthEntity plnIssueMonthEntity = new PlnIssueMonthEntity();
        BeanUtils.copyProperties(plnIssueMonthVO, plnIssueMonthEntity);
        if (plnIssueMonthEntity.getIssueTime() == null) {
            plnIssueMonthEntity.setIssueTime(LocalDate.from(LocalDateTime.now()));
        }
        plnIssueMonthEntity.setIssueMonthNo(remoteSeqConfigService.create("plan_pms").getData());

        // 修改日志保存
        PlnUpdateLogEntity plnUpdateLogEntities = new PlnUpdateLogEntity();
        BeanUtils.copyProperties(plnIssueMonthEntity, plnUpdateLogEntities);
        plnUpdateLogEntities.setOperateTime(plnIssueMonthEntity.getIssueTime().atStartOfDay());
        plnUpdateLogEntities.setModule(ModuleEnum.PMS.getCode());
        plnUpdateLogEntities.setUpdateType(UpdateTypeEnum.NUM_ADD.getCode());
        plnUpdateLogEntities.setNewValue(plnIssueMonthVO.getNum().toString());
        plnUpdateLogService.saveUpdateLog(Collections.singletonList(plnUpdateLogEntities));

        return this.save(plnIssueMonthEntity);
    }

    /**
     * 按月统计计划下达数据
     *
     * @param monthPlanQueryDTO 查询条件
     */
    @Override
    public Map<String, List<PlnIssueMonthVO>> getIssueMonth(MonthPlanQueryDTO monthPlanQueryDTO) {
        Map<String, List<PlnIssueMonthVO>> map = new HashMap<>();

        QueryWrapper<PlnIssueMonthEntity> pmsQueryWrapper = Wrappers.query();
        pmsQueryWrapper.select("product_code, plan_type, sum(num) as num");
        pmsQueryWrapper.lambda()
                .like(ObjUtil.isNotEmpty(monthPlanQueryDTO.getCustomerCode()), PlnIssueMonthEntity::getCustomerCode, monthPlanQueryDTO.getCustomerCode())
                .in(ObjUtil.isNotEmpty(monthPlanQueryDTO.getMatCodes()), PlnIssueMonthEntity::getProductCode, monthPlanQueryDTO.getMatCodes())
                .like(ObjUtil.isNotEmpty(monthPlanQueryDTO.getMatName()), PlnIssueMonthEntity::getProductName, monthPlanQueryDTO.getMatName())
                .between(PlnIssueMonthEntity::getIssueTime, monthPlanQueryDTO.getTime().atStartOfDay(), monthPlanQueryDTO.getTime().with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX))
                .eq(PlnIssueMonthEntity::getType, "issue")
                .groupBy(PlnIssueMonthEntity::getProductCode, PlnIssueMonthEntity::getPlanType);
        List<PlnIssueMonthEntity> pmsList = this.list(pmsQueryWrapper);

        map.put(ModuleEnum.PMS.getCode(),
                pmsList.stream().map(entity -> {
                    PlnIssueMonthVO vo = new PlnIssueMonthVO();
                    BeanUtils.copyProperties(entity, vo);
                    vo.setModule(ModuleEnum.PMS.getCode());
                    return vo;
                }).toList());

        QueryWrapper<PlnIssueEntity> wrapper = Wrappers.query();
        wrapper.select("product_code, plan_type, sum(num) as num");
        wrapper.lambda().like(ObjUtil.isNotEmpty(monthPlanQueryDTO.getCustomerCode()), PlnIssueEntity::getCustomerCode, monthPlanQueryDTO.getCustomerCode())
                .in(ObjUtil.isNotEmpty(monthPlanQueryDTO.getMatCodes()), PlnIssueEntity::getProductCode, monthPlanQueryDTO.getMatCodes())
                .like(ObjUtil.isNotEmpty(monthPlanQueryDTO.getMatName()), PlnIssueEntity::getProductName, monthPlanQueryDTO.getMatName())
                .between(PlnIssueEntity::getIssueTime, monthPlanQueryDTO.getTime().atStartOfDay(), monthPlanQueryDTO.getTime().with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX))
                .eq(PlnIssueEntity::getType, "issue")
                .groupBy(PlnIssueEntity::getProductCode, PlnIssueEntity::getPlanType);
        List<PlnIssueEntity> plnIssueList = plnIssueService.list(wrapper);
        List<PlnIssueMonthVO> mesList = plnIssueList.stream().map(entity -> {
            PlnIssueMonthVO vo = new PlnIssueMonthVO();
            BeanUtils.copyProperties(entity, vo);
            vo.setModule(ModuleEnum.MES.getCode());
            return vo;
        }).toList();
        map.put(ModuleEnum.MES.getCode(), mesList);
        return map;
    }

    /**
     * T+N 月计划
     *
     * @param templateQueryDTO 查询条件
     */
    @Override
    public List<Map<Object, Object>> temporary(TemporaryPlanQueryDTO templateQueryDTO) {
        QueryWrapper<PlnIssueMonthEntity> wrapper = Wrappers.query();
        wrapper.select("sum(num) as num", "product_code");
        wrapper.lambda().in(ObjUtil.isNotEmpty(templateQueryDTO.getMatCodes()), PlnIssueMonthEntity::getProductCode, templateQueryDTO.getMatCodes())
                .eq(ObjUtil.isNotEmpty(templateQueryDTO.getCustomerCode()), PlnIssueMonthEntity::getCustomerCode, templateQueryDTO.getCustomerCode())
                .between(PlnIssueMonthEntity::getIssueTime, templateQueryDTO.getTime().with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay(),
                        templateQueryDTO.getTime().with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX))
                .isNotNull(PlnIssueMonthEntity::getNum)
                .eq(PlnIssueMonthEntity::getType, "issue")
                .groupBy(PlnIssueMonthEntity::getProductCode);

        List<PlnIssueMonthEntity> list = this.list(wrapper);

        QueryWrapper<PlnIssueEntity> issueWrapper = Wrappers.query();
        issueWrapper.select("product_code, sum(num) as num");
        issueWrapper.lambda().like(ObjUtil.isNotEmpty(templateQueryDTO.getCustomerCode()), PlnIssueEntity::getCustomerCode, templateQueryDTO.getCustomerCode())
                .in(ObjUtil.isNotEmpty(templateQueryDTO.getMatCodes()), PlnIssueEntity::getProductCode, templateQueryDTO.getMatCodes())
                .like(ObjUtil.isNotEmpty(templateQueryDTO.getMatName()), PlnIssueEntity::getProductName, templateQueryDTO.getMatName())
                .between(PlnIssueEntity::getIssueTime, templateQueryDTO.getTime().atStartOfDay(), templateQueryDTO.getTime().plusDays(9).atTime(LocalTime.MAX))
                .eq(PlnIssueEntity::getType, "issue")
                .groupBy(PlnIssueEntity::getProductCode);
        Map<String, PlnIssueEntity> issueEntityMap = plnIssueService.list(issueWrapper)
                .stream()
                .collect(Collectors.toMap(PlnIssueEntity::getProductCode, Function.identity(), (a, b) -> a));
        List<Map<Object, Object>> result = new ArrayList<>();
        if (list.isEmpty()) {
            for (PlnIssueEntity plnIssue : issueEntityMap.values()) {
                Map<Object, Object> map = new HashMap<>();
                map.put("productCode", plnIssue.getProductCode());
                map.put("tenDayPlan", plnIssue.getNum());
                result.add(map);
            }
            return result;
        }
        for (PlnIssueMonthEntity plnIssueMonthEntity : list) {
            Map<Object, Object> map = new HashMap<>();
            map.put("productCode", plnIssueMonthEntity.getProductCode());
            map.put("novemberPlan", plnIssueMonthEntity.getNum());
            if (issueEntityMap.get(plnIssueMonthEntity.getProductCode()) != null) {
                map.put("tenDayPlan", issueEntityMap.get(plnIssueMonthEntity.getProductCode()).getNum());
            }
            result.add(map);
        }
        return result;
    }

    /**
     * 下发到各模块
     *
     * @param ids 计划下达ids
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Boolean module(Long[] ids) {
        List<PlnIssueMonthEntity> plnIssueMonthEntities = this.listByIds(Arrays.asList(ids));

        List<PlnUpdateLogEntity> plnUpdateLogs = new ArrayList<>();
        plnIssueMonthEntities.forEach(entity -> {
            PlnUpdateLogEntity plnUpdateLogEntity = new PlnUpdateLogEntity();
            BeanUtils.copyProperties(entity, plnUpdateLogEntity);
            plnUpdateLogEntity.setOperateTime(entity.getIssueTime().atStartOfDay());
            plnUpdateLogEntity.setModule(ModuleEnum.PMS.getCode());
            plnUpdateLogEntity.setUpdateType(UpdateTypeEnum.STATUS_UPDATE.getCode());
            plnUpdateLogEntity.setOldValue(entity.getStatus());
            plnUpdateLogEntity.setNewValue("1");
            plnUpdateLogs.add(plnUpdateLogEntity);
            entity.setStatus("1");
        });
        // 日志保存
        plnUpdateLogService.saveUpdateLog(plnUpdateLogs);

        this.updateBatchById(plnIssueMonthEntities);

        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(),
                        (a, b) -> a));

        for (PlnIssueMonthEntity plnIssueMonthEntity : plnIssueMonthEntities) {
            // 推送采购模块
            savePmsPlan(plnIssueMonthEntity, materialMap.get(plnIssueMonthEntity.getProductCode()));
        }
        return Boolean.TRUE;
    }

    /**
     * 修改月计划下达表
     *
     * @param plnIssueMonth 月计划下达表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateIssueMonth(PlnIssueMonthEntity plnIssueMonth) {
        PlnIssueMonthEntity plnIssueMonthEntity = this.getById(plnIssueMonth.getId());
        // 修改日志保存
        PlnUpdateLogEntity plnUpdateLogEntities = new PlnUpdateLogEntity();
        BeanUtils.copyProperties(plnIssueMonthEntity, plnUpdateLogEntities);
        plnUpdateLogEntities.setOperateTime(plnIssueMonthEntity.getIssueTime().atStartOfDay());
        plnUpdateLogEntities.setModule(ModuleEnum.PMS.getCode());
        plnUpdateLogEntities.setUpdateType(UpdateTypeEnum.STATUS_UPDATE.getCode());
        plnUpdateLogEntities.setNewValue(plnIssueMonth.getStatus());
        plnUpdateLogEntities.setOldValue(plnIssueMonthEntity.getStatus());
        plnUpdateLogService.saveUpdateLog(Collections.singletonList(plnUpdateLogEntities));

        plnIssueMonthEntity.setStatus(plnIssueMonth.getStatus());
        return this.updateById(plnIssueMonthEntity);
    }

    /**
     * 修改采购计划下达表
     *
     * @param plnIssueMonthEntityList 采购计划下达列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean edit(List<PlnIssueMonthEntity> plnIssueMonthEntityList) {
        Map<String, PlnIssueMonthEntity> plnIssueMonthEntityMap = this.list(new LambdaQueryWrapper<PlnIssueMonthEntity>().in(PlnIssueMonthEntity::getIssueMonthNo,
                        plnIssueMonthEntityList.stream().map(PlnIssueMonthEntity::getIssueMonthNo).collect(Collectors.toList())))
                .stream().collect(Collectors.toMap(entity -> entity.getIssueMonthNo() + "_" + entity.getProductCode(), Function.identity(), (v1, v2) -> v1));
        // 采购排产结果
        List<PlnIssueMonthEntity> updateList = new ArrayList<>();

        // mrp 完成数量修改
        List<OmsOrderLifeCycleDTO> matMrpList = new ArrayList<>();
        for (PlnIssueMonthEntity plnIssueMonthEntity : plnIssueMonthEntityList) {
            PlnIssueMonthEntity issueMonth = plnIssueMonthEntityMap.get(plnIssueMonthEntity.getIssueMonthNo() + "_" + plnIssueMonthEntity.getProductCode());
            if (plnIssueMonthEntity.getFinishNum() != null) {
                issueMonth.setFinishNum(Optional.ofNullable(issueMonth.getFinishNum()).orElse(BigDecimal.ZERO)
                        .add(plnIssueMonthEntity.getFinishNum()));
            }
            if ("mrp".equals(issueMonth.getType()) && plnIssueMonthEntity.getFinishNum() != null) {
                OmsOrderLifeCycleDTO omsOrderLifeCycleDTO = new OmsOrderLifeCycleDTO();
                omsOrderLifeCycleDTO.setNo(issueMonth.getIssueMonthNo());
                omsOrderLifeCycleDTO.setProductCode(issueMonth.getProductCode());
                omsOrderLifeCycleDTO.setFinishNum(plnIssueMonthEntity.getFinishNum());
                matMrpList.add(omsOrderLifeCycleDTO);
            }
            issueMonth.setStatus(plnIssueMonthEntity.getStatus());
            issueMonth.setFinishTime(plnIssueMonthEntity.getFinishTime());
            updateList.add(issueMonth);
        }

        if (matMrpList.size() > 0) {
            planMatMrpService.completeNumHandle(matMrpList);
        }
        return this.updateBatchById(updateList);
    }

    /**
     * 下发到采购模块
     *
     * @param plnIssueMonthEntity 月计划下达
     * @param mdmMaterialEntity   物料
     */
    private void savePmsPlan(PlnIssueMonthEntity plnIssueMonthEntity, MdmMaterialEntity mdmMaterialEntity) {
        if (mdmMaterialEntity == null) throw new CheckedException("月计划物料主数据不存在：" + plnIssueMonthEntity.getProductCode());
        String sourceFingerprint = sourceFingerprint(plnIssueMonthEntity);
        PmsPlanEntity pmsPlan = new PmsPlanEntity();
        pmsPlan.setPmsPlanNo(remoteSeqConfigService.create("pms_plan").getData());
        pmsPlan.setOrderNo(plnIssueMonthEntity.getIssueMonthNo());
        pmsPlan.setMatType(plnIssueMonthEntity.getPlanType());
        pmsPlan.setMatName(plnIssueMonthEntity.getProductName());
        pmsPlan.setMatCode(plnIssueMonthEntity.getProductCode());
        pmsPlan.setMatUnit(mdmMaterialEntity.getBasicUnit());
        pmsPlan.setMatSpec(plnIssueMonthEntity.getSpecificationModel());
        pmsPlan.setPmsNum(plnIssueMonthEntity.getNum().doubleValue());
        pmsPlan.setPlanDeliveryTime(plnIssueMonthEntity.getDemandTime());
        pmsPlan.setStatus(PmsPlanStatusEnum.NO_CREATEK.getCode());
        pmsPlan.setType(plnIssueMonthEntity.getMaterialAttribute());
        pmsPlan.setFactoryCode(plnIssueMonthEntity.getFactoryCode());
        pmsPlan.setSourceIssueMonthId(plnIssueMonthEntity.getId());
        pmsPlan.setSourceIssueMonthFingerprint(sourceFingerprint);
        com.kitex.common.core.util.R<PmsPlanEntity> response = remotePmsPlanService.saveFromPlanSource(pmsPlan);
        PmsPlanEntity data = response == null ? null : response.getData();
        if (response == null || !response.isOk() || data == null) {
            throw new CheckedException("采购模块推送失败");
        }
        if (!Objects.equals(data.getSourceIssueMonthId(), plnIssueMonthEntity.getId())
                || !Objects.equals(data.getSourceIssueMonthFingerprint(), sourceFingerprint)
                || !Objects.equals(data.getOrderNo(), plnIssueMonthEntity.getIssueMonthNo())
                || !Objects.equals(data.getMatCode(), plnIssueMonthEntity.getProductCode())) {
            throw new CheckedException("PMS返回的采购/外协计划来源不一致");
        }
        plnIssueMonthEntity.setDownstreamPlanId(data.getId());
        plnIssueMonthEntity.setDownstreamPlanNo(data.getPmsPlanNo());
        plnIssueMonthEntity.setDownstreamPlanFingerprint(sourceFingerprint);
        if (!updateById(plnIssueMonthEntity)) throw new CheckedException("PMS采购/外协计划精确关联回写失败");
    }

    private String sourceFingerprint(PlnIssueMonthEntity source) {
        String value = String.join("|", Objects.toString(source.getId(), ""), Objects.toString(source.getIssueMonthNo(), ""),
                Objects.toString(source.getProductCode(), ""), Objects.toString(source.getNum(), ""),
                Objects.toString(source.getDemandTime(), ""), Objects.toString(source.getMaterialAttribute(), ""),
                Objects.toString(source.getFactoryCode(), ""), Objects.toString(source.getType(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("PLAN月计划来源指纹生成失败");
        }
    }
}
