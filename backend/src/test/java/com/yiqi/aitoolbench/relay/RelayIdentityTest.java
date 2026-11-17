package com.yiqi.aitoolbench.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.security.SecretCrypto;
import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelayIdentityTest {
 JdbcTemplate jdbc;RelayRepository repository;RelayService relay;Sub2ApiClient client;SecretCrypto crypto;RelayIdentityService identities;
 @BeforeEach void setup(){
  // Keep the same in-memory database alive for all JDBC connections in this test.
  jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:key"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","sa",""));
  repository=new RelayRepository(jdbc,new ObjectMapper());relay=mock(RelayService.class);client=mock(Sub2ApiClient.class);
  crypto=new SecretCrypto("http",Base64.getEncoder().encodeToString(new byte[32]));
  identities=new RelayIdentityService(jdbc,repository,relay,client,crypto,7);
  repository.saveEmployee(new RelayPolicy.Employee("u","independent",100,2,42L));
 }
 @Test void clientKeyIsOneTimeAndRotationRevokesOnlyItsOwnPurpose(){
  when(client.createWorkbenchKey(eq(42L),eq(7L),eq("client"),anyString())).thenReturn(43L,44L);
  String first=identities.rotateClient("u").get("key").toString();
  assertThat(first).startsWith("sk-wb-");assertThat(jdbc.queryForObject("select encrypted_key from relay_key",String.class)).isNull();
  String second=identities.rotateClient("u").get("key").toString();assertThat(second).isNotEqualTo(first);
  verify(client).disableWorkbenchKey(42,43,"client");
  identities.revokeClient("u");verify(client).disableWorkbenchKey(42,44,"client");
  assertThat(identities.status("u").get("clientKeyStatus")).isEqualTo("absent");
 }
 @Test void failedCreateRetriesSameEncryptedStagedKey(){
  when(client.createWorkbenchKey(eq(42L),eq(7L),eq("client"),anyString())).thenThrow(new IllegalStateException("interrupted")).thenReturn(45L);
  assertThatThrownBy(()->identities.rotateClient("u")).isInstanceOf(IllegalStateException.class);
  String encrypted=jdbc.queryForObject("select encrypted_key from relay_key",String.class);
  assertThat(encrypted).startsWith("enc:v1:").doesNotContain("sk-wb-");
  String result=identities.rotateClient("u").get("key").toString();assertThat(result).isEqualTo(crypto.decrypt(encrypted));
  var keys=ArgumentCaptor.forClass(String.class);verify(client,times(2)).createWorkbenchKey(eq(42L),eq(7L),eq("client"),keys.capture());
  assertThat(keys.getAllValues()).containsOnly(result);
 }
 @Test void internalKeyRemainsEncryptedAndIsNotRotatedByClientOperations(){
  when(client.configurationStatus()).thenReturn(Map.of("configured",true));
  when(client.workbenchGet("/capabilities")).thenReturn(new ObjectMapper().createObjectNode().put("contractVersion","1"));
  when(relay.configuration()).thenReturn(Map.of("employees",List.of(Map.of("id","u","name","员工","enabled",true,"configured",true,"relayUserId",42L,"mode","independent","personalLimit",100L,"concurrency",2))));
  when(client.createWorkbenchKey(eq(42L),eq(7L),eq("internal"),anyString())).thenReturn(46L);
  identities.provision();String internal=identities.internalCipher("u");assertThat(internal).startsWith("enc:v1:");
  when(client.createWorkbenchKey(eq(42L),eq(7L),eq("client"),anyString())).thenReturn(47L);
  identities.rotateClient("u");assertThat(identities.internalCipher("u")).isEqualTo(internal);
  verify(client,never()).disableWorkbenchKey(eq(42L),anyLong(),eq("internal"));
 }
}
