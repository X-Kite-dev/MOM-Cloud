package com.kitex.mom.qms.service.impl;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.ExcelImportUtil;
import cn.afterturn.easypoi.excel.entity.ImportParams;
import cn.afterturn.easypoi.excel.entity.TemplateExportParams;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.req.QualityStandardQueryReq;
import com.kitex.mom.qms.dto.req.QualityStandardReq;
import com.kitex.mom.qms.dto.req.QualityStandardStatusReq;
import com.kitex.mom.qms.dto.resp.QmsQualityStandardDTO;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import com.kitex.mom.qms.enums.CheckClassifyEnum;
import com.kitex.mom.qms.enums.CheckTypeEnum;
import com.kitex.mom.qms.enums.InspectionMethodEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.mapper.QualityStandardMapper;
import com.kitex.mom.qms.service.QualityStandardItemService;
import com.kitex.mom.qms.service.QualityStandardService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 质检标准
 *
 * @author kitex
 * @date 2022/12/01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityStandardServiceImpl extends ServiceImpl<QualityStandardMapper, QmsQualityStandardEntity> implements QualityStandardService {

    private final QualityStandardItemService itemService;

    /** 保存当前组织的标准及完整项目，项目继承主标准的组织归属。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrUpdate(QualityStandardReq param) {
        if (param == null || param.getStandard() == null || CollectionUtil.isEmpty(param.getItems())) {
            throw new CheckedException("请添加质检项");
        }
        QmsQualityStandardEntity saveTemplate = param.getStandard();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        requireStandardScope(tenant, factory);
        if (saveTemplate.getId() != null) {
            requireOwnedStandard(saveTemplate.getId(), tenant, factory);
        }
        saveTemplate.setTenantId(tenant);
        saveTemplate.setFactoryCode(factory);
        QmsQualityStandardEntity existCodeTemplate = this.getOne(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                .ne(Objects.nonNull(saveTemplate.getId()), QmsQualityStandardEntity::getId, saveTemplate.getId())
                .eq(QmsQualityStandardEntity::getTenantId, tenant)
                .eq(QmsQualityStandardEntity::getFactoryCode, factory)
                .eq(QmsQualityStandardEntity::getStandardName, saveTemplate.getStandardName()));
        if (Objects.nonNull(existCodeTemplate)) {
            throw new CheckedException("该模板编码已存在，请勿重复添加");
        }
        // 确认唯一标准
        QmsQualityStandardEntity existTemplate = this.getOne(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                .ne(Objects.nonNull(saveTemplate.getId()), QmsQualityStandardEntity::getId, saveTemplate.getId())
                .eq(QmsQualityStandardEntity::getTenantId, tenant)
                .eq(QmsQualityStandardEntity::getFactoryCode, factory)
                .eq(QmsQualityStandardEntity::getMatCode, saveTemplate.getMatCode())
                .eq(QmsQualityStandardEntity::getInspectionType, saveTemplate.getInspectionType())
                .eq(QmsQualityStandardEntity::getPublished, 1));
        if (Objects.nonNull(existTemplate)) {
            throw new RuntimeException("该物料已存在生效中的模板，请勿重复添加");
        }
        // 固定数量方式校验质检数量是否填写
        if (InspectionMethodEnum.FIXED.name().equals(saveTemplate.getInspectionMethod())) {
            if (Objects.isNull(saveTemplate.getNum())) {
                throw new RuntimeException("质检数量必须填写");
            }
        }
        if (!this.saveOrUpdate(saveTemplate) || saveTemplate.getId() == null) {
            throw new CheckedException("质检标准保存失败");
        }
        itemService.remove(ownedItems(saveTemplate));
        saveItems(saveTemplate, param.getItems());
    }


    /**
     * 分页
     */
    public Page<QmsQualityStandardEntity> page(Page<QmsQualityStandardEntity> page, QualityStandardQueryReq query,
                                               CommonQuery commonQuery) {
//        QueryWrapper<QmsQualityStandard> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        QueryWrapper<QmsQualityStandardEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda().like(StrUtil.isNotBlank(query.getStandardName()), QmsQualityStandardEntity::getStandardName, query.getStandardName());
        if (StrUtil.isNotBlank(query.getInspectionType())) {
            autoQueryWrapper.lambda().in(QmsQualityStandardEntity::getInspectionType, Arrays.asList(query.getInspectionType().split(",")));
        }
        if (StrUtil.isNotBlank(query.getPublished())) {
            autoQueryWrapper.lambda().in(QmsQualityStandardEntity::getPublished, Arrays.asList(query.getPublished().split(",")));
        }
        autoQueryWrapper.lambda().orderByDesc(QmsQualityStandardEntity::getCreateTime);
        return this.page(page, autoQueryWrapper);
    }


    /**
     * 修改状态
     *
     * @param req
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(QualityStandardStatusReq req) {
        // 启用时验证是否存在已启用的及质检标准
        if (Objects.nonNull(req.getPublished()) && Objects.equals(req.getPublished(), 1)) {
            QmsQualityStandardEntity changeStandard = this.getById(req.getId());
            long existCount = this.count(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                    .eq(QmsQualityStandardEntity::getMatCode, changeStandard.getMatCode())
                    .eq(QmsQualityStandardEntity::getInspectionType, changeStandard.getInspectionType())
                    .eq(QmsQualityStandardEntity::getPublished, req.getPublished()));
            if (existCount > 0) {
                throw new RuntimeException("该物料已存在启用中的模板，同一物料同一质检类型只能启用一个质检标准");
            }
        }
        this.update(new LambdaUpdateWrapper<QmsQualityStandardEntity>()
                .eq(QmsQualityStandardEntity::getId, req.getId())
                .set(QmsQualityStandardEntity::getPublished, req.getPublished()));
    }

    /** 在同租户同工厂复制标准和项目，副本以停用状态保存。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copy(Long id) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        requireStandardScope(tenant, factory);
        QmsQualityStandardEntity standard = requireOwnedStandard(id, tenant, factory);
        List<QmsQualityStandardItemEntity> items = itemService.list(ownedItems(standard));

        QmsQualityStandardEntity newStandard = new QmsQualityStandardEntity();
        BeanUtil.copyProperties(standard, newStandard);
        newStandard.setId(null);
        newStandard.setPublished(0);
        newStandard.setStandardName(standard.getStandardName() + "-复制");
        newStandard.setCreateTime(LocalDateTime.now());
        newStandard.setCreateBy(SecurityUtils.getUser().getUsername());
        if (!this.save(newStandard) || newStandard.getId() == null) {
            throw new CheckedException("质检标准副本保存失败");
        }
        if (!items.isEmpty()) {
            saveItems(newStandard, items);
        }
    }

    private void requireStandardScope(Long tenant, String factory) {
        if (tenant == null || tenant <= 0 || TenantContextHolder.getTenantSkip()
                || StrUtil.isBlank(factory) || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("维护质检标准须指定实际租户和工厂");
        }
    }

    private QmsQualityStandardEntity requireOwnedStandard(Long id, Long tenant, String factory) {
        QmsQualityStandardEntity standard = this.getOne(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                .eq(QmsQualityStandardEntity::getId, id)
                .eq(QmsQualityStandardEntity::getTenantId, tenant)
                .eq(QmsQualityStandardEntity::getFactoryCode, factory));
        if (standard == null) {
            throw new CheckedException("当前租户和工厂下未找到质检标准");
        }
        return standard;
    }

    /** 兼容归属尚未补齐的旧项目。只读写当前已核对的主标准下、当前工厂的项目。 */
    private LambdaQueryWrapper<QmsQualityStandardItemEntity> ownedItems(QmsQualityStandardEntity standard) {
        return new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, standard.getId())
                .eq(QmsQualityStandardItemEntity::getFactoryCode, standard.getFactoryCode())
                .and(scope -> scope.eq(QmsQualityStandardItemEntity::getTenantId, standard.getTenantId())
                        .or().isNull(QmsQualityStandardItemEntity::getTenantId));
    }

    /** 租户和工厂取已保存的主标准，防止表单漏填或传入其他组织导致检验无法冻结。 */
    private void saveItems(QmsQualityStandardEntity standard, List<QmsQualityStandardItemEntity> items) {
        for (var item : items) {
            item.setId(null);
            item.setStandardId(standard.getId());
            item.setStandardName(standard.getStandardName());
            item.setTenantId(standard.getTenantId());
            item.setFactoryCode(standard.getFactoryCode());
        }
        if (!itemService.saveBatch(items)) {
            throw new CheckedException("质检标准项目保存失败");
        }
    }

    /**
     * 详情
     *
     * @param id
     * @return
     */
    public QmsQualityStandardDTO findById(Long id) {
        QmsQualityStandardEntity standard = this.getById(id);
        if (Objects.isNull(standard)) {
            throw new RuntimeException("查询质检标准失败");
        }
        QmsQualityStandardDTO dto = new QmsQualityStandardDTO();
        BeanUtil.copyProperties(standard, dto);
        List<QmsQualityStandardItemEntity> items = itemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, id));
        dto.setItems(items);
        return dto;
    }

    /**
     * 质检标准正在使用版本分页
     *
     * @param page
     * @param query
     * @return
     */
    public Page<QmsQualityStandardEntity> pageUsed(Page<QmsQualityStandardEntity> page, QmsQualityStandardEntity query) {
        return this.page(page,
                new LambdaQueryWrapper<QmsQualityStandardEntity>()
                        .like(StrUtil.isNotBlank(query.getStandardName()), QmsQualityStandardEntity::getStandardName,
                                query.getStandardName())
                        .eq(Objects.nonNull(query.getInspectionType()), QmsQualityStandardEntity::getInspectionType,
                                query.getInspectionType())
                        .eq(QmsQualityStandardEntity::getPublished, 1));
    }


    /**
     * 获取质检标准
     *
     * @param inspectionType
     * @param matCode
     * @return
     */
    public QmsQualityStandardEntity getRandomQualityStandard(String inspectionType, String matCode) {
        return this.getOne(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                .eq(QmsQualityStandardEntity::getInspectionType, inspectionType)
                // 新增产品编码
                .eq(QmsQualityStandardEntity::getMatCode, matCode)
                .eq(QmsQualityStandardEntity::getPublished, 1));
    }

    /**
     * 删除
     */
    public void remove(Long id) {
        QmsQualityStandardEntity standard = this.getById(id);
        if (Objects.isNull(standard)) {
            throw new RuntimeException("数据不存在");
        }
        if (Objects.equals(standard.getPublished(), 1)) {
            throw new RuntimeException("当前状态无法进行删除");
        }
        this.removeById(id);
        // 删除质检项
        itemService.remove(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, standard.getId()));
    }

    /**
     * 导入并读取返回
     */
    public List<QmsQualityStandardItemEntity> importExcel(MultipartFile file) {
        // 读取模板内容
        List<QmsQualityStandardItemEntity> list;
        try {
            ImportParams params = new ImportParams();
            list = ExcelImportUtil.importExcel(file.getInputStream(), QmsQualityStandardItemEntity.class, params);

        } catch (Exception e) {
            throw new RuntimeException("无法读取文件");
        }
        list = list.stream().filter(o -> !BeanUtil.isEmpty(o)).collect(Collectors.toList());

        // 处理类型
        for (QmsQualityStandardItemEntity standardItem : list) {
            String checkType = standardItem.getCheckType();
            if (StrUtil.isNotBlank(checkType)) {
                standardItem.setCheckType(CheckTypeEnum.findByValue(checkType).name());
            }
            String checkClassify = standardItem.getCheckClassify();
            if (StrUtil.isNotBlank(checkClassify)) {
                standardItem.setCheckClassify(CheckClassifyEnum.findByValue(checkClassify).name());
            }
        }

        return list;
    }

    /**
     * 导出下载
     */
    @SneakyThrows
    public ResponseEntity<byte[]> down(Long id) {
        // 查询质检标准
        QmsQualityStandardEntity qualityStandard = this.getById(id);
        if (Objects.isNull(qualityStandard)) {
            throw new RuntimeException("数据不存在");
        }
        TemplateExportParams params = new TemplateExportParams(StrUtil.format("templates/QualityStandardDown.xlsx"));
        Map<String, Object> map = BeanUtil.beanToMap(qualityStandard);
        this.convertMap(map, qualityStandard);
        Workbook workbook = ExcelExportUtil.exportExcel(params, map);
        // 设置header信息
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment",
                new String("质检标准导出信息.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        return new ResponseEntity<>(byteArrayOutputStream.toByteArray(), headers, HttpStatus.OK);
    }

    /**
     * 批量导出
     */
    @SneakyThrows
    public ResponseEntity<byte[]> down(List<Long> ids) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(outputStream);

        for (val qualityStandard : this.listByIds(ids)) {
            TemplateExportParams params = new TemplateExportParams(
                    StrUtil.format("templates/QualityStandardDown.xlsx"));
            Map<String, Object> map = BeanUtil.beanToMap(qualityStandard);
            this.convertMap(map, qualityStandard);
            Workbook workbook = ExcelExportUtil.exportExcel(params, map);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            workbook.write(byteArrayOutputStream);
            zip.putNextEntry(new ZipEntry(qualityStandard.getStandardName() + "xlsx"));
            IOUtils.write(byteArrayOutputStream.toByteArray(), zip);
            zip.flush();
            zip.closeEntry();
        }
        IoUtil.close(zip);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment",
                new String(("批量导出质检标准.zip").getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
        return new ResponseEntity<>(outputStream.toByteArray(), headers, HttpStatus.OK);
    }

    private void convertMap(Map<String, Object> map, QmsQualityStandardEntity qualityStandard) {
        if (Objects.nonNull(qualityStandard.getInspectionType())) {
            map.put("inspectionType",
                    InspectionTaskTypeEnum.findByName(qualityStandard.getInspectionType()).getValue());
        }
        List<QmsQualityStandardItemEntity> list = itemService.list(new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                .eq(QmsQualityStandardItemEntity::getStandardId, qualityStandard.getId()));
        // 处理类型
        for (QmsQualityStandardItemEntity standardItem : list) {
            String checkType = standardItem.getCheckType();
            if (StrUtil.isNotBlank(checkType)) {
                standardItem.setCheckType(CheckTypeEnum.findByName(checkType).getValue());
            }
        }
    }

}
