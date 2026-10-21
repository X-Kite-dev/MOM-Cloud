package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.agent.dto.MdmProcessKnowledgeDTO;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mdm.entity.MdmProcessParameterStandardEntity;

/**
 * 工艺参数标准版本服务。
 *
 * <p>负责草稿维护、四眼发布、退役、复制新版本，以及向MES提供不可歧义的已发布标准。</p>
 */
public interface MdmProcessParameterStandardService
        extends IService<MdmProcessParameterStandardEntity> {

    /**
     * 创建包含参数明细的草稿版本。
     */
    Long createDraft(MdmProcessParameterStandardDTO.SaveCommand command);

    /**
     * 原子替换草稿表头和全部参数明细。
     */
    Boolean updateDraft(MdmProcessParameterStandardDTO.SaveCommand command);

    /**
     * 删除尚未发布的草稿及其明细，并保留审计记录。
     */
    Boolean deleteDraft(Long id);

    /**
     * 复制任一历史版本，生成同一标准编码的下一草稿版本。
     */
    Long copyVersion(Long id);

    /**
     * 执行发布或退役动作；发布实行编制、审批岗位分离。
     */
    Boolean changeStatus(MdmProcessParameterStandardDTO.ActionCommand command);

    /**
     * 查询表头、明细和不可变生命周期审计记录。
     */
    MdmProcessParameterStandardDTO.Detail detail(Long id);

    /**
     * 按实际租户、工厂、路线和生效时点解析唯一已发布版本，供MES下达时冻结。
     */
    MdmProcessParameterStandardDTO.FrozenCatalog resolveReleased(
            MdmProcessParameterStandardDTO.ResolveQuery query);

    /** 按精确节点冻结；历史V1只做唯一投影，不修改其原内容及指纹。 */
    MdmProcessParameterStandardDTO.FrozenCatalog resolveNodes(
            MdmProcessParameterStandardDTO.ResolveQuery query);

    /** 当前工厂正式知识来源摘要；不读取正文、不修改工艺版本。 */
    MdmProcessKnowledgeDTO.SourcePage searchKnowledgeSources(MdmProcessKnowledgeDTO.SearchQuery query);

    /** 精确版本的当前适用性核验，失效或结构不完整时不返回任何可用正文。 */
    MdmProcessKnowledgeDTO.SourceDetail verifyKnowledgeSource(MdmProcessKnowledgeDTO.VerifyQuery query);
}
