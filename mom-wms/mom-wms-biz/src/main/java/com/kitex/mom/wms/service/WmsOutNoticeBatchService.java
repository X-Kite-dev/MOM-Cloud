package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsOutNoticeBatchEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;

import java.util.ArrayList;

public interface WmsOutNoticeBatchService extends IService<WmsOutNoticeBatchEntity> {

    WmsOutNoticeBatchEntity getNoticeBatchById(Long id);

    Boolean saveNoticeBatch(WmsOutNoticeBatchEntity wmsOutNoticeBatch);

    Boolean updateNoticeBatchById(WmsOutNoticeBatchEntity wmsOutNoticeBatch);

    Boolean removeNoticeBatchById(ArrayList<Long> toList);


    Page<WmsOutNoticeEntity> selectNoticePage(SearchPage page, WmsInNoticeQueryDTO queryDTO);
}