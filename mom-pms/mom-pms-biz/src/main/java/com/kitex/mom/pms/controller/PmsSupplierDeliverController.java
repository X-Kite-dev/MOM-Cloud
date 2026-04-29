package com.kitex.mom.pms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.dto.PmsSupplierDeliverDTO;
import com.kitex.mom.pms.service.PmsSupplierDeliverService;
import com.kitex.mom.pms.vo.PmsSupplierDeliverVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 供货管理
 *
 * @author kitex
 * @date 2025-03-20 09:29:57
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsSupplierDeliver")
@Tag(description = "pmsSupplierDeliver", name = "供货管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsSupplierDeliverController {

    private final PmsSupplierDeliverService pmsSupplierDeliverService;

    /**
     * 通过id查询供货信息
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询供货信息", description = "通过id查询供货信息")
    @GetMapping("/{id}")
    public R<PmsSupplierDeliverVO> getById(@PathVariable("id") Long id) {
        return R.ok(pmsSupplierDeliverService.getSupplierDeliverById(id));
    }

    /**
     * 根据供应商编码查询供货物料编码
     *
     * @param supplierCode 供应商编码
     * @return R
     */
    @Operation(summary = "根据供应商编码查询供货物料编码", description = "根据供应商编码查询供货物料编码")
    @GetMapping("/matCodes")
    public R<List<String>> getMatCodes(@RequestParam("supplierCode") String supplierCode) {
        return R.ok(pmsSupplierDeliverService.getMatCodes(supplierCode));
    }

    /**
     * 修改供应商-供货
     *
     * @param pmsSupplierDeliverDTO 供应商-供货表
     * @return R
     */
    @Operation(summary = "修改供应商-供货", description = "修改供应商-供货")
    @SysLog("修改供应商-供货")
    @PutMapping
    public R<Boolean> updateById(@RequestBody PmsSupplierDeliverDTO pmsSupplierDeliverDTO) {
        return R.ok(pmsSupplierDeliverService.updateSupplierDeliverById(pmsSupplierDeliverDTO));
    }
}