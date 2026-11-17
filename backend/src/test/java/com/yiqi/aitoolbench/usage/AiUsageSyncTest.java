package com.yiqi.aitoolbench.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.relay.RelayRepository;
import com.yiqi.aitoolbench.security.SecretCrypto;
import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiUsageSyncTest {
 JdbcTemplate jdbc;AiUsageRepository records;AiUsageSync sync;Sub2ApiClient client;ObjectMapper json=new ObjectMapper();
 @BeforeEach void setup(){
  jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:usagesync"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","sa",""));
  new LogRepository(jdbc,json);new RelayRepository(jdbc,json);records=new AiUsageRepository(jdbc,json,new SecretCrypto("http",Base64.getEncoder().encodeToString(new byte[32])));
  client=mock(Sub2ApiClient.class);when(client.configurationStatus()).thenReturn(Map.of("configured",true));when(client.workbenchGet("/capabilities")).thenReturn(json.createObjectNode().put("callRecords",true));
  when(client.asMap(any())).thenAnswer(i->json.convertValue(i.getArgument(0),Map.class));sync=new AiUsageSync(records,client,json);
 }
 @Test void repeatSyncAndLateMeteringMergeIntoWorkbenchCallWithoutZeroFabrication(){
  var row=new LinkedHashMap<String,Object>();row.put("id","wb_logical");row.put("channel","workbench");row.put("origin","workbench");row.put("status","success");row.put("userId","employee");row.put("occurredAt",Instant.now().toString());records.save(row,Map.of("request","prompt","response","answer"));
  var event=new LinkedHashMap<String,Object>();event.put("eventId","event1");event.put("logicalId","wb_logical");event.put("channel","workbench");event.put("userId","employee");event.put("status","success");event.put("occurredAt",Instant.now().toString());event.put("complete",true);
  when(client.workbenchGet(startsWith("/calls?"))).thenReturn(json.valueToTree(Map.of("items",List.of(event),"hasMore",false)));
  sync.synchronize();sync.synchronize();assertThat(jdbc.queryForObject("select count(*) from ai_usage_record",Integer.class)).isEqualTo(1);assertThat(records.find("wb_logical").get("inputTokens")).isNull();
  event.put("inputTokens",17);event.put("outputTokens",5);event.put("cacheTokens",3);event.put("sourceUsageId","9");
  when(client.workbenchGet(startsWith("/calls?"))).thenReturn(json.valueToTree(Map.of("items",List.of(event),"hasMore",false)));sync.synchronize();
  assertThat(records.find("wb_logical").get("inputTokens")).isEqualTo(17L);assertThat(records.find("wb_logical").get("requestId")).isEqualTo("event1");assertThat(records.payload(records.find("wb_logical")).toString()).contains("prompt");
 }
 @Test void failedSyncKeepsCheckpointAndDoesNotClearExistingFacts(){
  when(client.workbenchGet(startsWith("/calls?"))).thenThrow(new IllegalStateException("unavailable"));sync.synchronize();assertThat(records.syncState().get("last_sync")).isNull();assertThat(records.syncState().get("last_error")).isNotNull();
 }
 @Test void asyncTaskUpdatesOriginalRecordAndCannotBeReadAsAnotherUser(){
  var row=new LinkedHashMap<String,Object>();row.put("id","async1");row.put("status","running");row.put("channel","workbench");row.put("origin","workbench");row.put("userId","employee");row.put("occurredAt",Instant.now().toString());row.put("complete",true);records.save(row,Map.of("request",Map.of("prompt","red circle")));
  sync.bindTask("task1","async1","employee");sync.updateTask("task1","other",Map.of("status","completed","results",List.of("https://example.com/a.png")));assertThat(records.find("async1").get("status")).isEqualTo("running");
  sync.updateTask("task1","employee",Map.of("status","completed","results",List.of("https://example.com/a.png")));
  assertThat(jdbc.queryForObject("select count(*) from ai_usage_record",Integer.class)).isEqualTo(1);assertThat(records.find("async1").get("status")).isEqualTo("success");assertThat(records.find("async1").get("imageCount")).isEqualTo(1L);assertThat(records.payload(records.find("async1")).toString()).contains("red circle","a.png");
 }
}
