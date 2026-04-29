package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WmsInBillDetailMapper extends MomBaseMapper<WmsInBillDetailEntity> {

    Page<WmsInBillDetailEntity> fetchDetails(Page page, @Param("params") WmsInBillDetailQueryDTO params, @Param("ids") Long[] ids);

    List<WmsInBillDetailEntity> fetchDetails(@Param("params") WmsInBillDetailQueryDTO params, @Param("ids") Long[] ids);

}