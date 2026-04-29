package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WmsOutBillDetailMapper extends MomBaseMapper<WmsOutBillDetailEntity> {


    Page<WmsOutBillDetailEntity> fetchDetails(Page page, @Param("params") WmsInBillDetailQueryDTO params, @Param("ids") Long[] ids);

    List<WmsOutBillDetailEntity> fetchDetails(@Param("params") WmsInBillDetailQueryDTO params, @Param("ids") Long[] ids);
}