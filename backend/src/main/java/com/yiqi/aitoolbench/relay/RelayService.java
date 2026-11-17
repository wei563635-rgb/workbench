package com.yiqi.aitoolbench.relay;

import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import com.yiqi.aitoolbench.LogRepository;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RelayService {
    private final RelayRepository repository;
    private final Sub2ApiClient client;
    private final LogRepository logs;
    public RelayService(RelayRepository repository,Sub2ApiClient client,LogRepository logs) { this.repository=repository;this.client=client;this.logs=logs; }

    public Map<String,Object> poolSummary(){return client.accountSummary();}

    /** O(员工数 × 部门深度)，防止循环组织结构使限额继承无限遍历。 */
    public static String rootDepartment(String id,Map<String,Map<String,Object>> departments) {
        var visited=new HashSet<String>();
        while(id!=null&&!id.isBlank()) {
            if(!visited.add(id)||!departments.containsKey(id)) return null;
            String parent=(String)departments.get(id).get("parentId");
            if(parent==null||parent.isBlank()) return id;
            id=parent;
        }
        return null;
    }
    public Map<String,Object> configuration() {
        var departments=repository.departments();
        var byId=new LinkedHashMap<String,Map<String,Object>>(); departments.forEach(d->byId.put((String)d.get("id"),d));
        var dp=repository.departmentPolicies();var ep=repository.employeePolicies();
        var usage=repository.monthlyUsage(RelayPolicy.month(Instant.now()));
        var employees=new ArrayList<Map<String,Object>>();var pools=new HashMap<String,Long>();
        for(var employee:repository.employees()) {
            String id=(String)employee.get("id");String root=rootDepartment((String)employee.get("primaryDepartmentId"),byId);
            var policy=ep.get(id);var department=dp.get(root);boolean independent=policy!=null&&policy.mode().equals("independent");
            long used=usage.getOrDefault(id,0L);
            var row=new LinkedHashMap<String,Object>(employee);
            row.put("departmentId",root);row.put("departmentName",root==null?"未分配一级部门":byId.get(root).get("name"));
            row.put("mode",independent?"independent":"inherited");
            Long personalLimit=department==null?null:department.personalLimit();
            Integer concurrency=department==null?null:department.concurrency();
            if(independent){personalLimit=policy.personalLimit();concurrency=policy.concurrency();}
            row.put("personalLimit",personalLimit);row.put("concurrency",concurrency);
            row.put("relayUserId",policy==null?null:policy.relayUserId());row.put("used",used);
            row.put("configured",independent||department!=null);
            employees.add(row);if(!independent&&root!=null) pools.merge(root,used,Long::sum);
        }
        var roots=new ArrayList<Map<String,Object>>();
        for(var d:departments) {
            if(d.get("parentId")!=null&&!d.get("parentId").toString().isBlank())continue;
            String id=d.get("id").toString();var policy=dp.get(id);var row=new LinkedHashMap<String,Object>(d);
            row.put("personalLimit",policy==null?null:policy.personalLimit());row.put("poolLimit",policy==null?null:policy.poolLimit());row.put("concurrency",policy==null?null:policy.concurrency());
            row.put("used",pools.getOrDefault(id,0L));row.put("configured",policy!=null);
            row.put("memberCount",employees.stream().filter(u->id.equals(u.get("departmentId"))).count());
            row.put("independentCount",employees.stream().filter(u->id.equals(u.get("departmentId"))&&"independent".equals(u.get("mode"))).count());roots.add(row);
        }
        for(var row:employees) {
            String root=(String)row.get("departmentId");var department=dp.get(root);
            if(Boolean.TRUE.equals(row.get("configured"))) row.put("decision",RelayPolicy.evaluate("independent".equals(row.get("mode")),((Number)row.get("personalLimit")).longValue(),((Number)row.get("used")).longValue(),department==null?null:department.poolLimit(),pools.getOrDefault(root,0L)));
        }
        return Map.of("departments",roots,"employees",employees,"sync",repository.syncState(),"month",RelayPolicy.month(Instant.now()),"resetsAt",RelayPolicy.nextMonth(Instant.now()).toString());
    }
    public void saveDepartment(RelayPolicy.Department input) {
        boolean allowed=repository.departments().stream().anyMatch(d->input.id().equals(d.get("id"))&&(d.get("parentId")==null||d.get("parentId").toString().isBlank())&&Boolean.TRUE.equals(d.get("enabled")));
        if(!allowed)throw new IllegalArgumentException("仅可配置启用的一级部门");repository.saveDepartment(input);
    }
    public void saveEmployee(RelayPolicy.Employee input) {
        if(repository.employees().stream().noneMatch(u->input.id().equals(u.get("id"))))throw new IllegalArgumentException("员工不存在");
        repository.saveEmployee(input);
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String,Object> synchronize() {
        try {
            var capability=client.workbenchGet("/capabilities");
            if(!"1".equals(capability.path("contractVersion").asText()))throw new IllegalStateException("中转站未安装兼容扩展");
            var config=configuration();
            var employees=(List<Map<String,Object>>)config.get("employees");
            var snapshot=new LinkedHashMap<String,Object>();snapshot.put("departments",config.get("departments"));
            snapshot.put("employees",employees.stream().filter(u->u.get("relayUserId")!=null).map(u->{var v=new LinkedHashMap<String,Object>();for(String field:List.of("id","enabled","departmentId","mode","personalLimit","concurrency","relayUserId","configured"))v.put(field,u.get(field));return v;}).toList());
            // 用量不属于策略内容，避免每次用量变化都导致策略版本递增。
            snapshot.put("departments",((List<Map<String,Object>>)config.get("departments")).stream().map(d->{var v=new LinkedHashMap<String,Object>();for(String f:List.of("id","personalLimit","poolLimit","concurrency","configured"))v.put(f,d.get(f));return v;}).toList());
            long revision=repository.stageSnapshot(repository.encode(snapshot));snapshot.put("revision",revision);
            var result=client.workbenchPut("/policy",snapshot);
            if(result.path("revision").asLong(-1)!=revision)throw new IllegalStateException("中转站策略版本未确认");
            repository.applied(revision);
            // 重叠回查 + 幂等来源 ID；首次与每日对账覆盖全部保留周期。
            var state=repository.syncState();Instant now=Instant.now();
            Instant from=state.get("lastSync")==null?now.minus(Duration.ofDays(180)):Instant.parse(state.get("lastSync").toString()).minus(Duration.ofHours(2));
            pull(from,now);repository.synced();
            if("applied".equals(repository.syncState().get("status"))){
                var refreshed=configuration();repository.reconcileAlerts((List<Map<String,Object>>)refreshed.get("employees"),(List<Map<String,Object>>)refreshed.get("departments"),refreshed.get("month").toString());
            }
            return repository.syncState();
        } catch(RuntimeException ex) {repository.failed();throw ex;}
    }
    private void pull(Instant from,Instant until) {
        long cursor=0;
        for(int page=0;page<10000;page++) {
            var response=client.workbenchGet("/usage?from="+from+"&until="+until+"&after="+cursor);
            var records=new ArrayList<Map<String,Object>>();
            for(var node:response.path("items")) records.add(client.asMap(node));
            repository.ingest(records);
            long next=response.path("nextCursor").asLong(cursor);if(!response.path("hasMore").asBoolean())return;
            if(next<=cursor)throw new IllegalStateException("中转同步游标未前进");cursor=next;
        }
        throw new IllegalStateException("中转同步记录超出单批上限");
    }
    @Scheduled(fixedDelayString="${app.relay.sync-delay-ms:60000}")
    public void scheduledSync() { if(Boolean.TRUE.equals(client.configurationStatus().get("configured")))try{synchronize();}catch(RuntimeException ignored){ /* 状态页保留安全错误；下一周期重试。 */ } }
    @Scheduled(cron="0 20 2 * * *",zone="Asia/Shanghai")
    public synchronized void reconcile() { if(Boolean.TRUE.equals(client.configurationStatus().get("configured")))try{pull(Instant.now().minus(Duration.ofDays(180)),Instant.now());}catch(RuntimeException ignored){repository.failed();} }

    public Map<String,Object> report(String from,String to,String user,String department,String mode,String sort,int page,int size) {
        return report(from,to,user,department,mode,sort,page,size,false,null,null);
    }
    public Map<String,Object> report(String from,String to,String user,String department,String mode,String sort,int page,int size,String search,String tool) {
        return report(from,to,user,department,mode,sort,page,size,false,search,tool);
    }
    public Map<String,Object> report(String from,String to,String user,String department,String mode,String sort,int page,int size,String search,String tool,String employeeName) {
        var result=report(from,to,user,department,mode,sort,page,size,true,search,tool);
        if(employeeName==null||employeeName.isBlank()){
            @SuppressWarnings("unchecked") var all=(List<Map<String,Object>>)result.get("employees");
            result.put("employees",slice(all,Math.max(1,page),Math.max(1,Math.min(100,size))));return result;
        }
        String term=employeeName.trim().toLowerCase(Locale.ROOT);
        @SuppressWarnings("unchecked") var all=(List<Map<String,Object>>)result.get("employees");
        var filtered=all.stream().filter(row->Objects.toString(row.get("name"),"").toLowerCase(Locale.ROOT).contains(term)).toList();
        result.put("employees",slice(filtered,Math.max(1,page),Math.max(1,Math.min(100,size))));result.put("employeeTotal",filtered.size());
        return result;
    }
    @SuppressWarnings("unchecked")
    public String employeeCsv(String from,String to,String department,String mode,String sort){
        var report=report(from,to,null,department,mode,sort,1,1,true,null,null);
        var csv=new StringBuilder("\ufeff员工,当前所属部门,限额类型,所选周期Token,本月已用,个人月限额,调用次数,图片数,最近调用\r\n");
        for(var row:(List<Map<String,Object>>)report.get("employees")){
            csv.append(List.of("name","departmentName","mode","totalTokens","used","personalLimit","requests","imageCount","lastActive").stream().map(k->csvCell(row.get(k))).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return csv.toString();
    }
    public List<Map<String,Object>> compareCandidates(String search) {
        String term=Objects.toString(search,"").trim().toLowerCase(Locale.ROOT);
        @SuppressWarnings("unchecked") var employees=(List<Map<String,Object>>)configuration().get("employees");
        return employees.stream().filter(row->term.isEmpty()||Objects.toString(row.get("name"),"").toLowerCase(Locale.ROOT).contains(term))
            .map(row->Map.<String,Object>of("id",row.get("id"),"name",row.get("name"),"departmentName",Objects.toString(row.get("departmentName"),"")))
            .limit(30).toList();
    }
    public Map<String,Object> compare(String from,String to,List<String> selectedIds) {
        if(selectedIds==null||selectedIds.size()<2||selectedIds.size()>8||selectedIds.stream().anyMatch(id->id==null||id.isBlank())||new HashSet<>(selectedIds).size()!=selectedIds.size())
            throw new IllegalArgumentException("请选择 2 至 8 名不同的员工进行对比");
        LocalDate end,start;
        try {end=to==null?LocalDate.now(RelayPolicy.ZONE):LocalDate.parse(to);start=from==null?end.withDayOfMonth(1):LocalDate.parse(from);}
        catch(DateTimeException ex){throw new IllegalArgumentException("日期须为有效的 YYYY-MM-DD 格式");}
        if(start.isAfter(end)||start.isBefore(end.minusDays(366)))throw new IllegalArgumentException("查询范围须在 367 天内且开始不晚于结束");
        @SuppressWarnings("unchecked") var employees=(List<Map<String,Object>>)configuration().get("employees");
        var directory=new HashMap<String,Map<String,Object>>();for(var row:employees)directory.put(row.get("id").toString(),row);
        if(selectedIds.stream().anyMatch(id->!directory.containsKey(id)))throw new IllegalArgumentException("选择的员工不存在");
        var usage=repository.compareUsage(start.toString(),end.toString(),selectedIds);
        var rows=new ArrayList<Map<String,Object>>();
        for(String id:selectedIds){var person=directory.get(id);var data=usage.get(id);var daily=(List<Map<String,Object>>)data.get("daily");
            long tokens=daily.stream().mapToLong(row->((Number)row.get("tokens")).longValue()).sum();
            long images=daily.stream().mapToLong(row->((Number)row.get("images")).longValue()).sum();
            long requests=daily.stream().mapToLong(row->((Number)row.get("requests")).longValue()).sum();
            long inputTokens=daily.stream().mapToLong(row->((Number)row.get("inputTokens")).longValue()).sum();
            long outputTokens=daily.stream().mapToLong(row->((Number)row.get("outputTokens")).longValue()).sum();
            var row=new LinkedHashMap<String,Object>();for(String key:List.of("id","name","departmentName","mode","personalLimit","used"))row.put(key,person.get(key));
            row.putAll(data);row.put("totalTokens",tokens);row.put("imageCount",images);row.put("requests",requests);row.put("inputTokens",inputTokens);row.put("outputTokens",outputTokens);rows.add(row);
        }
        return Map.of("from",start.toString(),"to",end.toString(),"employees",rows);
    }
    private static String csvCell(Object value){String s=Objects.toString(value,"");if(s.matches("^[=+@\\-\\t\\r].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    private Map<String,Object> report(String from,String to,String user,String department,String mode,String sort,int page,int size,boolean exporting,String search,String tool) {
        LocalDate end, start;
        try {
            end=to==null?LocalDate.now(RelayPolicy.ZONE):LocalDate.parse(to);
            start=from==null?end.withDayOfMonth(1):LocalDate.parse(from);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("日期须为有效的 YYYY-MM-DD 格式");
        }
        if(start.isAfter(end)||start.isBefore(end.minusDays(366)))throw new IllegalArgumentException("查询范围须在 367 天内且开始不晚于结束");
        var config=configuration();
        @SuppressWarnings("unchecked") var employees=(List<Map<String,Object>>)config.get("employees");
        var allowed=new HashSet<String>();var byId=new HashMap<String,Map<String,Object>>();
        for(var employee:employees) {String id=employee.get("id").toString();byId.put(id,employee);if((user==null||user.equals(id))&&(mode==null||mode.isBlank()||mode.equals(employee.get("mode"))))allowed.add(id);}
        int pageSize=Math.max(1,Math.min(100,size));int currentPage=Math.max(1,page);
        var out=repository.reportUsage(start.toString(),end.toString(),user,department,mode,currentPage,pageSize,search,tool);
        @SuppressWarnings("unchecked") var usageByUser=(Map<String,Map<String,Object>>)out.remove("users");
        var rankings=new ArrayList<Map<String,Object>>();
        for(String id:allowed) {
            var own=usageByUser.get(id);var row=new LinkedHashMap<String,Object>(byId.get(id));
            row.putAll(own==null?Map.of("totalTokens",0L,"requests",0L,"imageCount",0L,"lastActive",""):own);
            if(department==null||department.isBlank()||department.equals(row.get("departmentId"))||own!=null)rankings.add(row);
        }
        if("recent".equals(sort))rankings.sort(Comparator.comparing(r->Objects.toString(r.get("lastActive"),""),Comparator.reverseOrder()));
        else rankings.sort(Comparator.comparingLong((Map<String,Object> r)->RelayRepository.number(r,"requests".equals(sort)?"requests":"totalTokens")).reversed().thenComparing(r->r.get("id").toString()));
        out.put("employees",exporting?rankings:slice(rankings,currentPage,pageSize));out.put("employeeTotal",rankings.size());
        out.put("page",currentPage);out.put("sync",config.get("sync"));out.put("from",start.toString());out.put("to",end.toString());out.put("resetsAt",config.get("resetsAt"));
        out.put("departmentDirectory",repository.departments().stream().map(d->Map.of("id",d.get("id"),"name",d.get("name"))).toList());
        var own=user==null?null:byId.get(user);
        // Historical department attribution is available only for relay records.
        if(department==null||department.isBlank())out.put("otherTools",logs.directStats(start.atStartOfDay(RelayPolicy.ZONE).toInstant(),end.plusDays(1).atStartOfDay(RelayPolicy.ZONE).toInstant(),user));
        out.put("alerts",repository.alerts(user,own!=null&&"inherited".equals(own.get("mode"))?(String)own.get("departmentId"):null));
        if(user!=null)out.put("quota",own);return out;
    }
    private static <T> List<T> slice(List<T> rows,int page,int size) {long offset=(long)(page-1)*size;int start=(int)Math.min(offset,rows.size());return rows.subList(start,Math.min(start+size,rows.size()));}
}
