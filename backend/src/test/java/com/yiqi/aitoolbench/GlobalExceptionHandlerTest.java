package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.yiqi.aitoolbench.ai.ProviderGatewayException;
import com.yiqi.aitoolbench.sub2api.Sub2ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {
    @Test
    void providerFailuresUseStableBadGatewayResponseWithoutRawDetails() {
        var response = new GlobalExceptionHandler().providerGateway(
                new ProviderGatewayException(new IllegalStateException("token supplier-secret rejected")));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).containsEntry("code", "AI_PROVIDER_ERROR")
                .containsEntry("message", "AI 供应商调用失败，请稍后重试");
        assertThat(response.getBody().toString()).doesNotContain("supplier-secret").doesNotContain("token");
    }

    @Test
    void upstreamProviderResponseIsPassedThroughWithoutRewriting() {
        var upstreamBody = "{\"error\":{\"code\":\"moderation_blocked\",\"message\":\"output rejected\"}}";
        var response = new GlobalExceptionHandler().providerGateway(new ProviderGatewayException(400, upstreamBody));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", upstreamBody);
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void databaseConflictsDoNotExposeSqlDetails() {
        var response = handler.dataConflict(new DuplicateKeyException("insert into app_user ..."));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", "DATA_CONFLICT");
        assertThat(response.getBody().get("message").toString()).doesNotContain("app_user", "insert into");
    }

    @Test
    void businessStateConflictsReturnConflictInsteadOfServerError() {
        var response = handler.illegalState(new IllegalStateException("部门仍有下级部门，不能删除"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", "CONFLICT");
        assertThat(response.getBody()).containsEntry("message", "部门仍有下级部门，不能删除");
    }

    @Test
    void oversizedUploadsReturnAReadablePayloadTooLargeResponse() {
        var response = handler.uploadTooLarge(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).containsEntry("code", "FILE_TOO_LARGE");
        assertThat(response.getBody()).containsEntry("message", "文件不能超过 5MB");
    }

    @Test
    void sub2ApiFailuresKeepStableStatusWithoutLeakingSecrets() {
        var response = handler.handleSub2Api(new Sub2ApiException(503, "Sub2API 尚未配置"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("code", "SUB2API_ERROR")
                .containsEntry("message", "Sub2API 尚未配置");
    }
}
