package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/**
 * 移动端出库单
 *
 * @author kitex
 * @date 2024/12/30 15:31
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/outBill")
@Tag(description = "AppOutBillController", name = "移动端出库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class AppOutBillController {

    private final DefaultWmsOutNoticeService wmsOutNoticeService;
    private final DefaultWmsOutBillService wmsOutBillService;

    /**
     * 根据通知单id查询实时库存
     *
     * @return R 对象
     */
    @Operation(summary = "根据通知单id查询实时库存", description = "根据通知单id查询实时库存")
    @GetMapping("/getLiveStockByNoticeId")
    public R getLiveStockByNoticeId(Long id) {
        WmsOutBillEntity wmsOutNoticeEntity = wmsOutNoticeService.getLiveStockByNoticeIdApp(id);
        return R.ok(wmsOutNoticeEntity);
    }

    /**
     * 出库 提交某一条物料数量，更新原出库单或生成出库单
     *
     * @return R 对象
     */
    @Operation(summary = "出库", description = "出库")
    @PostMapping("/setOutBillNum")
    public R<String> setInBillNum(@RequestBody @Valid WmsOutBillDetailAppDTO params) {
        String billNo = wmsOutBillService.setOutBillNum(params);
        return R.ok(billNo, null);
    }
}
