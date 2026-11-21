package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MdmBomChildDTO;
import com.kitex.mom.mes.entity.MesMaterialListEntity;
import com.kitex.mom.mes.entity.MesMaterialListRecordEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.mapper.MesMaterialListMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.util.MesFrozenBomProjection;
import com.kitex.mom.mes.dto.MesMaterialListDetailDTO;
import com.kitex.mom.mes.entity.MesMaterialListDetailEntity;
import com.kitex.mom.mes.enums.MesMaterialPickStatusEnum;
import com.kitex.mom.mes.enums.MesMaterialListStatusEnum;
import com.kitex.mom.mes.mapper.MesMaterialListDetailMapper;
import com.kitex.mom.mes.service.MesMaterialListDetailService;
import com.kitex.mom.mes.service.MesMaterialListRecordService;
import com.kitex.mom.mes.vo.MesMaterialListPickVO;
import com.kitex.mom.mes.vo.MesMaterialListReturnVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p>
 * 用料清单明细表 服务实现类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MesMaterialListDetailServiceImpl extends ServiceImpl<MesMaterialListDetailMapper, MesMaterialListDetailEntity> implements MesMaterialListDetailService {

    private final MesActualFactoryAccessService factoryAccess;
    private final MesWarehouseLiveStockService lineStock;
    private final MesMaterialListMapper materialLists;
    private final MesTaskMapper tasks;
    private final MesReworkMaterialPlanService reworkMaterialPlans;
    private final MesMaterialListRecordService materialListRecordService;

    @Override
    public List<MesMaterialListDetailDTO> getDetailsByMaterialListId(Long materialListId) {
        String factory = factoryAccess.require("read");
        return baseMapper.selectDetailsByMaterialListId(materialListId, TenantContextHolder.getTenantId(), factory);
    }

    @Override
    public List<MesMaterialListDetailDTO> getDetailsByTaskId(Long taskId) {
        String factory = factoryAccess.require("read");
        return baseMapper.selectDetailsByTaskId(taskId, TenantContextHolder.getTenantId(), factory);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean pickMaterial(MesMaterialListPickVO pickVO) {
        var locked = lockPosting(pickVO.getMaterialListDetailId());
        var row = locked.row();
        BigDecimal quantity = postingQuantity(pickVO.getQuantity());
        BigDecimal remaining = remaining(row, locked.task());
        if (quantity.compareTo(remaining) > 0) {
            throw new CheckedException("领料登记超过该明细剩余需求量 " + remaining.toPlainString());
        }
        row.setPickedQuantity(row.getPickedQuantity().add(quantity));
        savePosting(locked);
        if (!materialListRecordService.createPickRecord(row.getId(), row.getTaskId(), row.getMaterialCode(),
                quantity, pickVO.getWarehouseCode(), pickVO.getWarehouseName(), pickVO.getBatchNo(),
                pickVO.getReferenceBillNo(), pickVO.getRemark())) {
            throw new CheckedException("领料登记回执保存失败，已回滚");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean returnMaterial(MesMaterialListReturnVO returnVO) {
        var locked = lockPosting(returnVO.getMaterialListDetailId());
        var row = locked.row();
        BigDecimal quantity = postingQuantity(returnVO.getQuantity());
        if (quantity.compareTo(row.getPickedQuantity().subtract(row.getReturnedQuantity())) > 0) {
            throw new CheckedException("退料登记超过该明细的实际净领用量");
        }
        row.setReturnedQuantity(row.getReturnedQuantity().add(quantity));
        savePosting(locked);
        if (!materialListRecordService.createReturnRecord(row.getId(), row.getTaskId(), row.getMaterialCode(),
                quantity, returnVO.getWarehouseCode(), returnVO.getWarehouseName(), returnVO.getBatchNo(),
                returnVO.getReferenceBillNo(), returnVO.getRemark())) {
            throw new CheckedException("退料登记回执保存失败，已回滚");
        }
        return true;
    }

    /** 明细只用于定位。按生成清单相同的顺序锁工单、清单和实际明细，再核对归属。 */
    private LockedPosting lockPosting(Long id) {
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        var query = new LambdaQueryWrapper<MesMaterialListDetailEntity>()
                .eq(MesMaterialListDetailEntity::getId, id).eq(MesMaterialListDetailEntity::getTenantId, tenant)
                .eq(MesMaterialListDetailEntity::getFactoryCode, factory);
        var found = getOne(query);
        if (found == null) {
            throw new CheckedException("当前工厂未找到原用料明细");
        }
        var task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, found.getTaskId()).eq(MesTaskEntity::getTenantId, tenant)
                .eq(MesTaskEntity::getFactoryCode, factory).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("当前工厂未找到原生产工单");
        }
        reworkMaterialPlans.assertReady(task);
        var head = materialLists.selectOne(new LambdaQueryWrapper<MesMaterialListEntity>()
                .eq(MesMaterialListEntity::getId, found.getMaterialListId())
                .eq(MesMaterialListEntity::getTaskId, task.getId()).eq(MesMaterialListEntity::getTenantId, tenant)
                .eq(MesMaterialListEntity::getFactoryCode, factory).last("FOR UPDATE"));
        var row = getOne(query.last("FOR UPDATE"));
        if (head == null || row == null || !Objects.equals(row.getTaskId(), task.getId())
                || !Objects.equals(row.getMaterialListId(), head.getId())) {
            throw new CheckedException("用料明细的原单归属已变化，请重新读取");
        }
        remaining(row, task);
        return new LockedPosting(task, head, row);
    }

    private record LockedPosting(MesTaskEntity task, MesMaterialListEntity head, MesMaterialListDetailEntity row) {
    }

    /** 台账明细最多保留八位小数。拒绝超精度登记，避免流水与余额分别舍入。 */
    private BigDecimal postingQuantity(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 8) {
            throw new CheckedException("领退料登记数量须为正数，最多支持八位小数");
        }
        return value;
    }

    /** 普通工单以净领用扣减需求；批准返工的补料上限只扣累计领用。 */
    private BigDecimal remaining(MesMaterialListDetailEntity row, MesTaskEntity task) {
        if (row.getRequiredQuantity() == null || row.getRequiredQuantity().signum() <= 0
                || row.getPickedQuantity() == null || row.getReturnedQuantity() == null
                || row.getReturnedQuantity().signum() < 0 || row.getPickedQuantity().compareTo(row.getReturnedQuantity()) < 0) {
            throw new CheckedException("用料明细 " + row.getId() + " 的需求量或领退余额异常，请核对原登记");
        }
        BigDecimal balance = row.getRequiredQuantity().subtract(row.getPickedQuantity());
        return task.getReworkMaterialPlanId() == null ? balance.add(row.getReturnedQuantity()) : balance;
    }

    private String postingStatus(MesMaterialListDetailEntity row, MesTaskEntity task) {
        if (remaining(row, task).signum() <= 0) {
            return MesMaterialPickStatusEnum.PICKED.getCode();
        }
        return row.getPickedQuantity().signum() > 0 ? MesMaterialPickStatusEnum.PICKING.getCode()
                : MesMaterialPickStatusEnum.PENDING.getCode();
    }

    /** 明细、整单进度及领退记录属于同一事务，任一写入失败时回滚。 */
    private void savePosting(LockedPosting locked) {
        var row = locked.row();
        row.setActualQuantity(row.getPickedQuantity().subtract(row.getReturnedQuantity()));
        row.setPickStatus(postingStatus(row, locked.task()));
        if (!updateById(row)) {
            throw new CheckedException("原用料明细未更新");
        }
        applyHeadStatus(locked.head(), locked.task(), null);
    }

    @Override
    public boolean applyHeadStatus(MesMaterialListEntity head, MesTaskEntity task, String requested) {
        var rows = list(new LambdaQueryWrapper<MesMaterialListDetailEntity>()
                .eq(MesMaterialListDetailEntity::getMaterialListId, head.getId())
                .eq(MesMaterialListDetailEntity::getTaskId, task.getId())
                .eq(MesMaterialListDetailEntity::getTenantId, task.getTenantId())
                .eq(MesMaterialListDetailEntity::getFactoryCode, task.getFactoryCode()));
        boolean done = rows.isEmpty() ? reworkMaterialPlans.approvedWithoutMaterials(task)
                : rows.stream().allMatch(row -> remaining(row, task).signum() <= 0);
        var next = MesMaterialListStatusEnum.NEW;
        if (done) {
            next = MesMaterialListStatusEnum.COMPLETED;
        } else if (rows.stream().anyMatch(row -> row.getPickedQuantity() != null && row.getPickedQuantity().signum() > 0)) {
            next = MesMaterialListStatusEnum.PICKING;
        }
        if (requested != null && !next.getCode().equals(requested)) {
            throw new CheckedException("清单状态与原领退数量不符，当前应为" + next.getCode());
        }
        if (!next.getCode().equals(head.getStatus())) {
            var update = new MesMaterialListEntity();
            update.setId(head.getId());
            update.setStatus(next.getCode());
            if (materialLists.updateById(update) != 1) {
                throw new CheckedException("用料清单进度未更新");
            }
        }
        return done;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchPickMaterial(List<MesMaterialListPickVO> pickVOList) {
        if (CollectionUtils.isEmpty(pickVOList)) {
            return true;
        }
        for (var pick : pickVOList) {
            pickMaterial(pick);
        }
        return true;
    }

    /**
     * 从原工单生成本层领用的直接子件。子装配以自身为领料对象，其下级按子装配工单领用。
     * 重算时锁定旧明细；相同需求沿用原 ID，有领退记录的行不能被删除或重置。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createMaterialDetailsByBom(Long materialListId, Long taskId, Long bomId,
                                              String bomVersion, BigDecimal planQuantity) {
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        MesTaskEntity task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, taskId).eq(MesTaskEntity::getTenantId, tenant)
                .eq(MesTaskEntity::getFactoryCode, factory).last("FOR UPDATE"));
        if (task == null || !Objects.equals(bomId, task.getBomId()) || !Objects.equals(bomVersion, task.getBomVersion())
                || planQuantity == null || planQuantity.signum() <= 0 || !equalQuantity(planQuantity, task.getNoticeNum())) {
            throw new CheckedException("用料清单与当前工厂原工单的 BOM 或计划数量不符");
        }
        if ("FINISH".equals(task.getStatus()) || "CLOSE".equals(task.getStatus())) {
            throw new CheckedException("工单已完成或关闭，请查看原用料清单");
        }
        MesMaterialListEntity head = materialLists.selectOne(new LambdaQueryWrapper<MesMaterialListEntity>()
                .eq(MesMaterialListEntity::getId, materialListId).eq(MesMaterialListEntity::getTaskId, taskId)
                .eq(MesMaterialListEntity::getTenantId, tenant).eq(MesMaterialListEntity::getFactoryCode, factory)
                .last("FOR UPDATE"));
        if (head == null) {
            throw new CheckedException("原工单未找到对应的用料清单");
        }
        if ("REWORK".equals(task.getResourceType())) {
            return createReworkDetails(materialListId, task, planQuantity);
        }

        var children = MesFrozenBomProjection.readSnapshot(task.getBomSnapshot());
        if (children.isEmpty()) {
            throw new CheckedException("工单冻结 BOM 没有子件，请核对原工程版本");
        }
        // 现有领用台账以清单和物料唯一。同料同计量及发料条件可以合计，仓库等条件冲突须核工程原单。
        Map<String, MesMaterialListDetailEntity> expected = new LinkedHashMap<>();
        for (var child : children) {
            var next = frozenDetail(task, materialListId, child);
            var prior = expected.putIfAbsent(next.getMaterialCode(), next);
            if (prior != null) {
                if (!sameIssuanceRules(prior, next)) {
                    throw new CheckedException("工单 " + task.getCode() + " 的同层组件 " + next.getMaterialCode()
                            + " 有不同单位或发料条件，无法合并登记，请核对冻结工程明细");
                }
                prior.setBomQuantity(prior.getBomQuantity().add(next.getBomQuantity()));
                prior.setRequiredQuantity(prior.getRequiredQuantity().add(next.getRequiredQuantity()));
            }
        }
        var existing = list(new LambdaQueryWrapper<MesMaterialListDetailEntity>()
                .eq(MesMaterialListDetailEntity::getMaterialListId, materialListId)
                .orderByAsc(MesMaterialListDetailEntity::getId).last("FOR UPDATE"));
        List<MesMaterialListDetailEntity> unmatched = new ArrayList<>();
        for (var old : existing) {
            if (!Objects.equals(old.getTaskId(), taskId) || !Objects.equals(old.getTenantId(), tenant)
                    || !Objects.equals(old.getFactoryCode(), factory)) {
                throw new CheckedException("用料清单旧明细与原工单组织不一致，请核实原明细");
            }
            var row = expected.get(old.getMaterialCode());
            if (row != null && sameRequirement(old, row)) {
                expected.remove(old.getMaterialCode());
            } else {
                unmatched.add(old);
            }
        }
        var missing = expected.values();
        if (missing.isEmpty() && unmatched.isEmpty()) {
            return true;
        }
        if (existing.stream().anyMatch(row -> !untouched(row))
                || materialListRecordService.count(new LambdaQueryWrapper<MesMaterialListRecordEntity>()
                        .eq(MesMaterialListRecordEntity::getTaskId, taskId)
                        .eq(MesMaterialListRecordEntity::getTenantId, tenant)
                        .eq(MesMaterialListRecordEntity::getFactoryCode, factory)) > 0) {
            throw new CheckedException("工单 " + task.getCode() + " 已有领退登记且清单与冻结需求不一致，请核对原明细");
        }
        for (var old : unmatched) {
            if (!removeById(old.getId())) {
                throw new CheckedException("用料清单旧明细的组织或删除回执异常");
            }
        }
        for (var row : missing) {
            if (!save(row)) {
                throw new CheckedException("冻结用料清单明细保存失败");
            }
            var stored = getById(row.getId());
            if (stored == null || !sameRequirement(stored, row)) {
                throw new CheckedException("用料清单回读与冻结用量或单位不一致，请核对原单及数量精度");
            }
        }
        return true;
    }

    private MesMaterialListDetailEntity frozenDetail(MesTaskEntity task, Long listId, MdmBomChildDTO child) {
        if (child.getQuantity() == null || child.getQuantity().signum() <= 0
                || child.getUnit() == null || child.getUnit().isBlank()) {
            throw new CheckedException("工单 " + task.getCode() + " 的冻结子件 " + child.getMaterialCode() + " 缺少有效用量或单位");
        }
        MesMaterialListDetailEntity row = new MesMaterialListDetailEntity();
        row.setTenantId(task.getTenantId());
        row.setFactoryCode(task.getFactoryCode());
        row.setTaskId(task.getId());
        row.setMaterialListId(listId);
        row.setMaterialCode(child.getMaterialCode());
        row.setMaterialName(child.getMaterialName());
        row.setMaterialSpec(child.getMaterialSpec());
        row.setUnit(child.getUnit());
        row.setBomQuantity(child.getQuantity());
        row.setRequiredQuantity(child.getQuantity().multiply(task.getNoticeNum()));
        row.setDefaultWarehouse(child.getDefaultIssuanceWarehouse());
        row.setMaterialAttribute(child.getMaterialAttribute());
        row.setFixedLossRate(child.getFixedLossRate());
        row.setVariableLossRate(child.getVariableLossRate());
        row.setIsLossIssued(Boolean.TRUE.equals(child.getIsLossIssued()));
        row.setUsageType(child.getUsageType());
        row.setPickedQuantity(BigDecimal.ZERO);
        row.setReturnedQuantity(BigDecimal.ZERO);
        row.setActualQuantity(BigDecimal.ZERO);
        row.setPickStatus(MesMaterialPickStatusEnum.PENDING.getCode());
        return row;
    }

    /** 名称和描述不改变领用依据，数量、单位及发料规则按原值精确比较。 */
    private boolean sameRequirement(MesMaterialListDetailEntity left, MesMaterialListDetailEntity right) {
        return Objects.equals(left.getTaskId(), right.getTaskId())
                && Objects.equals(left.getTenantId(), right.getTenantId())
                && Objects.equals(left.getFactoryCode(), right.getFactoryCode())
                && Objects.equals(left.getMaterialCode(), right.getMaterialCode())
                && equalQuantity(left.getBomQuantity(), right.getBomQuantity())
                && equalQuantity(left.getRequiredQuantity(), right.getRequiredQuantity())
                && sameIssuanceRules(left, right);
    }

    /** 同码组件只有在台账记录的计量和发料属性一致时才可以合计。 */
    private boolean sameIssuanceRules(MesMaterialListDetailEntity left, MesMaterialListDetailEntity right) {
        return Objects.equals(left.getUnit(), right.getUnit())
                && Objects.equals(left.getDefaultWarehouse(), right.getDefaultWarehouse())
                && Objects.equals(left.getMaterialAttribute(), right.getMaterialAttribute())
                && Objects.equals(left.getUsageType(), right.getUsageType())
                && Objects.equals(left.getIsLossIssued(), right.getIsLossIssued())
                && equalQuantity(left.getFixedLossRate(), right.getFixedLossRate())
                && equalQuantity(left.getVariableLossRate(), right.getVariableLossRate());
    }

    private boolean equalQuantity(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private boolean untouched(MesMaterialListDetailEntity row) {
        return MesMaterialPickStatusEnum.PENDING.getCode().equals(row.getPickStatus())
                && equalQuantity(row.getPickedQuantity(), BigDecimal.ZERO)
                && equalQuantity(row.getReturnedQuantity(), BigDecimal.ZERO)
                && equalQuantity(row.getActualQuantity(), BigDecimal.ZERO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updatePickStatus(Long id, String pickStatus) {
        var locked = lockPosting(id);
        String actual = postingStatus(locked.row(), locked.task());
        boolean fullyPicked = MesMaterialPickStatusEnum.PICKED.getCode().equals(actual);
        if (!actual.equals(pickStatus) && !(fullyPicked && MesMaterialPickStatusEnum.COMPLETED.getCode().equals(pickStatus))) {
            throw new CheckedException("明细领料状态与实际登记数量不符");
        }
        locked.row().setPickStatus(pickStatus);
        if (!updateById(locked.row())) {
            throw new CheckedException("用料明细状态未更新");
        }
        applyHeadStatus(locked.head(), locked.task(), null);
        return true;
    }

    private boolean createReworkDetails(Long listId, com.kitex.mom.mes.entity.MesTaskEntity task, BigDecimal quantity) {
        reworkMaterialPlans.assertReady(task);
        if (quantity == null || task.getNoticeNum() == null || quantity.compareTo(task.getNoticeNum()) != 0)
            throw new com.kitex.common.core.exception.CheckedException("返工用料数量须等于批准工单数量");
        var bom = com.kitex.mom.mes.util.MesMaterialSubstituteResolver.flattenBom(task.getBomSnapshot());
        var old = list(new LambdaQueryWrapper<MesMaterialListDetailEntity>().eq(MesMaterialListDetailEntity::getMaterialListId, listId));
        if (!old.isEmpty()) {
            if (old.size() != bom.size())
                throw new com.kitex.common.core.exception.CheckedException("已有返工用料清单与冻结方案不同，禁止删除重建掩盖原领退料历史");
            for (var item : bom) {
                var matches = old.stream().filter(x -> item.getMaterialCode().equals(x.getMaterialCode())).toList();
                if (matches.size() != 1 || matches.get(0).getRequiredQuantity() == null || matches.get(0).getRequiredQuantity().compareTo(item.getQuantity().multiply(quantity)) != 0
                        || !java.util.Objects.equals(matches.get(0).getUnit(), item.getUnit()))
                    throw new com.kitex.common.core.exception.CheckedException("已有返工用料与批准量/单位不一致，不能静默重置");
            }
            return true;
        }
        for (var item : bom) {
            if (item.getQuantity() == null || item.getQuantity().signum() <= 0)
                throw new com.kitex.common.core.exception.CheckedException("返工冻结用料含未知或非正数量");
            var d = new MesMaterialListDetailEntity();
            d.setMaterialListId(listId);
            d.setTaskId(task.getId());
            d.setTenantId(task.getTenantId());
            d.setFactoryCode(task.getFactoryCode());
            d.setMaterialCode(item.getMaterialCode());
            d.setMaterialName(item.getMaterialName());
            d.setUnit(item.getUnit());
            d.setBomQuantity(item.getQuantity());
            d.setRequiredQuantity(item.getQuantity().multiply(quantity));
            d.setPickedQuantity(BigDecimal.ZERO);
            d.setReturnedQuantity(BigDecimal.ZERO);
            d.setActualQuantity(BigDecimal.ZERO);
            d.setMaterialAttribute(item.getMaterialAttribute());
            d.setDefaultWarehouse(item.getDefaultIssuanceWarehouse());
            d.setFixedLossRate(BigDecimal.ZERO);
            d.setVariableLossRate(BigDecimal.ZERO);
            d.setIsLossIssued(false);
            d.setUsageType(item.getUsageType());
            d.setPickStatus(MesMaterialPickStatusEnum.PENDING.getCode());
            if (!save(d) || getById(d.getId()).getRequiredQuantity().compareTo(d.getRequiredQuantity()) != 0)
                throw new com.kitex.common.core.exception.CheckedException("返工用料保存失败或数据库数量发生舍入，已回滚");
        }
        return true;
    }

    @Override
    public boolean checkMaterialStock(String materialCode, BigDecimal requiredQuantity) {
        if (materialCode == null || materialCode.isBlank() || requiredQuantity == null || requiredQuantity.signum() <= 0) {
            throw new CheckedException("物料库存核验须指定物料编码和正的需求数量");
        }
        var query = new MesWarehouseLiveStockInoutDTO();
        query.setMatCode(materialCode);
        query.setFactoryCode(factoryAccess.require("read"));
        // 未指定生产来源，只统计未专属工序且未被预留的合格候选。工位开工仍按原派工再次核验。
        BigDecimal eligible = lineStock.previewFeedSources(query).stream()
                .filter(row -> row.getProduceTaskCode() == null || row.getProduceTaskCode().isBlank())
                .filter(row -> row.getReservationNo() == null || row.getReservationNo().isBlank())
                .map(MesWarehouseLiveStockEntity::getNum).reduce(BigDecimal.ZERO, BigDecimal::add);
        return eligible.compareTo(requiredQuantity) >= 0;
    }
}
