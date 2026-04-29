package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmSparePartsReplaceEntity;

public interface TpmSparePartsReplaceService extends IService<TpmSparePartsReplaceEntity> {

    TpmSparePartsReplaceEntity getReplaceById(Long id);

    Boolean saveReplace(TpmSparePartsReplaceEntity tpmSparePartsReplace);

    Boolean handle(TpmSparePartsReplaceEntity tpmSparePartsReplace);
}