package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmStationSettingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工序-工位绑定 Mapper 接口
 *
 * @author claude
 * @date 2025-07-30
 */
@Mapper
public interface MdmStationSettingMapper extends BaseMapper<MdmStationSettingEntity> {

    /**
     * 根据线体ID获取工序-工位绑定列表
     *
     * @param lineId 线体ID
     * @return 绑定列表
     */
    List<MdmStationSettingEntity> selectByLineId(@Param("lineId") Long lineId);

    /**
     * 根据工序编码获取对应的工位列表
     *
     * @param procedureCode 工序编码
     * @return 工位列表
     */
    List<MdmStationSettingEntity> selectStationsByProcedure(@Param("procedureCode") String procedureCode);

    /**
     * 根据工位ID获取对应的工序列表
     *
     * @param stationId 工位ID
     * @return 工序列表
     */
    List<MdmStationSettingEntity> selectProceduresByStation(@Param("stationId") Long stationId);

    /**
     * 根据车间ID获取所有工序-工位绑定
     *
     * @param workshopId 车间ID
     * @return 绑定列表
     */
    List<MdmStationSettingEntity> selectByWorkshopId(@Param("workshopId") Long workshopId);
}