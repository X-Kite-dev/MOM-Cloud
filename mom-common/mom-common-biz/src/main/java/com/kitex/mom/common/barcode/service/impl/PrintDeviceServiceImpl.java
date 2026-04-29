package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.barcode.dto.req.PrintObj;
import com.kitex.mom.common.barcode.entity.PrintDeviceEntity;
import com.kitex.mom.common.barcode.entity.PrintLogEntity;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.mapper.PrintDeviceMapper;
import com.kitex.mom.common.barcode.mapper.PrintLogMapper;
import com.kitex.mom.common.barcode.service.PrintDeviceService;
import com.kitex.mom.common.barcode.service.PrintTemplateService;
import com.kitex.mom.common.barcode.utils.PrintUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 打印设备
 *
 * @author kitex
 * @date 2023-08-11 10:26:30
 */
@Service
@AllArgsConstructor
@Slf4j
public class PrintDeviceServiceImpl extends ServiceImpl<PrintDeviceMapper, PrintDeviceEntity> implements PrintDeviceService {

    private final PrintTemplateService printTemplateService;

    private final PrintLogMapper printLogMapper;

    /**
     * 打印 1. 查询对应的设备是否存在 2. 查询对应的终端是否在线 3. 查询对应的模板是否正确 4. 记录打印日志 5. 调用终端进行打印 6.
     * 打印成功修改对应的打印日志
     *
     * @param obj
     */
    @Override
    public void print(PrintObj obj) {
        PrintDeviceEntity device = getOne(new LambdaQueryWrapper<PrintDeviceEntity>().eq(PrintDeviceEntity::getCode, obj.getDeviceCode()), false);
        if (Objects.isNull(device)) {
            throw new CheckedException(String.format("选择的设备%s为空", obj.getDeviceCode()));
        }

        PrintTemplateEntity template = printTemplateService
                .getOne(Wrappers.<PrintTemplateEntity>lambdaQuery().eq(PrintTemplateEntity::getCode, obj.getTemplateCode()));

        // 生成打印日志
        PrintLogEntity printLog = new PrintLogEntity();
        printLog.setDeviceName(device.getName());
        printLog.setDeviceCode(device.getCode());
        printLog.setAddress(device.getIp());
        printLog.setJsonData(obj.getJsonData());
        printLog.setTemplateContent(template.getContent());
        printLog.setTemplateCode(template.getCode());
        printLog.setResult(Boolean.FALSE);
        printLog.setFactoryCode(template.getFactoryCode());
        printLogMapper.insert(printLog);

        Boolean print = PrintUtils.print(printLog, device);
        if (print) {
            printLog.setResult(Boolean.TRUE);
            printLogMapper.updateById(printLog);
        }

    }

}