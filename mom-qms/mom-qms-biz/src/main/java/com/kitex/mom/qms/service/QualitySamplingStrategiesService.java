package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.mom.qms.dto.req.QualitySamplingStrategiesReq;
import com.kitex.mom.qms.entity.Frequency;
import com.kitex.mom.qms.entity.QmsQualitySamplingStrategiesEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 质检标准项分类
 *
 * @author kitex
 * @date 2022/11/29
 */
public interface QualitySamplingStrategiesService extends IService<QmsQualitySamplingStrategiesEntity> {

    /**
     * 新增
     *
     * @param param
     */
    void addData(QmsQualitySamplingStrategiesEntity param);


    /**
     * 分页
     */
    Page<QmsQualitySamplingStrategiesEntity> page(Page<QmsQualitySamplingStrategiesEntity> page, QualitySamplingStrategiesReq query, CommonQuery commonQuery);


    /**
     * 导入并读取返回
     */
    List<Frequency> importExcel(MultipartFile file);

    /**
     * 导出下载
     */
    ResponseEntity<byte[]> down(Long id);

    /**
     * 批量导出
     */
    ResponseEntity<byte[]> down(List<Long> ids);
}
