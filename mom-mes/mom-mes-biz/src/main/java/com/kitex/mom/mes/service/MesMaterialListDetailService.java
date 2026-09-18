package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesMaterialListDetailDTO;
import com.kitex.mom.mes.entity.MesMaterialListDetailEntity;
import com.kitex.mom.mes.vo.MesMaterialListPickVO;
import com.kitex.mom.mes.vo.MesMaterialListReturnVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 用料清单明细表 服务类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
public interface MesMaterialListDetailService extends IService<MesMaterialListDetailEntity> {

    /**
     * 根据用料清单ID获取明细列表
     *
     * @param materialListId 用料清单ID
     * @return 明细列表
     */
    List<MesMaterialListDetailDTO> getDetailsByMaterialListId(Long materialListId);

    /**
     * 根据工单ID获取明细列表
     *
     * @param taskId 工单ID
     * @return 明细列表
     */
    List<MesMaterialListDetailDTO> getDetailsByTaskId(Long taskId);

    /**
     * 物料领料操作
     *
     * @param pickVO 领料信息
     * @return 领料结果
     */
    boolean pickMaterial(MesMaterialListPickVO pickVO);

    /**
     * 物料退料操作
     *
     * @param returnVO 退料信息
     * @return 退料结果
     */
    boolean returnMaterial(MesMaterialListReturnVO returnVO);

    /**
     * 批量领料操作
     *
     * @param pickVOList 批量领料信息
     * @return 领料结果
     */
    boolean batchPickMaterial(List<MesMaterialListPickVO> pickVOList);

    /**
     * 根据BOM信息创建用料清单明细
     *
     * @param materialListId 用料清单ID
     * @param taskId         工单ID
     * @param bomId          BOM ID
     * @param bomVersion     BOM版本
     * @param planQuantity   计划数量
     * @return 创建结果
     */
    boolean createMaterialDetailsByBom(Long materialListId, Long taskId, Long bomId, String bomVersion, BigDecimal planQuantity);

    /**
     * 更新明细的领料状态
     *
     * @param id         明细ID
     * @param pickStatus 领料状态
     * @return 更新结果
     */
    boolean updatePickStatus(Long id, String pickStatus);

    /**
     * 检查物料库存是否充足
     *
     * @param materialCode     物料编码
     * @param requiredQuantity 需求数量
     * @return 是否充足
     */
    boolean checkMaterialStock(String materialCode, BigDecimal requiredQuantity);
}