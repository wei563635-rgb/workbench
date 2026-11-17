package com.yiqi.aitoolbench.relay;

import com.yiqi.aitoolbench.auth.AuthContext;
import com.yiqi.aitoolbench.ApiController.OperationLog;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.security.PermissionService;
import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:relay-access;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RelayAccessTest {
 @Autowired MockMvc mvc;
 @MockBean PermissionService permissions;
 @MockBean RelayService relay;
 @MockBean RelayIdentityService identities;
 @MockBean LogRepository logs;
 @MockBean Sub2ApiClient client;
 @BeforeEach void setup(){
  when(permissions.hasPermission(any(),anyString())).thenAnswer(call->((AuthContext)call.getArgument(0)).permissions().contains(call.getArgument(1)));
 }
 private void actor(String id,String... grants){when(permissions.requireContext("Bearer local-test")).thenReturn(new AuthContext("local-test",id,"tester","测试用户",false,1,List.of(grants)));}
 @Test void ownReportCannotBeRetargetedUsingQueryParameter()throws Exception{
  actor("u_member","workbench:access");when(relay.report(any(),any(),eq("u_member"),isNull(),isNull(),eq("tokens"),eq(1),eq(20),isNull(),isNull())).thenReturn(Map.of("owner","u_member"));
  mvc.perform(get("/api/relay/me?userId=u_admin").header("Authorization","Bearer local-test")).andExpect(status().isOk()).andExpect(jsonPath("$.owner").value("u_member"));
 }
 @Test void quotaReaderCannotWriteOrReadAudit()throws Exception{
  actor("u_member","admin:relay-quota:read");
  mvc.perform(post("/api/admin/relay/quotas/sync").header("Authorization","Bearer local-test")).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/relay/audit/1").header("Authorization","Bearer local-test")).andExpect(status().isForbidden());
  verify(relay,never()).synchronize();verify(client,never()).workbenchGet(anyString());
 }
 @Test void rawAuditGrantAloneDoesNotMakeAnOrdinaryUserSuperAdmin()throws Exception{
  actor("u_member","admin:relay-audit:read");
  mvc.perform(get("/api/admin/relay/audit/1?export=true").header("Authorization","Bearer local-test")).andExpect(status().isForbidden());
  verify(client,never()).workbenchGet(anyString());
 }
 @Test void comparisonUsesUsageReadPermission()throws Exception{
  actor("u_member","workbench:access");
  mvc.perform(get("/api/admin/relay/usage/compare?userIds=u_member,u_admin").header("Authorization","Bearer local-test")).andExpect(status().isForbidden());
  verify(relay,never()).compare(nullable(String.class),nullable(String.class),anyList());
  actor("u_admin","admin:relay-usage:read");
  when(relay.compare(nullable(String.class),nullable(String.class),anyList())).thenReturn(Map.of("employees",List.of()));
  mvc.perform(get("/api/admin/relay/usage/compare?userIds=u_member,u_admin").header("Authorization","Bearer local-test")).andExpect(status().isOk());
 }
 @Test void poolSummaryUsesUsageReadPermissionWithoutReturningAccountDetails()throws Exception{
  actor("u_member","workbench:access");
  mvc.perform(get("/api/admin/relay/usage/pool-summary").header("Authorization","Bearer local-test")).andExpect(status().isForbidden());
  verify(relay,never()).poolSummary();
  actor("u_reader","admin:relay-usage:read");
  when(relay.poolSummary()).thenReturn(Map.of("window5h",Map.of("accountCount",2,"averageRemainingPercent",75.0)));
  mvc.perform(get("/api/admin/relay/usage/pool-summary").header("Authorization","Bearer local-test"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.window5h.averageRemainingPercent").value(75.0))
   .andExpect(jsonPath("$.items").doesNotExist());
 }
 @Test void onlyUserManagersCanIssueAndRevokeAnotherUsersClientKey()throws Exception{
  actor("u_member","workbench:access");
  mvc.perform(post("/api/admin/users/u_admin/relay-client-key").header("Authorization","Bearer local-test").contentType("application/json").content("{}"))
   .andExpect(status().isForbidden());
  mvc.perform(delete("/api/admin/users/u_admin/relay-client-key").header("Authorization","Bearer local-test"))
   .andExpect(status().isForbidden());
  actor("u_reader","admin:user:read");
  when(identities.status("u_admin")).thenReturn(Map.of("identityReady",true,"clientKeyStatus","active"));
  mvc.perform(get("/api/admin/users/u_admin/relay-client-key").header("Authorization","Bearer local-test"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.clientKeyStatus").value("active")).andExpect(jsonPath("$.key").doesNotExist());
  mvc.perform(post("/api/admin/users/u_admin/relay-client-key").header("Authorization","Bearer local-test").contentType("application/json").content("{}"))
   .andExpect(status().isForbidden());
  actor("u_manager","admin:user:write");
  when(identities.rotateClient("u_admin")).thenReturn(Map.of("key","one-time-test-key"));
  mvc.perform(post("/api/admin/users/u_admin/relay-client-key").header("Authorization","Bearer local-test").contentType("application/json").content("{}"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.key").value("one-time-test-key"));
  mvc.perform(delete("/api/admin/users/u_admin/relay-client-key").header("Authorization","Bearer local-test"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
  verify(identities).rotateClient("u_admin");verify(identities).revokeClient("u_admin");
  verify(logs,times(2)).insertOperationLog(argThat((OperationLog item)->item.operator().equals("u_manager")
   && item.detail().equals(Map.of("targetUserId","u_admin"))));
 }
 @Test void personalKeyRoutesRejectSelfServiceAndUnknownUserCannotReceiveKey()throws Exception{
  actor("u_member","workbench:access");
  mvc.perform(get("/api/relay/me/key").header("Authorization","Bearer local-test")).andExpect(status().isForbidden());
  mvc.perform(post("/api/relay/me/key").header("Authorization","Bearer local-test").contentType("application/json").content("{}"))
   .andExpect(status().isForbidden());
  actor("u_manager","admin:user:write");
  mvc.perform(post("/api/admin/users/missing-user/relay-client-key").header("Authorization","Bearer local-test").contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest());
  verify(identities,never()).rotateClient("missing-user");
 }
}
