package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyActionDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogQueryDTO;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyEntity;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyLogEntity;

import java.util.List;

/**
 * 版本化生产批量策略的编制、复核和正式目录服务。
 */
public interface MdmProductionLotPolicyService extends IService<MdmProductionLotPolicyEntity> {
    /**
     * 查询当前租户、当前工厂的策略版本，不接受页面传入的组织字段扩大范围。
     */
    Page<MdmProductionLotPolicyEntity> pagePolicies(SearchPage page, MdmProductionLotPolicyEntity query);

    /**
     * 读取当前工厂的策略详情，供启用复核与草稿编辑使用。
     */
    MdmProductionLotPolicyEntity detail(Long id);

    /**
     * 按同编号全部已有版本建议下一个版本号；最终保存仍由唯一约束防止并发重号。
     */
    Integer nextVersion(Long id);

    /**
     * 先核验策略归属，再读取不可变生命周期履历。
     */
    List<MdmProductionLotPolicyLogEntity> logs(Long id);

    /**
     * 新增草稿；只采纳业务参数，身份和审计字段由服务端生成。
     */
    Long saveDraft(MdmProductionLotPolicyEntity entity);

    /**
     * 在策略行锁内编辑草稿，保留原编制人、租户及工厂。
     */
    Boolean updateDraft(MdmProductionLotPolicyEntity entity);

    /**
     * 在策略行锁内执行启用、暂停或退役，并原子保存操作快照。
     */
    Boolean changeStatus(MdmProductionLotPolicyActionDTO action);

    /**
     * 按当前工厂及业务时刻读取可被MPS采用的正式目录。
     */
    MdmProductionLotPolicyCatalogDTO catalog(MdmProductionLotPolicyCatalogQueryDTO query);
}
