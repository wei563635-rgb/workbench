package com.yiqi.aitoolbench.relay;

import com.yiqi.aitoolbench.security.SecretCrypto;
import com.yiqi.aitoolbench.sub2api.Sub2ApiClient;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RelayIdentityService {
    private final JdbcTemplate jdbc;private final RelayRepository repository;private final RelayService relay;
    private final Sub2ApiClient client;private final SecretCrypto crypto;private final long groupId;
    public RelayIdentityService(JdbcTemplate jdbc,RelayRepository repository,RelayService relay,Sub2ApiClient client,SecretCrypto crypto,@Value("${app.relay.group-id:0}")long groupId){
        this.jdbc=jdbc;this.repository=repository;this.relay=relay;this.client=client;this.crypto=crypto;this.groupId=groupId;
        jdbc.execute("create table if not exists relay_key(user_id varchar(80) not null,purpose varchar(20) not null,group_id bigint not null,key_id bigint,encrypted_key text,status varchar(20) not null,primary key(user_id,purpose))");
    }
    @Scheduled(fixedDelayString="${app.relay.sync-delay-ms:60000}",initialDelay=15000)
    public synchronized void provision(){
        if(groupId<=0||!Boolean.TRUE.equals(client.configurationStatus().get("configured")))return;
        try{
            if(!"1".equals(client.workbenchGet("/capabilities").path("contractVersion").asText()))return;
            client.validateRelayGroup(groupId);
            @SuppressWarnings("unchecked") var employees=(List<Map<String,Object>>)relay.configuration().get("employees");
            for(var u:employees){if(!Boolean.TRUE.equals(u.get("enabled"))||!Boolean.TRUE.equals(u.get("configured")))continue;
                String id=u.get("id").toString();if(u.get("relayUserId")==null){long remote=client.ensureWorkbenchUser(id,u.get("name").toString(),((Number)u.get("concurrency")).intValue(),groupId);if(remote<=0)throw new IllegalStateException("中转身份创建未返回有效 ID");
                    repository.saveEmployee(new RelayPolicy.Employee(id,u.get("mode").toString(),((Number)u.get("personalLimit")).longValue(),((Number)u.get("concurrency")).intValue(),remote));}
            }
            relay.synchronize();
            for(var u:employees)if(Boolean.TRUE.equals(u.get("enabled"))&&Boolean.TRUE.equals(u.get("configured")))ensureKey(u.get("id").toString(),"internal");
        }catch(RuntimeException ex){repository.failed();}
    }
    public synchronized String internalCipher(String userId){
        var row=keyRow(userId,"internal");if(row==null||!"active".equals(row.get("status")))throw new IllegalStateException("员工中转身份尚未就绪，请等待配置同步");
        return Objects.toString(row.get("encrypted_key"));
    }
    public synchronized Map<String,Object> status(String userId){
        var row=keyRow(userId,"client");var employee=repository.employeePolicies().get(userId);
        return Map.of("configured",groupId>0,"identityReady",employee!=null&&employee.relayUserId()!=null,"clientKeyStatus",row==null?"absent":row.get("status"));
    }
    public synchronized Map<String,Object> rotateClient(String userId){
        var old=keyRow(userId,"client");var employee=repository.employeePolicies().get(userId);
        if(groupId<=0||employee==null||employee.relayUserId()==null)throw new IllegalStateException("员工中转身份尚未配置");
        if(old!=null&&old.get("key_id")!=null&&"active".equals(old.get("status"))){client.disableWorkbenchKey(employee.relayUserId(),((Number)old.get("key_id")).longValue(),"client");jdbc.update("delete from relay_key where user_id=? and purpose='client'",userId);}
        String key=ensureKey(userId,"client");
        // Client key is returned once. Clear staged ciphertext immediately after activation.
        jdbc.update("update relay_key set encrypted_key=null where user_id=? and purpose='client'",userId);
        return Map.of("key",key,"message","仅展示一次，请妥善保存；旧 Key 已停用");
    }
    public synchronized void revokeClient(String userId){var row=keyRow(userId,"client");var employee=repository.employeePolicies().get(userId);if(row==null)return;if(employee==null||employee.relayUserId()==null)throw new IllegalStateException("员工中转身份缺失");
        if(row.get("key_id")!=null)client.disableWorkbenchKey(employee.relayUserId(),((Number)row.get("key_id")).longValue(),"client");
        jdbc.update("delete from relay_key where user_id=? and purpose='client'",userId);
    }
    private String ensureKey(String id,String purpose){
        var employee=repository.employeePolicies().get(id);if(employee==null||employee.relayUserId()==null)throw new IllegalStateException("中转身份未创建");
        var row=keyRow(id,purpose);String key;
        if(row==null){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);key="sk-wb-"+HexFormat.of().formatHex(entropy);String encrypted=crypto.encrypt(key);
            jdbc.update("insert into relay_key values(?,?,?,null,?,'pending')",id,purpose,groupId,encrypted);
        }else{if("active".equals(row.get("status")))return "internal".equals(purpose)?crypto.decrypt(row.get("encrypted_key").toString()):"";key=crypto.decrypt(row.get("encrypted_key").toString());}
        long remote=client.createWorkbenchKey(employee.relayUserId(),groupId,purpose,key);if(remote<=0)throw new IllegalStateException("中转 Key 创建未确认");
        jdbc.update("update relay_key set key_id=?,status='active' where user_id=? and purpose=?",remote,id,purpose);return key;
    }
    private Map<String,Object> keyRow(String user,String purpose){var rows=jdbc.queryForList("select * from relay_key where user_id=? and purpose=?",user,purpose);return rows.isEmpty()?null:rows.getFirst();}
}
