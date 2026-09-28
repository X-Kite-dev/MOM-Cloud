package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.feign.RemoteBomService;
import com.kitex.mom.mes.dto.MesMaterialListDetailDTO;
import com.kitex.mom.mes.entity.MesMaterialListDetailEntity;
import com.kitex.mom.mes.enums.MesMaterialPickStatusEnum;
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
import java.util.List;

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

    private final RemoteBomService remoteBomService;
    private final com.kitex.mom.mes.mapper.MesTaskMapper tasks;
    private final MesReworkMaterialPlanService reworkMaterialPlans;
    private final MesMaterialListRecordService materialListRecordService;

    @Override
    public List<MesMaterialListDetailDTO> getDetailsByMaterialListId(Long materialListId) {
        return baseMapper.selectDetailsByMaterialListId(materialListId);
    }

    @Override
    public List<MesMaterialListDetailDTO> getDetailsByTaskId(Long taskId) {
        return baseMapper.selectDetailsByTaskId(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean pickMaterial(MesMaterialListPickVO pickVO) {
        try {
            if (pickVO.getQuantity() == null || pickVO.getQuantity().signum() <= 0)
                throw new com.kitex.common.core.exception.CheckedException("领料登记数量必须为正数");
            MesMaterialListDetailEntity detail = getOne(new LambdaQueryWrapper<MesMaterialListDetailEntity>().eq(MesMaterialListDetailEntity::getId, pickVO.getMaterialListDetailId()).last("FOR UPDATE"));
            if (detail == null) {
                log.error("用料清单明细不存在，id: {}", pickVO.getMaterialListDetailId());
                return false;
            }

            BigDecimal remainingQuantity = detail.getRequiredQuantity()
                    .subtract(detail.getPickedQuantity())
                    .add(detail.getReturnedQuantity());
            var task = tasks.selectById(detail.getTaskId());
            if (task != null && task.getReworkMaterialPlanId() != null) {
                reworkMaterialPlans.assertReady(task);
                remainingQuantity = detail.getRequiredQuantity().subtract(detail.getPickedQuantity());
            }

            if (pickVO.getQuantity().compareTo(remainingQuantity) > 0) {
                log.error("领料数量超过剩余需求数量，materialCode: {}, pickQuantity: {}, remainingQuantity: {}",
                        detail.getMaterialCode(), pickVO.getQuantity(), remainingQuantity);
                return false;
            }

            detail.setPickedQuantity(detail.getPickedQuantity().add(pickVO.getQuantity()));
            detail.setActualQuantity(detail.getPickedQuantity().subtract(detail.getReturnedQuantity()));

            if (detail.getPickedQuantity().compareTo(detail.getRequiredQuantity()) >= 0) {
                detail.setPickStatus(MesMaterialPickStatusEnum.PICKED.getCode());
            } else {
                detail.setPickStatus(MesMaterialPickStatusEnum.PICKING.getCode());
            }

            boolean updateResult = this.updateById(detail);
            if (!updateResult) {
                log.error("更新用料清单明细失败，id: {}", detail.getId());
                return false;
            }

            boolean recordResult = materialListRecordService.createPickRecord(
                    pickVO.getMaterialListDetailId(),
                    detail.getTaskId(),
                    detail.getMaterialCode(),
                    pickVO.getQuantity(),
                    pickVO.getWarehouseCode(),
                    pickVO.getWarehouseName(),
                    pickVO.getBatchNo(),
                    pickVO.getReferenceBillNo(),
                    pickVO.getRemark()
            );

            if (!recordResult) {
                log.error("创建领料记录失败，materialListDetailId: {}", pickVO.getMaterialListDetailId());
                throw new com.kitex.common.core.exception.CheckedException("领料登记回执保存失败，已回滚");
            }

            log.info("物料领料成功，materialCode: {}, quantity: {}", detail.getMaterialCode(), pickVO.getQuantity());
            return true;

        } catch (Exception e) {
            log.error("物料领料异常，materialListDetailId: {}", pickVO.getMaterialListDetailId(), e);
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean returnMaterial(MesMaterialListReturnVO returnVO) {
        try {
            if (returnVO.getQuantity() == null || returnVO.getQuantity().signum() <= 0)
                throw new com.kitex.common.core.exception.CheckedException("退料登记数量必须为正数");
            MesMaterialListDetailEntity detail = getOne(new LambdaQueryWrapper<MesMaterialListDetailEntity>().eq(MesMaterialListDetailEntity::getId, returnVO.getMaterialListDetailId()).last("FOR UPDATE"));
            if (detail == null) {
                log.error("用料清单明细不存在，id: {}", returnVO.getMaterialListDetailId());
                return false;
            }

            if (returnVO.getQuantity().compareTo(detail.getPickedQuantity().subtract(detail.getReturnedQuantity())) > 0) {
                log.error("退料数量超过已领料数量，materialCode: {}, returnQuantity: {}, availableQuantity: {}",
                        detail.getMaterialCode(), returnVO.getQuantity(),
                        detail.getPickedQuantity().subtract(detail.getReturnedQuantity()));
                return false;
            }

            detail.setReturnedQuantity(detail.getReturnedQuantity().add(returnVO.getQuantity()));
            detail.setActualQuantity(detail.getPickedQuantity().subtract(detail.getReturnedQuantity()));

            if (detail.getActualQuantity().compareTo(detail.getRequiredQuantity()) < 0) {
                detail.setPickStatus(MesMaterialPickStatusEnum.PICKING.getCode());
            }

            boolean updateResult = this.updateById(detail);
            if (!updateResult) {
                log.error("更新用料清单明细失败，id: {}", detail.getId());
                return false;
            }

            boolean recordResult = materialListRecordService.createReturnRecord(
                    returnVO.getMaterialListDetailId(),
                    detail.getTaskId(),
                    detail.getMaterialCode(),
                    returnVO.getQuantity(),
                    returnVO.getWarehouseCode(),
                    returnVO.getWarehouseName(),
                    returnVO.getBatchNo(),
                    returnVO.getReferenceBillNo(),
                    returnVO.getRemark()
            );

            if (!recordResult) {
                log.error("创建退料记录失败，materialListDetailId: {}", returnVO.getMaterialListDetailId());
                throw new com.kitex.common.core.exception.CheckedException("退料登记回执保存失败，已回滚");
            }

            log.info("物料退料成功，materialCode: {}, quantity: {}", detail.getMaterialCode(), returnVO.getQuantity());
            return true;

        } catch (Exception e) {
            log.error("物料退料异常，materialListDetailId: {}", returnVO.getMaterialListDetailId(), e);
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchPickMaterial(List<MesMaterialListPickVO> pickVOList) {
        if (CollectionUtils.isEmpty(pickVOList)) {
            return true;
        }

        int successCount = 0;
        for (MesMaterialListPickVO pickVO : pickVOList) {
            try {
                if (pickMaterial(pickVO)) {
                    successCount++;
                }
            } catch (Exception e) {
                log.error("批量领料失败，materialListDetailId: {}", pickVO.getMaterialListDetailId(), e);
            }
        }

        log.info("批量领料完成，总数: {}, 成功: {}", pickVOList.size(), successCount);
        return successCount == pickVOList.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createMaterialDetailsByBom(Long materialListId, Long taskId, Long bomId, String bomVersion, BigDecimal planQuantity) {
        try {
            var task = tasks.selectOne(new LambdaQueryWrapper<com.kitex.mom.mes.entity.MesTaskEntity>().eq(com.kitex.mom.mes.entity.MesTaskEntity::getId, taskId).last("FOR UPDATE"));
            if (task != null && "REWORK".equals(task.getResourceType()))
                return createReworkDetails(materialListId, task, planQuantity);
            this.remove(new LambdaQueryWrapper<MesMaterialListDetailEntity>()
                    .eq(MesMaterialListDetailEntity::getMaterialListId, materialListId));

            //TODO 获取BOM物料清单 待验证完善
            R<List<MdmBomChildDTO>> bomResult = remoteBomService.getChildMaterials("", bomId, bomVersion);
            if (bomResult == null || !bomResult.isOk() || CollectionUtils.isEmpty(bomResult.getData())) {
                log.error("获取BOM物料清单失败，bomId: {}", bomId);
                return false;
            }

            List<MdmBomChildDTO> bomChildren = bomResult.getData();
            for (MdmBomChildDTO bomChild : bomChildren) {
                MesMaterialListDetailEntity detail = new MesMaterialListDetailEntity();
                detail.setMaterialListId(materialListId);
                detail.setTaskId(taskId);
                detail.setMaterialCode(bomChild.getMaterialCode());
                detail.setMaterialName(bomChild.getMaterialName());
                detail.setMaterialSpec(bomChild.getMaterialSpec());
                detail.setUnit(bomChild.getUnit());
                detail.setBomQuantity(bomChild.getQuantity());
                detail.setRequiredQuantity(bomChild.getQuantity().multiply(planQuantity));
                detail.setPickedQuantity(BigDecimal.ZERO);
                detail.setReturnedQuantity(BigDecimal.ZERO);
                detail.setActualQuantity(BigDecimal.ZERO);
                detail.setMaterialAttribute(bomChild.getMaterialAttribute());
                detail.setFixedLossRate(bomChild.getFixedLossRate());
                detail.setVariableLossRate(bomChild.getVariableLossRate());
                detail.setDefaultWarehouse(bomChild.getDefaultIssuanceWarehouse());
                detail.setIsLossIssued(Boolean.TRUE.equals(bomChild.getIsLossIssued()));
                detail.setUsageType(bomChild.getUsageType());
                detail.setPickStatus(MesMaterialPickStatusEnum.PENDING.getCode());

                this.save(detail);
            }

            log.info("成功创建用料清单明细，materialListId: {}, 物料数量: {}", materialListId, bomChildren.size());
            return true;

        } catch (Exception e) {
            log.error("创建用料清单明细异常，materialListId: {}, bomId: {}", materialListId, bomId, e);
            throw e;
        }
    }

    @Override
    public boolean updatePickStatus(Long id, String pickStatus) {
        MesMaterialListDetailEntity entity = new MesMaterialListDetailEntity();
        entity.setId(id);
        entity.setPickStatus(pickStatus);
        return this.updateById(entity);
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
        // 这里应该调用库存服务来检查库存
        // 暂时返回true，实际实现时需要调用WMS模块的库存查询接口
        log.info("检查物料库存，materialCode: {}, requiredQuantity: {}", materialCode, requiredQuantity);
        return true;
    }
}
