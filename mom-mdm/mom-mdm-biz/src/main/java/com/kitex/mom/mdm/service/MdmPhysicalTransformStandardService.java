package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardEntity;

/** 实物转换标准的完整编制、异人复核和永久版本治理，不提供通用CRUD HTTP入口。 */
public interface MdmPhysicalTransformStandardService extends IService<MdmPhysicalTransformStandardEntity> {
    /** 实际组织内分页查询，输入筛选不能改变scope。 */
    Page<MdmPhysicalTransformStandardEntity> pageStandards(SearchPage page, String standardNo, String materialCode, String status);
    /** 读取精确版本及完整履历。 */
    MdmPhysicalTransformStandardDTO.Detail detail(Long id);
    /** 实际路线/工序/换算依据分页，不以固定500条截断或客户端工厂替代实际组织。 */
    Page<MdmPhysicalTransformStandardDTO.BasisOption> basisPage(SearchPage page, String kind, String materialCode, String routeId);
    /** 保存十五文本字段，修改或显式复制绑定原SHA/动作号，期望组织只比对。 */
    MdmPhysicalTransformStandardEntity saveDraft(String rawJson);
    /** 完整七字段状态动作及COPY；返回实际保存后的版本，不假成功。 */
    MdmPhysicalTransformStandardEntity act(String rawJson);
    /** 已认证内部精确版本解析，只交付真实已发布原内容。 */
    MdmPhysicalTransformStandardDTO.Frozen resolveReleased(MdmPhysicalTransformStandardDTO.ResolveQuery query);
}
