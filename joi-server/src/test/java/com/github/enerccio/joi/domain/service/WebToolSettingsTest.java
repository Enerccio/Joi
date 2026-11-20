package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.WebToolSettingsService.Pages;
import com.github.enerccio.joi.domain.service.WebToolSettingsService.Search;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.domain.web.PageConfig;
import com.github.enerccio.joi.domain.web.PageSourceType;
import com.github.enerccio.joi.domain.web.SearchSource;
import com.github.enerccio.joi.domain.web.SearchSourceType;
import com.github.enerccio.joi.domain.web.WebSearchConfig;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The search and page options along the chain, their secrets (kept, changed, removed, never in the clear), and who may write. */
class WebToolSettingsTest extends PlatformTestBase {

    @Autowired
    private WebToolSettingsService settings;
    @Autowired
    private ToolMetadataService metadataService;
    @Autowired
    private JoiService joiService;

    private Joi joi;
    private User me;

    @BeforeEach
    void setUp() throws Exception {
        loginAdmin();
        for (ToolMetadata left : metadataService.findAll(ToolScope.APP, null)) {
            metadataService.delete(left, true);
        }
        me = login();
        joi = joiService.create("Kairo");
    }

    private static SearchSource source(String id, SearchSourceType type) {
        return new SearchSource(id, id, type, true, false, null, null, List.of(), null);
    }

    private static WebSearchConfig config(String proxy, SearchSource... sources) {
        return new WebSearchConfig(List.of(sources), proxy, 10, 4, "en", false, null, false, 3, 2);
    }

    @Test
    void withNothingSetTheDefaultsApply() throws Exception {
        Search search = settings.search(joi);
        assertThat(search.from()).isNull();
        assertThat(search.config().sources()).isEmpty();
        assertThat(search.config().maxSearches()).isEqualTo(WebSearchConfig.SEARCHES);
        assertThat(search.keys()).isEmpty();
        Pages pages = settings.pages(joi);
        assertThat(pages.from()).isNull();
        assertThat(pages.config().type()).isEqualTo(PageSourceType.DIRECT);
        assertThat(pages.token()).isNull();
    }

    @Test
    void theAdministratorsAreTheDefaultAndTheUsersOwnReplaceThem() throws Exception {
        loginAdmin();
        settings.saveSearch(ToolScope.APP, null, config("http://proxy.lan:3128", source("brave", SearchSourceType.BRAVE)),
                Map.of("source:brave", "admin-key", "proxy", "svc:pw"));
        loginAs(me);

        Search search = settings.search(joi);
        assertThat(search.from()).isEqualTo(ToolScope.APP);
        assertThat(search.config().sources()).extracting(SearchSource::id).containsExactly("brave");
        assertThat(search.key(search.config().source("brave"))).isEqualTo("admin-key");
        assertThat(search.proxyAuth()).isEqualTo("svc:pw");
        assertThat(search.config().proxy()).isEqualTo("http://proxy.lan:3128");

        // the user's own: whole, with their own keys; none of the administrator's come with it
        settings.saveSearch(ToolScope.USER, null, config(null, source("tavily", SearchSourceType.TAVILY)), Map.of("source:tavily", "my-key"));
        search = settings.search(joi);
        assertThat(search.from()).isEqualTo(ToolScope.USER);
        assertThat(search.config().sources()).extracting(SearchSource::id).containsExactly("tavily");
        assertThat(search.keys()).containsOnlyKeys("source:tavily");
        assertThat(search.proxyAuth()).isNull();

        // and the assistant's own over both
        settings.saveSearch(ToolScope.JOI, joi, config(null, source("ddgs", SearchSourceType.DDGS)), Map.of());
        assertThat(settings.search(joi).from()).isEqualTo(ToolScope.JOI);
        assertThat(settings.search(joi).config().sources()).extracting(SearchSource::id).containsExactly("ddgs");
    }

    @Test
    void aSecretIsKeptWhenNotMentionedChangedWhenGivenAndRemovedWhenBlank() throws Exception {
        SearchSource brave = source("brave", SearchSourceType.BRAVE);
        SearchSource tavily = source("tavily", SearchSourceType.TAVILY);
        settings.saveSearch(ToolScope.USER, null, config(null, brave, tavily), Map.of("source:brave", "b1", "source:tavily", "t1"));

        // saved again with another option and no mention of the keys: they stay
        settings.saveSearch(ToolScope.USER, null, config("http://p:1", brave, tavily), new HashMap<>());
        assertThat(settings.search(joi).keys()).containsEntry("source:brave", "b1").containsEntry("source:tavily", "t1");
        assertThat(settings.search(joi).config().proxy()).isEqualTo("http://p:1");

        Map<String, String> change = new HashMap<>();
        change.put("source:brave", "b2");
        change.put("source:tavily", "  ");
        settings.saveSearch(ToolScope.USER, null, config(null, brave, tavily), change);
        assertThat(settings.search(joi).keys()).containsOnlyKeys("source:brave").containsEntry("source:brave", "b2");

        // the key of a source that was taken out goes with it, and a secret the options do not know is not kept
        settings.saveSearch(ToolScope.USER, null, config(null, tavily), Map.of("source:tavily", "t2", "source:elsewhere", "x", "other", "y"));
        ToolMetadata stored = metadataService.find(ToolScope.USER, null, ServerTool.WEB_SEARCH);
        assertThat(stored.protectedSecrets().keySet()).containsExactly("source:tavily");
        assertThat(new String(stored.getExtendedContent())).doesNotContain("t2").doesNotContain("b2");
    }

    @Test
    void thePageOptionsHaveTheirOwnTokenAndChain() throws Exception {
        PageConfig crawl = new PageConfig(PageSourceType.CRAWL4AI_PROXY, "http://crawl.lan:8000", true, null, 15, 4000, false);
        settings.savePages(ToolScope.USER, null, crawl, Map.of("token", "tok", "proxy", "u:p", "stray", "z"));
        Pages pages = settings.pages(joi);
        assertThat(pages.from()).isEqualTo(ToolScope.USER);
        assertThat(pages.config()).isEqualTo(crawl);
        assertThat(pages.token()).isEqualTo("tok");
        assertThat(pages.proxyAuth()).isEqualTo("u:p");
        assertThat(metadataService.find(ToolScope.USER, null, ServerTool.FETCH_PAGE).protectedSecrets().keySet()).containsExactlyInAnyOrder("token", "proxy");
        // the search options are another tool's
        assertThat(settings.search(joi).from()).isNull();
    }

    @Test
    void onlyAdministratorsWriteTheAdministratorsOptions() throws Exception {
        assertThatThrownBy(() -> settings.saveSearch(ToolScope.APP, null, config(null), Map.of())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> settings.savePages(ToolScope.APP, null, PageConfig.from(null), Map.of())).isInstanceOf(SecurityException.class);
        User other = createUser();
        loginAs(other);
        assertThatThrownBy(() -> settings.saveSearch(ToolScope.JOI, joi, config(null), Map.of())).isInstanceOf(SecurityException.class);
    }
}
