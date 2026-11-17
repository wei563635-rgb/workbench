package com.yiqi.aitoolbench.relay;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RelayServiceTest {
    JdbcTemplate jdbc;RelayRepository repository;RelayService service;
    @BeforeEach void setup(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:relay"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table organization_department(id varchar primary key,name varchar,parent_id varchar,enabled boolean,sort_order int,deleted_at varchar)");
        jdbc.execute("create table sys_user(id varchar primary key,display_name varchar,enabled boolean,deleted_at varchar)");
        jdbc.execute("create table sys_user_department(user_id varchar,department_id varchar,primary_department boolean)");
        jdbc.update("insert into organization_department values('a','部门A',null,true,1,null),('b','部门B',null,true,2,null),('child','小组','a',true,3,null)");
        jdbc.update("insert into sys_user values('u','张三',true,null),('v','李四',true,null)");
        jdbc.update("insert into sys_user_department values('u','child',true),('v','a',true)");
        repository=new RelayRepository(jdbc,new ObjectMapper());service=new RelayService(repository,mock(Sub2ApiClient.class),mock(com.yiqi.aitoolbench.LogRepository.class));
        repository.saveDepartment(new RelayPolicy.Department("a",50,100,2));repository.saveDepartment(new RelayPolicy.Department("b",50,100,2));
        repository.ingest(List.of(record("1","u",30),record("2","v",50)));
    }
    Map<String,Object> record(String id,String user,int used){return Map.of("sourceId",id,"userId",user,"departmentId","a","occurredAt",Instant.now().toString(),"inputTokens",used,"outputTokens",0);}
    @SuppressWarnings("unchecked") long pool(String id){return ((List<Map<String,Object>>)service.configuration().get("departments")).stream().filter(d->id.equals(d.get("id"))).mapToLong(d->((Number)d.get("used")).longValue()).findFirst().orElseThrow();}
    @Test void switchingPolicyRetroactivelyChangesPoolWithoutChangingReports(){
        assertThat(pool("a")).isEqualTo(80);
        service.saveEmployee(new RelayPolicy.Employee("u","independent",200,3,1L));assertThat(pool("a")).isEqualTo(50);
        assertThat(((Map<?,?>)service.report(null,null,null,null,null,"tokens",1,20).get("totals")).get("totalTokens")).isEqualTo(80L);
        service.saveEmployee(new RelayPolicy.Employee("u","inherited",0,1,1L));assertThat(pool("a")).isEqualTo(80);
    }
    @Test void departmentTransferMovesQuotaButKeepsHistoricalConsumption(){
        jdbc.update("update sys_user_department set department_id='b' where user_id='u'");
        assertThat(pool("a")).isEqualTo(50);assertThat(pool("b")).isEqualTo(30);
        assertThat(((Map<?,?>)service.report(null,null,null,"a",null,"tokens",1,20).get("totals")).get("totalTokens")).isEqualTo(80L);
    }
    @Test void duplicateUsageIsIdempotentAndOwnReportDoesNotLeakOtherUsers(){
        repository.ingest(List.of(record("1","u",30)));
        var report=service.report(null,null,"u",null,null,"tokens",1,20);
        assertThat(((Map<?,?>)report.get("totals")).get("totalTokens")).isEqualTo(30L);
        assertThat((List<?>)report.get("employees")).hasSize(1);
    }
    @Test void deletedUserDisappearsFromQuotaListButHistoricalUsageRemains(){
        jdbc.update("update sys_user set enabled=false,deleted_at=? where id='u'",Instant.now().toString());
        var config=service.configuration();
        assertThat((List<?>)config.get("employees")).hasSize(1);
        assertThat(((List<Map<String,Object>>)config.get("employees")).getFirst().get("id")).isEqualTo("v");
        assertThat(((Map<?,?>)service.report(null,null,null,null,null,"tokens",1,20).get("totals")).get("totalTokens")).isEqualTo(80L);
    }
    @Test void cannotConfigureChildOrApplyUnacknowledgedRevision(){
        assertThatThrownBy(()->service.saveDepartment(new RelayPolicy.Department("child",10,20,1))).isInstanceOf(IllegalArgumentException.class);
        repository.applied(0);assertThat(repository.syncState().get("status")).isEqualTo("pending");
    }
    @Test void paginationPreservesFullTotalsAndModeFilter(){
        var page1=service.report(null,null,null,null,null,"tokens",1,1);
        var page2=service.report(null,null,null,null,null,"tokens",2,1);
        assertThat((List<?>)page1.get("logs")).hasSize(1);
        assertThat((List<?>)page2.get("logs")).hasSize(1);
        assertThat(page1.get("totals")).isEqualTo(page2.get("totals"));
        assertThat(page1.get("logTotal")).isEqualTo(2L);
        service.saveEmployee(new RelayPolicy.Employee("u","independent",200,1,1L));
        var independent=service.report(null,null,null,null,"independent","tokens",1,20);
        assertThat(((Map<?,?>)independent.get("totals")).get("totalTokens")).isEqualTo(30L);
        assertThatThrownBy(()->service.saveEmployee(new RelayPolicy.Employee("u","independent",200,1,2L))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unconfiguredAndUnassignedEmployeesRemainVisibleWithoutInventedLimits(){
        jdbc.update("delete from relay_department_policy");
        jdbc.update("delete from sys_user_department where user_id='u'");
        var result=service.report(null,null,"u",null,null,"tokens",1,20);
        var quota=(Map<?,?>)result.get("quota");
        assertThat(quota.get("configured")).isEqualTo(false);
        assertThat(quota.get("personalLimit")).isNull();
        assertThat(quota.get("concurrency")).isNull();
    }
    @Test void invalidDateIsRejectedAsAnInputError(){
        assertThatThrownBy(()->service.report("2026-02-30",null,null,null,null,"tokens",1,20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YYYY-MM-DD");
    }
    @Test void deletedOrganizationDepartmentsCannotReceiveNewPolicies(){
        jdbc.update("update organization_department set deleted_at=? where id='b'",Instant.now().toString());
        assertThat(repository.departments()).noneMatch(d->"b".equals(d.get("id")));
        assertThatThrownBy(()->service.saveDepartment(new RelayPolicy.Department("b",50,100,1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @SuppressWarnings("unchecked")
    @Test void exhaustionAlertsAreDeduplicatedScopedAndResolved(){
        var config=service.configuration();
        var employees=(List<Map<String,Object>>)config.get("employees");
        var departments=(List<Map<String,Object>>)config.get("departments");
        String month=config.get("month").toString();
        repository.reconcileAlerts(employees,departments,month);
        repository.reconcileAlerts(employees,departments,month);
        assertThat(jdbc.queryForObject("select count(*) from relay_alert",Integer.class)).isEqualTo(1);
        assertThat(repository.alerts("u","a")).isEmpty();
        assertThat(repository.alerts("v","a")).hasSize(1);
        service.saveEmployee(new RelayPolicy.Employee("v","independent",200,1,2L));
        config=service.configuration();
        repository.reconcileAlerts((List<Map<String,Object>>)config.get("employees"),(List<Map<String,Object>>)config.get("departments"),month);
        assertThat(repository.alerts(null,null)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from relay_alert",Integer.class)).isEqualTo(1);
    }
    @Test void hourlyConsumptionUsesChinaTimezoneAcrossUtcDateBoundary() {
        repository.ingest(List.of(Map.of("sourceId","midnight","userId","u","departmentId","a","occurredAt","2026-09-27T16:05:00Z","inputTokens",17)));
        var report=service.report("2026-09-28","2026-09-28","u",null,null,"tokens",1,20);
        var hourly=(List<Map<String,Object>>)report.get("hourly");
        assertThat(hourly.stream().filter(h->Integer.valueOf(0).equals(h.get("key"))).mapToLong(h->((Number)h.get("tokens")).longValue()).sum()).isGreaterThanOrEqualTo(17);
        var other=service.report("2026-09-28","2026-09-28","v",null,null,"tokens",1,20);
        assertThat(other.get("logs").toString()).doesNotContain("midnight");
    }

    @Test void logSearchKeepsTotalsAndCannotReadAnotherEmployeesPrompt() {
        jdbc.execute("create table tool_run(run_id varchar primary key,user_id varchar,tool_key varchar,project_id varchar,input_summary varchar,status varchar)");
        jdbc.update("insert into tool_run values('run-u','u','market',null,'新品分析','success'),('run-v','v','market',null,'新品分析同事私有','success')");
        repository.ingest(List.of(Map.of("sourceId","log-u","userId","u","departmentId","a","occurredAt",Instant.now().toString(),"inputTokens",10,"channel","internal","runId","run-u"),Map.of("sourceId","log-v","userId","v","departmentId","a","occurredAt",Instant.now().toString(),"inputTokens",20,"channel","internal","runId","run-v")));
        var report=service.report(null,null,"u",null,null,"tokens",1,20,"新品","market");
        assertThat(report.get("logTotal")).isEqualTo(1L);
        assertThat(report.get("logs").toString()).contains("新品分析").doesNotContain("同事私有");
        assertThat(((Map<?,?>)report.get("totals")).get("totalTokens")).isEqualTo(40L);
    }

    @Test void comparisonUsesPreviousEqualLengthPeriodAndMinutePeak() {
        repository.ingest(List.of(Map.of("sourceId","prev","userId","u","occurredAt","2020-01-01T00:00:01Z","inputTokens",10),Map.of("sourceId","now1","userId","u","occurredAt","2020-01-02T00:00:01Z","inputTokens",20),Map.of("sourceId","now2","userId","u","occurredAt","2020-01-02T00:00:59Z","inputTokens",30)));
        var report=service.report("2020-01-02","2020-01-02","u",null,null,"tokens",1,20);
        assertThat(((Map<?,?>)report.get("previousTotals")).get("totalTokens")).isEqualTo(10L);
        assertThat(((Map<?,?>)report.get("totals")).get("peakRequestsPerMinute")).isEqualTo(2L);
    }
    @SuppressWarnings("unchecked")
    @Test void employeeComparisonUsesSameDatesAndOnlySelectedUsers() {
        var result=service.compare(null,null,List.of("u","v"));
        var rows=(List<Map<String,Object>>)result.get("employees");
        assertThat(rows).extracting(row->row.get("id")).containsExactly("u","v");
        assertThat(rows).extracting(row->row.get("totalTokens")).containsExactly(30L,50L);
        assertThat(((List<?>)rows.getFirst().get("daily"))).hasSize(1);
        assertThat(((List<?>)rows.getFirst().get("tools"))).hasSize(1);
        assertThatThrownBy(()->service.compare(null,null,List.of("u","u"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.compare(null,null,List.of("1","2","3","4","5","6","7","8","9"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("8");
        assertThatThrownBy(()->service.compare(null,null,List.of("u","absent"))).isInstanceOf(IllegalArgumentException.class);
    }
    @SuppressWarnings("unchecked")
    @Test void employeeNameFilterAppliesBeforePagination() {
        var result=service.report(null,null,null,null,null,"tokens",1,1,null,null,"李");
        assertThat(result.get("employeeTotal")).isEqualTo(1);
        assertThat(((List<Map<String,Object>>)result.get("employees")).getFirst().get("name")).isEqualTo("李四");
    }

}
