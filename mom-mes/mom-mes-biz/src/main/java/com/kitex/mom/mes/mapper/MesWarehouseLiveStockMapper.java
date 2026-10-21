package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.dto.MesLiveStockQueryDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesWarehouseLiveStockMapper extends MomBaseMapper<MesWarehouseLiveStockEntity> {

    /**
     * 核本次实际扣量；耗尽行已逻辑归档，不能用过滤del_flag的selectById漏掉零余额。
     * 主键、实际租户及工厂全部限定，不允许省略组织或跨厂读取。
     */
    @Select("SELECT * FROM \"mom_mes\".mes_warehouse_live_stock "
            + "WHERE id=#{stockId} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode}")
    MesWarehouseLiveStockEntity selectOutProof(@Param("stockId") Long stockId,
                                              @Param("tenantId") Long tenantId,
                                              @Param("factoryCode") String factoryCode);

    IPage<MesWarehouseFeedDTO> getPageFeedBack(Page<MesWarehouseFeedDTO> pageParam, @Param("query") MesWarehouseFeedDTO query);

    /**
     * 分页查询-线边库原材料
     *
     * @param page         分页对象
     * @param mesLiveStock 实时库存
     */
    Page<MesWarehouseLiveStockVO> getWmsLiveStockPageByMatRaw(Page page, @Param("query") MesLiveStockQueryDTO mesLiveStock);

    /**
     * 分页查询-线边库类型（在制品、半成品、成品）
     *
     * @param page         分页对象
     * @param mesLiveStock 实时库存
     */
    Page<MesWarehouseLiveStockVO> getWmsLiveStockPageByMatOther(Page page, @Param("query") MesLiveStockQueryDTO mesLiveStock);
}
