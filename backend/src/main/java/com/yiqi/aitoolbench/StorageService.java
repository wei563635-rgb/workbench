package com.yiqi.aitoolbench;

import com.yiqi.aitoolbench.file.FileAssetRepository;
import com.yiqi.aitoolbench.file.FileAssetRepository.FileAsset;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorageService {
    private static final String DOWNLOAD_PATH = "/api/files/demo-download/";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_REDIRECTS = 3;

    private final String mode;
    private final Path localRoot;
    private final String publicOrigin;
    private final long maxBytes;
    private final Set<String> allowedMimeTypes;
    private final String ossEndpoint;
    private final String ossBucket;
    private final AddressResolver addressResolver;
    private final RemoteSource remoteSource;
    private final FileAssetRepository fileAssets;
    private final Map<String, FileAsset> testAssetIndex = new ConcurrentHashMap<>();

    @Autowired
    public StorageService(
            @Value("${app.storage.mode:local}") String mode,
            @Value("${app.storage.local-root:target/uploads}") String localRoot,
            @Value("${app.storage.public-origin:http://localhost:8080}") String publicOrigin,
            @Value("${app.storage.max-bytes:20971520}") long maxBytes,
            @Value("${app.storage.allowed-mime-types:image/png,image/jpeg,image/webp,application/pdf,application/octet-stream}") String allowedMimeTypes,
            @Value("${app.storage.remote-timeout-seconds:30}") int remoteTimeoutSeconds,
            @Value("${app.storage.oss.endpoint:}") String ossEndpoint,
            @Value("${app.storage.oss.bucket:}") String ossBucket,
            FileAssetRepository fileAssets) {
        this(mode, localRoot, publicOrigin, maxBytes, allowedMimeTypes, remoteTimeoutSeconds,
                ossEndpoint, ossBucket, InetAddress::getAllByName,
                new PinnedHttpRemoteSource(remoteTimeoutSeconds), fileAssets);
    }

    StorageService(String mode, String localRoot, String publicOrigin, long maxBytes,
                   String allowedMimeTypes, int remoteTimeoutSeconds, String ossEndpoint,
                   String ossBucket, AddressResolver addressResolver, RemoteSource remoteSource) {
        this(mode, localRoot, publicOrigin, maxBytes, allowedMimeTypes, remoteTimeoutSeconds,
                ossEndpoint, ossBucket, addressResolver, remoteSource, null);
    }

    private StorageService(String mode, String localRoot, String publicOrigin, long maxBytes,
                   String allowedMimeTypes, int remoteTimeoutSeconds, String ossEndpoint,
                   String ossBucket, AddressResolver addressResolver, RemoteSource remoteSource,
                   FileAssetRepository fileAssets) {
        this.mode = mode;
        this.localRoot = Path.of(localRoot).toAbsolutePath().normalize();
        this.publicOrigin = normalizePublicOrigin(publicOrigin);
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("文件最大字节数必须大于 0");
        }
        this.maxBytes = maxBytes;
        this.allowedMimeTypes = Arrays.stream(allowedMimeTypes.split(","))
                .map(StorageService::normalizeContentType)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowedMimeTypes.isEmpty()) {
            throw new IllegalArgumentException("至少配置一种允许的文件类型");
        }
        this.ossEndpoint = ossEndpoint;
        this.ossBucket = ossBucket;
        this.addressResolver = addressResolver;
        this.remoteSource = remoteSource;
        this.fileAssets = fileAssets;
    }

    /** O(n) time for n input bytes and O(1) additional memory. */
    public StoredFile save(String runId, String fileName, String contentType, long declaredSize,
                           InputStream inputStream) throws Exception {
        return save(runId, null, "system", fileName, contentType, declaredSize, inputStream);
    }

    public StoredFile save(String runId, String projectId, String createdBy, String fileName,
                           String contentType, long declaredSize, InputStream inputStream) throws Exception {
        validateRunId(runId);
        var safeName = sanitizeFileName(fileName);
        var safeContentType = requireAllowedContentType(contentType);
        validateDeclaredSize(declaredSize);
        return saveStream(runId, projectId, createdBy, safeName, safeContentType, declaredSize, inputStream, true);
    }

    /**
     * Stores a file without applying the general attachment-size limit.
     * Intended only for trusted, permission-protected upload flows such as brand HTML management.
     * O(n) time for n input bytes and O(1) additional memory.
     */
    StoredFile saveWithoutSizeLimit(String runId, String projectId, String createdBy, String fileName,
                                    String contentType, long declaredSize, InputStream inputStream) throws Exception {
        validateRunId(runId);
        var safeName = sanitizeFileName(fileName);
        var safeContentType = requireAllowedContentType(contentType);
        validateDeclaredSizeValue(declaredSize);
        return saveStream(runId, projectId, createdBy, safeName, safeContentType, declaredSize, inputStream, false);
    }

    /** Downloads one response stream at a time: O(n) time and O(1) additional memory. */
    public StoredFile saveRemote(String runId, String sourceUrl, String fileName) throws Exception {
        return saveRemote(runId, null, "system", sourceUrl, fileName);
    }

    public StoredFile saveRemote(String runId, String projectId, String createdBy,
                                 String sourceUrl, String fileName) throws Exception {
        validateRunId(runId);
        var current = parseAndValidateRemoteUri(sourceUrl);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            var validatedAddresses = validateResolvedAddress(current);
            try (var response = remoteSource.open(current, validatedAddresses)) {
                if (isRedirect(response.statusCode())) {
                    if (redirects == MAX_REDIRECTS) {
                        throw new IllegalArgumentException("远端图片重定向次数过多");
                    }
                    var location = firstHeader(response.headers(), "location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalArgumentException("远端图片重定向缺少地址");
                    }
                    current = parseAndValidateRemoteUri(current.resolve(location).toString());
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("远端图片下载失败，HTTP " + response.statusCode());
                }
                var contentType = requireAllowedContentType(firstHeader(response.headers(), "content-type"));
                var contentLength = parseContentLength(firstHeader(response.headers(), "content-length"));
                validateDeclaredSize(contentLength);
                var resolvedName = fileName == null || fileName.isBlank()
                        ? nameFromUri(current) : fileName;
                return saveStream(runId, projectId, createdBy, sanitizeFileName(resolvedName), contentType,
                        contentLength, response.body(), true);
            }
        }
        throw new IllegalStateException("远端图片转存状态异常");
    }

    public StoredDownload load(String fileId) throws Exception {
        if (fileId == null || !fileId.matches("file_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("文件标识不合法");
        }
        var asset = fileAssets == null
                ? java.util.Optional.ofNullable(testAssetIndex.get(fileId))
                : fileAssets.findActiveById(fileId);
        var record = asset.orElseThrow(() -> new IllegalArgumentException("文件不存在"));
        var path = localRoot.resolve(record.storageKey()).normalize();
        if (!path.startsWith(localRoot) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在");
        }
        return new StoredDownload(path, record.fileName(), record.contentType(), record.fileSize());
    }

    public Map<String, Object> metadataOnly(String runId, String fileName, String contentType, long size) {
        throw new IllegalArgumentException("必须上传文件内容或提供远端源地址");
    }

    private StoredFile saveStream(String runId, String projectId, String createdBy,
                                  String safeName, String contentType,
                                  long declaredSize, InputStream inputStream,
                                  boolean enforceSizeLimit) throws Exception {
        if ("oss".equalsIgnoreCase(mode)) {
            ensureOssConfig();
            throw new IllegalStateException("OSS 存储模式已预留，等待接入 SDK 上传");
        }
        var uploadDir = localRoot.resolve(runId).normalize();
        if (!uploadDir.startsWith(localRoot)) {
            throw new IllegalArgumentException("运行标识不合法");
        }
        Files.createDirectories(uploadDir);
        var fileId = newFileId();
        var target = uploadDir.resolve(fileId + "_" + safeName);
        long actualSize = 0;
        var signature = new byte[12];
        var signatureLength = 0;
        var completed = false;
        try (inputStream; OutputStream output = Files.newOutputStream(target)) {
            var buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                actualSize += read;
                if (declaredSize >= 0 && actualSize > declaredSize) {
                    throw new IllegalArgumentException("文件实际大小与声明不一致");
                }
                if (enforceSizeLimit && actualSize > maxBytes) {
                    throw new IllegalArgumentException("文件大小超过限制");
                }
                if (signatureLength < signature.length) {
                    var copied = Math.min(read, signature.length - signatureLength);
                    System.arraycopy(buffer, 0, signature, signatureLength, copied);
                    signatureLength += copied;
                }
                output.write(buffer, 0, read);
            }
            if (declaredSize >= 0 && actualSize != declaredSize) {
                throw new IllegalArgumentException("文件实际大小与声明不一致");
            }
            validateMagicBytes(contentType, signature, signatureLength);
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(target);
            }
        }
        var storageKey = localRoot.relativize(target).toString();
        var asset = new FileAsset(fileId, projectId, runId, storageKey, safeName, contentType,
                actualSize, "public", createdBy == null ? "system" : createdBy, Instant.now());
        try {
            if (fileAssets == null) {
                testAssetIndex.put(fileId, asset);
            } else {
                fileAssets.save(asset);
            }
        } catch (RuntimeException ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
        return new StoredFile(fileId, safeName, contentType, actualSize, target.toString(), downloadUrl(fileId));
    }

    private URI parseAndValidateRemoteUri(String sourceUrl) {
        try {
            var uri = URI.create(sourceUrl == null ? "" : sourceUrl.trim());
            var scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("远端地址只支持无凭证的 HTTP/HTTPS URL");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("远端地址不合法", ex);
        }
    }

    private InetAddress[] validateResolvedAddress(URI uri) {
        var host = uri.getHost();
        if (host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
            throw new IllegalArgumentException("远端地址不能指向本机或内网");
        }
        try {
            var addresses = addressResolver.resolve(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(StorageService::isBlockedAddress)) {
                throw new IllegalArgumentException("远端地址不能指向本机或内网");
            }
            return addresses.clone();
        } catch (IOException ex) {
            throw new IllegalArgumentException("远端地址无法解析", ex);
        }
    }

    private static void validateMagicBytes(String contentType, byte[] bytes, int length) {
        var valid = switch (contentType) {
            case "image/png" -> length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8),
                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/webp" -> length >= 12 && asciiEquals(bytes, 0, "RIFF")
                    && asciiEquals(bytes, 8, "WEBP");
            case "application/pdf" -> length >= 5 && asciiEquals(bytes, 0, "%PDF-");
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("文件内容与声明类型不一致");
        }
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != (byte) expected.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        var bytes = address.getAddress();
        if (bytes.length == 4) {
            var first = Byte.toUnsignedInt(bytes[0]);
            var second = Byte.toUnsignedInt(bytes[1]);
            var third = Byte.toUnsignedInt(bytes[2]);
            return first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113);
        }
        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc
                || (Byte.toUnsignedInt(bytes[0]) == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8));
    }

    private void validateDeclaredSize(long size) {
        validateDeclaredSizeValue(size);
        if (size > maxBytes) {
            throw new IllegalArgumentException("文件大小超过限制");
        }
    }

    private static void validateDeclaredSizeValue(long size) {
        if (size < -1) {
            throw new IllegalArgumentException("文件大小不合法");
        }
    }

    private String requireAllowedContentType(String contentType) {
        var normalized = normalizeContentType(contentType);
        if (normalized.isBlank() || !allowedMimeTypes.contains(normalized)) {
            throw new IllegalArgumentException("文件类型不被允许");
        }
        return normalized;
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeFileName(String fileName) {
        var value = fileName == null || fileName.isBlank() ? "upload.bin" : fileName;
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        value = value.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]+", "_")
                .replaceAll("\\s+", " ").trim();
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            value = "upload.bin";
        }
        if (value.length() > 180) {
            value = value.substring(value.length() - 180);
        }
        return value;
    }

    private static void validateRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,120}")) {
            throw new IllegalArgumentException("运行标识不合法");
        }
    }

    private static String normalizePublicOrigin(String value) {
        try {
            var uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("文件公开 Origin 必须是绝对 HTTP/HTTPS Origin");
            }
            var result = uri.toString();
            return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("文件公开 Origin 配置不合法", ex);
        }
    }

    private static String nameFromUri(URI uri) {
        var path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return "remote-image.bin";
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static long parseContentLength(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("远端文件大小不合法", ex);
        }
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse(null);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private String downloadUrl(String fileId) {
        return publicOrigin + DOWNLOAD_PATH + fileId;
    }

    private static String newFileId() {
        return "file_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void ensureOssConfig() {
        if (ossEndpoint.isBlank() || ossBucket.isBlank()) {
            throw new IllegalStateException("OSS 配置不完整：app.storage.oss.endpoint 和 app.storage.oss.bucket 必填");
        }
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    @FunctionalInterface
    interface RemoteSource {
        RemoteResponse open(URI uri, InetAddress[] validatedAddresses) throws Exception;
    }

    record RemoteResponse(int statusCode, Map<String, List<String>> headers, InputStream body,
                          AutoCloseable closeAction) implements AutoCloseable {
        RemoteResponse(int statusCode, Map<String, List<String>> headers, InputStream body) {
            this(statusCode, headers, body, body::close);
        }

        @Override
        public void close() throws IOException {
            try {
                closeAction.close();
            } catch (IOException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        }
    }

    private static final class PinnedHttpRemoteSource implements RemoteSource {
        private final int timeoutSeconds;

        private PinnedHttpRemoteSource(int timeoutSeconds) {
            this.timeoutSeconds = Math.max(1, timeoutSeconds);
        }

        @Override
        public RemoteResponse open(URI uri, InetAddress[] validatedAddresses) throws Exception {
            DnsResolver pinnedResolver = new DnsResolver() {
                @Override public InetAddress[] resolve(String host) { return validatedAddresses.clone(); }
                @Override public String resolveCanonicalHostname(String host) { return host; }
            };
            var timeout = Timeout.ofSeconds(timeoutSeconds);
            var manager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(pinnedResolver)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                    .build();
            var client = HttpClients.custom().setConnectionManager(manager)
                    .disableRedirectHandling().build();
            var request = new HttpGet(uri);
            request.setHeader(HttpHeaders.ACCEPT, "image/*");
            request.setConfig(RequestConfig.custom().setRedirectsEnabled(false)
                    .setResponseTimeout(timeout).build());
            try {
                var response = client.execute(request);
                try {
                    var headers = Arrays.stream(response.getHeaders())
                            .collect(Collectors.groupingBy(header -> header.getName().toLowerCase(Locale.ROOT),
                                    Collectors.mapping(header -> header.getValue(), Collectors.toList())));
                    var entity = response.getEntity();
                    var rawBody = entity == null ? InputStream.nullInputStream() : entity.getContent();
                    var body = new DeadlineInputStream(rawBody, timeoutSeconds);
                    return new RemoteResponse(response.getCode(), headers, body, () -> {
                        try { response.close(); } finally { client.close(); }
                    });
                } catch (Exception ex) {
                    response.close();
                    throw ex;
                }
            } catch (Exception ex) {
                client.close();
                throw ex;
            }
        }
    }

    private static final class DeadlineInputStream extends FilterInputStream {
        private final long deadlineNanos;

        private DeadlineInputStream(InputStream input, int timeoutSeconds) {
            super(input);
            deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        }

        @Override public int read() throws IOException {
            checkDeadline();
            var value = super.read();
            checkDeadline();
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkDeadline();
            var value = super.read(bytes, offset, length);
            checkDeadline();
            return value;
        }

        private void checkDeadline() throws IOException {
            if (System.nanoTime() > deadlineNanos) {
                throw new IOException("远端文件读取超时");
            }
        }
    }

    public record StoredFile(String fileId, String fileName, String contentType, long size,
                             String storagePath, String downloadUrl) {}

    public record StoredDownload(Path path, String fileName, String contentType, long size) {}
}
