package com.yiqi.aitoolbench.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:usageintegration;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","app.ai.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class AiUsageIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired AiUsageRepository records;
 String login(String account,String password)throws Exception{return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("account",account,"password",password)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();}
 @Test void rejectsUnauthenticatedGenerationAndProtectsAllReadingPaths()throws Exception {
  mvc.perform(post("/api/ai/chat/completions").contentType(MediaType.APPLICATION_JSON).content("{\"runId\":\"none\",\"model\":\"gpt-test\",\"params\":{\"prompt\":\"private\",\"api_key\":\"sk-abcdefghijklmnop\"}}" )).andExpect(status().isUnauthorized());
  var rows=jdbc.queryForList("select id,status,user_id from ai_usage_record where status='rejected'");assertThat(rows).isNotEmpty();assertThat(rows.getFirst().get("user_id")).isNull();
  mvc.perform(get("/api/admin/ai-usage-records")).andExpect(status().isUnauthorized());
 }
 @Test void successfulWorkbenchCallIsOneEncryptedRecordAndOnlyAdminCanReadIt()throws Exception {
  String token=login("admin","admin123");
  var loginView=json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin\",\"password\":\"admin123\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertThat(loginView.path("user").path("superAdmin").asBoolean()).isTrue();
  String departmentId=jdbc.queryForObject("select id from organization_department where deleted_at is null order by id limit 1",String.class);
  var project=json.readTree(mvc.perform(post("/api/projects").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","usage integration","departmentId",departmentId)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  String projectId=project.path("id").asText();
  var run=json.readTree(mvc.perform(post("/api/tool-runs/start").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("toolKey","market-intelligence","projectId",projectId,"inputSummary","private input")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  String runId=run.path("runId").asText();
  var response=mvc.perform(post("/api/ai/chat/completions").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("toolKey","market-intelligence","runId",runId,"model","gpt-4.1","params",Map.of("prompt","unique private prompt"))))).andExpect(status().isOk()).andReturn().getResponse();
  String id=response.getHeader("X-Workbench-Call-ID");assertThat(id).startsWith("wb_");
  var row=records.find(id);assertThat(row.get("status")).isEqualTo("success");assertThat(row.get("userId")).isEqualTo("u_admin");
  assertThat(row.get("inputTokens")).isEqualTo(128L);assertThat(row.get("encryptedPayload").toString()).doesNotContain("unique private prompt");
  assertThat(jdbc.queryForObject("select request_params from ai_call_log where id=?",String.class,row.get("legacyLogId"))).doesNotContain("unique private prompt");
  String listed=mvc.perform(get("/api/admin/ai-usage-records").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(listed).contains(id).doesNotContain("unique private prompt","encryptedPayload");
  String detailed=mvc.perform(get("/api/admin/ai-usage-records/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(detailed).contains("unique private prompt");
  // Same signed-in user loses super-admin role; generic audit permission must not bypass the gate.
  jdbc.update("delete from sys_user_role where user_id='u_admin'");jdbc.update("insert into sys_user_role(user_id,role_code,created_at) values('u_admin','auditor',?)",java.time.Instant.now().toString());
  try {
   mvc.perform(get("/api/admin/ai-usage-records").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
   mvc.perform(get("/api/admin/ai-usage-records/"+id).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
   mvc.perform(get("/api/admin/ai-call-logs").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  } finally {jdbc.update("delete from sys_user_role where user_id='u_admin'");jdbc.update("insert into sys_user_role(user_id,role_code,created_at) values('u_admin','admin',?)",java.time.Instant.now().toString());}
 }

}
