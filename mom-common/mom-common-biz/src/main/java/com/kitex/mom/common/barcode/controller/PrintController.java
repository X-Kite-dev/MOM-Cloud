package com.kitex.mom.common.barcode.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.qrcode.QrCodeUtil;
import cn.hutool.extra.qrcode.QrConfig;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintObj;
import com.kitex.mom.common.barcode.service.PrintDeviceService;
import com.kitex.mom.common.barcode.service.PrintTemplateService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 打印
 */
@RestController
@AllArgsConstructor
@RequestMapping("/print")
@Tag(description = "print", name = "打印")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PrintController {

    private final PrintDeviceService printDeviceService;

    private final PrintTemplateService printTemplateService;

    /**
     * 打印
     *
     * @return
     */
    @PostMapping("print")
    public R print(@RequestBody PrintObj obj) {
        printDeviceService.print(obj);

        return R.ok();
    }

    /**
     * 预览
     *
     * @return
     */
    @PostMapping("preview")
    public R preview(@RequestBody PreviewObj obj) {
        return R.ok(printTemplateService.billPreview(obj));
    }

    @GetMapping(value = "/createQr", produces = "image/png")
    @Inner(false)
    public void createQr(String data, HttpServletResponse response) {
        if (StrUtil.isEmpty(data)) {
            return;
        }
        try {
            data = URLDecoder.decode(data.trim(), "utf8");
            QrConfig qrConfig = new QrConfig(300, 300);
            qrConfig.setCharset(StandardCharsets.UTF_8);
            qrConfig.setMargin(0);
            qrConfig.setQrVersion(5);
            qrConfig.setErrorCorrection(ErrorCorrectionLevel.M);
            qrConfig.setRatio(1);
            response.setContentType("image/png");
            response.setCharacterEncoding("UTF-8");
            QrCodeUtil.generate(data, qrConfig, "PNG", response.getOutputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @GetMapping("/createQrInputStream")
    @Inner(false)
    public R<byte[]> createQrInputStream(String data) {
        try {
            data = URLDecoder.decode(data.trim(), "utf8");
            QrConfig qrConfig = new QrConfig(300, 300);
            qrConfig.setCharset(StandardCharsets.UTF_8);
            qrConfig.setMargin(0);
            qrConfig.setQrVersion(5);
            qrConfig.setErrorCorrection(ErrorCorrectionLevel.M);
            qrConfig.setRatio(1);

            // 使用 ByteArrayOutputStream 将二维码图像写入内存
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            QrCodeUtil.generate(data, qrConfig, "PNG", byteArrayOutputStream);

            // 获取二维码图像的 byte[]
            return R.ok(byteArrayOutputStream.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
