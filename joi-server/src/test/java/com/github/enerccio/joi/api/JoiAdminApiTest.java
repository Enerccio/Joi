package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.ApiAccessService;
import com.github.enerccio.joi.protocol.http.AdminScope;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.domain.service.ExtensionVerificationException;
import com.github.enerccio.marginalia.domain.service.OsgiService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The administration calls of the API ({@code /api/v1/admin}): the scopes of a token (given only by an administrator, working
 * only while the owner is one, never on an OAuth device), the update of the installation and the extensions (on a stand-in
 * for OSGi, which the tests do not start). Every answer is checked against the schemas of joi-protocol.
 */
class JoiAdminApiTest extends PlatformTestBase {

    static final String BASE = JoiApiTest.BASE;

    @Autowired
    private JoiApi api;
    @Autowired
    private JoiAdminApi adminApi;
    @Autowired
    private ApiAccessService apiAccessService;
    @Autowired
    private AccessTokenService accessTokenService;
    @Autowired
    private UserService userService;

    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(m -> m.mapPrefix(BASE, "classpath:joi-protocol/schemas/")));

    private Object realOsgi;

    @BeforeEach
    void setUp() {
        realOsgi = ReflectionTestUtils.getField(adminApi, "osgiService");
    }

    @AfterEach
    void tearDown() throws Exception {
        ReflectionTestUtils.setField(adminApi, "osgiService", realOsgi);
        loginAdmin();
        apiAccessService.save(ApiAccess.DEFAULT);
    }

    private JoiApiTest.Answer call(String method, String path, String token, byte[] body) throws Exception {
        JoiApiTest.Answer answer = new JoiApiTest.Answer();
        int query = path.indexOf('?');
        Map<String, String> headers = token == null ? Map.of() : Map.of("authorization", "Bearer " + token);
        byte[] bytes = body == null ? new byte[0] : body;
        // an install streams its body, as the servlet hands it over
        api.handle(new ApiCall(method, query < 0 ? path : path.substring(0, query), query < 0 ? Map.of() : ApiCall.parse(path.substring(query + 1)),
                headers, JoiAdminApi.isExtensionUpload(method, path) ? new byte[0] : bytes, "http://joi.test:8080", false,
                JoiAdminApi.isExtensionUpload(method, path) ? new ByteArrayInputStream(bytes) : null), answer);
        return answer;
    }

    private void valid(String schema, String json) {
        assertThat(schemas.getSchema(SchemaLocation.of(BASE + schema + ".schema.json")).validate(json, InputFormat.JSON))
                .as(schema + ": " + json).isEmpty();
    }

    private String adminToken(AdminScope... scopes) throws Exception {
        loginAdmin();
        return accessTokenService.create("admin", null, List.of(scopes)).text();
    }

    private static List<String> scopes(JoiApiTest.Answer me) {
        return me.json().getAsJsonArray("scopes").asList().stream().map(JsonElement::getAsString).toList();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the scopes
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void onlyAnAdministratorGivesScopesAndMeListsThem() throws Exception {
        login();
        assertThatThrownBy(() -> accessTokenService.create("mine", null, List.of(AdminScope.EXTENSIONS))).isInstanceOf(SecurityException.class);
        String plain = accessTokenService.create("mine", null).text();
        JoiApiTest.Answer me = call("GET", "/api/v1/me", plain, null);
        valid("Me", me.body);
        assertThat(scopes(me)).isEmpty();
        assertThat(call("GET", "/api/v1/admin/extensions", plain, null).status).isEqualTo(403);
        assertThat(call("GET", "/api/v1/admin/update", plain, null).status).isEqualTo(403);

        String admin = adminToken(AdminScope.UPDATE, AdminScope.EXTENSIONS);
        JoiApiTest.Answer adminMe = call("GET", "/api/v1/me", admin, null);
        valid("Me", adminMe.body);
        assertThat(scopes(adminMe)).containsExactly("update", "extensions");
    }

    @Test
    void aScopeOpensOnlyItsPart() throws Exception {
        String update = adminToken(AdminScope.UPDATE);
        assertThat(call("GET", "/api/v1/admin/update", update, null).status).isEqualTo(200);
        JoiApiTest.Answer denied = call("GET", "/api/v1/admin/extensions", update, null);
        assertThat(denied.status).isEqualTo(403);
        valid("ApiError", denied.body);
        assertThat(denied.json().get("code").getAsString()).isEqualTo("forbidden");
        assertThat(call("GET", "/api/v1/admin/nothing", update, null).status).isEqualTo(404);
    }

    @Test
    void theScopesWorkOnlyWhileTheOwnerIsAnAdministrator() throws Exception {
        User other = createUser("chief", "pw", true);
        loginAs(other);
        String token = accessTokenService.create("chief", null, List.of(AdminScope.UPDATE)).text();
        assertThat(call("GET", "/api/v1/admin/update", token, null).status).isEqualTo(200);

        loginAdmin();
        other = userService.find(other.getId());
        other.setAdmin(false);
        userService.save(other);
        assertThat(call("GET", "/api/v1/admin/update", token, null).status).isEqualTo(403);
        assertThat(scopes(call("GET", "/api/v1/me", token, null))).isEmpty();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the update
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void theUpdateAnswersItsStatusAndLogAndRefusesWithoutAWatcher() throws Exception {
        String token = adminToken(AdminScope.UPDATE);
        JoiApiTest.Answer status = call("GET", "/api/v1/admin/update", token, null);
        assertThat(status.status).isEqualTo(200);
        valid("UpdateStatus", status.body);
        assertThat(status.json().get("watching").getAsBoolean()).isFalse();
        assertThat(status.json().get("canRequest").getAsBoolean()).isFalse();

        JoiApiTest.Answer request = call("POST", "/api/v1/admin/update", token, null);
        assertThat(request.status).isEqualTo(409);
        valid("ApiError", request.body);

        JoiApiTest.Answer log = call("GET", "/api/v1/admin/update/log?bytes=100", token, null);
        assertThat(log.status).isEqualTo(200);
        assertThat(log.contentType).startsWith("text/plain");
        assertThat(call("GET", "/api/v1/admin/update/log?bytes=0", token, null).status).isEqualTo(400);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the extensions
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void withoutOsgiTheExtensionsAreUnavailable() throws Exception {
        JoiApiTest.Answer answer = call("GET", "/api/v1/admin/extensions", adminToken(AdminScope.EXTENSIONS), null);
        assertThat(answer.status).isEqualTo(503);
        valid("ApiError", answer.body);
        assertThat(answer.json().get("code").getAsString()).isEqualTo("unavailable");
    }

    @Test
    void theExtensionsAreListedInstalledStoppedStartedAndRemoved() throws Exception {
        FakeOsgi osgi = new FakeOsgi();
        osgi.add("joi-grok.jar", "com.github.enerccio.joi.grok");
        use(osgi);
        String token = adminToken(AdminScope.EXTENSIONS);

        JoiApiTest.Answer list = call("GET", "/api/v1/admin/extensions", token, null);
        assertThat(list.status).isEqualTo(200);
        valid("ExtensionList", list.body);
        assertThat(list.json().getAsJsonArray("extensions")).hasSize(1);

        byte[] jar = "PK fake jar".getBytes(StandardCharsets.UTF_8);
        JoiApiTest.Answer installed = call("PUT", "/api/v1/admin/extensions/joi-anthropic.jar", token, jar);
        assertThat(installed.status).isEqualTo(200);
        valid("ExtensionInfo", installed.body);
        assertThat(installed.json().get("jar").getAsString()).isEqualTo("joi-anthropic.jar");
        assertThat(osgi.installed.get("joi-anthropic.jar")).isEqualTo(jar);

        JoiApiTest.Answer stopped = call("POST", "/api/v1/admin/extensions/joi-anthropic.jar/stop", token, null);
        valid("ExtensionInfo", stopped.body);
        assertThat(stopped.json().get("stopped").getAsBoolean()).isTrue();
        JoiApiTest.Answer started = call("POST", "/api/v1/admin/extensions/joi-anthropic.jar/start", token, null);
        assertThat(started.json().get("stopped").getAsBoolean()).isFalse();
        assertThat(call("GET", "/api/v1/admin/extensions/joi-anthropic.jar/log", token, null).body).isEqualTo("log of joi-anthropic.jar");

        assertThat(call("DELETE", "/api/v1/admin/extensions/joi-anthropic.jar", token, null).status).isEqualTo(204);
        assertThat(call("GET", "/api/v1/admin/extensions/joi-anthropic.jar", token, null).status).isEqualTo(404);
    }

    @Test
    void aBadInstallIsRefused() throws Exception {
        FakeOsgi osgi = new FakeOsgi();
        use(osgi);
        String token = adminToken(AdminScope.EXTENSIONS);
        assertThat(call("PUT", "/api/v1/admin/extensions/notes.txt", token, new byte[]{1}).status).isEqualTo(400);
        assertThat(call("PUT", "/api/v1/admin/extensions/empty.jar", token, new byte[0]).status).isEqualTo(400);

        osgi.reject = true;
        JoiApiTest.Answer rejected = call("PUT", "/api/v1/admin/extensions/old.jar", token, new byte[]{1, 2});
        assertThat(rejected.status).isEqualTo(422);
        valid("ApiError", rejected.body);
        assertThat(rejected.json().get("code").getAsString()).isEqualTo("extension_rejected");
        assertThat(rejected.json().get("message").getAsString()).contains("needs API 2");
        assertThat(osgi.installed).isEmpty();
    }

    private void use(OsgiService osgi) {
        ReflectionTestUtils.setField(adminApi, "osgiService", new ObjectProvider<OsgiService>() {
            @Override
            public OsgiService getObject() {
                return osgi;
            }

            @Override
            public OsgiService getObject(Object... args) {
                return osgi;
            }

            @Override
            public OsgiService getIfAvailable() {
                return osgi;
            }

            @Override
            public OsgiService getIfUnique() {
                return osgi;
            }
        });
    }

    /** A stand-in for OSGi: bundles by their JAR, what was installed, stopped. */
    static final class FakeOsgi {
        final Map<String, byte[]> installed = new LinkedHashMap<>();
        final Map<String, Bundle> bundles = new LinkedHashMap<>();
        final Set<String> stopped = new HashSet<>();
        boolean reject;
        long nextId = 1;

        Bundle add(String jar, String symbolicName) {
            long id = nextId++;
            Bundle bundle = (Bundle) Proxy.newProxyInstance(Bundle.class.getClassLoader(), new Class<?>[]{Bundle.class}, (p, m, a) -> switch (m.getName()) {
                case "getBundleId" -> id;
                case "getLocation" -> "file:/data/extensions/" + jar;
                case "getSymbolicName" -> symbolicName;
                case "getHeaders" -> new Hashtable<>(Map.of("Bundle-Name", symbolicName));
                case "getVersion" -> new Version(1, 0, 0);
                case "getState" -> stopped.contains(jar) ? Bundle.RESOLVED : Bundle.ACTIVE;
                case "hashCode" -> (int) id;
                case "equals" -> p == a[0];
                case "toString" -> jar;
                default -> throw new UnsupportedOperationException(m.getName());
            });
            bundles.put(jar, bundle);
            return bundle;
        }

        private static String jar(Bundle bundle) {
            return bundle.toString();
        }

        OsgiService service() {
            return (OsgiService) Proxy.newProxyInstance(OsgiService.class.getClassLoader(), new Class<?>[]{OsgiService.class}, (p, m, a) -> switch (m.getName()) {
                case "getBundles" -> List.copyOf(bundles.values());
                case "findBundleByJar" -> bundles.get((String) a[0]);
                case "installPackage" -> {
                    if (reject) {
                        throw new ExtensionVerificationException((String) a[0], "needs API 2");
                    }
                    installed.put((String) a[0], (byte[]) a[1]);
                    yield add((String) a[0], "installed." + a[0]);
                }
                case "uninstallPackage" -> {
                    bundles.remove(jar((Bundle) a[0]));
                    yield null;
                }
                case "stopExtension" -> {
                    stopped.add(jar((Bundle) a[0]));
                    yield null;
                }
                case "startExtension" -> {
                    stopped.remove(jar((Bundle) a[0]));
                    yield null;
                }
                case "isStopped" -> stopped.contains(jar((Bundle) a[0]));
                case "getLoadedExtensions" -> List.of();
                case "getVerificationReport" -> new OsgiService.ExtensionReport(true, "");
                case "getSha256" -> "ab12";
                case "readLog" -> "log of " + jar((Bundle) a[0]);
                case "hashCode" -> 1;
                case "equals" -> p == a[0];
                case "toString" -> "FakeOsgi";
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private void use(FakeOsgi osgi) {
        use(osgi.service());
    }
}
