package com.kitex.mom.mdm.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 工程发布的限定跨域来源；函数以显式组织参数返回完整模具绑定，不读取任意表。 */
@Mapper
public interface MdmRouteEngineeringSourceMapper {

    /** 返回该真实路线全部模具绑定原文，来源函数不得分页或截断。 */
    @Select("SELECT mom_mdm.mom_route_engineering_molds_v1(#{tenant}, #{factory}, #{route})::text")
    String molds(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("route") String route);
}
