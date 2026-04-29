package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.dto.MesMaterialListRecordDTO;
import com.kitex.mom.mes.entity.MesMaterialListRecordEntity;
import com.kitex.mom.mes.enums.MesMaterialOperationTypeEnum;
import com.kitex.mom.mes.mapper.MesMaterialListRecordMapper;
import com.kitex.mom.mes.service.MesMaterialListRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用料清单领退料记录表 服务实现类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MesMaterialListRecordServiceImpl extends ServiceImpl<MesMaterialListRecordMapper, MesMaterialListRecordEntity> implements MesMaterialListRecordService {

    @Override
    public List<MesMaterialListRecordDTO> getRecordsByDetailId(Long materialListDetailId) {
        return baseMapper.selectRecordsByDetailId(materialListDetailId);
    }

    @Override
    public List<MesMaterialListRecordDTO> getRecordsByTaskId(Long taskId) {
        return baseMapper.selectRecordsByTaskId(taskId);
    }

    @Override
    public boolean createPickRecord(Long materialListDetailId, Long taskId, String materialCode,
                                    BigDecimal quantity, String warehouseCode, String warehouseName,
                                    String batchNo, String referenceBillNo, String remark) {
        try {
            MesMaterialListRecordEntity record = new MesMaterialListRecordEntity();
            record.setMaterialListDetailId(materialListDetailId);
            record.setTaskId(taskId);
            record.setMaterialCode(materialCode);
            record.setOperationType(MesMaterialOperationTypeEnum.PICK.getCode());
            record.setQuantity(quantity);
            record.setWarehouseCode(warehouseCode);
            record.setWarehouseName(warehouseName);
            record.setBatchNo(batchNo);
            record.setOperationTime(LocalDateTime.now());
            record.setReferenceBillNo(referenceBillNo);
            record.setRemark(remark);

            boolean saveResult = this.save(record);
            if (saveResult) {
                log.info("创建领料记录成功，materialCode: {}, quantity: {}", materialCode, quantity);
            } else {
                log.error("创建领料记录失败，materialCode: {}, quantity: {}", materialCode, quantity);
            }

            return saveResult;

        } catch (Exception e) {
            log.error("创建领料记录异常，materialCode: {}", materialCode, e);
            return false;
        }
    }

    @Override
    public boolean createReturnRecord(Long materialListDetailId, Long taskId, String materialCode,
                                      BigDecimal quantity, String warehouseCode, String warehouseName,
                                      String batchNo, String referenceBillNo, String remark) {
        try {
            MesMaterialListRecordEntity record = new MesMaterialListRecordEntity();
            record.setMaterialListDetailId(materialListDetailId);
            record.setTaskId(taskId);
            record.setMaterialCode(materialCode);
            record.setOperationType(MesMaterialOperationTypeEnum.RETURN.getCode());
            record.setQuantity(quantity);
            record.setWarehouseCode(warehouseCode);
            record.setWarehouseName(warehouseName);
            record.setBatchNo(batchNo);
            record.setOperationTime(LocalDateTime.now());
            record.setReferenceBillNo(referenceBillNo);
            record.setRemark(remark);

            boolean saveResult = this.save(record);
            if (saveResult) {
                log.info("创建退料记录成功，materialCode: {}, quantity: {}", materialCode, quantity);
            } else {
                log.error("创建退料记录失败，materialCode: {}, quantity: {}", materialCode, quantity);
            }

            return saveResult;

        } catch (Exception e) {
            log.error("创建退料记录异常，materialCode: {}", materialCode, e);
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> getPickReturnSummaryByTaskId(Long taskId) {
        return baseMapper.selectPickReturnSummaryByTaskId(taskId);
    }
}