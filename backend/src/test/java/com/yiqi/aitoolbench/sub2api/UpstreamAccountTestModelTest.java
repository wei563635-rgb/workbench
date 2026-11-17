package com.yiqi.aitoolbench.sub2api;

import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.WorkbenchRepository;
import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.security.PermissionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class UpstreamAccountTestModelTest {
    @Test
    void explicitGeminiModelDoesNotRequireChangingWorkbenchDefault() {
        var client=mock(Sub2ApiClient.class);
        var permissions=mock(PermissionService.class);
        var repository=mock(WorkbenchRepository.class);
        when(client.accountPlatform(1)).thenReturn("gemini");
        when(client.test(1,"gemini-test")).thenReturn(Map.of("success",true));
        when(permissions.requireContext(null)).thenReturn(new AuthContext("test","user","test","测试",false,1,List.of()));
        var controller=new UpstreamAccountController(client,permissions,mock(LogRepository.class),2,repository);
        assertThat(controller.test(1,new UpstreamAccountController.AccountTest("gemini-test"),new MockHttpServletRequest())).containsEntry("success",true);
        verifyNoInteractions(repository);
        assertThatThrownBy(()->controller.test(1,new UpstreamAccountController.AccountTest("gpt-wrong-platform"),new MockHttpServletRequest())).isInstanceOf(IllegalArgumentException.class);
        verify(client,never()).test(1,"gpt-wrong-platform");
    }
}
