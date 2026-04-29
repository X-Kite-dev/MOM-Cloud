package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.service.QmsStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 质量统计
 *
 * @author kitex
 * @since 2024-04-02
 */
@Tag(description = "badInfo", name = "不合格品管理")
@RestController
@RequestMapping("/statistics")
@RequiredArgsConstructor
public class QmsStatisticsController {

    private final QmsStatisticsService service;

    @Operation(summary = "查询统计栏数量", description = "查询统计栏数量")
    @GetMapping("/lineNum")
    public R<Map<String, Object>> lineNum() {
        return R.ok(service.getLineNum());
    }

    @Operation(summary = "查询合格率", description = "查询合格率")
    @GetMapping("/cardNum")
    public R<Map<String, Object>> cardNum() {
        return R.ok(service.getCardNum());
    }

}
