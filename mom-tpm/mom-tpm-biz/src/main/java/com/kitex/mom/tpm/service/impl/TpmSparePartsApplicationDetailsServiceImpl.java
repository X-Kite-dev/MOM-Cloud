package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.tpm.dto.ApplyBySourceCodePageDto;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationDetailsEntity;
import com.kitex.mom.tpm.mapper.TpmSparePartsApplicationDetailsMapper;
import com.kitex.mom.tpm.service.TpmSparePartsApplicationDetailsService;
import org.springframework.stereotype.Service;

/**
 * 备件申请明细表（记录具体物料及出入库位置信息）
 *
 * @author kitex
 * @date 2025-03-18 10:35:14
 */
@Service
public class TpmSparePartsApplicationDetailsServiceImpl extends ServiceImpl<TpmSparePartsApplicationDetailsMapper, TpmSparePartsApplicationDetailsEntity> implements TpmSparePartsApplicationDetailsService {
    @Override
    public Page<TpmSparePartsApplicationDetailsEntity> getApplyBySourceCodePage(SearchPage page, ApplyBySourceCodePageDto dto) {
        return this.baseMapper.getApplyBySourceCodePage(page.toMpPage(), dto);
    }
}