package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmSchedulingFamilyAssignDTO;
import com.kitex.mom.mdm.entity.MdmSchedulingFamilyEntity;
import com.kitex.mom.mdm.service.MdmSchedulingFamilyService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmSchedulingFamily")
public class MdmSchedulingFamilyController {
    private final MdmSchedulingFamilyService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_schedulingFamily_view')")
    public R<Page<MdmSchedulingFamilyEntity>> page(SearchPage page,
                                                   @ParameterObject MdmSchedulingFamilyEntity query) {
        return R.ok(service.page(page.toMpPage(), new LambdaQueryWrapper<MdmSchedulingFamilyEntity>()
                .like(StrUtil.isNotBlank(query.getFamilyCode()), MdmSchedulingFamilyEntity::getFamilyCode, query.getFamilyCode())
                .like(StrUtil.isNotBlank(query.getFamilyName()), MdmSchedulingFamilyEntity::getFamilyName, query.getFamilyName())
                .eq(query.getEnabled() != null, MdmSchedulingFamilyEntity::getEnabled, query.getEnabled())
                .orderByAsc(MdmSchedulingFamilyEntity::getSortOrder)
                .orderByDesc(MdmSchedulingFamilyEntity::getCreateTime)));
    }

    @GetMapping("/list")
    public R<List<MdmSchedulingFamilyEntity>> list() {
        return R.ok(service.list(new LambdaQueryWrapper<MdmSchedulingFamilyEntity>()
                .eq(MdmSchedulingFamilyEntity::getEnabled, true)
                .orderByAsc(MdmSchedulingFamilyEntity::getSortOrder)));
    }

    @PostMapping
    @SysLog("新增APS排产产品族")
    @PreAuthorize("@pms.hasPermission('mdm_schedulingFamily_edit')")
    public R<Boolean> save(@RequestBody MdmSchedulingFamilyEntity entity) {
        return R.ok(service.saveValidated(entity));
    }

    @PutMapping
    @SysLog("修改APS排产产品族")
    @PreAuthorize("@pms.hasPermission('mdm_schedulingFamily_edit')")
    public R<Boolean> update(@RequestBody MdmSchedulingFamilyEntity entity) {
        return R.ok(service.updateValidated(entity));
    }

    @PutMapping("/assign-products")
    @SysLog("批量归属APS排产产品族")
    @PreAuthorize("@pms.hasPermission('mdm_schedulingFamily_assign')")
    public R<Boolean> assignProducts(@RequestBody MdmSchedulingFamilyAssignDTO dto) {
        return R.ok(service.assignProducts(dto));
    }
}
