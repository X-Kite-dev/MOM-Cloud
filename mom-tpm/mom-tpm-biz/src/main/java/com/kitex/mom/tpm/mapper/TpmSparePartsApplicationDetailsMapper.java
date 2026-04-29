package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.dto.ApplyBySourceCodePageDto;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationDetailsEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TpmSparePartsApplicationDetailsMapper extends MomBaseMapper<TpmSparePartsApplicationDetailsEntity> {


    Page<TpmSparePartsApplicationDetailsEntity> getApplyBySourceCodePage(Page toMpPage, @Param("query") ApplyBySourceCodePageDto dto);
}