package com.yiqi.aitoolbench.usage;

import com.yiqi.aitoolbench.ApiController.OperationLog;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.security.*;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AiUsageController {
    private final AiUsageRepository records;private final PermissionService permissions;private final LogRepository logs;private final AiUsageSync sync;
    public AiUsageController(AiUsageRepository records,PermissionService permissions,LogRepository logs,AiUsageSync sync){this.sync=sync;this.records=records;this.permissions=permissions;this.logs=logs;}
    private String requireAdmin(HttpServletRequest request){
        var actor=permissions.requireContext(request.getHeader("Authorization"));
        int n=records.jdbc().queryForObject("select count(*) from sys_user_role ur join sys_role r on r.code=ur.role_code where ur.user_id=? and r.code='admin' and r.built_in=true and r.enabled=true",Integer.class,actor.userId());
        if(actor.toolSession() || n==0)throw new ForbiddenException("使用记录仅超级管理员可查看");return actor.userId();
    }
    @GetMapping("/api/admin/ai-usage-records")
    public Object list(@RequestParam Map<String,String> filters,HttpServletRequest request,HttpServletResponse response){requireAdmin(request);response.setHeader("Cache-Control","no-store");return records.list(filters,positive(filters.get("page"),1,100000),positive(filters.get("pageSize"),20,100));}
    @GetMapping("/api/admin/ai-usage-records/{id}")
    public Object detail(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
        String actor=requireAdmin(request);response.setHeader("Cache-Control","no-store");var row=records.find(id);if(row==null)throw new IllegalArgumentException("使用记录不存在");
        logs.insertOperationLog(new OperationLog("op_"+UUID.randomUUID(),actor,"AI_USAGE_DETAIL_READ","查看 AI 使用记录详情",Map.of("recordId",id),Instant.now()));
        var out=new LinkedHashMap<>(row);out.remove("encryptedPayload");
        if(row.get("userId")!=null){var users=records.jdbc().queryForList("select account,display_name from sys_user where id=?",row.get("userId"));if(!users.isEmpty()){out.put("userName",users.getFirst().get("display_name"));out.put("account",users.getFirst().get("account"));}}
        if(row.get("departmentId")!=null){var names=records.jdbc().queryForList("select name from organization_department where id=?",String.class,row.get("departmentId"));if(!names.isEmpty())out.put("departmentName",names.getFirst());}
        out.put("payload",sync.remotePayload(row));return out;
    }
    private static int positive(String value,int fallback,int maximum){try{int n=value==null?fallback:Integer.parseInt(value);if(n<1||n>maximum)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("分页参数无效");}}
}
