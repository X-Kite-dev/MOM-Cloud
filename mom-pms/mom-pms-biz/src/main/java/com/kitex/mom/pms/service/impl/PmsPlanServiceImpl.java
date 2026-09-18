package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
import com.kitex.mom.pms.dto.PmsAgentSupplyEvidenceDTO;
import com.kitex.mom.pms.dto.PmsAgentSupplyEvidenceQueryDTO;
import com.kitex.mom.pms.dto.PmsSupplyCommitmentEvidenceDTO;
import com.kitex.mom.pms.dto.PmsPlanSupplyWithdrawalDTO;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentEntity;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentItemEntity;
import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOutboxEntity;
import com.kitex.mom.pms.entity.PmsPlanSupplyWithdrawalReceiptEntity;
import com.kitex.mom.pms.enums.PmsPlanStatusEnum;
import com.kitex.mom.pms.mapper.PmsPlanMapper;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import com.kitex.mom.pms.mapper.PmsPlanCommitmentEventOutboxMapper;
import com.kitex.mom.pms.mapper.PmsPlanSupplyWithdrawalReceiptMapper;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.service.PmsDeliveryCommitmentService;
import com.kitex.mom.pms.service.PmsPlanService;
import com.kitex.mom.pms.vo.PmsPlanExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private final PmsDeliveryCommitmentService deliveryCommitmentService;

    private final PmsOrderDetailMapper pmsOrderDetailMapper;

    private final PmsOrderMapper pmsOrderMapper;

    private final PmsPlanCommitmentEventOutboxMapper planCommitmentEventOutboxMapper;

    private final PmsPlanSupplyWithdrawalReceiptMapper planSupplyWithdrawalReceiptMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsPlanEntity saveFromPlanSource(PmsPlanEntity plan) {
        require(plan != null && plan.getSourceIssueMonthId() != null
                        && StrUtil.isNotBlank(plan.getSourceIssueMonthFingerprint())
                        && plan.getSourceIssueMonthFingerprint().length() == 64,
                "PLAN来源采购/外协计划必须提供月计划ID和完整来源指纹");
        baseMapper.lockKey("SOURCE-ISSUE-MONTH:" + plan.getSourceIssueMonthId());
        PmsPlanEntity old = getOne(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getSourceIssueMonthId, plan.getSourceIssueMonthId()).last("FOR UPDATE"), false);
        if (old != null) {
            require(Objects.equals(old.getSourceIssueMonthFingerprint(), plan.getSourceIssueMonthFingerprint()),
                    "PLAN月计划ID已被不同来源事实使用");
            return old;
        }
        require(StrUtil.isNotBlank(plan.getPmsPlanNo()) && StrUtil.isNotBlank(plan.getOrderNo())
                        && StrUtil.isNotBlank(plan.getMatCode()) && StrUtil.isNotBlank(plan.getFactoryCode())
                        && StrUtil.isNotBlank(plan.getType()) && plan.getPmsNum() != null && plan.getPmsNum() > 0,
                "PLAN来源采购/外协计划业务字段不完整");
        plan.setStatus(PmsPlanStatusEnum.NO_CREATEK.getCode());
        require(save(plan), "PLAN来源采购/外协计划保存失败");
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsPlanSupplyWithdrawalDTO.Receipt withdrawSupplyPlan(PmsPlanSupplyWithdrawalDTO.Command command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId())
                        && command.getSourceIssueMonthId() != null && command.getPmsPlanId() != null
                        && StrUtil.isNotBlank(command.getPmsPlanNo()) && StrUtil.isNotBlank(command.getPlanDemandNo())
                        && StrUtil.isNotBlank(command.getMaterialCode()) && command.getExpectedQuantity() != null
                        && command.getExpectedQuantity().signum() > 0 && StrUtil.isNotBlank(command.getSupplyType())
                        && StrUtil.isNotBlank(command.getFactoryCode()) && StrUtil.isNotBlank(command.getReason()),
                "PMS供应计划撤回命令不完整");
        String requestId = text(command.getRequestId(), 64, "PMS供应计划撤回请求号");
        String reason = text(command.getReason(), 500, "PMS供应计划撤回原因");
        String requestFingerprint = sha256(command.getSourceIssueMonthId() + "|" + command.getPmsPlanId() + "|"
                + command.getPmsPlanNo() + "|" + command.getPlanDemandNo() + "|" + command.getMaterialCode() + "|"
                + command.getExpectedQuantity().stripTrailingZeros().toPlainString() + "|" + command.getSupplyType()
                + "|" + command.getFactoryCode() + "|" + StrUtil.blankToDefault(command.getExpectedEvidenceFingerprint(), "") + "|" + reason);
        baseMapper.lockKey("SUPPLY-WITHDRAWAL-REQUEST:" + requestId);
        PmsPlanSupplyWithdrawalReceiptEntity replay = planSupplyWithdrawalReceiptMapper.selectOne(
                new LambdaQueryWrapper<PmsPlanSupplyWithdrawalReceiptEntity>()
                        .eq(PmsPlanSupplyWithdrawalReceiptEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "PMS供应计划撤回请求号已被不同命令使用");
            return withdrawalReceipt(replay, "ALREADY_WITHDRAWN");
        }
        baseMapper.lockKey("PLAN:" + command.getPmsPlanId());
        PmsPlanEntity plan = getOne(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getId, command.getPmsPlanId()).last("FOR UPDATE"), false);
        requirePlanIdentity(plan, command);
        if (Boolean.TRUE.equals(command.getPreview()) && PmsPlanStatusEnum.CANCELLED.getCode().equals(plan.getStatus())) {
            PmsPlanSupplyWithdrawalReceiptEntity completed = planSupplyWithdrawalReceiptMapper.selectOne(
                    new LambdaQueryWrapper<PmsPlanSupplyWithdrawalReceiptEntity>()
                            .eq(PmsPlanSupplyWithdrawalReceiptEntity::getPmsPlanId, plan.getId())
                            .eq(PmsPlanSupplyWithdrawalReceiptEntity::getStatus, "SUCCEEDED")
                            .orderByDesc(PmsPlanSupplyWithdrawalReceiptEntity::getHandledTime)
                            .orderByDesc(PmsPlanSupplyWithdrawalReceiptEntity::getId).last("LIMIT 1"));
            require(completed != null, "PMS供应计划已关闭但缺少受控撤回回执，须先完成账实核查");
            return withdrawalReceipt(completed, "ALREADY_WITHDRAWN");
        }
        require(PmsPlanStatusEnum.NO_CREATEK.getCode().equals(plan.getStatus()),
                "PMS供应计划已下单、到货、完成或被非受控关闭，须进入订单撤单/合同终止流程");
        List<PmsOrderDetailEntity> details = pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getFactoryCode, command.getFactoryCode())
                .eq(PmsOrderDetailEntity::getPmsPlanNo, plan.getPmsPlanNo()).orderByAsc(PmsOrderDetailEntity::getId));
        require(details.isEmpty(), "PMS供应计划已形成采购/外协订单行，须先完成订单受控撤回");
        require(decimal(plan.getPlanOrderNum()).signum() == 0, "PMS供应计划下单占用不为0，须先完成订单受控撤回及计划占用回退");
        require(decimal(plan.getPlanArrivalNum()).signum() == 0, "PMS供应计划已有到货事实，不能直接撤回");
        List<PmsDeliveryCommitmentEntity> commitments = deliveryCommitmentService.listActiveByPlanNos(
                command.getFactoryCode(), List.of(plan.getPmsPlanNo()));
        require(commitments.isEmpty(), "PMS供应计划仍有活动供应承诺，须先通过订单变更流程取消");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("plan", Arrays.asList(plan.getId(), plan.getPmsPlanNo(), plan.getSourceIssueMonthId(),
                plan.getSourceIssueMonthFingerprint(), plan.getOrderNo(), plan.getMatCode(), plan.getPmsNum(),
                plan.getPlanOrderNum(), plan.getPlanArrivalNum(), plan.getStatus(), plan.getType(), plan.getFactoryCode()));
        facts.put("orderDetails", List.of());
        facts.put("activeCommitments", List.of());
        String evidenceSnapshot = JSONUtil.toJsonStr(facts);
        String evidenceFingerprint = sha256(evidenceSnapshot);
        if (Boolean.TRUE.equals(command.getPreview())) {
            return new PmsPlanSupplyWithdrawalDTO.Receipt().setRequestId(requestId)
                    .setSourceIssueMonthId(plan.getSourceIssueMonthId()).setPmsPlanId(plan.getId())
                    .setPmsPlanNo(plan.getPmsPlanNo()).setStatus("ELIGIBLE")
                    .setEvidenceFingerprint(evidenceFingerprint).setEvidenceSnapshot(evidenceSnapshot)
                    .setMessage("PMS采购/外协计划尚未形成订单、到货和活动供应承诺，可受控撤回")
                    .setHandledTime(LocalDateTime.now());
        }
        require(StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint())
                        && Objects.equals(command.getExpectedEvidenceFingerprint().trim(), evidenceFingerprint),
                "PMS供应计划事实已变化，请重新申请撤回");
        plan.setStatus(PmsPlanStatusEnum.CANCELLED.getCode());
        require(updateById(plan), "PMS供应计划撤回状态保存失败");
        PmsPlanSupplyWithdrawalReceiptEntity receipt = new PmsPlanSupplyWithdrawalReceiptEntity()
                .setRequestId(requestId).setRequestFingerprint(requestFingerprint)
                .setSourceIssueMonthId(plan.getSourceIssueMonthId()).setPmsPlanId(plan.getId()).setPmsPlanNo(plan.getPmsPlanNo())
                .setExpectedEvidenceFingerprint(command.getExpectedEvidenceFingerprint().trim())
                .setActualEvidenceFingerprint(evidenceFingerprint).setEvidenceSnapshot(evidenceSnapshot)
                .setStatus("SUCCEEDED").setReason(reason).setMessage("PMS采购/外协计划已受控撤回")
                .setHandledTime(LocalDateTime.now()).setFactoryCode(plan.getFactoryCode());
        require(planSupplyWithdrawalReceiptMapper.insert(receipt) == 1, "PMS供应计划撤回永久回执保存失败");
        return withdrawalReceipt(receipt, "SUCCEEDED");
    }

    private void requirePlanIdentity(PmsPlanEntity plan, PmsPlanSupplyWithdrawalDTO.Command command) {
        require(plan != null && Objects.equals(plan.getSourceIssueMonthId(), command.getSourceIssueMonthId())
                        && Objects.equals(plan.getPmsPlanNo(), command.getPmsPlanNo())
                        && Objects.equals(plan.getOrderNo(), command.getPlanDemandNo())
                        && Objects.equals(plan.getMatCode(), command.getMaterialCode())
                        && Objects.equals(plan.getType(), command.getSupplyType())
                        && Objects.equals(plan.getFactoryCode(), command.getFactoryCode())
                        && decimal(plan.getPmsNum()).compareTo(command.getExpectedQuantity()) == 0,
                "PMS供应计划来源、物料、类型、工厂或数量不一致");
    }

    private PmsPlanSupplyWithdrawalDTO.Receipt withdrawalReceipt(PmsPlanSupplyWithdrawalReceiptEntity receipt, String status) {
        return new PmsPlanSupplyWithdrawalDTO.Receipt().setRequestId(receipt.getRequestId())
                .setSourceIssueMonthId(receipt.getSourceIssueMonthId()).setPmsPlanId(receipt.getPmsPlanId())
                .setPmsPlanNo(receipt.getPmsPlanNo()).setStatus(status)
                .setEvidenceFingerprint(receipt.getActualEvidenceFingerprint()).setEvidenceSnapshot(receipt.getEvidenceSnapshot())
                .setMessage(receipt.getMessage()).setHandledTime(receipt.getHandledTime());
    }

    private BigDecimal decimal(Double value) { return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value); }
    private String text(String value, int max, String label) {
        String result = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(result) && result.length() <= max, label + "不能为空且不能超过" + max + "字");
        return result;
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new CheckedException("PMS供应计划撤回指纹生成失败"); }
    }
    private void require(boolean condition, String message) { if (!condition) throw new CheckedException(message); }

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
        wrapper.select("sum( plan_order_num - COALESCE(plan_arrival_num,0) ) as planArrivalNum", "mat_code", "mat_type");
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackPlanOrderNum(List<PmsPlanEntity> allocations) {
        if (CollUtil.isEmpty(allocations)) return;
        Map<String, Double> rollbackByPlan = allocations.stream()
                .filter(item -> StrUtil.isNotBlank(item.getPmsPlanNo()))
                .collect(Collectors.groupingBy(PmsPlanEntity::getPmsPlanNo,
                        Collectors.summingDouble(item -> Optional.ofNullable(item.getPlanOrderNum()).orElse(0D))));
        for (Map.Entry<String, Double> entry : rollbackByPlan.entrySet()) {
            PmsPlanEntity plan = getOne(new LambdaQueryWrapper<PmsPlanEntity>()
                    .eq(PmsPlanEntity::getPmsPlanNo, entry.getKey()).last("FOR UPDATE"), false);
            if (plan == null) throw new CheckedException("采购订单驳回回退时采购计划不存在：" + entry.getKey());
            double ordered = Math.max(0D, Optional.ofNullable(plan.getPlanOrderNum()).orElse(0D) - entry.getValue());
            plan.setPlanOrderNum(ordered);
            plan.setStatus(ordered <= 0.00000001 ? PmsPlanStatusEnum.NO_CREATEK.getCode()
                    : ordered + 0.00000001 >= Optional.ofNullable(plan.getPmsNum()).orElse(0D)
                    ? PmsPlanStatusEnum.CREATE.getCode() : PmsPlanStatusEnum.CREATEING.getCode());
            updateById(plan);
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
        pmsPlanEntity.setLastArrivalTime(java.time.LocalDateTime.now());
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

    @Override
    public List<PmsAgentSupplyEvidenceDTO> agentSupplyEvidence(PmsAgentSupplyEvidenceQueryDTO query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode()) || CollUtil.isEmpty(query.getOrderNos())) {
            throw new CheckedException("Agent供应证据查询必须指定工厂和计划需求号");
        }
        List<String> orderNos = query.getOrderNos().stream().filter(StrUtil::isNotBlank).distinct().toList();
        if (orderNos.isEmpty() || orderNos.size() > 200) {
            throw new CheckedException("Agent供应证据单次查询计划需求号数量应为1至200");
        }
        List<PmsPlanEntity> plans = list(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getFactoryCode, query.getFactoryCode())
                .in(PmsPlanEntity::getOrderNo, orderNos)
                .orderByAsc(PmsPlanEntity::getPlanDeliveryTime)
                .orderByAsc(PmsPlanEntity::getPmsPlanNo));
        List<String> planNos = plans.stream().map(PmsPlanEntity::getPmsPlanNo).filter(StrUtil::isNotBlank).distinct().toList();
        List<PmsDeliveryCommitmentEntity> active = deliveryCommitmentService.listActiveByPlanNos(query.getFactoryCode(), planNos);
        List<PmsOrderDetailEntity> allDetails = planNos.isEmpty() ? List.of()
                : pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getFactoryCode, query.getFactoryCode())
                .in(PmsOrderDetailEntity::getPmsPlanNo, planNos));
        Map<Long, PmsOrderDetailEntity> detailMap = allDetails.stream()
                .collect(Collectors.toMap(PmsOrderDetailEntity::getId, Function.identity()));
        Map<Long, PmsOrderEntity> orderMap = allDetails.isEmpty() ? Map.of()
                : pmsOrderMapper.selectBatchIds(allDetails.stream().map(PmsOrderDetailEntity::getPmsOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(PmsOrderEntity::getId, Function.identity()));
        Map<String, List<PmsOrderDetailEntity>> detailsByPlan = allDetails.stream()
                .collect(Collectors.groupingBy(PmsOrderDetailEntity::getPmsPlanNo));
        Map<String, List<PmsDeliveryCommitmentEntity>> byPlan = active.stream()
                .collect(Collectors.groupingBy(PmsDeliveryCommitmentEntity::getPmsPlanNo));
        Map<String, PmsPlanCommitmentEventOutboxEntity> latestEventByPlanMaterial = planNos.isEmpty() ? Map.of()
                : planCommitmentEventOutboxMapper.selectList(
                        new LambdaQueryWrapper<PmsPlanCommitmentEventOutboxEntity>()
                                .eq(PmsPlanCommitmentEventOutboxEntity::getFactoryCode, query.getFactoryCode())
                                .in(PmsPlanCommitmentEventOutboxEntity::getPmsPlanNo, planNos)
                                .orderByAsc(PmsPlanCommitmentEventOutboxEntity::getOccurredTime)
                                .orderByAsc(PmsPlanCommitmentEventOutboxEntity::getId)).stream()
                .collect(Collectors.toMap(event -> planMaterialKey(event.getPmsPlanNo(), event.getMaterialCode()),
                        Function.identity(), (left, right) -> right, LinkedHashMap::new));
        return plans.stream().map(row -> {
            List<PmsSupplyCommitmentEvidenceDTO> commitments = new ArrayList<>(byPlan.getOrDefault(row.getPmsPlanNo(), List.of()).stream()
                    .flatMap(commitment -> openCommitmentEvidence(commitment, detailMap.get(commitment.getOrderDetailId())).stream())
                    .sorted(Comparator.comparing(PmsSupplyCommitmentEvidenceDTO::getConfirmedDeliveryTime)).toList());
            List<PmsOrderDetailEntity> orderDetails = detailsByPlan.getOrDefault(row.getPmsPlanNo(), List.of());
            BigDecimal approvedOrdered = orderDetails.stream()
                    .filter(detail -> approvedOrder(orderMap.get(detail.getPmsOrderId())))
                    .map(detail -> decimal(detail.getOrderNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal arrived = orderDetails.stream()
                    .filter(detail -> approvedOrder(orderMap.get(detail.getPmsOrderId())))
                    .map(detail -> decimal(detail.getArrivalNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal openOrdered = orderDetails.stream()
                    .filter(detail -> approvedOpenOrder(orderMap.get(detail.getPmsOrderId())))
                    .map(detail -> decimal(detail.getOrderNum()).subtract(decimal(detail.getArrivalNum())).max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal pendingApproval = orderDetails.stream()
                    .filter(detail -> pendingOrder(orderMap.get(detail.getPmsOrderId())))
                    .map(detail -> decimal(detail.getOrderNum()).subtract(decimal(detail.getArrivalNum())).max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal formalConfirmed = commitments.stream().map(PmsSupplyCommitmentEvidenceDTO::getCommittedNum)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            PmsPlanCommitmentEventOutboxEntity latestEvent = latestEventByPlanMaterial.get(
                    planMaterialKey(row.getPmsPlanNo(), row.getMatCode()));
            if (validManualForecast(latestEvent, row)) {
                BigDecimal currentGap = openOrdered.subtract(formalConfirmed).max(BigDecimal.ZERO);
                BigDecimal frozenGap = value(latestEvent.getOpenOrderedQuantity())
                        .subtract(value(latestEvent.getConfirmedOpenQuantity())).max(BigDecimal.ZERO);
                BigDecimal forecastQuantity = currentGap.min(frozenGap);
                if (forecastQuantity.signum() > 0) {
                    commitments.add(new PmsSupplyCommitmentEvidenceDTO()
                            .setCommitmentNo("FORECAST:" + latestEvent.getEventId()).setVersionNo(1)
                            .setCommittedNum(forecastQuantity)
                            .setConfirmedDeliveryDate(latestEvent.getExpectedSupplyTime().toLocalDate())
                            .setConfirmedDeliveryTime(latestEvent.getExpectedSupplyTime())
                            .setDeliveryTimePrecision("MINUTE")
                            .setSourceType("PMS_EVENT_FORECAST").setEvidenceRef(latestEvent.getForecastBasis()));
                    commitments.sort(Comparator.comparing(PmsSupplyCommitmentEvidenceDTO::getConfirmedDeliveryTime));
                }
            }
            BigDecimal confirmed = commitments.stream().map(PmsSupplyCommitmentEvidenceDTO::getCommittedNum)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String coverage = openOrdered.compareTo(BigDecimal.ZERO) == 0 ? "RECEIVED"
                    : confirmed.compareTo(BigDecimal.ZERO) == 0 ? "NONE"
                    : confirmed.compareTo(openOrdered) >= 0 ? "FULL" : "PARTIAL";
            return new PmsAgentSupplyEvidenceDTO()
                    .setOrderNo(row.getOrderNo()).setPmsPlanNo(row.getPmsPlanNo())
                    .setMaterialCode(row.getMatCode()).setMaterialName(row.getMatName())
                    .setSupplyType(row.getType()).setPlanStatus(row.getStatus())
                    .setPlannedNum(decimal(row.getPmsNum())).setOrderedNum(approvedOrdered)
                    .setArrivedNum(arrived).setOpenApprovedOrderedNum(openOrdered)
                    .setPendingApprovalNum(pendingApproval).setPlannedDeliveryDate(row.getPlanDeliveryTime())
                    .setPlannedDeliveryTime(row.getPlanDeliveryTime() == null ? null
                            : row.getPlanDeliveryTime().atTime(LocalTime.of(23, 59)))
                    .setPlannedDeliveryTimePrecision(row.getPlanDeliveryTime() == null ? null : "DATE_END")
                    .setConfirmedCommittedNum(confirmed).setCommitmentCoverageStatus(coverage)
                    .setCommitments(commitments).setLastArrivalTime(row.getLastArrivalTime()).setFactoryCode(row.getFactoryCode());
        }).toList();
    }

    /** 最新承诺变化事实天然覆盖旧预测；只允许有审计操作者、未来时刻且仍在有效生命周期的预测进入齐套证据。 */
    private boolean validManualForecast(PmsPlanCommitmentEventOutboxEntity event, PmsPlanEntity plan) {
        return event != null && event.getManualRequestId() != null && event.getManualOperationTime() != null
                && event.getExpectedSupplyTime() != null && event.getExpectedSupplyTime().isAfter(LocalDateTime.now())
                && Set.of("PENDING", "PROCESSING", "RETRY_WAIT", "DELIVERED", "DEAD_LETTER").contains(event.getStatus())
                && Objects.equals(event.getPlanDemandNo(), plan.getOrderNo())
                && Objects.equals(event.getMaterialCode(), plan.getMatCode())
                && Objects.equals(event.getFactoryCode(), plan.getFactoryCode());
    }

    private String planMaterialKey(String pmsPlanNo, String materialCode) {
        return Objects.toString(pmsPlanNo, "") + '\u0000' + Objects.toString(materialCode, "");
    }

    private BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private boolean approvedOrder(PmsOrderEntity order) {
        return order != null && Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(), PmsOrderStatusEnum.RECEIVING.getCode(),
                PmsOrderStatusEnum.PART_RECEIVE.getCode(), PmsOrderStatusEnum.COMPLETE.getCode()).contains(order.getStatus());
    }

    private boolean approvedOpenOrder(PmsOrderEntity order) {
        return order != null && Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(), PmsOrderStatusEnum.RECEIVING.getCode(),
                PmsOrderStatusEnum.PART_RECEIVE.getCode()).contains(order.getStatus());
    }

    private boolean pendingOrder(PmsOrderEntity order) {
        return order != null && Set.of(PmsOrderStatusEnum.NO_CHECK.getCode(), PmsOrderStatusEnum.CHECKING.getCode())
                .contains(order.getStatus());
    }

    private List<PmsSupplyCommitmentEvidenceDTO> openCommitmentEvidence(PmsDeliveryCommitmentEntity commitment,
                                                                         PmsOrderDetailEntity detail) {
        BigDecimal receivedAfterConfirmation = detail == null ? BigDecimal.ZERO
                : decimal(detail.getArrivalNum()).subtract(commitment.getArrivalNumBaseline()).max(BigDecimal.ZERO);
        List<PmsSupplyCommitmentEvidenceDTO> result = new ArrayList<>();
        for (PmsDeliveryCommitmentItemEntity item : commitment.getItems().stream()
                .sorted(Comparator.comparing(this::deliveryTime)).toList()) {
            BigDecimal committed = item.getCommittedNum();
            BigDecimal consumed = receivedAfterConfirmation.min(committed);
            receivedAfterConfirmation = receivedAfterConfirmation.subtract(consumed);
            BigDecimal open = committed.subtract(consumed);
            if (open.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new PmsSupplyCommitmentEvidenceDTO().setCommitmentNo(commitment.getCommitmentNo())
                        .setVersionNo(commitment.getVersionNo()).setCommittedNum(open)
                        .setConfirmedDeliveryDate(item.getCommittedDeliveryDate())
                        .setConfirmedDeliveryTime(deliveryTime(item))
                        .setDeliveryTimePrecision(StrUtil.blankToDefault(item.getDeliveryTimePrecision(), "DATE_END"))
                        .setSourceType(commitment.getSourceType()).setEvidenceRef(commitment.getEvidenceRef()));
            }
        }
        return result;
    }

    private LocalDateTime deliveryTime(PmsDeliveryCommitmentItemEntity item) {
        if (item.getCommittedDeliveryTime() != null) return item.getCommittedDeliveryTime();
        return Objects.requireNonNull(item.getCommittedDeliveryDate(), "供应承诺日期不能为空")
                .atTime(LocalTime.of(23, 59));
    }

    /**
     * 新增
     *
     * @param pmsPlan 采购计划
     */
    @Override
    @Transactional
    public Boolean savePlan(PmsPlanEntity pmsPlan) {
        require(pmsPlan != null && StrUtil.isNotBlank(pmsPlan.getMatCode())
                        && pmsPlan.getPmsNum() != null && pmsPlan.getPmsNum() > 0,
                "采购计划必须提供物料和大于0的计划数量");
        require(pmsPlan.getSourceIssueMonthId() == null && StrUtil.isBlank(pmsPlan.getSourceIssueMonthFingerprint()),
                "人工新增采购计划不得伪造PLAN来源，来源计划必须通过内部幂等接口创建");
        pmsPlan.setStatus(PmsPlanStatusEnum.NO_CREATEK.getCode());
        pmsPlan.setPlanOrderNum(0D);
        pmsPlan.setPlanArrivalNum(0D);
        pmsPlan.setLastArrivalTime(null);
        return save(pmsPlan);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePlan(PmsPlanEntity change) {
        require(change != null && change.getId() != null, "采购计划修改必须提供计划ID");
        baseMapper.lockKey("PLAN:" + change.getId());
        PmsPlanEntity plan = getOne(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getId, change.getId()).last("FOR UPDATE"), false);
        assertManualPlanMutable(plan, "修改");
        require(StrUtil.isBlank(change.getPmsPlanNo()) || Objects.equals(change.getPmsPlanNo(), plan.getPmsPlanNo()),
                "采购计划号不可修改");
        require(StrUtil.isBlank(change.getStatus()) || Objects.equals(change.getStatus(), plan.getStatus()),
                "采购计划状态不可通过通用编辑修改");
        require(change.getPlanOrderNum() == null || decimal(change.getPlanOrderNum()).compareTo(decimal(plan.getPlanOrderNum())) == 0,
                "下单占用数量不可通过通用编辑修改");
        require(change.getPlanArrivalNum() == null || decimal(change.getPlanArrivalNum()).compareTo(decimal(plan.getPlanArrivalNum())) == 0,
                "到货数量不可通过通用编辑修改");
        require(StrUtil.isBlank(change.getFactoryCode()) || Objects.equals(change.getFactoryCode(), plan.getFactoryCode()),
                "采购计划所属工厂不可修改");
        require(change.getSourceIssueMonthId() == null && StrUtil.isBlank(change.getSourceIssueMonthFingerprint()),
                "采购计划来源字段不可通过通用编辑修改");
        require(StrUtil.isNotBlank(change.getMatCode()) && change.getPmsNum() != null && change.getPmsNum() > 0,
                "采购计划必须保留物料和大于0的计划数量");
        plan.setOrderNo(change.getOrderNo());
        plan.setMatCode(change.getMatCode());
        plan.setMatName(change.getMatName());
        plan.setMatUnit(change.getMatUnit());
        plan.setMatSpec(change.getMatSpec());
        plan.setMatType(change.getMatType());
        plan.setPmsNum(change.getPmsNum());
        plan.setPlanDeliveryTime(change.getPlanDeliveryTime());
        plan.setQuality(change.getQuality());
        plan.setQualityReason(change.getQualityReason());
        plan.setType(change.getType());
        require(updateById(plan), "采购计划修改失败");
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removePlans(List<Long> ids) {
        require(CollUtil.isNotEmpty(ids), "采购计划删除必须选择计划");
        List<Long> planIds = ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
        require(planIds.size() == ids.size(), "采购计划删除ID不能为空或重复");
        for (Long id : planIds) {
            baseMapper.lockKey("PLAN:" + id);
            PmsPlanEntity plan = getOne(new LambdaQueryWrapper<PmsPlanEntity>()
                    .eq(PmsPlanEntity::getId, id).last("FOR UPDATE"), false);
            assertManualPlanMutable(plan, "删除");
        }
        require(removeBatchByIds(planIds), "采购计划删除失败");
        return true;
    }

    private void assertManualPlanMutable(PmsPlanEntity plan, String operation) {
        require(plan != null, "采购计划不存在");
        require(plan.getSourceIssueMonthId() == null && StrUtil.isBlank(plan.getSourceIssueMonthFingerprint()),
                "PLAN来源采购/外协计划不得直接" + operation + "，必须回到来源侧走受控变更或撤回");
        require(PmsPlanStatusEnum.NO_CREATEK.getCode().equals(plan.getStatus()),
                "仅未下单状态的人工计划允许" + operation);
        require(decimal(plan.getPlanOrderNum()).signum() == 0 && decimal(plan.getPlanArrivalNum()).signum() == 0,
                "采购计划已有下单或到货事实，不能直接" + operation);
        require(pmsOrderDetailMapper.selectCount(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsPlanNo, plan.getPmsPlanNo())) == 0,
                "采购计划已有订单行，不能直接" + operation);
        require(deliveryCommitmentService.listActiveByPlanNos(plan.getFactoryCode(), List.of(plan.getPmsPlanNo())).isEmpty(),
                "采购计划已有活动供应承诺，不能直接" + operation);
    }
}
