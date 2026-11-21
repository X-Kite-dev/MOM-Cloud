package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.dto.MesMaterialListDetailDTO;
import com.kitex.mom.mes.entity.MesMaterialListDetailEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 用料清单明细表 Mapper 接口
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Mapper
public interface MesMaterialListDetailMapper extends BaseMapper<MesMaterialListDetailEntity> {

    /**
     * 根据用料清单ID查询明细列表（包含库存信息）
     *
     * @param materialListId 用料清单ID
     * @return 明细列表
     */
    List<MesMaterialListDetailDTO> selectDetailsByMaterialListId(@Param("materialListId") Long materialListId,
            @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

    /**
     * 根据工单ID查询明细列表（包含库存信息）
     *
     * @param taskId 工单ID
     * @return 明细列表
     */
    List<MesMaterialListDetailDTO> selectDetailsByTaskId(@Param("taskId") Long taskId,
            @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

    /**
     * 批量更新物料领料数量
     *
     * @param details 明细列表
     * @return 更新数量
     */
    int batchUpdatePickedQuantity(@Param("details") List<MesMaterialListDetailEntity> details);

    /**
     * 批量更新物料退料数量
     *
     * @param details 明细列表
     * @return 更新数量
     */
    int batchUpdateReturnedQuantity(@Param("details") List<MesMaterialListDetailEntity> details);

    /**
     * 根据物料编码查询相关的用料清单明细
     *
     * @param materialCode 物料编码
     * @param factoryCode  工厂编码
     * @return 明细列表
     */
    List<MesMaterialListDetailDTO> selectDetailsByMaterialCode(@Param("materialCode") String materialCode, @Param("factoryCode") String factoryCode);
}