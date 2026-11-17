package com.yiqi.aitoolbench.usage;

import java.util.*;
import java.util.regex.Pattern;

/** Shared redaction for new captures and historical payload reads. Never accepts headers. */
public final class UsagePayload {
    private UsagePayload() {}
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-[a-z0-9_-]{8,}|Bearer\\s+[a-z0-9._~+/-]{8,})");
    private static final Set<String> FIELDS = Set.of("apikey", "key", "authorization", "cookie", "setcookie", "password", "secret", "token", "accesstoken", "refreshtoken", "privatekey", "toolsession", "servicecredential");
    @SuppressWarnings("unchecked")
    public static Map<String,Object> cleanMap(Map<String,Object> value){return (Map<String,Object>)clean(value);}
    public static Object clean(Object value) {
        if (value instanceof Map<?,?> map) {
            var out = new LinkedHashMap<String,Object>();
            map.forEach((k,v) -> { String field=k.toString(); out.put(field, FIELDS.contains(field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "")) ? "[REDACTED]" : clean(v)); });
            return out;
        }
        if (value instanceof Collection<?> list) return list.stream().map(UsagePayload::clean).toList();
        if (value instanceof String text) return SECRET.matcher(text).replaceAll("[REDACTED]");
        return value;
    }
}
