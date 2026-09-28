package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesWarehousePickingBillDetailEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MesWarehousePickingBillDetailMapper extends MomBaseMapper<MesWarehousePickingBillDetailEntity> {
    @org.apache.ibatis.annotations.Select("SELECT d.* FROM mes_warehouse_picking_bill_detail d JOIN mes_warehouse_picking_bill b ON b.id=d.bill_id WHERE b.task_code=#{task} AND b.tenant_id=#{tenant} AND b.factory_code=#{factory} AND d.tenant_id=b.tenant_id AND d.factory_code=b.factory_code AND b.del_flag='0' AND d.del_flag='0' ORDER BY d.id LIMIT 5001")
    java.util.List<MesWarehousePickingBillDetailEntity> reworkBudget(@org.apache.ibatis.annotations.Param("task") String task, @org.apache.ibatis.annotations.Param("tenant") Long tenant, @org.apache.ibatis.annotations.Param("factory") String factory);


}
