package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.tpm.dto.ApplyBySourceCodePageDto;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationDetailsEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationEntity;

import java.util.List;

public interface TpmSparePartsApplicationService extends IService<TpmSparePartsApplicationEntity> {


    Boolean addApply(TpmSparePartsApplicationEntity entity);

    Boolean addOutApplication(TpmSparePartsApplicationEntity entity);

    TpmSparePartsApplicationEntity getApplicationById(Long id);

    List<TpmSparePartsApplicationDetailsEntity> getApplyBySourceCode(String sourceCode, String source);

    Page<TpmSparePartsApplicationDetailsEntity> getApplyBySourceCodePage(SearchPage page, ApplyBySourceCodePageDto dto);


    /**
     * 备件申请审批
     *
     * @param handleType 0 审批不通过  1 审批通过
     */
    Boolean outApproval(TpmSparePartsApplicationEntity application, String handleType);

    /**
     * 申请退回审批
     *
     * @param handleType 0 审批不通过  1 审批通过
     */
    Boolean inApproval(TpmSparePartsApplicationEntity application, String handleType);

}