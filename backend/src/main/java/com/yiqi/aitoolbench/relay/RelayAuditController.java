package com.yiqi.aitoolbench.relay;

import com.yiqi.aitoolbench.ApiController.OperationLog;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.PermissionService;
import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class RelayAuditController {
    private final JdbcTemplate jdbc;private final PermissionService permissions;private final Sub2ApiClient client;private final LogRepository logs;
    public RelayAuditController(JdbcTemplate jdbc,PermissionService permissions,Sub2ApiClient client,LogRepository logs){this.jdbc=jdbc;this.permissions=permissions;this.client=client;this.logs=logs;}
    @GetMapping("/api/admin/relay/audit/{id}")
    public Object read(@PathVariable long id,@RequestParam(defaultValue="false") boolean export,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        var actor=permissions.requireContext(request.getHeader("Authorization"));
        // A grant of generic log/read permissions must not grant raw payload access.
        var count=jdbc.queryForObject("select count(*) from sys_user_role ur join sys_role r on r.code=ur.role_code where ur.user_id=? and r.code='admin' and r.built_in=true and r.enabled=true",Integer.class,actor.userId());
        if(count==null||count==0)throw new ForbiddenException("完整网关审计正文仅超级管理员可查看");
        if(id<=0)throw new IllegalArgumentException("审计记录 ID 无效");
        logs.insertOperationLog(new OperationLog("op_"+UUID.randomUUID(),actor.userId(),export?"RELAY_AUDIT_EXPORT":"RELAY_AUDIT_READ","查看中转网关审计正文",Map.of("sourceId",id),Instant.now()));
        return client.asMap(client.workbenchGet("/usage/"+id+"/audit"));
    }
}
