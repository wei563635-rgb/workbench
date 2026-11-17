package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {
    @TempDir
    Path root;

    @Test
    void savesSanitizedNameAndBuildsAbsoluteDownloadUrl() throws Exception {
        var storage = storage(1024, List.of("image/png"));
        var bytes = pngBytes();

        var stored = storage.save("run_1", "../input.png", "image/png", bytes.length,
                new ByteArrayInputStream(bytes));

        assertThat(stored.fileName()).isEqualTo("input.png");
        assertThat(stored.downloadUrl())
                .isEqualTo("https://workbench.internal/api/files/demo-download/" + stored.fileId());
        assertThat(Files.readAllBytes(Path.of(stored.storagePath()))).containsExactly(bytes);
    }

    @Test
    void sanitizesWindowsPathsAndUnsafeFilenameCharacters() throws Exception {
        var stored = storage(1024, List.of("image/png")).save(
                "run_1", "..\\folder\\bad\u0000:name.png", "image/png", pngBytes().length,
                new ByteArrayInputStream(pngBytes()));

        assertThat(stored.fileName()).isEqualTo("bad_name.png");
    }

    @Test
    void rejectsDeclaredAndActualFilesOverLimitAndRemovesPartialFile() throws Exception {
        var storage = storage(3, List.of("image/png"));

        assertThatThrownBy(() -> storage.save("run_1", "large.png", "image/png", 4,
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4})))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("大小");
        assertThatThrownBy(() -> storage.save("run_1", "lied.png", "image/png", 2,
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4})))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("实际");
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void savesBrandHtmlWithoutApplyingTheGeneralFileSizeLimit() throws Exception {
        var storage = storage(3, List.of("application/octet-stream"));
        var html = "<html>brand page</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var stored = storage.saveWithoutSizeLimit(
                "brand_page_1", null, "u_admin", "brand.html",
                "application/octet-stream", html.length, new ByteArrayInputStream(html));

        assertThat(stored.size()).isEqualTo(html.length);
        assertThat(Files.readAllBytes(Path.of(stored.storagePath()))).containsExactly(html);
    }

    @Test
    void rejectsDeclaredSizeThatDoesNotMatchActualStream() {
        assertThatThrownBy(() -> storage(1024, List.of("image/png")).save(
                "run_1", "short.png", "image/png", 3,
                new ByteArrayInputStream(new byte[] {1, 2})))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("实际");
    }

    @Test
    void rejectsUnsupportedMimeAndInvalidFileId() {
        var storage = storage(1024, List.of("image/png", "image/jpeg"));

        assertThatThrownBy(() -> storage.save("run_1", "payload.svg", "image/svg+xml", 1,
                new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("类型");
        assertThatThrownBy(() -> storage.load("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("标识");
        assertThatThrownBy(() -> storage.load("file_"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("标识");
    }

    @Test
    void rejectsHtmlDisguisedAsAnImage() {
        var html = "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> storage(1024, List.of("image/png")).save(
                "run_1", "attack.png", "image/png", html.length, new ByteArrayInputStream(html)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("内容");
    }

    @Test
    void metadataOnlyCannotCreateAUrlForAFileThatDoesNotExist() {
        assertThatThrownBy(() -> storage(1024, List.of("image/png")).metadataOnly(
                "run_1", "missing.png", "image/png", 12))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("文件内容");
    }

    @Test
    void streamsRemoteImageIntoPermanentStorage() throws Exception {
        var bytes = pngBytes();
        StorageService.RemoteSource source = (uri, addresses) -> new StorageService.RemoteResponse(
                200, Map.of("content-type", List.of("image/png"), "content-length", List.of("8")),
                new ByteArrayInputStream(bytes));
        var storage = storage(1024, List.of("image/png"), publicResolver(), source);

        var stored = storage.saveRemote("run_1", "https://images.example.test/result.png", "result.png");

        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(stored.size()).isEqualTo(8);
        assertThat(Files.readAllBytes(Path.of(stored.storagePath()))).containsExactly(bytes);
    }

    @Test
    void rejectsLocalPrivateLinkLocalAndResolvedPrivateRemoteAddresses() throws Exception {
        var unusedSource = (StorageService.RemoteSource) (uri, addresses) -> {
            throw new AssertionError("blocked address must not be fetched");
        };
        var storage = storage(1024, List.of("image/png"), InetAddress::getAllByName, unusedSource);

        for (var url : List.of(
                "http://localhost/image.png",
                "http://127.0.0.1/image.png",
                "http://0.0.0.1/image.png",
                "http://10.1.2.3/image.png",
                "http://169.254.169.254/latest/meta-data",
                "http://192.0.2.1/image.png",
                "http://198.51.100.1/image.png",
                "http://203.0.113.1/image.png",
                "http://224.0.0.1/image.png",
                "http://[::1]/image.png",
                "http://[fc00::1]/image.png",
                "http://[2001:db8::1]/image.png")) {
            assertThatThrownBy(() -> storage.saveRemote("run_1", url, "result.png"))
                    .as(url).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("远端地址");
        }

        var rebinding = storage(1024, List.of("image/png"),
                host -> new InetAddress[] {InetAddress.getByName("192.168.1.20")}, unusedSource);
        assertThatThrownBy(() -> rebinding.saveRemote(
                "run_1", "https://public-looking.example.test/result.png", "result.png"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("远端地址");
    }

    @Test
    void validatesEveryRedirectBeforeFollowingIt() throws Exception {
        StorageService.RemoteSource source = (uri, addresses) -> new StorageService.RemoteResponse(
                302, Map.of("location", List.of("http://127.0.0.1/secret")),
                new ByteArrayInputStream(new byte[0]));
        var storage = storage(1024, List.of("image/png"), publicResolver(), source);

        assertThatThrownBy(() -> storage.saveRemote(
                "run_1", "https://images.example.test/result.png", "result.png"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("远端地址");
    }

    @Test
    void passesTheFirstValidatedPublicAddressToTheConnectionWithoutResolvingAgain() throws Exception {
        var resolutions = new java.util.concurrent.atomic.AtomicInteger();
        var publicAddress = InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
        var privateAddress = InetAddress.getByName("127.0.0.1");
        StorageService.AddressResolver changingDns = host -> resolutions.getAndIncrement() == 0
                ? new InetAddress[] {publicAddress} : new InetAddress[] {privateAddress};
        StorageService.RemoteSource pinnedConnection = (uri, addresses) -> {
            assertThat(addresses).containsExactly(publicAddress);
            return new StorageService.RemoteResponse(200,
                    Map.of("content-type", List.of("image/png"), "content-length", List.of("8")),
                    new ByteArrayInputStream(pngBytes()));
        };

        storage(1024, List.of("image/png"), changingDns, pinnedConnection)
                .saveRemote("run_1", "https://changing.example.test/image.png", "image.png");

        assertThat(resolutions).hasValue(1);
    }

    private StorageService storage(long maxBytes, List<String> allowedMimeTypes) {
        return storage(maxBytes, allowedMimeTypes, publicResolver(), (uri, addresses) -> {
            throw new AssertionError("unexpected remote fetch: " + uri);
        });
    }

    private StorageService storage(long maxBytes, List<String> allowedMimeTypes,
                                   StorageService.AddressResolver resolver,
                                   StorageService.RemoteSource remoteSource) {
        return new StorageService("local", root.toString(), "https://workbench.internal",
                maxBytes, String.join(",", allowedMimeTypes), 5, "", "", resolver, remoteSource);
    }

    private StorageService.AddressResolver publicResolver() {
        return host -> new InetAddress[] {
                "127.0.0.1".equals(host)
                        ? InetAddress.getByName(host)
                        : InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})
        };
    }

    private byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    }
}
