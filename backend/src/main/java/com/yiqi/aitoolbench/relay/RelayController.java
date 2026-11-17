package com.yiqi.aitoolbench.relay;

import com.yiqi.aitoolbench.ApiController.OperationLog;
import com.yiqi.aitoolbench.LogRepository;
import com.yiqi.aitoolbench.security.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.*;

@RestController
public class RelayController {
    private final RelayService service;
    private final PermissionService permissions;
    private final LogRepository logs;
    public RelayController(RelayService service,PermissionService permissions,LogRepository logs) {this.service=service;this.permissions=permissions;this.logs=logs;}

    @GetMapping("/api/admin/relay/quotas")
    public Map<String,Object> configuration() {return service.configuration();}

    @PutMapping("/api/admin/relay/quotas/departments/{id}")
    public Map<String,Object> department(@PathVariable String id,@RequestBody RelayPolicy.Department body,HttpServletRequest request) {
        if(!id.equals(body.id()))throw new IllegalArgumentException("部门 ID 不一致");service.saveDepartment(body);
        audit(request,"RELAY_DEPARTMENT_QUOTA",Map.of("departmentId",id,"personalLimit",body.personalLimit(),"poolLimit",body.poolLimit(),"concurrency",body.concurrency()));
        try {service.synchronize();}catch(RuntimeException ignored){}return service.configuration();
    }
    @PutMapping("/api/admin/relay/quotas/employees/{id}")
    public Map<String,Object> employee(@PathVariable String id,@RequestBody RelayPolicy.Employee body,HttpServletRequest request) {
        if(!id.equals(body.id()))throw new IllegalArgumentException("员工 ID 不一致");service.saveEmployee(body);
        audit(request,"RELAY_EMPLOYEE_QUOTA",Map.of("userId",id,"mode",body.mode(),"personalLimit",body.personalLimit(),"concurrency",body.concurrency()));
        try {service.synchronize();}catch(RuntimeException ignored){}return service.configuration();
    }
    @PostMapping("/api/admin/relay/quotas/sync")
    public Map<String,Object> sync(HttpServletRequest request) {audit(request,"RELAY_SYNC",Map.of());return service.synchronize();}

    @GetMapping("/api/admin/relay/usage")
    public Map<String,Object> report(@RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(required=false) String userId,@RequestParam(required=false) String departmentId,
            @RequestParam(required=false) String mode,@RequestParam(defaultValue="tokens") String sort,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String search,@RequestParam(required=false) String tool,@RequestParam(required=false) String employeeName) {
        return service.report(from,to,userId,departmentId,mode,sort,page,pageSize,search,tool,employeeName);
    }
    @GetMapping("/api/admin/relay/usage/pool-summary")
    public Map<String,Object> poolSummary(){return service.poolSummary();}
    @GetMapping("/api/admin/relay/usage/compare/candidates")
    public java.util.List<Map<String,Object>> compareCandidates(@RequestParam(required=false) String search) {return service.compareCandidates(search);}
    @GetMapping("/api/admin/relay/usage/compare")
    public Map<String,Object> compare(@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam java.util.List<String> userIds) {
        return service.compare(from,to,userIds);
    }
    @GetMapping("/api/relay/me")
    public Map<String,Object> mine(@RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String search,@RequestParam(required=false) String tool,HttpServletRequest request) {
        String id=permissions.requireContext(request.getHeader("Authorization")).userId();
        return service.report(from,to,id,null,null,"tokens",page,pageSize,search,tool);
    }
    @GetMapping(value="/api/admin/relay/usage/export",produces="text/csv;charset=UTF-8")
    public org.springframework.http.ResponseEntity<String> export(@RequestParam(required=false)String from,@RequestParam(required=false)String to,
        @RequestParam(required=false)String departmentId,@RequestParam(required=false)String mode,@RequestParam(defaultValue="tokens")String sort,HttpServletRequest request){
        String content=service.employeeCsv(from,to,departmentId,mode,sort);
        audit(request,"RELAY_USAGE_EXPORT",Map.of("report","employee-usage"));
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Disposition","attachment; filename=relay-employee-usage.csv").body(content);
    }
    private void audit(HttpServletRequest request,String action,Map<String,Object> detail) {
        var actor=permissions.requireContext(request.getHeader("Authorization"));
        logs.insertOperationLog(new OperationLog("op_"+UUID.randomUUID(),actor.userId(),action,"中转站配额操作",detail,Instant.now()));
    }
    @Configuration
    @EnableScheduling
    static class Scheduling {}
}
