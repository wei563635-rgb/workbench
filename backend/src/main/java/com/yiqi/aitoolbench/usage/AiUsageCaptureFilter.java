package com.yiqi.aitoolbench.usage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiqi.aitoolbench.security.PermissionService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Records ingress before MVC permission checks. Response tee preserves flushing and cancellation. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AiUsageCaptureFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE=AiUsageCaptureFilter.class.getName();
    private final AiUsageRepository records;
    private final ObjectMapper json;
    private final PermissionService permissions;
    private final AiUsageSync sync;
    public AiUsageCaptureFilter(AiUsageRepository records,ObjectMapper json,PermissionService permissions,AiUsageSync sync){this.records=records;this.json=json;this.permissions=permissions;this.sync=sync;}
    public static String currentId(){var attrs=RequestContextHolder.getRequestAttributes();return attrs instanceof ServletRequestAttributes a?Objects.toString(a.getRequest().getAttribute(ATTRIBUTE),""):"";}
    public static void annotate(Map<String,Object> values){var attrs=RequestContextHolder.getRequestAttributes();if(attrs instanceof ServletRequestAttributes a){
        var merged=new LinkedHashMap<String,Object>();Object old=a.getRequest().getAttribute(ATTRIBUTE+".facts");if(old instanceof Map<?,?> m)m.forEach((k,v)->merged.put(k.toString(),v));merged.putAll(values);a.getRequest().setAttribute(ATTRIBUTE+".facts",merged);
    }}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !(r.getRequestURI().startsWith("/api/ai/tasks/") || "POST".equals(r.getMethod()) && Set.of("/api/ai/chat/completions","/api/ai/images/generations").contains(r.getRequestURI()));}
    @Override protected void doFilterInternal(HttpServletRequest original,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if ("GET".equals(original.getMethod())) {
            var tee=new TeeResponse(response);chain.doFilter(original,tee);tee.flushWriter();
            if(tee.getStatus()==200)try{var actor=permissions.requireContext(original.getHeader("Authorization"));Object decoded=decode(tee.body.toByteArray());if(decoded instanceof Map<?,?> m)sync.updateTask(original.getRequestURI().substring("/api/ai/tasks/".length()),actor.userId(),json.convertValue(m,new TypeReference<Map<String,Object>>(){}));}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).error("异步 AI 使用记录更新失败");}
            return;
        }
        var request=new ContentCachingRequestWrapper(original,AiUsageRepository.BODY_LIMIT);
        var tee=new TeeResponse(response);String id="wb_"+UUID.randomUUID().toString().replace("-","");
        request.setAttribute(ATTRIBUTE,id);response.setHeader("X-Workbench-Call-ID",id);
        long start=System.nanoTime();var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("requestId",id);row.put("channel","workbench");row.put("origin","workbench");row.put("status","running");row.put("occurredAt",Instant.now().toString());row.put("complete",false);
        try{var actor=permissions.requireContext(request.getHeader("Authorization"));row.put("userId",actor.userId());}catch(RuntimeException ignored){/* Unauthenticated calls have no claimed owner. */}
        safeSave(row,null);
        Throwable failure=null;
        try{chain.doFilter(request,tee);}catch(IOException|ServletException|RuntimeException e){failure=e;throw e;}
        finally {
            tee.flushWriter();
            var input=decode(request.getContentAsByteArray());var output=decode(tee.body.toByteArray());
            if(input instanceof Map<?,?> m){row.put("runId",bounded(m.get("runId"),120));row.put("toolKey",bounded(m.get("toolKey"),100));row.put("model",bounded(m.get("model"),200));}
            Object facts=request.getAttribute(ATTRIBUTE+".facts");if(facts instanceof Map<?,?> m)m.forEach((k,v)->row.put(k.toString(),v));
            int code=failure!=null?500:tee.getStatus();row.put("httpStatus",code);row.put("durationMs",(System.nanoTime()-start)/1_000_000);
            row.put("status",code==401||code==403||code==429?"rejected":code>=400?"failed":"success");
            if(output instanceof Map<?,?> m){
                if(code<400 && m.get("error")!=null)row.put("status","failed");
                if(m.get("usage") instanceof Map<?,?> usage){row.put("inputTokens",number(usage,"prompt_tokens","input_tokens","promptTokens"));row.put("outputTokens",number(usage,"completion_tokens","output_tokens","completionTokens"));}
                if(code<400 && (isPending(m) || ("evolink".equals(row.get("provider")) && m.get("id")!=null && !m.containsKey("data") && !m.containsKey("results")))){
                    row.put("status","running");Object task=m.get("task_id");if(task==null)task=m.get("taskId");if(task==null)task=m.get("id");if(task!=null)sync.bindTask(task.toString(),id,(String)row.get("userId"));
                }
            }
            if(row.get("actualUsage") instanceof Map<?,?> usage){
                row.put("inputTokens",number(usage,"prompt_tokens","input_tokens","input_count"));row.put("outputTokens",number(usage,"completion_tokens","output_tokens","output_count"));
                Long cached=number(usage,"cached_tokens","cache_tokens");
                if(cached==null && usage.get("prompt_tokens_details") instanceof Map<?,?> details)cached=number(details,"cached_tokens");
                if(cached==null && usage.get("input_tokens_details") instanceof Map<?,?> details)cached=number(details,"cached_tokens");
                row.put("cacheTokens",cached);
            }
            if("success".equals(row.get("status")) && "image".equals(row.get("modelType")) && output instanceof Map<?,?> m && m.get("data") instanceof Collection<?> list)row.put("imageCount",list.size());
            row.put("complete",tee.complete && request.getContentAsByteArray().length>0 && request.getContentLengthLong()<=AiUsageRepository.BODY_LIMIT);
            try{enrichDepartment(row);}catch(RuntimeException ignored){/* Missing historical department is explicit. */}
            var payload=new LinkedHashMap<String,Object>();payload.put("request",input);payload.put("response",output);if(row.get("supplierResponse")!=null)payload.put("providerResponse",row.get("supplierResponse"));if(failure!=null)payload.put("error","调用未正常结束");
            if("unknown".equals(row.get("resultStatus")))row.put("status","unknown");
            safeSave(row,payload);
        }
    }
    private void enrichDepartment(Map<String,Object> row){if(row.get("userId")==null)return;var departments=records.jdbc().queryForList("select department_id from sys_user_department where user_id=? and primary_department=true",row.get("userId"));if(!departments.isEmpty())row.put("departmentId",departments.getFirst().get("department_id"));}
    private Object decode(byte[] raw){try{return json.readValue(raw,Object.class);}catch(Exception e){return UsagePayload.clean(new String(raw,java.nio.charset.StandardCharsets.UTF_8));}}
    private void safeSave(Map<String,Object> row,Object payload){try{records.save(row,payload);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("AI 使用记录保存失败（不含请求正文），请检查存储状态");}}
    static Long number(Map<?,?> m,String...keys){for(String k:keys)if(m.get(k) instanceof Number n)return Math.max(0,n.longValue());return null;}
    static boolean isPending(Map<?,?> m){String status=Objects.toString(m.get("status"),"");return Set.of("pending","queued","processing","running","submitted").contains(status.toLowerCase(Locale.ROOT)) || ((m.containsKey("task_id")||m.containsKey("taskId")) && !m.containsKey("results"));}
    private String bounded(Object o,int limit){String s=Objects.toString(o,"");return s.length()<=limit?s:null;}
    private static class TeeResponse extends HttpServletResponseWrapper {
        final ByteArrayOutputStream body=new ByteArrayOutputStream();boolean complete=true;ServletOutputStream stream;PrintWriter writer;
        TeeResponse(HttpServletResponse r){super(r);}
        private void capture(byte[] b,int off,int len){int remaining=AiUsageRepository.BODY_LIMIT-body.size();body.write(b,off,Math.min(remaining,len));if(len>remaining)complete=false;}
        @Override public ServletOutputStream getOutputStream()throws IOException {
            if(stream==null){var delegate=super.getOutputStream();stream=new ServletOutputStream(){public boolean isReady(){return delegate.isReady();}public void setWriteListener(WriteListener l){delegate.setWriteListener(l);}public void write(int b)throws IOException{byte[] a={(byte)b};write(a,0,1);}public void write(byte[] b,int o,int n)throws IOException{capture(b,o,n);delegate.write(b,o,n);}public void flush()throws IOException{delegate.flush();}};}return stream;
        }
        @Override public PrintWriter getWriter()throws IOException{if(writer==null)writer=new PrintWriter(new OutputStreamWriter(getOutputStream(),getCharacterEncoding()),true);return writer;}
        void flushWriter(){if(writer!=null)writer.flush();}
        @Override public void flushBuffer()throws IOException{if(writer!=null)writer.flush();super.flushBuffer();}
    }
}
