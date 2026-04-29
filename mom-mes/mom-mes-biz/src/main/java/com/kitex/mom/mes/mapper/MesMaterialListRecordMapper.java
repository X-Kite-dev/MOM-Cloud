package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.dto.MesMaterialListRecordDTO;
import com.kitex.mom.mes.entity.MesMaterialListRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用料清单领退料记录表 Mapper 接口
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Mapper
public interface MesMaterialListRecordMapper extends BaseMapper<MesMaterialListRecordEntity> {

    /**
     * 根据用料清单明细ID查询领退料记录
     *
     * @param materialListDetailId 用料清单明细ID
     * @return 记录列表
     */
    List<MesMaterialListRecordDTO> selectRecordsByDetailId(@Param("materialListDetailId") Long materialListDetailId);

    /**
     * 根据工单ID查询领退料记录
     *
     * @param taskId 工单ID
     * @return 记录列表
     */
    List<MesMaterialListRecordDTO> selectRecordsByTaskId(@Param("taskId") Long taskId);

    /**
     * 根据物料编码查询领退料记录
     *
     * @param materialCode 物料编码
     * @param factoryCode  工厂编码
     * @return 记录列表
     */
    List<MesMaterialListRecordDTO> selectRecordsByMaterialCode(@Param("materialCode") String materialCode, @Param("factoryCode") String factoryCode);

    /**
     * 统计工单的领退料汇总信息
     *
     * @param taskId 工单ID
     * @return 汇总信息
     */
    List<Map<String, Object>> selectPickReturnSummaryByTaskId(@Param("taskId") Long taskId);
}