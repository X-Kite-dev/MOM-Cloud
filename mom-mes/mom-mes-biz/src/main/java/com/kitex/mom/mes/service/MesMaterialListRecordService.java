package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesMaterialListRecordDTO;
import com.kitex.mom.mes.entity.MesMaterialListRecordEntity;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用料清单领退料记录表 服务类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
public interface MesMaterialListRecordService extends IService<MesMaterialListRecordEntity> {

    /**
     * 根据用料清单明细ID获取领退料记录
     *
     * @param materialListDetailId 用料清单明细ID
     * @return 记录列表
     */
    List<MesMaterialListRecordDTO> getRecordsByDetailId(Long materialListDetailId);

    /**
     * 根据工单ID获取领退料记录
     *
     * @param taskId 工单ID
     * @return 记录列表
     */
    List<MesMaterialListRecordDTO> getRecordsByTaskId(Long taskId);

    /**
     * 创建领料记录
     *
     * @param materialListDetailId 用料清单明细ID
     * @param taskId               工单ID
     * @param materialCode         物料编码
     * @param quantity             数量
     * @param warehouseCode        仓库编码
     * @param warehouseName        仓库名称
     * @param batchNo              批次号
     * @param referenceBillNo      关联单据号
     * @param remark               备注
     * @return 创建结果
     */
    boolean createPickRecord(Long materialListDetailId, Long taskId, String materialCode,
                             java.math.BigDecimal quantity, String warehouseCode, String warehouseName,
                             String batchNo, String referenceBillNo, String remark);

    /**
     * 创建退料记录
     *
     * @param materialListDetailId 用料清单明细ID
     * @param taskId               工单ID
     * @param materialCode         物料编码
     * @param quantity             数量
     * @param warehouseCode        仓库编码
     * @param warehouseName        仓库名称
     * @param batchNo              批次号
     * @param referenceBillNo      关联单据号
     * @param remark               备注
     * @return 创建结果
     */
    boolean createReturnRecord(Long materialListDetailId, Long taskId, String materialCode,
                               java.math.BigDecimal quantity, String warehouseCode, String warehouseName,
                               String batchNo, String referenceBillNo, String remark);

    /**
     * 获取工单的领退料汇总信息
     *
     * @param taskId 工单ID
     * @return 汇总信息
     */
    List<Map<String, Object>> getPickReturnSummaryByTaskId(Long taskId);
}