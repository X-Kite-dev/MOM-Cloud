package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalPositionOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 永久采集与异人决策事件，只追加不可改删；永久查询不以逻辑删除隐藏原历史。
 */
@Mapper
public interface MesPhysicalPositionOperationMapper extends BaseMapper<MesPhysicalPositionOperationEntity> {
    /**
     * 原主账全部子事实，服务层核实际工厂和完整集合，不分页截断。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_position_operation WHERE tenant_id=#{tenantId} AND survey_id=#{surveyId} ORDER BY id")
    List<MesPhysicalPositionOperationEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("surveyId") Long surveyId);

    /**
     * 操作原号租户内永久唯一，跨厂结果先由领域服务拒绝，不能自动再发新号。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_position_operation WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesPhysicalPositionOperationEntity permanent(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
}
