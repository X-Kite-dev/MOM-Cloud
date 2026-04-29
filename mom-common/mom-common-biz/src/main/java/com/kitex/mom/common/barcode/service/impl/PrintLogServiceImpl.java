package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.barcode.entity.PrintDeviceEntity;
import com.kitex.mom.common.barcode.entity.PrintLogEntity;
import com.kitex.mom.common.barcode.mapper.PrintLogMapper;
import com.kitex.mom.common.barcode.service.PrintDeviceService;
import com.kitex.mom.common.barcode.service.PrintLogService;
import com.kitex.mom.common.barcode.template.HtmlTemplate;
import com.kitex.mom.common.barcode.utils.PrintUtils;
import com.kitex.mom.common.barcode.utils.VelocityKit;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 打印记录
 *
 * @author kitex
 * @date 2023-08-17 16:29:36
 */
@Service
@Slf4j
@AllArgsConstructor
public class PrintLogServiceImpl extends ServiceImpl<PrintLogMapper, PrintLogEntity> implements PrintLogService {

    private final PrintDeviceService printDeviceService;

    /**
     * 预览
     *
     * @param id
     * @return
     */
    @Override
    public String preview(Long id) {
        PrintLogEntity printLog = getById(id);
        String html = HtmlTemplate.getHtml();
        String jsonStr = VelocityKit.templateJsonStr(printLog.getTemplateContent(), printLog.getJsonData());
        return VelocityKit.renderStr(html, jsonStr);
    }

    /**
     * 重新打印
     *
     * @param id
     * @return
     */
    @Override
    public void reprint(Long id) {
        PrintLogEntity printLog = getById(id);
        String deviceCode = printLog.getDeviceCode();

        PrintDeviceEntity device = printDeviceService.getOne(Wrappers.<PrintDeviceEntity>lambdaQuery().eq(PrintDeviceEntity::getCode, deviceCode));

        PrintUtils.print(printLog, device);
    }

}