package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.service.inBill.WmsInBillFactory;
import com.kitex.mom.wms.service.inBill.WmsInBillHandler;
import com.kitex.mom.wms.service.inNotice.DefaultWmsInNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/**
 * 移动端入库单
 *
 * @author kitex
 * @date 2024/12/30 15:31
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/inBill")
@Tag(description = "AppInBillController", name = "移动端入库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class AppInBillController {

    private final DefaultWmsInNoticeService inNoticeService;

    /**
     * 查询入库通知单详情
     *
     * @return R  对象
     */
    @Operation(summary = "查询入库通知单详情", description = "查询入库通知单详情")
    @GetMapping("/detailsById")
    public R getDetails(@RequestParam Long id) {
        return R.ok(inNoticeService.selectByIdApp(id));
    }

    /**
     * 入库
     * 提交某一条物料数量，更新入库单已收数量或生成入库单
     *
     * @return R  对象
     */
    @Operation(summary = "入库", description = "入库")
    @PostMapping("/setInBillNum")
    public R setInBillNum(@RequestBody @Valid WmsInBillDetailAppDTO params) {
        WmsInNoticeEntity inNotice = inNoticeService.selectById(params.getNoticeId());
        WmsInBillHandler handler = WmsInBillFactory.getHandler(inNotice.getBillType());
        String billNo = handler.setInBillNum(params);
        return R.ok(billNo);
    }
}
