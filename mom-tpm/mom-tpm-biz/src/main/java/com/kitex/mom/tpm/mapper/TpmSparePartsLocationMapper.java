package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmSparePartsLocationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TpmSparePartsLocationMapper extends MomBaseMapper<TpmSparePartsLocationEntity> {

    Page<TpmSparePartsLocationEntity> pageList(Page toMpPage, @Param("query") TpmSparePartsLocationEntity tpmSparePartsLocation);
}