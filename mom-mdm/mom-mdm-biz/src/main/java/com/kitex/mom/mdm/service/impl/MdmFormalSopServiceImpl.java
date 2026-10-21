package com.kitex.mom.mdm.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.MdmSopOriginalEntity;
import com.kitex.mom.mdm.entity.MdmSopVersionEntity;
import com.kitex.mom.mdm.mapper.MdmSopOriginalMapper;
import com.kitex.mom.mdm.mapper.MdmSopVersionMapper;
import com.kitex.mom.mdm.service.MdmFormalSopService;
import com.kitex.mom.mdm.sop.MdmSopJournal;
import com.kitex.mom.mdm.sop.MdmSopOriginalStorage;
import com.kitex.mom.mdm.sop.MdmSopSupport;
import com.kitex.mom.mdm.sop.MdmSopWorkspace;
import com.kitex.mom.mdm.sop.MdmSopOriginalWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import static com.kitex.mom.mdm.sop.MdmSopSupport.*;

/**
 * 正式SOP编制与独立批准。版本族锁串行分配版本号；行锁和原修订指纹阻止并发覆盖。
 * RELEASED内容永远不原地编辑，安全撤回与普通退役保留不同业务含义。
 */
@Service
@RequiredArgsConstructor
public class MdmFormalSopServiceImpl extends ServiceImpl<MdmSopVersionMapper, MdmSopVersionEntity>
        implements MdmFormalSopService {
    private static final Set<String> EDIT_ACTIONS = Set.of("CREATE", "REVISE", "UPDATE", "SUBMIT");
    private static final Set<String> REVIEW_ACTIONS = Set.of("APPROVE", "REJECT", "RETIRE", "WITHDRAW");
    private final MdmSopOriginalMapper originals;
    private final MdmSopSupport support;
    private final MdmSopJournal journal;
    private final MdmSopOriginalStorage storage;
    private final MdmSopOriginalWriter originalWriter;
    private final MdmSopWorkspace workspace;

    @Override
    public Page<MdmSopVersionEntity> catalog(String keyword, String status, long current, long size) {
        support.access(false);
        require(current > 0 && size > 0 && size <= 50, "SOP目录每页1至50条");
        var query = scope();
        if (keyword != null && !keyword.isBlank()) {
            String term = text(keyword, 100, "查询关键字", false);
            query.and(q -> q.like(MdmSopVersionEntity::getSopNo, term).or().like(MdmSopVersionEntity::getName, term));
        }
        query.eq(status != null && !status.isBlank(), MdmSopVersionEntity::getStatus, status);
        return baseMapper.selectPage(new Page<>(current, size), query.orderByDesc(MdmSopVersionEntity::getId));
    }

    @Override
    public MdmSopVersionEntity detail(String versionId) {
        support.access(false);
        return exact(id(versionId, "SOP版本ID"), false);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mdm_sop_edit')")
    @Transactional(rollbackFor = Exception.class)
    public MdmFormalSopDTO.Receipt upload(String requestId, MultipartFile file) {
        support.access(true);
        var upload = storage.inspect(file);
        var attempt = journal.begin(requestId, "UPLOAD", List.of(upload.name(), upload.mediaType(), upload.hash(), upload.bytes().length));
        if (attempt.previous() != null) return attempt.previous();
        var original = originalWriter.save(upload);
        return journal.finish(attempt, null, original.getId().toString(), "UPLOADED", original.getSha256(), null);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mdm_sop_edit')")
    @Transactional(rollbackFor = Exception.class)
    public MdmFormalSopDTO.Receipt edit(MdmFormalSopDTO.Command command) {
        require(command != null && command.getAction() != null && EDIT_ACTIONS.contains(command.getAction()), "编制入口不接受审核或作废动作");
        return execute(command);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mdm_sop_review')")
    @Transactional(rollbackFor = Exception.class)
    public MdmFormalSopDTO.Receipt review(MdmFormalSopDTO.Command command) {
        require(command != null && command.getAction() != null && REVIEW_ACTIONS.contains(command.getAction()), "审核入口动作不正确");
        return execute(command);
    }

    private MdmFormalSopDTO.Receipt execute(MdmFormalSopDTO.Command command) {
        support.access(true);
        var attempt = journal.begin(command.getRequestId(), command.getAction(), command);
        if (attempt.previous() != null) return attempt.previous();
        String action = command.getAction();
        require(Set.of("CREATE", "REVISE").contains(action)
                        || (command.getSourceNodeId() == null && command.getLegacyAttachmentId() == null
                        && command.getDerivedFromVersionId() == null), "已建版本不能改写编制来源");
        MdmSopVersionEntity version;
        String before = null;
        if ("CREATE".equals(action)) {
            String sopNo = text(command.getSopNo(), 80, "SOP编号", true);
            require(sopNo.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"), "SOP编号仅支持字母数字及_.-");
            journal.lock("FAMILY:" + sopNo);
            require(baseMapper.selectCount(scope().eq(MdmSopVersionEntity::getSopNo, sopNo)) == 0,
                    "该SOP编号已存在，请从原版本修订，不要重建版本族");
            var origin = workspace.prepare(command);
            Long derivedId = null;
            if (command.getDerivedFromVersionId() != null) {
                derivedId = id(command.getDerivedFromVersionId(), "派生来源版本");
                var source = exact(derivedId, false);
                require("RELEASED".equals(source.getStatus()) && Objects.equals(source.getContentHash(), contentHash(source)),
                        "另建专用SOP须基于有效已发布版本");
                if (command.getOriginalId() == null || command.getOriginalId().isBlank()) {
                    command.setOriginalId(source.getOriginalId().toString());
                }
            }
            version = newDraft(sopNo, 1, null);
            applyContent(version, command);
            refreshHashes(version);
            require(baseMapper.insert(version) == 1, "SOP草稿创建失败");
            workspace.record(origin, version, derivedId);
        } else {
            // 先以只读版本取得不可变族编号，再锁族及行；所有操作采用相同锁次序。
            Long versionId = id(command.getVersionId(), "SOP版本ID");
            journal.lock("FAMILY:" + exact(versionId, false).getSopNo());
            version = exact(versionId, true);
            require(Objects.equals(version.getRevisionHash(), command.getExpectedHash()), "SOP版本已被修改，请刷新后重新确认");
            before = JSONUtil.toJsonStr(version);
            if ("REVISE".equals(action)) {
                require(command.getLegacyAttachmentId() == null && command.getDerivedFromVersionId() == null,
                        "共享修订不能同时转入旧附件或另建版本族");
                var origin = workspace.prepare(command);
                require(Set.of("RELEASED", "RETIRED", "WITHDRAWN").contains(version.getStatus()), "仅正式历史版本可以新建修订草稿");
                require(Objects.equals(version.getContentHash(), contentHash(version)), "来源版本内容指纹异常，不能修订");
                var latest = baseMapper.selectList(scope().eq(MdmSopVersionEntity::getSopNo, version.getSopNo())
                        .orderByDesc(MdmSopVersionEntity::getVersionNo).last("LIMIT 1"));
                int next = Math.addExact(latest.get(0).getVersionNo(), 1);
                var revised = newDraft(version.getSopNo(), next, version.getId());
                revised.setName(version.getName());
                revised.setDescription(version.getDescription());
                revised.setApplicability(version.getApplicability());
                applyOriginal(revised, original(version.getOriginalId()));
                refreshHashes(revised);
                require(baseMapper.insert(revised) == 1, "SOP修订草稿创建失败");
                workspace.record(origin, revised, version.getId());
                version = revised;
            } else {
                transition(version, command);
                refreshHashes(version);
                require(baseMapper.updateById(version) == 1, "SOP版本变更未成功");
            }
        }
        return journal.finish(attempt, version.getId(), version.getId().toString(), version.getStatus(),
                version.getRevisionHash(), before);
    }

    private void transition(MdmSopVersionEntity version, MdmFormalSopDTO.Command command) {
        String action = command.getAction();
        LocalDateTime now = LocalDateTime.now();
        switch (action) {
            case "UPDATE" -> {
                require(Set.of("DRAFT", "REJECTED").contains(version.getStatus()), "只有草稿或退回版可编辑");
                applyContent(version, command);
                version.setStatus("DRAFT");
                // 保留之前提交/审核实名审计字段；再次提交时替换本轮提交人，历史仍在永久操作账。
            }
            case "SUBMIT" -> {
                require(Set.of("DRAFT", "REJECTED").contains(version.getStatus()), "当前状态不能提交审核");
                require(Objects.equals(version.getContentHash(), contentHash(version)), "SOP内容指纹异常");
                storage.readVerified(original(version.getOriginalId()));
                addContributor(version, support.actor());
                version.setStatus("IN_REVIEW");
                version.setSubmittedBy(support.actor());
                version.setSubmittedTime(now);
            }
            case "APPROVE", "REJECT" -> {
                require("IN_REVIEW".equals(version.getStatus()), "只有待审核版本可以审核");
                require(!contributors(version).contains(support.actor())
                                && !Objects.equals(version.getSubmittedBy(), support.actor()),
                        "SOP审核必须由所有编制、编辑、提交人员之外的独立人员执行");
                version.setReviewReason(text(command.getReason(), 1000, "审核意见", true));
                version.setReviewedBy(support.actor());
                version.setReviewedTime(now);
                if ("APPROVE".equals(action)) {
                    require(Objects.equals(version.getContentHash(), contentHash(version)), "待批准SOP指纹已变化");
                    storage.readVerified(original(version.getOriginalId()));
                    version.setStatus("RELEASED");
                    version.setEffectiveFrom(now);
                } else version.setStatus("REJECTED");
            }
            case "RETIRE", "WITHDRAW" -> {
                require("RELEASED".equals(version.getStatus())
                                || "WITHDRAW".equals(action) && "RETIRED".equals(version.getStatus()),
                        "退役仅限已发布版；安全撤回可用于已发布或已退役版");
                version.setRetireReason(text(command.getReason(), 1000, "退役/安全撤回原因", true));
                version.setRetiredBy(support.actor());
                version.setRetiredTime(now);
                version.setStatus("WITHDRAW".equals(action) ? "WITHDRAWN" : "RETIRED");
            }
            default -> throw new IllegalArgumentException("不支持的SOP生命周期动作");
        }
    }

    private MdmSopVersionEntity newDraft(String sopNo, int number, Long parentId) {
        var version = new MdmSopVersionEntity();
        version.setTenantId(support.tenant());
        version.setFactoryCode(support.access(true));
        version.setSopNo(sopNo);
        version.setVersionNo(number);
        version.setParentVersionId(parentId);
        version.setStatus("DRAFT");
        version.setContributorsJson(JSONUtil.toJsonStr(List.of(support.actor())));
        return version;
    }

    private void applyContent(MdmSopVersionEntity version, MdmFormalSopDTO.Command command) {
        version.setName(text(command.getName(), 200, "SOP名称", true));
        version.setDescription(text(command.getDescription(), 12000, "编制说明", false));
        version.setApplicability(text(command.getApplicability(), 4000, "适用范围及限制", true));
        applyOriginal(version, original(id(command.getOriginalId(), "原件ID")));
        addContributor(version, support.actor());
    }

    private void applyOriginal(MdmSopVersionEntity version, MdmSopOriginalEntity original) {
        storage.readVerified(original);
        version.setOriginalId(original.getId());
        version.setOriginalHash(original.getSha256());
        version.setOriginalSize(original.getByteSize());
        version.setMediaType(original.getMediaType());
        // 上传原件同样属于编制贡献，不能通过换提交人绕过独立审核。
        addContributor(version, original.getUploadedBy());
    }

    private Set<String> contributors(MdmSopVersionEntity version) {
        return new TreeSet<>(JSONUtil.toList(version.getContributorsJson(), String.class));
    }

    private void addContributor(MdmSopVersionEntity version, String actor) {
        Set<String> contributors = contributors(version);
        contributors.add(actor);
        require(contributors.size() <= 1000, "SOP编制参与者超过审计上限，请由管理员核对版本治理");
        version.setContributorsJson(JSONUtil.toJsonStr(contributors));
    }

    public static String contentHash(MdmSopVersionEntity version) {
        return hash("FORMAL_SOP_CONTENT_V1", version.getTenantId(), version.getFactoryCode(), version.getSopNo(),
                version.getVersionNo(), version.getParentVersionId(), version.getName(), version.getOriginalId(),
                version.getOriginalHash(), version.getOriginalSize(), version.getMediaType(),
                version.getDescription(), version.getApplicability());
    }

    private void refreshHashes(MdmSopVersionEntity version) {
        version.setContentHash(contentHash(version));
        version.setRevisionHash(hash("FORMAL_SOP_REVISION_V1", version.getContentHash(), version.getStatus(),
                version.getContributorsJson(), version.getSubmittedBy(), version.getSubmittedTime(),
                version.getReviewedBy(), version.getReviewedTime(), version.getReviewReason(),
                version.getEffectiveFrom(), version.getRetiredBy(), version.getRetiredTime(), version.getRetireReason()));
    }

    public MdmSopOriginalEntity original(Long originalId) {
        var original = originals.selectOne(new LambdaQueryWrapper<MdmSopOriginalEntity>()
                .eq(MdmSopOriginalEntity::getTenantId, support.tenant())
                .eq(MdmSopOriginalEntity::getFactoryCode, support.access(false))
                .eq(MdmSopOriginalEntity::getId, originalId));
        require(original != null, "SOP原件不存在或不属于当前授权工厂");
        return original;
    }

    private MdmSopVersionEntity exact(Long id, boolean lock) {
        var version = baseMapper.selectOne(scope().eq(MdmSopVersionEntity::getId, id).last(lock ? "FOR UPDATE" : ""));
        require(version != null, "SOP版本不存在或不属于当前授权工厂");
        return version;
    }

    private LambdaQueryWrapper<MdmSopVersionEntity> scope() {
        return new LambdaQueryWrapper<MdmSopVersionEntity>()
                .eq(MdmSopVersionEntity::getTenantId, support.tenant())
                .eq(MdmSopVersionEntity::getFactoryCode, support.access(false));
    }
}
