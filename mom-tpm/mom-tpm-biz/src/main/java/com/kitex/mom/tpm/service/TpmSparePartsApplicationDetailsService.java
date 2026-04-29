package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.tpm.dto.ApplyBySourceCodePageDto;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationDetailsEntity;

public interface TpmSparePartsApplicationDetailsService extends IService<TpmSparePartsApplicationDetailsEntity> {

    Page<TpmSparePartsApplicationDetailsEntity> getApplyBySourceCodePage(SearchPage page, ApplyBySourceCodePageDto dto);
}