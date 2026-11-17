package com.yiqi.aitoolbench.relay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RelayRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public RelayRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
        jdbc.execute("create table if not exists relay_department_policy (id varchar(80) primary key, personal_limit bigint not null, pool_limit bigint not null, concurrency int not null)");
        jdbc.execute("create table if not exists relay_employee_policy (id varchar(80) primary key, mode varchar(20) not null, personal_limit bigint not null, concurrency int not null, relay_user_id bigint unique)");
        jdbc.execute("create table if not exists relay_sync_state (id int primary key, revision bigint not null, applied_revision bigint not null, status varchar(30) not null, last_error varchar(500), last_sync varchar(40), snapshot text)");
        if (jdbc.queryForObject("select count(*) from relay_sync_state", Integer.class) == 0)
            jdbc.update("insert into relay_sync_state(id,revision,applied_revision,status) values(1,0,0,'not_configured')");
        jdbc.execute("""
            create table if not exists relay_usage (
              source_id varchar(100) primary key, request_id varchar(150), user_id varchar(80) not null,
              department_id varchar(80), tool_key varchar(100), project_id varchar(100), model varchar(200),
              occurred_at varchar(40) not null, day_key varchar(10) not null, month_key varchar(7) not null,
              input_tokens bigint not null, output_tokens bigint not null, cache_tokens bigint not null,
              image_count bigint not null, duration_ms bigint not null, status varchar(30) not null,
              channel varchar(30) not null)
            """);
        jdbc.execute("create index if not exists idx_relay_usage_month_user on relay_usage(month_key,user_id)");
        jdbc.execute("alter table relay_usage add column if not exists run_id varchar(120)");
        jdbc.execute("create index if not exists idx_relay_usage_day_department on relay_usage(day_key,department_id)");
        jdbc.execute("create table if not exists relay_alert(id varchar(220) primary key,month_key varchar(7) not null,scope varchar(20) not null,scope_id varchar(80) not null,reason varchar(60) not null,occurred_at varchar(40) not null,active boolean not null)");
    }

    public List<Map<String,Object>> departments() {
        // Organization management owns this table; sys_department is a legacy seed catalogue.
        return jdbc.query("select id,name,parent_id,enabled from organization_department where deleted_at is null order by sort_order,id", (r,n) -> {
            var row = new LinkedHashMap<String,Object>(); row.put("id",r.getString(1)); row.put("name",r.getString(2));
            row.put("parentId",r.getString(3)); row.put("enabled",r.getBoolean(4)); return row;
        });
    }
    public List<Map<String,Object>> employees() {
        return jdbc.query("""
            select u.id,u.display_name,u.enabled,d.department_id
            from sys_user u left join sys_user_department d on d.user_id=u.id and d.primary_department=true
            where u.deleted_at is null
            order by u.display_name,u.id
            """, (r,n) -> {
            var row = new LinkedHashMap<String,Object>(); row.put("id",r.getString(1)); row.put("name",r.getString(2));
            row.put("enabled",r.getBoolean(3)); row.put("primaryDepartmentId",r.getString(4)); return row;
        });
    }
    public Map<String,RelayPolicy.Department> departmentPolicies() {
        var result = new LinkedHashMap<String,RelayPolicy.Department>();
        jdbc.query("select * from relay_department_policy", r -> { var d = new RelayPolicy.Department(r.getString("id"),r.getLong("personal_limit"),r.getLong("pool_limit"),r.getInt("concurrency")); result.put(d.id(),d); });
        return result;
    }
    public Map<String,RelayPolicy.Employee> employeePolicies() {
        var result = new LinkedHashMap<String,RelayPolicy.Employee>();
        jdbc.query("select * from relay_employee_policy", r -> { var u = new RelayPolicy.Employee(r.getString("id"),r.getString("mode"),r.getLong("personal_limit"),r.getInt("concurrency"),(Long)r.getObject("relay_user_id")); result.put(u.id(),u); });
        return result;
    }
    @Transactional
    public void saveDepartment(RelayPolicy.Department d) {
        lock();
        if (jdbc.update("update relay_department_policy set personal_limit=?,pool_limit=?,concurrency=? where id=?",d.personalLimit(),d.poolLimit(),d.concurrency(),d.id())==0)
            jdbc.update("insert into relay_department_policy values(?,?,?,?)",d.id(),d.personalLimit(),d.poolLimit(),d.concurrency());
        dirty();
    }
    @Transactional
    public void saveEmployee(RelayPolicy.Employee u) {
        lock();
        var current=jdbc.queryForList("select relay_user_id from relay_employee_policy where id=?",u.id());
        if(!current.isEmpty()&&current.getFirst().get("relay_user_id")!=null&&!Objects.equals(current.getFirst().get("relay_user_id"),u.relayUserId()))
            throw new IllegalArgumentException("已建立的中转身份不可更换或清空，以免额度与 Key 错配");
        if (jdbc.update("update relay_employee_policy set mode=?,personal_limit=?,concurrency=?,relay_user_id=? where id=?",u.mode(),u.personalLimit(),u.concurrency(),u.relayUserId(),u.id())==0)
            jdbc.update("insert into relay_employee_policy values(?,?,?,?,?)",u.id(),u.mode(),u.personalLimit(),u.concurrency(),u.relayUserId());
        dirty();
    }
    private void lock() { jdbc.queryForObject("select revision from relay_sync_state where id=1 for update",Long.class); }
    private void dirty() { jdbc.update("update relay_sync_state set revision=revision+1,status='pending',last_error=null where id=1"); }
    public Map<String,Object> syncState() {
        return jdbc.queryForObject("select * from relay_sync_state where id=1", (r,n) -> {
            var row=new LinkedHashMap<String,Object>(); row.put("revision",r.getLong("revision")); row.put("appliedRevision",r.getLong("applied_revision"));
            row.put("status",r.getString("status")); row.put("lastError",r.getString("last_error")); row.put("lastSync",r.getString("last_sync")); return row;
        });
    }
    /** 组织变更也生成新快照；仅在内容变化时递增，不依赖管理页面触发。 */
    @Transactional
    public long stageSnapshot(String snapshot) {
        lock();
        String previous=jdbc.queryForObject("select snapshot from relay_sync_state where id=1",String.class);
        if (!Objects.equals(previous,snapshot)) { jdbc.update("update relay_sync_state set snapshot=? where id=1",snapshot); dirty(); }
        return jdbc.queryForObject("select revision from relay_sync_state where id=1",Long.class);
    }
    public void applied(long revision) {
        jdbc.update("update relay_sync_state set applied_revision=?,status=case when revision=? then 'applied' else 'pending' end,last_error=null where id=1",revision,revision);
    }
    public void failed() { jdbc.update("update relay_sync_state set status='failed',last_error='中转同步失败，请检查连接及扩展版本后重试' where id=1"); }
    public void synced() { jdbc.update("update relay_sync_state set last_sync=? where id=1",Instant.now().toString()); }

    @Transactional
    public void ingest(List<Map<String,Object>> records) {
        // 序列化同步任务，唯一来源 ID 防止重叠时间窗口重复计量。
        lock();
        for (var row:records) {
            String id=Objects.toString(row.get("sourceId"),"");
            if(id.isBlank()) throw new IllegalArgumentException("用量缺少来源 ID");
            String runId=null,toolKey=null,projectId=null;
            if("internal".equals(row.get("channel"))&&row.get("runId")!=null){
                var runs=jdbc.queryForList("select run_id,tool_key,project_id from tool_run where run_id=? and user_id=?",row.get("runId"),row.get("userId"));
                if(!runs.isEmpty()){runId=(String)runs.getFirst().get("run_id");toolKey=(String)runs.getFirst().get("tool_key");projectId=(String)runs.getFirst().get("project_id");}
            }
            if(jdbc.queryForObject("select count(*) from relay_usage where source_id=?",Integer.class,id)>0) {
                if(runId!=null)jdbc.update("update relay_usage set run_id=?,tool_key=?,project_id=?,channel='internal' where source_id=?",runId,toolKey,projectId,id);
                continue;
            }
            Instant when=Instant.parse(Objects.toString(row.get("occurredAt")));
            var day=when.atZone(RelayPolicy.ZONE).toLocalDate();
            jdbc.update("insert into relay_usage values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,row.get("requestId"),row.get("userId"),row.get("departmentId"),toolKey,projectId,row.get("model"),when.toString(),day.toString(),RelayPolicy.month(when),number(row,"inputTokens"),number(row,"outputTokens"),number(row,"cacheTokens"),number(row,"imageCount"),number(row,"durationMs"),Objects.toString(row.get("status"),"unknown"),Objects.toString(row.get("channel"),"client"),runId);
        }
    }
    public Map<String,Long> monthlyUsage(String month) {
        var result=new HashMap<String,Long>();
        jdbc.query("select user_id,sum(input_tokens+output_tokens+cache_tokens) from relay_usage where month_key=? group by user_id",r->{ result.put(r.getString(1),r.getLong(2)); },month); return result;
    }
    /** 数据库聚合及分页，内存随员工/分组数量增长，不随调用明细数量增长。 */
    public Map<String,Object> reportUsage(String from,String to,String user,String department,String mode,int page,int size) {
        return reportUsage(from,to,user,department,mode,page,size,null,null);
    }
    public Map<String,Object> reportUsage(String from,String to,String user,String department,String mode,int page,int size,String search,String tool) {
        var args=new ArrayList<Object>(List.of(from,to));
        var where=new StringBuilder(" from relay_usage u join sys_user s on s.id=u.user_id left join relay_employee_policy p on p.id=u.user_id where u.day_key>=? and u.day_key<=?");
        if(user!=null&&!user.isBlank()){where.append(" and u.user_id=?");args.add(user);}
        if(department!=null&&!department.isBlank()){where.append(" and u.department_id=?");args.add(department);}
        if(mode!=null&&!mode.isBlank()){where.append(" and coalesce(p.mode,'inherited')=?");args.add(mode);}
        String token="u.input_tokens+u.output_tokens+u.cache_tokens";
        var out=new LinkedHashMap<String,Object>();
        var totals=usageTotals(where.toString(),args);out.put("totals",totals);
        var start=java.time.LocalDate.parse(from);var end=java.time.LocalDate.parse(to);
        long days=java.time.temporal.ChronoUnit.DAYS.between(start,end)+1;
        var previousArgs=new ArrayList<Object>(args);previousArgs.set(0,start.minusDays(days).toString());previousArgs.set(1,start.minusDays(1).toString());
        out.put("previousTotals",usageTotals(where.toString(),previousArgs));
        totals.put("peakRequestsPerMinute",jdbc.queryForObject("select coalesce(max(n),0) from (select count(*) as n"+where+" group by substring(u.occurred_at,1,16)) grouped_minutes",Long.class,args.toArray()));
        for(var group:Map.of("daily","day_key","departments","department_id","tools","tool_key","models","model").entrySet()){
            out.put(group.getKey(),jdbc.query("select u."+group.getValue()+",sum("+token+"),count(*),count(distinct u.user_id)"+where+" group by u."+group.getValue()+" order by u."+group.getValue(),(r,n)->Map.<String,Object>of("key",Objects.toString(r.getString(1),"未关联"),"tokens",r.getLong(2),"requests",r.getLong(3),"activeUsers",r.getLong(4)),args.toArray()));
        }
        // occurred_at is normalized to Instant (UTC) at ingestion; reports use Asia/Shanghai.
        String hour="mod(cast(substring(u.occurred_at,12,2) as integer)+8,24)";
        out.put("hourly",jdbc.query("select "+hour+",sum("+token+")"+where+" group by "+hour+" order by "+hour,
                (r,n)->Map.<String,Object>of("key",r.getInt(1),"tokens",r.getLong(2)),args.toArray()));
        out.put("flows",jdbc.query("select u.department_id,u.tool_key,u.model,sum("+token+")"+where+" group by u.department_id,u.tool_key,u.model order by sum("+token+") desc",(r,n)->Map.<String,Object>of("department",Objects.toString(r.getString(1),"未分配"),"tool",Objects.toString(r.getString(2),"客户端调用"),"model",Objects.toString(r.getString(3),"未知模型"),"tokens",r.getLong(4)),args.toArray()));
        var users=new HashMap<String,Map<String,Object>>();
        jdbc.query("select u.user_id,sum("+token+"),count(*),sum(u.image_count),max(u.occurred_at)"+where+" group by u.user_id",r->{users.put(r.getString(1),Map.of("totalTokens",r.getLong(2),"requests",r.getLong(3),"imageCount",r.getLong(4),"lastActive",r.getString(5)));},args.toArray());
        out.put("users",users);
        if(tool!=null&&!tool.isBlank()){where.append(" and u.tool_key=?");args.add(tool);}
        if(search!=null&&!search.isBlank()){
            where.append(" and (lower(coalesce(u.tool_key,'')) like ? or exists(select 1 from tool_run tr where tr.run_id=u.run_id and tr.user_id=u.user_id and lower(coalesce(tr.input_summary,'')) like ?))");
            String term="%"+search.trim().toLowerCase(Locale.ROOT)+"%";args.add(term);args.add(term);
        }
        out.put("logTotal",jdbc.queryForObject("select count(*)"+where,Long.class,args.toArray()));
        args.add(size);args.add((long)(page-1)*size);
        var records=jdbc.query("select u.*"+where+" order by u.occurred_at desc,u.source_id desc limit ? offset ?",(r,n)->usageRow(r),args.toArray());
        // Only the already-authorized user's linked business records may contribute summaries.
        for(var record:records)if(record.get("runId")!=null){
            var linked=jdbc.queryForList("select input_summary,status from tool_run where run_id=? and user_id=?",record.get("runId"),record.get("userId"));
            if(!linked.isEmpty()){record.put("promptSummary",linked.getFirst().get("input_summary"));record.put("status",linked.getFirst().get("status"));}
        }
        out.put("logs",records);
        return out;
    }
    /** Two grouped queries serve any selected employee count; memory is O(selected users × active days and tools). */
    public Map<String,Map<String,Object>> compareUsage(String from,String to,List<String> userIds) {
        var result=new LinkedHashMap<String,Map<String,Object>>();
        for(String id:userIds){var row=new LinkedHashMap<String,Object>();row.put("daily",new ArrayList<Map<String,Object>>());row.put("tools",new ArrayList<Map<String,Object>>());result.put(id,row);}
        String marks=String.join(",",Collections.nCopies(userIds.size(),"?"));
        var args=new ArrayList<Object>();args.add(from);args.add(to);args.addAll(userIds);
        String where=" where day_key>=? and day_key<=? and user_id in ("+marks+")";
        String token="input_tokens+output_tokens+cache_tokens";
        jdbc.query("select user_id,day_key,sum("+token+"),sum(image_count),count(*),sum(input_tokens),sum(output_tokens) from relay_usage"+where+" group by user_id,day_key order by day_key",r->{
            @SuppressWarnings("unchecked") var daily=(List<Map<String,Object>>)result.get(r.getString(1)).get("daily");
            daily.add(Map.of("key",r.getString(2),"tokens",r.getLong(3),"images",r.getLong(4),"requests",r.getLong(5),"inputTokens",r.getLong(6),"outputTokens",r.getLong(7)));
        },args.toArray());
        jdbc.query("select user_id,coalesce(tool_key,'客户端调用'),sum("+token+") from relay_usage"+where+" group by user_id,tool_key order by user_id,tool_key",r->{
            @SuppressWarnings("unchecked") var tools=(List<Map<String,Object>>)result.get(r.getString(1)).get("tools");
            tools.add(Map.of("key",r.getString(2),"tokens",r.getLong(3)));
        },args.toArray());
        return result;
    }
    private Map<String,Object> usageTotals(String where,List<Object> args) {
        return jdbc.queryForObject("select count(*),count(distinct u.user_id),coalesce(sum(u.input_tokens+u.output_tokens+u.cache_tokens),0),coalesce(sum(u.input_tokens),0),coalesce(sum(u.output_tokens),0),coalesce(sum(u.cache_tokens),0),coalesce(sum(u.image_count),0)"+where,(r,n)->{
            var value=new LinkedHashMap<String,Object>();int i=1;
            for(String f:List.of("requests","activeUsers","totalTokens","inputTokens","outputTokens","cacheTokens","imageCount"))value.put(f,r.getLong(i++));return value;
        },args.toArray());
    }
    private Map<String,Object> usageRow(java.sql.ResultSet r) throws java.sql.SQLException {
        var row=new LinkedHashMap<String,Object>();
        for(String field:List.of("source_id","request_id","user_id","department_id","tool_key","project_id","model","occurred_at","day_key","status","channel","run_id"))row.put(camel(field),r.getString(field));
        for(String field:List.of("input_tokens","output_tokens","cache_tokens","image_count","duration_ms"))row.put(camel(field),r.getLong(field));
        row.put("totalTokens",r.getLong("input_tokens")+r.getLong("output_tokens")+r.getLong("cache_tokens"));return row;
    }
    public List<Map<String,Object>> usage(String from,String to,String user) {
        String filter=user==null?"":" and user_id=?";
        Object[] args=user==null?new Object[]{from,to}:new Object[]{from,to,user};
        return jdbc.query("select * from relay_usage where day_key>=? and day_key<=?"+filter+" order by occurred_at desc,source_id desc",(r,n)->{
            var row=new LinkedHashMap<String,Object>();
            for(String field:List.of("source_id","request_id","user_id","department_id","tool_key","project_id","model","occurred_at","day_key","status","channel","run_id")) row.put(camel(field),r.getString(field));
            for(String field:List.of("input_tokens","output_tokens","cache_tokens","image_count","duration_ms")) row.put(camel(field),r.getLong(field));
            row.put("totalTokens",r.getLong("input_tokens")+r.getLong("output_tokens")+r.getLong("cache_tokens"));return row;
        },args);
    }
    public String encode(Object value) { try {return json.writeValueAsString(value);} catch(Exception ex) {throw new IllegalArgumentException("配额数据无法序列化",ex);} }
    @Transactional
    public void reconcileAlerts(List<Map<String,Object>> employees,List<Map<String,Object>> departments,String month){
        lock();jdbc.update("update relay_alert set active=false where active=true");
        for(var u:employees)if(Boolean.TRUE.equals(u.get("configured"))&&number(u,"used")>=number(u,"personalLimit"))alert(month,"employee",u.get("id").toString(),"PERSONAL_QUOTA_EXHAUSTED");
        for(var d:departments)if(Boolean.TRUE.equals(d.get("configured"))&&number(d,"used")>=number(d,"poolLimit"))alert(month,"department",d.get("id").toString(),"DEPARTMENT_QUOTA_EXHAUSTED");
    }
    private void alert(String month,String scope,String id,String reason){
        String key=month+":"+scope+":"+id;
        if(jdbc.update("update relay_alert set active=true where id=?",key)==0)jdbc.update("insert into relay_alert values(?,?,?,?,?,?,true)",key,month,scope,id,reason,Instant.now().toString());
    }
    public List<Map<String,Object>> alerts(String user,String department){
        var args=new ArrayList<Object>();String filter="";
        if(user!=null){filter=" and ((scope='employee' and scope_id=?) or (scope='department' and scope_id=?))";args.add(user);args.add(department==null?"":department);}
        return jdbc.query("select scope,scope_id,reason,occurred_at from relay_alert where active=true"+filter+" order by occurred_at desc limit 100",(r,n)->Map.<String,Object>of("scope",r.getString(1),"scopeId",r.getString(2),"reason",r.getString(3),"occurredAt",r.getString(4)),args.toArray());
    }
    public static long number(Map<String,Object> row,String key) { Object v=row.get(key); long n=v instanceof Number x?x.longValue():0; if(n<0) throw new IllegalArgumentException("用量不能为负数");return n; }
    private static String camel(String s) { var parts=s.split("_"); var out=new StringBuilder(parts[0]); for(int i=1;i<parts.length;i++) out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));return out.toString(); }
}
