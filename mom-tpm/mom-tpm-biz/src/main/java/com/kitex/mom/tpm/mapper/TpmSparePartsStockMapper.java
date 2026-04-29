package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmSparePartsStockEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TpmSparePartsStockMapper extends MomBaseMapper<TpmSparePartsStockEntity> {


    Page<TpmSparePartsStockEntity> pageList(Page toMpPage, @Param("query") TpmSparePartsStockEntity tpmSparePartsStock);

    List<TpmSparePartsStockEntity> list(@Param("ids") Long[] ids);

    TpmSparePartsStockEntity getStockById(@Param("id") Long id);
}