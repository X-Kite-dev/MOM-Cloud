package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.wms.service.WmsPrintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出/入库通知单打印
 *
 * @author kitex
 * @date 2025-01-24 11:19:34
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/print")
@Tag(description = "print", name = "出/入库通知单打印")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsPrintController {

    private final WmsPrintService wmsPrintService;

    /**
     * 入库通知单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Operation(summary = "入库通知单打印", description = "入库通知单打印")
    @GetMapping("/inNotice/print")
    public R<String> inNoticePrint(@ParameterObject PrintReq printReq) {
        return R.ok(wmsPrintService.inNoticePrint(printReq));
    }

    /**
     * 出库通知单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Operation(summary = "出库通知单打印", description = "出库通知单打印")
    @GetMapping("/outNotice/print")
    public R<String> outNoticePrint(@ParameterObject PrintReq printReq) {
        return R.ok(wmsPrintService.outNoticePrint(printReq));
    }

}
