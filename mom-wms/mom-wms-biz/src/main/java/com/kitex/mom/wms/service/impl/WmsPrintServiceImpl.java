package com.kitex.mom.wms.service.impl;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.service.WmsPrintService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 出/入库通知单打印service
 *
 * @author kitex
 * @date 2025-01-24 11:30:21
 */
@Service
@RequiredArgsConstructor
public class WmsPrintServiceImpl implements WmsPrintService {

    private final RemotePrintService remotePrintService;

    private final WmsInNoticeMapper wmsInNoticeMapper;

    private final WmsInNoticeDetailMapper wmsInNoticeDetailMapper;

    private final WmsOutNoticeMapper wmsOutNoticeMapper;

    private final WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;

    /**
     * 入库通知单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Override
    public String inNoticePrint(PrintReq printReq) {
        List<WmsInNoticeEntity> wmsInNoticeEntities = wmsInNoticeMapper.selectByIds(Arrays.asList(printReq.getIds()));
        for (WmsInNoticeEntity wmsInNoticeEntity : wmsInNoticeEntities) {
            List<WmsInNoticeDetailEntity> wmsInNoticeDetailEntities = wmsInNoticeDetailMapper
                    .selectList(Wrappers.<WmsInNoticeDetailEntity>lambdaQuery().eq(WmsInNoticeDetailEntity::getNoticeId, wmsInNoticeEntity.getId()));
            wmsInNoticeEntity.setDetailList(wmsInNoticeDetailEntities);
        }
        return toJsonHtmlStr(printReq.getTemplateCode(), wmsInNoticeEntities);
    }

    /**
     * 出库通知单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Override
    public String outNoticePrint(PrintReq printReq) {
        List<WmsOutNoticeEntity> wmsOutNoticeEntities = wmsOutNoticeMapper.selectByIds(Arrays.asList(printReq.getIds()));
        for (WmsOutNoticeEntity notice : wmsOutNoticeEntities) {
            List<WmsOutNoticeDetailEntity> details = wmsOutNoticeDetailMapper
                    .selectList(Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery().eq(WmsOutNoticeDetailEntity::getNoticeId, notice.getId()));
            notice.setDetailList(details);
        }
        return toJsonHtmlStr(printReq.getTemplateCode(), wmsOutNoticeEntities);
    }

    /**
     * 获取html
     *
     * @param code 模板编码
     * @param data 数据
     * @return R html
     */
    private String toJsonHtmlStr(String code, Object data) {
        PreviewObj obj = new PreviewObj();
        obj.setTemplateCode(code);
        JSONConfig jsonConfig = new JSONConfig();
        jsonConfig.setDateFormat("yyyy-MM-dd HH:mm:ss");
        String jsonData = JSONUtil.toJsonStr(data, jsonConfig);
        obj.setJsonData(jsonData);
        return remotePrintService.preview(obj).getData();
    }
}
