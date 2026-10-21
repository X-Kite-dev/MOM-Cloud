package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesWrapOperationEntity;
import com.kitex.mom.mes.service.MesWrapOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWrapOperation")
@Tag(description = "mesWrapOperation", name = "包装拆箱重组审计")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWrapOperationController {

    private final MesWrapOperationService service;

    @Operation(summary = "操作审计分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesWrap:show')")
    public R<IPage<MesWrapOperationEntity>> page(Page<MesWrapOperationEntity> pageParam,
                                                 MesWrapOperationEntity query) {
        String sourceWrapCode = query.getSourceWrapCodes();
        query.setSourceWrapCodes(null);
        QueryWrapper<MesWrapOperationEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        if (sourceWrapCode != null && !sourceWrapCode.isBlank()) {
            wrapper.like("source_wrap_codes", sourceWrapCode.trim());
        }
        wrapper.orderByDesc("operation_time").orderByDesc("id");
        return R.ok(service.page(pageParam, wrapper));
    }
}
