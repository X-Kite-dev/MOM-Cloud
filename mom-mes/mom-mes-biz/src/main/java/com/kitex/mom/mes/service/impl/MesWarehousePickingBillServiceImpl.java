package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesPickingReceiveMatDTO;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.dto.MdmBomChildDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillDetailEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.enums.MesMaterialInStateEnum;
import com.kitex.mom.mes.enums.MesPickingBillStateEnum;
import com.kitex.mom.mes.enums.YesNoEnum;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillDetailMapper;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesWarehouseMaterialInBillService;
import com.kitex.mom.mes.service.MesWarehousePickingBillService;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import com.kitex.mom.wms.dto.WmsMaterialReservationLinkDTO;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生产领料
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Service
@RequiredArgsConstructor
public class MesWarehousePickingBillServiceImpl extends ServiceImpl<MesWarehousePickingBillMapper, MesWarehousePickingBillEntity> implements MesWarehousePickingBillService {

    private final MesWarehousePickingBillDetailMapper mesWarehousePickingBillDetailMapper;
    private final MesWarehouseMaterialInBillService mesWarehouseMaterialInBillService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteProducePickingNoticeService remoteProducePickingNoticeService;
    private final MesTaskMapper mesTaskMapper;
    private final MesMaterialReceiptService materialReceiptService;
    private final MesReworkMaterialPlanService reworkMaterialPlans;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MesWarehousePickingBillEntity entity) {
        if (entity == null || entity.getId() != null) throw new CheckedException("新增领料单不得携带已有ID");
        normalizePickingDetails(entity);
        // 序列号
        R<String> billNoR = remoteSeqConfigService.create("mes_picking_bill");
        if (!billNoR.isOk()) {
            throw new CheckedException("生成生产领料单号失败！");
        }
        String billNo = billNoR.getData();
        entity.setBillNo(billNo);
        entity.setStatus(MesPickingBillStateEnum.CREATED.getCode());
        MomUser user = SecurityUtils.getUser();
        entity.setPicker(user.getUsername());
        entity.setPickTime(LocalDateTime.now());
        baseMapper.insert(entity);

        for (MesWarehousePickingBillDetailEntity detail : entity.getDetailList()) {
            detail.setBillId(entity.getId());
            detail.setBillNo(billNo);
            detail.setStatus(YesNoEnum.NO.getCode());
            mesWarehousePickingBillDetailMapper.insert(detail);
            verifySavedPickingQuantities(detail);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MesWarehousePickingBillEntity entity) {
        if (entity == null || entity.getId() == null) throw new CheckedException("请选择领料单");
        MesWarehousePickingBillEntity original = lockDraft(entity.getId());
        entity.setBillNo(original.getBillNo());
        entity.setFactoryCode(original.getFactoryCode());
        entity.setTaskCode(original.getTaskCode());
        entity.setStatus(original.getStatus());
        if (entity.getDetailList() != null) for (MesWarehousePickingBillDetailEntity detail : entity.getDetailList()) {
            if (detail.getId() != null) {
                MesWarehousePickingBillDetailEntity existing = mesWarehousePickingBillDetailMapper.selectById(detail.getId());
                if (existing == null || !Objects.equals(existing.getBillId(), original.getId()))
                    throw new CheckedException("领料明细不属于当前单据");
            }
            detail.setBillId(original.getId());
            detail.setBillNo(original.getBillNo());
            detail.setFactoryCode(original.getFactoryCode());
            detail.setStatus(YesNoEnum.NO.getCode());
        }
        normalizePickingDetails(entity);
        baseMapper.updateById(entity);
        for (MesWarehousePickingBillDetailEntity mesWarehouseLockDetail : entity.getDetailList()) {
            if (Objects.isNull(mesWarehouseLockDetail.getId())) {
                mesWarehouseLockDetail.setBillId(entity.getId());
                mesWarehouseLockDetail.setBillId(entity.getId());
                mesWarehousePickingBillDetailMapper.insert(mesWarehouseLockDetail);
            } else {
                mesWarehousePickingBillDetailMapper.updateById(mesWarehouseLockDetail);
            }
            verifySavedPickingQuantities(mesWarehouseLockDetail);
        }
        return Boolean.TRUE;
    }

    /**
     * 领料单不能相信页面传入的替代标识和比例，必须以工单冻结的 BOM 快照重新判定。
     */
    private void normalizePickingDetails(MesWarehousePickingBillEntity entity) {
        if (entity == null || CollUtil.isEmpty(entity.getDetailList())) {
            throw new CheckedException("生产领料明细不能为空");
        }
        MesTaskEntity task = mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, entity.getTaskCode()).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("生产工单[" + entity.getTaskCode() + "]不存在");
        }
        reworkMaterialPlans.assertReady(task);
        if(task.getReworkMaterialPlanId()!=null){entity.setFactoryCode(task.getFactoryCode());entity.setTenantId(task.getTenantId());}
        Map<String, MdmBomChildDTO> requirements = MesMaterialSubstituteResolver.flattenBom(task.getBomSnapshot())
                .stream().collect(Collectors.toMap(MdmBomChildDTO::getMaterialCode, item -> item, (left, right) -> left));
        boolean reservationSnapshotFrozen = StrUtil.isNotBlank(task.getMaterialReservationSnapshot());
        List<WmsMaterialReservationLinkDTO> reservationLinks = reservationSnapshotFrozen
                ? JSONUtil.toList(task.getMaterialReservationSnapshot(), WmsMaterialReservationLinkDTO.class)
                : List.of();
        Map<String, BigDecimal> reservationUsed = new HashMap<>();
        for (MesWarehousePickingBillDetailEntity detail : entity.getDetailList()) {
            if (detail.getMatNum() == null || detail.getMatNum().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("领料物料[" + detail.getMatCode() + "]数量必须大于0");
            }
            MesMaterialSubstituteResolver.Binding binding = MesMaterialSubstituteResolver.resolveFeedBinding(
                    task.getBomSnapshot(), detail.getMatCode(), detail.getRequirementMatCode());
            MdmBomChildDTO requirement = requirements.get(binding.requirementMatCode());
            detail.setRequirementMatCode(binding.requirementMatCode());
            detail.setRequirementMatName(requirement == null ? null : requirement.getMaterialName());
            detail.setSubstituteFlag(binding.substitute());
            detail.setSubstituteRatio(binding.ratio());
            detail.setRequirementMatNum(detail.getMatNum().divide(binding.ratio(), 8, RoundingMode.HALF_UP));
            if(task.getReworkMaterialPlanId()!=null){
                detail.setRequirementMatNum(detail.getMatNum().divide(binding.ratio(),8,RoundingMode.UNNECESSARY).stripTrailingZeros());
                detail.setFactoryCode(task.getFactoryCode());detail.setTenantId(task.getTenantId());
            }
            detail.setReservationNo(null);
            if (reservationSnapshotFrozen) {
                List<WmsMaterialReservationLinkDTO> materialLinks = reservationLinks.stream()
                        .filter(link -> Objects.equals(link.getRequirementMatCode(), binding.requirementMatCode()))
                        .filter(link -> Objects.equals(link.getActualMatCode(), detail.getMatCode()))
                        .filter(link -> link.getSubstituteRatio() != null
                                && link.getSubstituteRatio().compareTo(binding.ratio()) == 0)
                        .toList();
                if (materialLinks.isEmpty()) {
                    if (binding.substitute()) {
                        throw new CheckedException("替代料[" + detail.getMatCode()
                                + "]不在工单冻结的PLAN批准预留中");
                    }
                    continue;
                }
                WmsMaterialReservationLinkDTO matched = materialLinks.stream()
                        .filter(link -> StrUtil.isBlank(link.getWarehouseCode())
                                || Objects.equals(link.getWarehouseCode(), detail.getWarehouseCode()))
                        .filter(link -> {
                            String key = reservationKey(link);
                            BigDecimal used = reservationUsed.getOrDefault(key, BigDecimal.ZERO);
                            return link.getReservedActualNum() != null
                                    && link.getReservedActualNum().subtract(used).compareTo(detail.getMatNum()) >= 0;
                        })
                        .findFirst()
                        .orElseThrow(() -> new CheckedException("物料[" + detail.getMatCode()
                                + "]不属于本次需求仓预留，或预留余额不足；请按预留量拆分领料明细"));
                detail.setReservationNo(matched.getReservationNo());
                reservationUsed.merge(reservationKey(matched), detail.getMatNum(), BigDecimal::add);
            }
        }
        if(task.getReworkMaterialPlanId()!=null)assertReworkPickingBudget(task,entity,requirements);
    }

    private void assertReworkPickingBudget(MesTaskEntity task,MesWarehousePickingBillEntity incoming,Map<String,MdmBomChildDTO> requirements){
        var prior=mesWarehousePickingBillDetailMapper.reworkBudget(task.getCode(),task.getTenantId(),task.getFactoryCode());
        if(prior.size()>5000)throw new CheckedException("返工领料行超过5000条，须受控扩容，不能截断后重新分配额度");
        Set<Long> replaced=incoming.getDetailList().stream().map(MesWarehousePickingBillDetailEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String,BigDecimal> used=new HashMap<>();
        List<MesWarehousePickingBillDetailEntity> all=new ArrayList<>(prior.stream().filter(d->!Objects.equals(d.getBillId(),incoming.getId()) || !replaced.contains(d.getId())).toList());
        all.addAll(incoming.getDetailList());
        for(var d:all){if(StrUtil.isBlank(d.getRequirementMatCode()) || d.getRequirementMatNum()==null || d.getRequirementMatNum().signum()<=0)
            throw new CheckedException("返工已有领料缺少精确需求量来源，不能绕过批准额度");
            used.merge(d.getRequirementMatCode(),d.getRequirementMatNum(),BigDecimal::add);}
        for(var entry:used.entrySet()){var requirement=requirements.get(entry.getKey());
            if(requirement==null || requirement.getQuantity()==null || entry.getValue().compareTo(requirement.getQuantity().multiply(task.getNoticeNum()))>0)
                throw new CheckedException("返工物料["+entry.getKey()+"]累计领料含草稿超过批准总量；删去未执行草稿或走后续受控补料，不自动回补退料额度");}
    }

    private void verifySavedPickingQuantities(MesWarehousePickingBillDetailEntity expected){
        var stored=mesWarehousePickingBillDetailMapper.selectById(expected.getId());
        if(stored==null || stored.getMatNum()==null || stored.getMatNum().compareTo(expected.getMatNum())!=0
                || stored.getRequirementMatNum()==null || stored.getRequirementMatNum().compareTo(expected.getRequirementMatNum())!=0
                || stored.getSubstituteRatio()==null || stored.getSubstituteRatio().compareTo(expected.getSubstituteRatio())!=0)
            throw new CheckedException("领料数量或替代换算保存发生舍入，已回滚");
    }

    private String reservationKey(WmsMaterialReservationLinkDTO link) {
        return link.getReservationNo() + "#" + link.getRequirementMatCode() + "#" + link.getActualMatCode();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        if (ids == null || ids.length == 0 || java.util.Arrays.asList(ids).contains(null)) throw new CheckedException("请选择领料单");
        for (Long id : java.util.Arrays.stream(ids).distinct().sorted().toList()) lockDraft(id);
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        mesWarehousePickingBillDetailMapper.delete(Wrappers.<MesWarehousePickingBillDetailEntity>lambdaQuery().in(MesWarehousePickingBillDetailEntity::getBillId, ids));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        if (ids == null || ids.length == 0) throw new CheckedException("请选择领料明细");
        List<MesWarehousePickingBillDetailEntity> details = mesWarehousePickingBillDetailMapper.selectBatchIds(java.util.Arrays.asList(ids));
        if (details.size() != java.util.Arrays.stream(ids).distinct().count()) throw new CheckedException("领料明细不存在");
        for (Long billId : details.stream().map(MesWarehousePickingBillDetailEntity::getBillId).distinct().sorted().toList()) lockDraft(billId);
        mesWarehousePickingBillDetailMapper.deleteBatchIds(CollUtil.toList(ids));
        return Boolean.TRUE;
    }

    /**
     * 提交生产领料单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean commitBill(Long id) {
        lockDraft(id);
        MesWarehousePickingBillEntity pickingBill = getByIdDeep(id);
        // 提交前再次核对已存明细；不能把草稿时期的旧方案或换算结果带入实际发料。
        normalizePickingDetails(pickingBill);
        for (var detail : pickingBill.getDetailList()) {
            if (mesWarehousePickingBillDetailMapper.updateById(detail) != 1) {
                throw new CheckedException("提交前领料需求复核保存失败");
            }
            verifySavedPickingQuantities(detail);
            if (!Double.isFinite(detail.getMatNum().doubleValue())
                    || BigDecimal.valueOf(detail.getMatNum().doubleValue()).compareTo(detail.getMatNum()) != 0) {
                throw new CheckedException("现有WMS通知接口无法无损表达该领料数量，禁止舍入发料");
            }
        }
        MomUser user = SecurityUtils.getUser();
        String username = user.getUsername();
        // 1、修改状态
        update(new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                        .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.COMMITTED.getCode())
//				.set(MesWarehousePickingBillEntity::getPicker, username)
//				.set(MesWarehousePickingBillEntity::getPickTime, LocalDateTime.now())
                        .eq(BaseEntity::getId, id)
        );
        // 2、生成出库通知单
        List<MesWarehousePickingBillDetailEntity> detailList = pickingBill.getDetailList();
        // 明细中存在不同的出库仓库，需要生成不同的出库单
        Map<String, List<MesWarehousePickingBillDetailEntity>> map = detailList.stream().collect(Collectors.groupingBy(MesWarehousePickingBillDetailEntity::getWarehouseCode));
        for (String warehouseCode : map.keySet()) {
            List<MesWarehousePickingBillDetailEntity> list = map.get(warehouseCode);
            String warehouseName = list.get(0).getWarehouseName();
            WmsOutNoticeEntity outNotice = new WmsOutNoticeEntity();
            BeanUtil.copyProperties(pickingBill, outNotice, "id");
            outNotice.setWarehouseCode(warehouseCode);
            outNotice.setWarehouseName(warehouseName);
            outNotice.setDocumentId(id);
            outNotice.setDocumentTable(DocumentEnum.MES_PICKING.getCode());
            List<WmsOutNoticeDetailEntity> outNoticeDetails = new ArrayList<>();
            for (MesWarehousePickingBillDetailEntity pickingBillDetail : list) {
                WmsOutNoticeDetailEntity outNoticeDetail = new WmsOutNoticeDetailEntity();
                BeanUtil.copyProperties(pickingBillDetail, outNoticeDetail, "id");
                outNoticeDetail.setNoticeOutNum(pickingBillDetail.getMatNum().doubleValue());
                outNoticeDetail.setDocumentId(id);
                outNoticeDetail.setDocumentTable(DocumentEnum.MES_PICKING.getCode());
                outNoticeDetails.add(outNoticeDetail);
            }
            outNotice.setDetailList(outNoticeDetails);
            R<Boolean> add = remoteProducePickingNoticeService.add(outNotice);
            if (!add.isOk()) {
                throw new CheckedException("生成WMS领料出库通知单失败！" + add.getMsg());
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 撤回
     *
     * @param id 生产领料单id
     */
    @Override
    @Transactional
    public Boolean withdraw(Long id) {
        MesWarehousePickingBillEntity pickingBill = baseMapper.selectOne(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
                .eq(MesWarehousePickingBillEntity::getId, id).last("FOR UPDATE"));
        if (pickingBill == null) {
            throw new CheckedException("生产领料单不存在");
        }
        if (!MesPickingBillStateEnum.COMMITTED.getCode().equals(pickingBill.getStatus())) {
            MesPickingBillStateEnum state = MesPickingBillStateEnum.getByCode(pickingBill.getStatus());
            throw new CheckedException("仅已提交且尚未出库的生产领料单允许撤回，当前状态："
                    + (state == null ? pickingBill.getStatus() : state.getName()));
        }
        if (mesWarehouseMaterialInBillService.count(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getBillId, id)) > 0)
            throw new CheckedException("已有WMS发料通知，须通过退料处置，不允许撤回领料来源");
        // 作废出库通知单
        R voidRes = remoteProducePickingNoticeService.voidObjByDocumentId(id, DocumentEnum.MES_PICKING.getCode());
        if (!voidRes.isOk()) {
            throw new CheckedException("作废领料出库通知单失败！" + voidRes.getMsg());
        }
        // 修改状态
        update(new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.CREATED.getCode())
                .eq(BaseEntity::getId, id)
        );
        return Boolean.TRUE;
    }

    /**
     * 领料出库单完成后返回结果
     */
    @Override
    @Transactional
    public Boolean wmsOutBillBack(MesPickingWmsOutBillBackDTO dto) {
        if (dto == null || dto.getId() == null || CollUtil.isEmpty(dto.getMatDetails())
                || dto.getMatDetails().stream().anyMatch(item -> item == null || item.getWmsOutDetailId() == null)) {
            throw new CheckedException("WMS发料回调必须包含生产领料单及精确出库明细来源");
        }
        MesWarehousePickingBillEntity bill = baseMapper.selectOne(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
                .eq(MesWarehousePickingBillEntity::getId, dto.getId()).last("FOR UPDATE"));
        if (bill == null || MesPickingBillStateEnum.CREATED.getCode().equals(bill.getStatus()))
            throw new CheckedException("领料单不存在或尚未正式提交");
        for (MesWarehouseMaterialInBillEntity detail : dto.getMatDetails().stream()
                .sorted(java.util.Comparator.comparing(MesWarehouseMaterialInBillEntity::getWmsOutDetailId)).toList()) {
            if (StrUtil.isNotBlank(detail.getFactoryCode()) && !Objects.equals(detail.getFactoryCode(), bill.getFactoryCode()))
                throw new CheckedException("WMS发料工厂与领料单不一致");
            detail.setBillId(bill.getId());
            detail.setBillNo(bill.getBillNo());
            detail.setFactoryCode(bill.getFactoryCode());
            materialReceiptService.registerNotice(detail);
        }
        materialReceiptService.refreshPicking(bill.getId());
        return true;
    }

    @Override
    @Transactional
    public Boolean receiveBill(MesPickingReceiveMatDTO dto) {
        if (dto == null || dto.getId() == null) throw new CheckedException("请选择生产领料单");
        return materialReceiptService.receiveAll(dto.getId(), dto.getSiteCode());
    }

    private MesWarehousePickingBillEntity lockDraft(Long id) {
        MesWarehousePickingBillEntity bill = baseMapper.selectOne(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
                .eq(MesWarehousePickingBillEntity::getId, id).last("FOR UPDATE"));
        if (bill == null || !MesPickingBillStateEnum.CREATED.getCode().equals(bill.getStatus())
                || mesWarehouseMaterialInBillService.count(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                        .eq(MesWarehouseMaterialInBillEntity::getBillId, id)) > 0)
            throw new CheckedException("只有未提交、无发料及签收事实的领料草案可以修改、删除或提交");
        return bill;
    }
}
