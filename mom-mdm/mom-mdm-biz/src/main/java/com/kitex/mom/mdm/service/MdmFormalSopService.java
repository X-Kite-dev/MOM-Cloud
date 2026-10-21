package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.MdmSopVersionEntity;
import org.springframework.web.multipart.MultipartFile;

/** 正式SOP版本治理服务；不通过通用CRUD发布制造依据。 */
public interface MdmFormalSopService extends IService<MdmSopVersionEntity> {
    /** 当前真实工厂分页目录。 */
    Page<MdmSopVersionEntity> catalog(String keyword, String status, long current, long size);
    /** 精确版本详情。 */
    MdmSopVersionEntity detail(String versionId);
    /** 上传不可变原件并保留永久回执。 */
    MdmFormalSopDTO.Receipt upload(String requestId, MultipartFile file);
    /** 编制、修订、保存、提交动作。 */
    MdmFormalSopDTO.Receipt edit(MdmFormalSopDTO.Command command);
    /** 独立审核、退役、安全撤回动作。 */
    MdmFormalSopDTO.Receipt review(MdmFormalSopDTO.Command command);
}
