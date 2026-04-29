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

@Mapper
public interface MesWarehouseLiveStockMapper extends MomBaseMapper<MesWarehouseLiveStockEntity> {

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