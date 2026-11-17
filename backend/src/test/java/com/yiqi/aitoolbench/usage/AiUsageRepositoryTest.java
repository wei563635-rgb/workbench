package com.yiqi.aitoolbench.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.security.SecretCrypto;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AiUsageRepositoryTest {
    JdbcTemplate jdbc;AiUsageRepository records;
    @BeforeEach void setup(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:usage"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table sys_user(id varchar(80),account varchar(120),display_name varchar(100))");
        jdbc.execute("create table organization_department(id varchar(80),name varchar(100))");
        records=new AiUsageRepository(jdbc,new ObjectMapper(),new SecretCrypto("http",Base64.getEncoder().encodeToString(new byte[32])));
    }
    Map<String,Object> row(String id){var r=new LinkedHashMap<String,Object>();r.put("id",id);r.put("channel","workbench");r.put("origin","workbench");r.put("status","success");r.put("occurredAt",Instant.now().toString());r.put("complete",true);return r;}
    @Test void payloadIsEncryptedRedactedAndAbsentFromList(){
        records.save(row("wb_1"),Map.of("request",Map.of("prompt","private prompt sk-abcdefghijklmnop","api_key","secret123"),"response",Map.of("text","private answer")));
        var stored=records.find("wb_1");assertThat(stored.get("encryptedPayload").toString()).startsWith("enc:v1:").doesNotContain("private prompt","private answer","secret123");
        assertThat(records.payload(stored).toString()).contains("private prompt","[REDACTED]").doesNotContain("secret123","sk-abcdefghijklmnop");
        assertThat(records.list(Map.of(),1,20).toString()).doesNotContain("encryptedPayload","private prompt","private answer");
    }
    @Test void nativeAndWorkbenchConvergeAndUnknownUsageDoesNotBecomeZero(){
        var row=row("wb_1");records.save(row,Map.of("request","prompt"));
        var nativeRow=row("wb_1");nativeRow.put("origin","relay");nativeRow.put("inputTokens",23L);nativeRow.put("outputTokens",5L);nativeRow.put("sourceUsageId","9");nativeRow.put("requestId","native1");records.save(nativeRow,null);
        assertThat(records.find("wb_1").get("cacheTokens")).isNull();assertThat(records.find("wb_1").get("inputTokens")).isEqualTo(23L);
        records.save(row,Map.of("request","prompt","response","answer"));
        assertThat(records.find("wb_1").get("inputTokens")).isEqualTo(23L);assertThat(records.list(Map.of(),1,20).get("total")).isEqualTo(1L);
    }
    @Test void filtersPaginationAndExpiredBodies(){
        for(int i=0;i<3;i++){var r=row("call"+i);r.put("channel",i==0?"key":"workbench");records.save(r,Map.of("response","answer"));}
        assertThat(records.list(Map.of("channel","key"),1,20).get("total")).isEqualTo(1L);
        assertThat((List<?>)records.list(Map.of(),2,2).get("items")).hasSize(1);
        var old=row("old");old.put("occurredAt",Instant.now().minus(Duration.ofDays(181)).toString());records.save(old,Map.of("response","old answer"));records.expirePayloads();assertThat(records.find("old").get("encryptedPayload")).isNull();
        assertThatThrownBy(()->records.list(Map.of("from","bad"),1,20)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void captureDoesNotTreatAdapterDefaultZerosAsReportedUsage()throws Exception {
        var permissions=org.mockito.Mockito.mock(com.yiqi.aitoolbench.security.PermissionService.class);
        var filter=new AiUsageCaptureFilter(records,new ObjectMapper(),permissions,org.mockito.Mockito.mock(AiUsageSync.class));
        for(boolean reported:List.of(false,true)) {
            var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/ai/chat/completions");request.setContentType("application/json");request.setContent("{\"model\":\"test\",\"params\":{\"prompt\":\"hello\"}}".getBytes());
            var response=new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(request,response,(req,res)->{
                req.getInputStream().readAllBytes();
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes((jakarta.servlet.http.HttpServletRequest)req));
                try {
                    AiUsageCaptureFilter.annotate(Map.of("actualUsage",reported?Map.of("prompt_tokens",0,"completion_tokens",0):Map.of()));
                    res.setContentType("application/json");res.getOutputStream().write("{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}".getBytes());
                } finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
            });
            var record=records.find(response.getHeader("X-Workbench-Call-ID"));
            if(reported){assertThat(record.get("inputTokens")).isEqualTo(0L);assertThat(record.get("outputTokens")).isEqualTo(0L);}
            else {assertThat(record.get("inputTokens")).isNull();assertThat(record.get("outputTokens")).isNull();}
        }
    }
    @Test void fractionalTimestampFilteringIncludesTheExactSecondBoundary(){
        var exact=row("exact");exact.put("occurredAt","2026-11-09T08:00:00Z");records.save(exact,null);
        var fraction=row("fraction");fraction.put("occurredAt","2026-11-09T08:00:00.100Z");records.save(fraction,null);
        assertThat(records.list(Map.of("from","2026-11-09T08:00:00Z","until","2026-11-09T08:00:00.050Z"),1,20).get("total")).isEqualTo(1L);
    }

}
