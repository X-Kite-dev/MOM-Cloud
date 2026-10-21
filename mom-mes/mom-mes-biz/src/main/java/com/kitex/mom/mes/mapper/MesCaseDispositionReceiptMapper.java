package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesCaseDispositionReceiptMapper extends BaseMapper<MesCaseDispositionReceiptEntity> {
    /**
     * 原隔离根库存耗尽后仍须可读；显式租户/工厂，不依赖逻辑删除插件。
     */
    @Select("SELECT * FROM mes_warehouse_live_stock WHERE id=#{id} AND tenant_id=#{tenant} AND factory_code=#{factory}")
    MesWarehouseLiveStockEntity rootStock(@Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);
}
