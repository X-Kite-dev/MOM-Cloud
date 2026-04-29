package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmEmployeeTeamEntity;
import com.kitex.mom.mdm.entity.MdmTeamEntity;
import com.kitex.mom.mdm.service.MdmTeamService;
import com.kitex.mom.mdm.vo.excel.MdmTeamExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 班组
 *
 * @author kitex
 * @date 2025-02-17 08:50:00
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmTeam")
@Tag(description = "mdmTeam", name = "班组管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmTeamController {

    private final MdmTeamService mdmTeamService;

    /**
     * 分页查询
     *
     * @param page    分页对象
     * @param mdmTeam 班组
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTeam_view')")
    public R<Page<MdmTeamEntity>> getMdmTeamPage(@ParameterObject SearchPage page, @ParameterObject MdmTeamEntity mdmTeam) {
        QueryWrapper<MdmTeamEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmTeam.getName()), MdmTeamEntity::getName, mdmTeam.getName())
                .like(StrUtil.isNotBlank(mdmTeam.getCode()), MdmTeamEntity::getCode, mdmTeam.getCode())
                .eq(StrUtil.isNotBlank(mdmTeam.getType()), MdmTeamEntity::getType, mdmTeam.getType())
                .orderByDesc(MdmTeamEntity::getCreateTime);
        return R.ok(mdmTeamService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各班组类型的数量
     *
     * @return
     */
    @Operation(summary = "查询各班组类型的数量", description = "查询各班组类型的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject MdmTeamEntity mdmTeam) {
        QueryWrapper<MdmTeamEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmTeamEntity::getType)
                .like(StrUtil.isNotBlank(mdmTeam.getName()), MdmTeamEntity::getName, mdmTeam.getName())
                .like(StrUtil.isNotBlank(mdmTeam.getCode()), MdmTeamEntity::getCode, mdmTeam.getCode());
        return R.ok(mdmTeamService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmTeamEntity::getType, Collectors.counting())));
    }

    /**
     * 查询各班组列表
     *
     * @return
     */
    @Operation(summary = "查询各班组列表", description = "查询各班组列表")
    @GetMapping("/list")
    public R<List<MdmTeamEntity>> list(@ParameterObject MdmTeamEntity mdmTeam) {
        QueryWrapper<MdmTeamEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmTeam.getName()), MdmTeamEntity::getName, mdmTeam.getName())
                .like(StrUtil.isNotBlank(mdmTeam.getCode()), MdmTeamEntity::getCode, mdmTeam.getCode())
                .eq(StrUtil.isNotBlank(mdmTeam.getType()), MdmTeamEntity::getType, mdmTeam.getType())
                .orderByDesc(MdmTeamEntity::getUpdateTime);
        return R.ok(mdmTeamService.list(wrapper));
    }

    /**
     * 通过条件查询班组
     *
     * @param mdmTeam 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTeam_view')")
    public R<List<MdmTeamEntity>> getDetails(@ParameterObject MdmTeamEntity mdmTeam) {
        return R.ok(mdmTeamService.listDeep(Wrappers.query(mdmTeam)));
    }

    /**
     * 根据类型查询班组及班组下的用户
     *
     * @param type 类型
     * @return R
     */
    @Operation(summary = "根据类型查询班组及班组下的用户", description = "根据类型查询班组及班组下的用户")
    @GetMapping("/type")
    public R<List<MdmTeamEntity>> getUserByTeamType(@RequestParam("type") String type) {
        return R.ok(mdmTeamService.listDeep(new LambdaQueryWrapper<MdmTeamEntity>().eq(MdmTeamEntity::getType, type)));
    }

    /**
     * 根据班组编码查询班组下的员工
     *
     * @param code 班组编码
     * @return R
     */
    @Operation(summary = "根据班组编码查询班组下的用户", description = "根据班组编码查询班组下的用户")
    @GetMapping("/code")
    public R<List<MdmEmployeeTeamEntity>> getUserByTeamCode(@RequestParam("code") String code) {
        return R.ok(mdmTeamService.getUserByTeamCode(code));
    }

    /**
     * 新增班组
     *
     * @param mdmTeam 班组
     * @return R
     */
    @Operation(summary = "新增班组", description = "新增班组")
    @SysLog("新增班组")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmTeam_add')")
    public R<Boolean> save(@RequestBody MdmTeamEntity mdmTeam) {
        return R.ok(mdmTeamService.saveDeep(mdmTeam));
    }

    /**
     * 修改班组
     *
     * @param mdmTeam 班组
     * @return R
     */
    @Operation(summary = "修改班组", description = "修改班组")
    @SysLog("修改班组")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmTeam_edit')")
    public R<Boolean> updateById(@RequestBody MdmTeamEntity mdmTeam) {
        return R.ok(mdmTeamService.updateDeep(mdmTeam));
    }

    /**
     * 通过id删除班组
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除班组", description = "通过id删除班组")
    @SysLog("通过id删除班组")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmTeam_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmTeamService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmTeam 查询条件
     * @param ids     导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTeam_export')")
    public List<MdmTeamExcelVO> exportTeam(MdmTeamEntity mdmTeam, Long[] ids) {
        return mdmTeamService.exportTeam(mdmTeam, ids);
    }

    /**
     * 导入excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTeam_export')")
    public R importTeam(@RequestExcel(headRowNumber = 2) List<MdmTeamExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmTeamService.importTeam(excelVOList, bindingResult);
    }
}