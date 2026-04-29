package com.kitex.mom.common.barcode.utils;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.barcode.entity.PrintDeviceEntity;
import com.kitex.mom.common.barcode.entity.PrintLogEntity;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PrintUtils {

    public static Boolean print(PrintLogEntity printLog, PrintDeviceEntity device) {
        String s = VelocityKit.renderStr(printLog.getTemplateContent(), printLog.getJsonData());
        log.info("打印内容 => {}: 打印模板 => {}; 打印数据 => {}", s, printLog.getTemplateContent(), printLog.getJsonData());

        // TODO 调用终端接口进行打印
        String url = String.format("%s:%s/print", device.getIp(), device.getPort());
        JSONObject object = new JSONObject();
        object.set("url", String.format("/log/previewByUrl?id=%s", printLog.getId()));
        object.set("printerName", printLog.getDeviceName());
        // 默认一份
        object.set("num", "1");
        try {
            String body = HttpRequest.post(url).body(object.toString()).execute().body();
            log.info("打印机调用成功 -》 {}", body);
            return Boolean.TRUE;

        } catch (Exception e) {
            log.error(e.getMessage());
            log.error("打印机调用失败");
            throw new CheckedException(String.format("打印机调用失败【%s】", e.getMessage()));
        }
    }

}
