package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesScrapMaterialEntity;
import com.kitex.mom.mes.mapper.MesScrapMaterialMapper;
import com.kitex.mom.mes.service.MesScrapMaterialService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 报废品管理服务实现
 *
 * @author system
 * @since 2025-01-27
 */
@Service
@RequiredArgsConstructor
public class MesScrapMaterialServiceImpl extends ServiceImpl<MesScrapMaterialMapper, MesScrapMaterialEntity> implements MesScrapMaterialService {

    @Override
    public IPage<MesScrapMaterialEntity> queryPage(Page<MesScrapMaterialEntity> pageParam, MesScrapMaterialEntity query) {
        QueryWrapper<MesScrapMaterialEntity> queryWrapper = new QueryWrapper<>();

        // 添加额外的查询条件
        queryWrapper.lambda()
                .like(StrUtil.isNotBlank(query.getMatCode()), MesScrapMaterialEntity::getMatCode, query.getMatCode())
                .like(StrUtil.isNotBlank(query.getMatName()), MesScrapMaterialEntity::getMatName, query.getMatName())
                .like(StrUtil.isNotBlank(query.getOperator()), MesScrapMaterialEntity::getOperator, query.getOperator())
                .like(StrUtil.isNotBlank(query.getWorkOrder()), MesScrapMaterialEntity::getWorkOrder, query.getWorkOrder())
                .eq(StrUtil.isNotBlank(query.getShift()), MesScrapMaterialEntity::getShift, query.getShift())
                .eq(StrUtil.isNotBlank(query.getMaterialType()), MesScrapMaterialEntity::getMaterialType, query.getMaterialType())
                .eq(StrUtil.isNotBlank(query.getStatus()), MesScrapMaterialEntity::getStatus, query.getStatus())
                .orderByDesc(MesScrapMaterialEntity::getCreateTime);

        return this.page(pageParam, queryWrapper);
    }

    @Override
    public void export(MesScrapMaterialEntity query, HttpServletResponse response) {
//        try {
//
//            // 添加额外的查询条件
//            queryWrapper.lambda()
//                    .like(StrUtil.isNotBlank(query.getMatCode()), MesScrapMaterialEntity::getMatCode, query.getMatCode())
//                    .like(StrUtil.isNotBlank(query.getMatName()), MesScrapMaterialEntity::getMatName, query.getMatName())
//                    .like(StrUtil.isNotBlank(query.getScrapCode()), MesScrapMaterialEntity::getScrapCode, query.getScrapCode())
//                    .like(StrUtil.isNotBlank(query.getScrapName()), MesScrapMaterialEntity::getScrapName, query.getScrapName())
//                    .like(StrUtil.isNotBlank(query.getOperator()), MesScrapMaterialEntity::getOperator, query.getOperator())
//                    .like(StrUtil.isNotBlank(query.getWorkOrder()), MesScrapMaterialEntity::getWorkOrder, query.getWorkOrder())
//                    .eq(StrUtil.isNotBlank(query.getShift()), MesScrapMaterialEntity::getShift, query.getShift())
//                    .eq(StrUtil.isNotBlank(query.getMaterialType()), MesScrapMaterialEntity::getMaterialType, query.getMaterialType())
//                    .eq(StrUtil.isNotBlank(query.getStatus()), MesScrapMaterialEntity::getStatus, query.getStatus())
//                    .orderByDesc(MesScrapMaterialEntity::getCreateTime);
//
//
//            List<MesScrapMaterial> list = this.list(queryWrapper);
//
//            ExcelUtil.exportExcel(list, MesScrapMaterial.class, "报废品管理", response);
//        } catch (Exception e) {
//            throw new CheckedException("导出失败：" + e.getMessage());
//        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importData(MultipartFile file) {
//        try {
//            List<MesScrapMaterial> list = ExcelUtil.importExcel(file.getInputStream(), MesScrapMaterial.class);
//
//            if (CollUtil.isEmpty(list)) {
//                throw new CheckedException("导入数据为空");
//            }
//
//            // 数据校验和处理
//            for (MesScrapMaterial item : list) {
//                // 设置默认值
//                if (item.getScrapTime() == null) {
//                    item.setScrapTime(LocalDateTime.now());
//                }
//                if (StrUtil.isBlank(item.getStatus())) {
//                    item.setStatus("0"); // 默认为待审核
//                }
//                if (item.getOrgId() == null) {
//                    item.setOrgId(SecurityUtils.getUser().getOrgId());
//                }
//            }
//
//            this.saveBatch(list);
//
//            return "导入成功，共导入" + list.size() + "条数据";
//        } catch (Exception e) {
//            throw new CheckedException("导入失败：" + e.getMessage());
//        }
        return null;
    }

    @Override
    public void template(HttpServletResponse response) {
//        try {
//            ExcelUtil.exportTemplate(MesScrapMaterial.class, "报废品管理导入模板", response);
//        } catch (Exception e) {
//            throw new CheckedException("模板下载失败：" + e.getMessage());
//        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        MesScrapMaterialEntity entity = this.getById(id);
        if (entity == null) {
            throw new CheckedException("报废品记录不存在");
        }

        if ("1".equals(entity.getStatus())) {
            throw new CheckedException("该记录已审核，无需重复审核");
        }

        entity.setStatus("1"); // 已审核
        entity.setAuditor(SecurityUtils.getUser().getUsername());
        entity.setAuditTime(LocalDateTime.now());

        this.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAudit(Long id) {
        MesScrapMaterialEntity entity = this.getById(id);
        if (entity == null) {
            throw new CheckedException("报废品记录不存在");
        }

        if ("0".equals(entity.getStatus())) {
            throw new CheckedException("该记录未审核，无需取消审核");
        }

        entity.setStatus("0"); // 待审核
        entity.setAuditor(null);
        entity.setAuditTime(null);

        this.updateById(entity);
    }
} 