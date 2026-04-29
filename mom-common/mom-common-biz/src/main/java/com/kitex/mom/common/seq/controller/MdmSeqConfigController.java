package com.kitex.mom.common.seq.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.seq.entity.MdmSeqConfigEntity;
import com.kitex.mom.common.seq.handler.MdmSequenceHandler;
import com.kitex.mom.common.seq.service.MdmSeqConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmSeq")
@Tag(description = "seq", name = "序列号配置")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmSeqConfigController {

    private final MdmSeqConfigService seqConfigService;

    private final MdmSequenceHandler sequenceHandler;

    /**
     * 分页查询
     *
     * @param page      分页对象
     * @param seqConfig 物料表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<Page<MdmSeqConfigEntity>> Page(Page page, MdmSeqConfigEntity seqConfig) {
        LambdaQueryWrapper<MdmSeqConfigEntity> mdmSeqConfigLambdaQueryWrapper = Wrappers.lambdaQuery(seqConfig);
        mdmSeqConfigLambdaQueryWrapper.orderByDesc(MdmSeqConfigEntity::getCreateTime);
        return R.ok(seqConfigService.page(page, mdmSeqConfigLambdaQueryWrapper));
    }

    /**
     * 通过id查询物料表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R<MdmSeqConfigEntity> getById(@PathVariable("id") Long id) {
        return R.ok(seqConfigService.getById(id));
    }

    /**
     * 根据业务号获取详情
     *
     * @param bizName 业务号
     * @return R
     */
    @Operation(summary = "根据业务号获取详情", description = "根据业务号获取详情")
    @GetMapping("/details")
    public R<MdmSeqConfigEntity> details(String bizName) {
        return R.ok(seqConfigService.getOne(Wrappers.<MdmSeqConfigEntity>lambdaQuery().eq(MdmSeqConfigEntity::getBizName, bizName), false));
    }

    /**
     * 新增物料表
     *
     * @param seqConfig 物料表
     * @return R
     */
    @SysLog("新增序列号配置")
    @PostMapping
    public R<Boolean> save(@RequestBody @Valid MdmSeqConfigEntity seqConfig) {
        return R.ok(seqConfigService.saveSeqConfig(seqConfig));
    }

    /**
     * 修改物料表
     *
     * @param seqConfig 物料表
     * @return R
     */
    @SysLog("修改序列号配置")
    @PutMapping
    public R<Boolean> updateById(@RequestBody @Valid MdmSeqConfigEntity seqConfig) {
        if (ObjUtil.isNull(seqConfig.getId())) {
            return R.failed("id不能为空");
        }
        return R.ok(seqConfigService.saveSeqConfig(seqConfig));
    }

    /**
     * 通过id删除物料表
     *
     * @param ids id
     * @return R
     */
    @SysLog("通过id删除序列")
    @DeleteMapping()
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(seqConfigService.removeBatchByIds(CollUtil.toList(ids)));
    }

    @Inner(value = false)
    @GetMapping("/create")
    public R<Object> create(String type) {
        String s = sequenceHandler.createSeq(type).nextNo();
        return R.ok((Object) s);
    }

}
