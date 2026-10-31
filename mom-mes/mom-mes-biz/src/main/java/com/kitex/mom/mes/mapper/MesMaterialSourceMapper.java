package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MesMaterialSourceMapper {
    @Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    MesTaskArrangeEntity arrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code} AND del_flag='0' LIMIT 2")
    List<MesTaskEntity> tasks(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

    @Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND code=#{code} AND task_code=#{task} AND del_flag='0' LIMIT 2")
    List<MesProduceTaskEntity> processes(@Param("tenant") Long tenant, @Param("factory") String factory,
                                         @Param("code") String code, @Param("task") String task);

    @Select("SELECT * FROM mom_mes.mes_warehouse_feed WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND id=#{id} AND task_code=#{task} AND arrange_code=#{arrange} AND produce_task_code=#{process} AND del_flag='0'")
    MesWarehouseFeedEntity feed(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
                                @Param("task") String task, @Param("arrange") String arrange, @Param("process") String process);

    @Select("SELECT * FROM mom_mes.mes_warehouse_live_stock WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    MesWarehouseLiveStockEntity stock(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);
}
