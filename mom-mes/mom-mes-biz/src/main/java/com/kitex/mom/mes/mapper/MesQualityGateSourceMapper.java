package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 质量门只读取实际组织中的工序，不通过默认schema猜测归属。
 */
@Mapper
public interface MesQualityGateSourceMapper {
    @Select(
            "SELECT"
                    + " id,tenant_id,factory_code,code,task_code,mat_code,mat_name,mat_spec,route_code,route_name,workshop_code,is_inspection,notice_num"
                    + " FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND code=#{code} AND del_flag='0' LIMIT 2")
    List<MesProduceTaskEntity> parents(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @Select(
            "SELECT id,tenant_id,factory_code,code,batch_rule_code FROM mom_mes.mes_task WHERE"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code} AND"
                    + " del_flag='0' LIMIT 2")
    List<MesTaskEntity> roots(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);
}
