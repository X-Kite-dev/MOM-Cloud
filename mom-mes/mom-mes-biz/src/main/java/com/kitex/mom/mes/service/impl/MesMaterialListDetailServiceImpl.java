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
            MesMaterialListDetailEntity detail = this.getById(pickVO.getMaterialListDetailId());
            if (detail == null) {
                log.error("用料清单明细不存在，id: {}", pickVO.getMaterialListDetailId());
                return false;
            }

            BigDecimal remainingQuantity = detail.getRequiredQuantity()
                    .subtract(detail.getPickedQuantity())
                    .add(detail.getReturnedQuantity());

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
                return false;
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
            MesMaterialListDetailEntity detail = this.getById(returnVO.getMaterialListDetailId());
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
                return false;
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
    public boolean createMaterialDetailsByBom(Long materialListId, Long taskId, Long bomId, BigDecimal planQuantity) {
        try {
            this.remove(new LambdaQueryWrapper<MesMaterialListDetailEntity>()
                    .eq(MesMaterialListDetailEntity::getMaterialListId, materialListId));

            //TODO 获取BOM物料清单 待验证完善
            R<List<MdmBomChildDTO>> bomResult = remoteBomService.getChildMaterials(null, bomId, null);
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
                detail.setIsLossIssued(bomChild.getIsLossIssued());
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

    @Override
    public boolean checkMaterialStock(String materialCode, BigDecimal requiredQuantity) {
        // 这里应该调用库存服务来检查库存
        // 暂时返回true，实际实现时需要调用WMS模块的库存查询接口
        log.info("检查物料库存，materialCode: {}, requiredQuantity: {}", materialCode, requiredQuantity);
        return true;
    }
}