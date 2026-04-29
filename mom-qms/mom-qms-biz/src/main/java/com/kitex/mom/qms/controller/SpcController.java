package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.QmsHistorySpcDTO;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import com.kitex.mom.qms.service.SpcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * spc控制图
 *
 * @author kitex
 * @since 2025-04-28 09:18:17
 */
@RestController
@RequestMapping("/spc")
@RequiredArgsConstructor
@Tag(description = "spc", name = "spc控制图")
public class SpcController {

    private final SpcService spcService;

    /**
     * 根据产品编码查询产品质检项（只获取范围质检项）
     *
     * @param matCode 产品编码
     */
    @Operation(summary = "根据产品编码查询产品质检项（只获取范围质检项）", description = "根据产品编码查询产品质检项（只获取范围质检项）")
    @GetMapping("/qualityItem")
    public R<List<QmsQualityStandardItemEntity>> qualityItem(@RequestParam("matCode") String matCode) {
        return R.ok(spcService.qualityItem(matCode));
    }

    /**
     * 根据质检项查询质检记录
     */
    @Operation(summary = "根据质检项查询质检记录", description = "根据质检项查询质检记录")
    @GetMapping("/qualityHistory/{id}")
    public R<Map<String, Object>> qualityHistory(@PathVariable("id") Long id) {
        return R.ok(spcService.qualityHistory(id));
    }

    /**
     * x-bar/r控制图
     */
    @Operation(summary = "x-bar/r控制图", description = "x-bar/r控制图")
    @GetMapping("/chart")
    public R<Map<String, Object>> chart(QmsHistorySpcDTO qmsHistorySpcDTO) {
        return R.ok(spcService.chart(qmsHistorySpcDTO));
    }

    /**
     * 数据分布直方图
     */
    @Operation(summary = "数据分布直方图", description = "数据分布直方图")
    @GetMapping("/hist")
    public R<Map<String, Object>> hist(@Valid @ParameterObject QmsHistorySpcDTO qmsHistorySpcDTO) {
        return R.ok(spcService.hist(qmsHistorySpcDTO));
    }

    /**
     * 统计信息
     */
    @Operation(summary = "统计信息", description = "统计信息")
    @GetMapping("/stat")
    public R<Map<String, Object>> stat(@Valid @ParameterObject QmsHistorySpcDTO qmsHistorySpcDTO) {
        return R.ok(spcService.stat(qmsHistorySpcDTO));
    }
}
