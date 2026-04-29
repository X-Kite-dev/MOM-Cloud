package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.mom.qms.dto.req.QualityStandardQueryReq;
import com.kitex.mom.qms.dto.req.QualityStandardReq;
import com.kitex.mom.qms.dto.req.QualityStandardStatusReq;
import com.kitex.mom.qms.dto.resp.QmsQualityStandardDTO;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 质检标准
 *
 * @author kitex
 * @date 2022/12/01
 */
public interface QualityStandardService extends IService<QmsQualityStandardEntity> {

    /**
     * 新增
     */
    void saveOrUpdate(QualityStandardReq param);

    /**
     * 修改状态
     *
     * @param req
     */
    void changeStatus(QualityStandardStatusReq req);


    /**
     * 复制
     *
     * @param id
     */
    void copy(Long id);


    /**
     * 分页
     */
    Page<QmsQualityStandardEntity> page(Page<QmsQualityStandardEntity> page, QualityStandardQueryReq query, CommonQuery commonQuery);

    QmsQualityStandardDTO findById(Long id);

    /**
     * 质检标准正在使用版本分页
     *
     * @param page
     * @param query
     * @return
     */
    Page<QmsQualityStandardEntity> pageUsed(Page<QmsQualityStandardEntity> page, QmsQualityStandardEntity query);


    /**
     * 获取质检标准
     *
     * @param inspectionType
     * @param productCode
     * @return
     */
    QmsQualityStandardEntity getRandomQualityStandard(String inspectionType, String productCode);

    /**
     * 删除
     */
    void remove(Long id);

    /**
     * 导入并读取返回
     */
    List<QmsQualityStandardItemEntity> importExcel(MultipartFile file);

    /**
     * 导出下载
     */
    ResponseEntity<byte[]> down(Long id);

    /**
     * 批量导出
     */
    ResponseEntity<byte[]> down(List<Long> ids);

}
