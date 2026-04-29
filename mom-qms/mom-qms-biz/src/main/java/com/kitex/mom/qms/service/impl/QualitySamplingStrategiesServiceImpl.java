package com.kitex.mom.qms.service.impl;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.ExcelImportUtil;
import cn.afterturn.easypoi.excel.entity.ImportParams;
import cn.afterturn.easypoi.excel.entity.TemplateExportParams;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.mom.qms.dto.req.QualitySamplingStrategiesReq;
import com.kitex.mom.qms.entity.Frequency;
import com.kitex.mom.qms.entity.QmsQualitySamplingStrategiesEntity;
import com.kitex.mom.qms.mapper.QualitySamplingStrategiesMapper;
import com.kitex.mom.qms.service.QualitySamplingStrategiesService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 质检标准项分类
 *
 * @author kitex
 * @date 2022/11/29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualitySamplingStrategiesServiceImpl extends ServiceImpl<QualitySamplingStrategiesMapper, QmsQualitySamplingStrategiesEntity> implements QualitySamplingStrategiesService {

    private final RemoteDictService remoteDictService;

    /**
     * 新增
     *
     * @param param
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addData(QmsQualitySamplingStrategiesEntity param) {
        long count = this.count(new LambdaQueryWrapper<QmsQualitySamplingStrategiesEntity>()
                .eq(QmsQualitySamplingStrategiesEntity::getAql, param.getAql())
                .eq(QmsQualitySamplingStrategiesEntity::getInspectionLevel, param.getInspectionLevel()));
        if (count > 0) {
            throw new RuntimeException("已存在相同AQL、检验水平的抽样策略，请勿重复保存");
        }
        this.save(param);
    }

    /**
     * 分页
     */
    public Page<QmsQualitySamplingStrategiesEntity> page(Page<QmsQualitySamplingStrategiesEntity> page,
                                                         QualitySamplingStrategiesReq query, CommonQuery commonQuery) {
        QueryWrapper<QmsQualitySamplingStrategiesEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda().ge(query.getAqlMin() != null, QmsQualitySamplingStrategiesEntity::getAql, query.getAqlMin())
                .le(query.getAqlMax() != null, QmsQualitySamplingStrategiesEntity::getAql, query.getAqlMax())
                .orderByDesc(QmsQualitySamplingStrategiesEntity::getCreateTime);
        if (StrUtil.isNotBlank(query.getInspectionLevel())) {
            autoQueryWrapper.lambda().in(StrUtil.isNotBlank(query.getInspectionLevel()),
                    QmsQualitySamplingStrategiesEntity::getInspectionLevel,
                    Arrays.asList(query.getInspectionLevel().split(",")));
        }
        return this.page(page, autoQueryWrapper);
    }

    /**
     * 导入并读取返回
     */
    public List<Frequency> importExcel(MultipartFile file) {
        // 读取模板内容
        List<Frequency> list;
        try {
            ImportParams params = new ImportParams();
            list = ExcelImportUtil.importExcel(file.getInputStream(), Frequency.class, params);
        } catch (Exception e) {
            throw new RuntimeException("无法读取文件");
        }
        return list.stream().filter(o -> !BeanUtil.isEmpty(o)).collect(Collectors.toList());
    }

    /**
     * 导出下载
     */
    @SneakyThrows
    public ResponseEntity<byte[]> down(Long id) {
        // 查询AQL
        QmsQualitySamplingStrategiesEntity samplingStrategies = this.getById(id);
        if (Objects.isNull(samplingStrategies)) {
            throw new RuntimeException("抽样策略未找到");
        }
        Map<String, String> levelDict = remoteDictService.getDictByType("inspection_level")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel));
        samplingStrategies.setInspectionLevel(levelDict.get(samplingStrategies.getInspectionLevel()));
        TemplateExportParams params = new TemplateExportParams(StrUtil.format("templates/AqlDown.xlsx"));
        Map<String, Object> map = BeanUtil.beanToMap(samplingStrategies);
        Workbook workbook = ExcelExportUtil.exportExcel(params, map);

        // 设置header信息
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment",
                new String("AQL信息导出.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

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

        for (QmsQualitySamplingStrategiesEntity samplingStrategies : this.listByIds(ids)) {
            TemplateExportParams params = new TemplateExportParams(StrUtil.format("templates/AqlDown.xlsx"));
            Map<String, Object> map = BeanUtil.beanToMap(samplingStrategies);
            Workbook workbook = ExcelExportUtil.exportExcel(params, map);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            workbook.write(byteArrayOutputStream);
            zip.putNextEntry(new ZipEntry(samplingStrategies.getAql() + "xlsx"));
            IOUtils.write(byteArrayOutputStream.toByteArray(), zip);
            zip.flush();
            zip.closeEntry();
        }
        IoUtil.close(zip);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment",
                new String(("批量AQL信息.zip").getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
        return new ResponseEntity<>(outputStream.toByteArray(), headers, HttpStatus.OK);
    }

}
