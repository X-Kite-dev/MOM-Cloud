package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.sop.MdmFormalSopSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.kitex.mom.mdm.sop.MdmSopSupport.require;

/**
 * 固定正式来源接口，必须携带原用户令牌并经过真实工厂READ核验。
 */
@RestController
@RequestMapping("/agent/knowledge/sops")
@RequiredArgsConstructor
public class MdmFormalSopSourceController {
    private final MdmFormalSopSourceService sources;

    @Inner
    @PostMapping("/search")
    public R<Page<MdmFormalSopDTO.Source>> search(@RequestBody MdmFormalSopDTO.SearchQuery query) {
        return R.ok(sources.search(query));
    }

    @Inner
    @PostMapping("/verify")
    public R<MdmFormalSopDTO.Verification> verify(@RequestBody MdmFormalSopDTO.VerifyQuery query) {
        return R.ok(sources.verify(query));
    }

    @Inner
    @PostMapping("/resolve-for-issue")
    public R<MdmFormalSopDTO.Manifest> resolve(@RequestBody MdmFormalSopDTO.ResolveQuery query) {
        return R.ok(sources.resolve(query));
    }

    /**
     * MES在自身精确派工/签收授权后可代理此入口，不给用户公开自由原件地址。
     */
    @Inner
    @PostMapping("/original")
    public ResponseEntity<byte[]> original(@RequestBody MdmFormalSopDTO.VerifyQuery query) {
        var verification = sources.verify(query);
        require(verification.isMatched(), "原件下载必须绑定原冻结正式版本指纹");
        return MdmFormalSopController.download(sources.download(verification.getSource().getOriginalId(),
                query.getVersionId(), query.getContentHash()));
    }
}
